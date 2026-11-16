package com.hci.teamp.shared.trial

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hci.teamp.shared.experiment.MetricsCalculator
import com.hci.teamp.shared.experiment.TaskDef
import com.hci.teamp.shared.experiment.Tasks
import com.hci.teamp.shared.experiment.TrialMetrics
import com.hci.teamp.shared.experiment.TrialStatus
import com.hci.teamp.shared.experiment.ValueSample
import com.hci.teamp.shared.experiment.VolumeRules
import com.hci.teamp.shared.log.EventLog
import com.hci.teamp.shared.log.EventType

/**
 * 한 시행의 진행과 기록 (Phase 1 테스트 앱용, Phase 2에서 확장).
 * - start(): 가상 음량 초기화 + 타이머 + START 를 같은 시점에 처리 (명세 5.3)
 *   벨소리(명세 9장)는 오디오 담당 브랜치에서 onStart/onValue/onFinish 훅에 연결합니다.
 * - 30초가 지나면 TIMEOUT (명세 8.2)
 * - 값 변경은 실제로 관찰된 것만 기록 (명세 11.1)
 */
class TrialController(
    private val log: EventLog,
    private val conditionTag: String,
) {
    var task: TaskDef by mutableStateOf(Tasks.PRACTICE.first())
        private set
    var volume by mutableIntStateOf(task.start)
        private set
    var running by mutableStateOf(false)
        private set
    var lastInput by mutableStateOf("-")
        private set
    var lastResult by mutableStateOf<Pair<TrialStatus, TrialMetrics>?>(null)
        private set
    var lastDurationMs by mutableStateOf<Long?>(null)
        private set
    var attemptId by mutableStateOf("")
        private set

    /** 오디오 등 외부 연결용 훅 (오디오 담당 브랜치에서 사용) */
    var onStart: (volume: Int) -> Unit = {}
    var onValue: (volume: Int) -> Unit = {}
    var onFinish: () -> Unit = {}

    private val changes = mutableListOf<ValueSample>()
    private var firstInputMs: Long? = null
    private var attemptCounter = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finish(TrialStatus.TIMEOUT) }

    fun selectTask(t: TaskDef) {
        if (running) return
        task = t
        volume = t.start
        lastResult = null
    }

    fun nextTask() {
        val all = Tasks.ALL
        selectTask(all[(all.indexOf(task) + 1) % all.size])
    }

    fun start() {
        if (running) return
        attemptCounter++
        attemptId = "TEST-$conditionTag-${task.id}-A%02d".format(attemptCounter)
        changes.clear()
        firstInputMs = null
        lastResult = null
        lastDurationMs = null
        // ---- 같은 시행 시작 시점 ----
        log.resetClock()
        volume = task.start
        onStart(volume)
        running = true
        log.log(EventType.START, attemptId, volume, raw = "task=${task.id};start=${task.start};target=${task.target}")
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    /** 원시 입력 시작 (버튼 누름, 베젤 접촉/회전 시작, 슬라이더 잡기) */
    fun inputDown(source: String, raw: String = "") {
        val e = log.log(EventType.INPUT_DOWN, attemptId.takeIf { running } ?: "", volume, source, raw)
        if (running && firstInputMs == null) firstInputMs = e.elapsedMs
        lastInput = "$source ↓"
    }

    fun inputUp(source: String, raw: String = "") {
        log.log(EventType.INPUT_UP, attemptId.takeIf { running } ?: "", volume, source, raw)
        lastInput = "$source ↑"
    }

    /** direction +1/-1 로 한 단계 조절 */
    fun step(direction: Int, source: String, raw: String = "") = setValue(VolumeRules.step(volume, direction), source, raw)

    /** 실제 관찰된 값으로 변경. 같은 값이면 기록하지 않음 (경계에서 멈춘 경우 raw에 남김) */
    fun setValue(newValue: Int, source: String, raw: String = "") {
        val v = VolumeRules.normalize(newValue)
        if (v == volume) {
            if (raw.isNotEmpty()) log.log(EventType.NOTE, attemptId.takeIf { running } ?: "", v, source, "no_change;$raw")
            return
        }
        volume = v
        val e = log.log(EventType.VALUE_CHANGE, attemptId.takeIf { running } ?: "", v, source, raw)
        if (running) {
            if (firstInputMs == null) firstInputMs = e.elapsedMs
            changes += ValueSample(e.elapsedMs, v)
        }
        if (running) onValue(v)
        lastInput = "$source → $v"
    }

    fun complete() = finish(TrialStatus.COMPLETED)

    fun invalidate() = finish(TrialStatus.INVALID)

    private fun finish(status: TrialStatus) {
        if (!running) return
        handler.removeCallbacks(timeoutRunnable)
        val type = when (status) {
            TrialStatus.COMPLETED -> EventType.COMPLETE
            TrialStatus.TIMEOUT -> EventType.TIMEOUT
            else -> EventType.INVALID
        }
        val e = log.log(type, attemptId, volume)
        onFinish()
        running = false
        lastDurationMs = e.elapsedMs
        lastResult = status to MetricsCalculator.compute(task.start, task.target, changes.toList(), firstInputMs)
    }

    /** 결과 요약 한 줄 */
    fun resultSummary(): String {
        val (status, m) = lastResult ?: return ""
        val t = lastDurationMs?.let { "%.2f초".format(it / 1000f) } ?: "-"
        return "$status · $t · 최종 ${m.finalValue} (오차 ${m.finalAbsError}) · " +
            "최대초과 ${m.maxOvershoot} · 초과진입 ${m.overshootCount}회 · 방향전환 ${m.directionChanges}"
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
