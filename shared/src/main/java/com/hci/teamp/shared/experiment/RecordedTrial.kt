package com.hci.teamp.shared.experiment

import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.recorder.AttemptRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordedPhase { READY, COUNTDOWN, ACTIVE, SAVING, RESULT, ERROR }

data class RecordedState(
    val phase: RecordedPhase = RecordedPhase.READY,
    val value: Int,
    val countdownSeconds: Int = 3,
    val outcome: EndStatus? = null,
    val error: String? = null,
)

interface TrialAudio {
    fun start(value: Int)
    fun setVolume(value: Int)
    fun stop()
}

/** One device owns the clock, input, audio and recorder for a trial. */
class RecordedTrial(
    val config: TrialConfig,
    private val recorder: AttemptRecorder,
    private val input: ExperimentInput,
    private val audio: TrialAudio,
    private val scope: CoroutineScope,
    private val nowNs: () -> Long,
) {
    private val mutableState = MutableStateFlow(RecordedState(value = config.startVolume))
    val state = mutableState.asStateFlow()
    private var countdownNs = 0L
    private var startedNs = 0L
    private var lastRaw = emptyMap<String, String>()

    init {
        require(input.condition.name == config.condition.name)
    }

    fun ready() {
        if (state.value.phase != RecordedPhase.READY) return
        countdownNs = nowNs()
        mutableState.value = state.value.copy(phase = RecordedPhase.COUNTDOWN)
    }

    fun tick() {
        val now = nowNs()
        when (state.value.phase) {
            RecordedPhase.COUNTDOWN -> {
                val elapsed = (now - countdownNs) / 1_000_000
                if (elapsed < COUNTDOWN_MS) {
                    mutableState.value = state.value.copy(countdownSeconds = ((COUNTDOWN_MS - elapsed + 999) / 1000).toInt())
                    return
                }
                startedNs = recorder.start()
                mutableState.value = state.value.copy(phase = RecordedPhase.ACTIVE)
                try {
                    audio.start(config.startVolume)
                    input.start(
                        Attempt(
                            config.attemptId, ParticipantId(config.participantId),
                            Condition.valueOf(config.condition.name),
                            Task(config.taskId, VirtualVolume(config.startVolume), VirtualVolume(config.targetVolume)),
                        ),
                        ::onInput,
                    )
                } catch (error: Exception) {
                    finish(EndStatus.INVALID, "INPUT_OR_AUDIO_ERROR")
                }
            }
            RecordedPhase.ACTIVE -> if ((now - startedNs) / 1_000_000 >= config.timeoutMs) {
                finish(EndStatus.TIMEOUT)
            }
            else -> Unit
        }
    }

    private fun onInput(event: InputEvent) {
        if (state.value.phase != RecordedPhase.ACTIVE || event.attemptId != config.attemptId) return
        if ((nowNs() - startedNs) / 1_000_000 >= config.timeoutMs) {
            finish(EndStatus.TIMEOUT)
            return
        }
        if (event.previous.percent != state.value.value) {
            finish(EndStatus.INVALID, "INPUT_DISCONTINUITY")
            return
        }
        val raw = lastRaw + mapOf("source_elapsed_realtime_ms" to event.elapsedRealtimeMs.toString())
        recorder.valueChange(event.current.percent, raw)
        mutableState.value = state.value.copy(value = event.current.percent)
        try {
            audio.setVolume(event.current.percent)
        } catch (error: Exception) {
            finish(EndStatus.INVALID, "AUDIO_UPDATE_ERROR")
        }
    }

    /** Call only for observed raw input. Inferred bezel gestures remain metadata. */
    fun raw(kind: String, data: Map<String, String>) {
        if (state.value.phase != RecordedPhase.ACTIVE) return
        lastRaw = data + ("kind" to kind)
        when (kind) {
            "DOWN" -> recorder.inputDown(lastRaw)
            "UP" -> recorder.inputUp(lastRaw)
        }
    }

    fun complete() = finish(EndStatus.COMPLETED)
    fun interrupt(reason: String = "ACTIVITY_STOPPED") {
        when (state.value.phase) {
            RecordedPhase.COUNTDOWN -> mutableState.value = state.value.copy(phase = RecordedPhase.READY)
            RecordedPhase.ACTIVE -> finish(EndStatus.INVALID, reason)
            else -> Unit
        }
    }

    private fun finish(outcome: EndStatus, reason: String = "ADMIN_ABORT") {
        if (state.value.phase != RecordedPhase.ACTIVE) return
        var effective = if ((nowNs() - startedNs) / 1_000_000 >= config.timeoutMs) EndStatus.TIMEOUT else outcome
        // Stop can emit a final observed key release before the recorder is finalized.
        val inputError = runCatching { input.stop() }.exceptionOrNull()
        val audioError = runCatching { audio.stop() }.exceptionOrNull()
        val stopFailed = inputError != null || audioError != null
        if (stopFailed) effective = EndStatus.INVALID
        mutableState.value = state.value.copy(phase = RecordedPhase.SAVING, outcome = effective)
        val saved = when (effective) {
            EndStatus.COMPLETED -> recorder.complete()
            EndStatus.TIMEOUT -> recorder.timeout()
            EndStatus.INVALID -> recorder.invalidate(if (stopFailed) "INPUT_OR_AUDIO_STOP_ERROR" else reason)
            EndStatus.IN_PROGRESS -> error("Cannot finish as IN_PROGRESS")
        }
        scope.launch {
            try {
                val outcomeSaved = saved.await()
                mutableState.value = if (outcomeSaved == SaveOutcome.DATA_CONFLICT) {
                    state.value.copy(phase = RecordedPhase.ERROR, error = "DATA_CONFLICT · 관리자 확인이 필요합니다.")
                } else {
                    state.value.copy(phase = RecordedPhase.RESULT)
                }
            } catch (error: Exception) {
                mutableState.value = state.value.copy(phase = RecordedPhase.ERROR, error = error.message ?: "저장 실패")
            }
        }
    }
}
