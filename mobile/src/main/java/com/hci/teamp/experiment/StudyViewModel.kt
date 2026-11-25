package com.hci.teamp.experiment

import com.hci.teamp.shared.model.InputResolution
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.sync.SyncCodec
import kotlinx.serialization.decodeFromString
import android.app.Application
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hci.teamp.experiment.input.PhoneVolumeKeys
import com.hci.teamp.experiment.input.PhoneVolumeSlider
import com.hci.teamp.experiment.input.RawInput
import com.hci.teamp.mobile.data.ExportManager
import com.hci.teamp.mobile.sync.PhoneSyncClient
import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.db.ParticipantEntity
import com.hci.teamp.shared.experiment.*
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.recorder.AttemptRecorder
import com.hci.teamp.shared.recorder.RepositoryAttemptStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StudyScreen { ADMIN, INSTRUCTIONS, MAIN_GATE, REST, READY, PHONE, WATCH, FINISHED }

class StudyViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = ExperimentRepository.get(application)
    private val sync = PhoneSyncClient.get(application)
    val keys = PhoneVolumeKeys()
    val slider = PhoneVolumeSlider()
    private val audio = RingtoneAudio.prepare(application)
    private var selected: ParticipantEntity? = null

    var screen by mutableStateOf(StudyScreen.ADMIN)
        private set
    var participants by mutableStateOf<List<ParticipantEntity>>(emptyList())
        private set
    var config by mutableStateOf<TrialConfig?>(null)
        private set
    var trial by mutableStateOf<RecordedTrial?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf("실험 기록을 불러오는 중입니다.")
        private set
    var restUntilMs by mutableStateOf(0L)
        private set
    var lastBackup by mutableStateOf<ExportManager.ExportResult?>(null)
        private set

    var completedTasks by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    val isPilot get() = selected?.groupId?.let(FixedRoster::pilot) == true

    val participantId get() = selected?.participantId
    val isDemo get() = selected?.groupId == DemoPlan.GROUP

    // ---------- 관리자 음량 확인 · 시험 재생 ----------
    private val prefs = application.getSharedPreferences("audio_check", android.content.Context.MODE_PRIVATE)
    private val audioManager = application.getSystemService(AudioManager::class.java)

    /** 시스템 미디어 음량 (현재 to 최대). 관리자 화면에서 주기적으로 갱신 */
    var mediaVolume by mutableStateOf(0 to 0)
        private set
    var gainCurve by mutableStateOf(GainCurve.fromName(prefs.getString("gain_curve", null)))
        private set
    /** 시험 재생 중인 가상 음량 (재생 중이 아니면 null) */
    var testPlaying by mutableStateOf<Int?>(null)
        private set
    private var testJob: Job? = null

    fun refreshMediaVolume() {
        mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) to
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /** 시스템 음량 패널만 띄웁니다. 앱이 값을 바꾸지 않습니다 (ADJUST_SAME). */
    fun showSystemVolumePanel() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    fun selectGainCurve(curve: GainCurve) {
        gainCurve = curve
        audio?.curve = curve
        prefs.edit().putString("gain_curve", curve.name).apply()
        scope.launch(Dispatchers.IO) { repo.log("AUDIO_GAIN_CURVE", "", curve.name) }
    }

    /** 관리자 화면에서만: 가상 음량 [volume]으로 벨소리를 [durationMs] 동안 재생 */
    fun testPlay(volume: Int, durationMs: Long = 2500) {
        val player = audio ?: run { notice = "벨소리 로딩 실패 · 앱을 다시 열어 주세요."; return }
        if (screen != StudyScreen.ADMIN) return
        testJob?.cancel()
        player.curve = gainCurve
        player.stop()
        player.start(VirtualVolume(volume))
        testPlaying = volume
        testJob = scope.launch {
            delay(durationMs)
            player.stop()
            testPlaying = null
        }
    }

    fun stopTestPlay() {
        testJob?.cancel()
        if (testPlaying != null) audio?.stop()
        testPlaying = null
    }

    init {
        val raw: (RawInput) -> Unit = { event ->
            trial?.raw(event.kind.name, mapOf(
                "source" to event.source,
                "source_elapsed_realtime_ms" to event.elapsedRealtimeMs.toString(),
                "detail" to event.detail,
            ))
            if (event.attemptId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    repo.log("RAW_INPUT", event.attemptId, event.toString())
                }
            }
        }
        keys.rawListener = raw
        slider.rawListener = raw
        audio?.curve = gainCurve
        refreshMediaVolume()
        action {
            repo.ensureFixedRoster()
            val recovered = PhoneStudyRecovery.initialize(repo)
            refreshRoster()
            notice = if (recovered.isEmpty()) "본 실험 P001~P016 또는 예비실험 P017·P018을 선택해 주세요."
            else "중단된 시도 ${recovered.size}건을 INVALID로 보존했습니다. 참가자를 선택해 재개해 주세요."
        }
    }

    private suspend fun refreshRoster() {
        participants = repo.dao.allParticipants()
        completedTasks = repo.dao.allAttempts().filter {
            it.endStatus == EndStatus.COMPLETED.name || it.endStatus == EndStatus.TIMEOUT.name
        }.groupBy { it.participantId }.mapValues { (_, rows) -> rows.map { it.logicalTaskId }.distinct().size }
    }

    fun resume(participant: ParticipantEntity) = action {
        check(participant.finishedAtWallMs == null) { "완료된 참가자는 다시 시작하지 않습니다." }
        stopTestPlay()
        selected = participant
        findNext()
    }

    private suspend fun findNext() {
        refreshRoster()
        val participant = checkNotNull(selected)
        check(repo.dao.unresolvedConflicts().none { it.attemptId.startsWith("${participant.participantId}-") }) {
            "미해결 DATA_CONFLICT가 있습니다. 관리자 검토 후 재개해 주세요."
        }
        val orders = checkNotNull(repo.taskOrdersOf(participant.participantId))
        val conditions = participant.conditionOrder.split(",").map(ConditionId::valueOf)
        val plan = if (isDemo) DemoPlan.configs(participant.participantId, conditions, orders)
        else StudyPlan.configs(participant.participantId, conditions, orders)
        val rows = repo.dao.attemptsOf(participant.participantId)
        val finished = rows.filter {
            it.endStatus == EndStatus.COMPLETED.name || it.endStatus == EndStatus.TIMEOUT.name
        }.map { it.logicalTaskId }.toSet()
        val next = plan.firstOrNull { it.logicalTaskId !in finished }
        trial = null
        if (next == null) {
            config = null
            screen = StudyScreen.FINISHED
            notice = "전 조건 종료 · 데이터 검증과 자동 백업을 진행합니다."
            lastBackup = withContext(Dispatchers.IO) {
                ExportManager(getApplication()).finishParticipantAndBackup(participant.participantId)
            }
            refreshRoster()
            notice = exportDescription(checkNotNull(lastBackup))
            return
        }
        check(rows.all { InputResolution.version(SyncCodec.json.decodeFromString<AttemptRecord>(it.recordJson)) == InputResolution.VERSION }) {
            "이 참가자는 이전 입력 설정으로 진행한 기록이 있습니다. 기록은 보존하고 새 참가자로 시작해 주세요."
        }
        val number = repo.nextAttemptNo(next.participantId, next.condition, next.taskId)
        config = StudyPlan.retry(next, number)
        val previousConditions = conditions.takeWhile { it != next.condition }.toSet()
        val latestPreviousEnd = rows.filter {
            ConditionId.valueOf(it.conditionId) in previousConditions &&
                (it.endStatus == EndStatus.COMPLETED.name || it.endStatus == EndStatus.TIMEOUT.name)
        }.mapNotNull { if (it.deviceType == DeviceType.WATCH.name) it.receivedAtWallMs else it.endWallMs }.maxOrNull()
        val remaining = if (isDemo) 0L else latestPreviousEnd?.let {
            (CONDITION_REST_MS - (System.currentTimeMillis() - it)).coerceIn(0L, CONDITION_REST_MS)
        } ?: 0L
        screen = when {
            isDemo -> StudyScreen.READY
            next.taskId == "PR01" && remaining > 0L -> {
                restUntilMs = SystemClock.elapsedRealtime() + remaining
                StudyScreen.REST
            }
            next.taskId == "PR01" -> StudyScreen.INSTRUCTIONS
            next.phase == com.hci.teamp.shared.model.TrialPhase.MAIN && next.orderIndex == 1 -> StudyScreen.MAIN_GATE
            else -> StudyScreen.READY
        }
        notice = if (isDemo) "촬영용 시연 · ${next.condition} ${next.orderIndex}/3 · 본 실험 분석 제외" else ""
    }

    fun continuePreparation() {
        if (busy) return
        screen = when (screen) {
            StudyScreen.REST -> if (SystemClock.elapsedRealtime() >= restUntilMs) StudyScreen.INSTRUCTIONS else return
            StudyScreen.INSTRUCTIONS, StudyScreen.MAIN_GATE -> StudyScreen.READY
            else -> return
        }
    }

    fun prepareTrial() = action {
        check(screen == StudyScreen.READY)
        val cfg = checkNotNull(config)
        stopTestPlay()
        if (cfg.device == DeviceType.WATCH) {
            val gate = sync.checkReadyForNextWatchTrial()
            check(gate.ready) { gate.reasons.joinToString("\n") }
            when (val sent = sync.sendConfig(cfg)) {
                is PhoneSyncClient.ConfigSendResult.Acked -> check(sent.accepted) { sent.ack.reason ?: sent.ack.status.name }
                else -> error("워치 설정 전송 실패 · $sent")
            }
            screen = StudyScreen.WATCH
            notice = "워치에서 준비를 누르고 시행해 주세요. 결과 저장을 기다리고 있습니다."
            return@action
        }
        stopTestPlay()
        val player = checkNotNull(audio) { "벨소리 로딩 실패 · 앱을 다시 열어 주세요." }
        // Administrator audio comparison must not change only the phone trial protocol.
        player.curve = GainCurve.LINEAR
        val manager = getApplication<Application>().getSystemService(AudioManager::class.java)
        check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            "시스템 미디어 음량이 0입니다. 관리자 화면의 '음량 확인'에서 미디어 음량을 올리고 시험 재생으로 확인해 주세요."
        }
        val input = when (cfg.condition) {
            ConditionId.C1 -> keys
            ConditionId.C2 -> slider
            else -> error("Unsupported phone condition")
        }
        val recorder = AttemptRecorder(cfg, RepositoryAttemptStore(repo))
        val sound = object : TrialAudio {
            override fun start(value: Int) = player.start(VirtualVolume(value))
            override fun setVolume(value: Int) = player.setVolume(VirtualVolume(value))
            override fun stop() = player.stop()
        }
        trial = RecordedTrial(cfg, recorder, input, sound, scope, SystemClock::elapsedRealtimeNanos)
        screen = StudyScreen.PHONE
        trial?.ready()
    }

    fun next() = action {
        val current = trial
        check(current == null || current.state.value.phase == RecordedPhase.RESULT) { "저장 완료 후 진행해 주세요." }
        findNext()
    }

    fun checkWatchResult() = action {
        val cfg = checkNotNull(config)
        val result = sync.awaitWatchResult(cfg.attemptId, timeoutMs = 1500)
        if (result == null || result.endStatus == EndStatus.IN_PROGRESS.name) {
            notice = "워치 결과 대기 중입니다. 연결 상태와 워치 저장 상태를 확인해 주세요."
        } else {
            val gate = sync.checkReadyForNextWatchTrial()
            check(gate.ready) { gate.reasons.joinToString("\n") }
            findNext()
        }
    }

    fun abort() {
        if (screen == StudyScreen.WATCH) action {
            val cfg = checkNotNull(config)
            check(sync.sendAbort(cfg.attemptId)) { "중단 요청을 전송하지 못했습니다." }
            notice = "중단 요청 전송 · 워치 결과가 저장된 뒤 재시도합니다."
        } else {
            trial?.interrupt("ADMIN_ABORT")
        }
    }

    fun interrupt() {
        trial?.interrupt()
    }

    fun showAdmin() {
        if (screen == StudyScreen.PHONE || screen == StudyScreen.WATCH || busy) return
        screen = StudyScreen.ADMIN
        refreshMediaVolume()
        action { refreshRoster() }
    }

    fun export(participant: ParticipantEntity? = selected) = action {
        val exporter = ExportManager(getApplication())
        lastBackup = withContext(Dispatchers.IO) {
            if (participant == null) exporter.exportAll() else exporter.exportParticipant(participant.participantId)
        }
        notice = exportDescription(checkNotNull(lastBackup))
    }

    private fun exportDescription(result: ExportManager.ExportResult): String =
        "백업 ${if (result.success) "성공" else "실패"} · 검증 ${if (result.validationPassed == true) "통과" else "검토 필요"}\n" +
            (result.fileName ?: result.error ?: "파일 생성 실패")

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice = error.message ?: error.toString() }
            finally { busy = false }
        }
    }

    override fun onCleared() {
        stopTestPlay()
        interrupt()
        keys.stop()
        slider.stop()
        audio?.release()
        scope.cancel()
    }
}

/** Recovery runs once per process, never when a second screen merely opens. */
private object PhoneStudyRecovery {
    private val mutex = Mutex()
    private var initialized = false
    suspend fun initialize(repo: ExperimentRepository): List<String> = mutex.withLock {
        if (initialized) return@withLock emptyList()
        val recovered = repo.invalidateInterruptedAttempts(DeviceType.PHONE)
        initialized = true
        recovered
    }
}
