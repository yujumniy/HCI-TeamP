package com.hci.teamp.shared.recorder

import com.hci.teamp.shared.model.InputResolution
import android.os.Build
import android.os.SystemClock
import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.metrics.TrialMetrics
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.EventType
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialEvent
import com.hci.teamp.shared.model.TrialResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.hci.teamp.shared.util.HciLog

/** 시행 기기의 단조 증가 시계(명세서 10.2). 통신 시간은 절대 포함되지 않는다. */
interface MonotonicClock {
    fun nowNs(): Long
    fun wallMs(): Long
}

object AndroidMonotonicClock : MonotonicClock {
    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
    override fun wallMs(): Long = System.currentTimeMillis()
}

/** 기록기가 결과를 넘기는 저장 대상. 스마트폰/워치가 각자 구현을 고른다. */
interface AttemptStore {
    suspend fun begin(partial: AttemptRecord, deviceType: DeviceType)
    /** 시행 중 이벤트 1건 영구 저장 (START 이후, 종료 전) */
    suspend fun appendEvent(event: TrialEvent)
    suspend fun finalize(record: AttemptRecord, deviceType: DeviceType): SaveOutcome
}

/** 기본 구현: 로컬 Room 저장. 워치는 wear 모듈의 WearAttemptStore(저장 후 자동 전송)를 사용. */
open class RepositoryAttemptStore(protected val repo: ExperimentRepository) : AttemptStore {
    override suspend fun begin(partial: AttemptRecord, deviceType: DeviceType) =
        repo.beginLocalAttempt(partial, deviceType)

    override suspend fun appendEvent(event: TrialEvent) {
        repo.appendLocalEvent(event)
    }

    override suspend fun finalize(record: AttemptRecord, deviceType: DeviceType): SaveOutcome =
        repo.finalizeLocalAttempt(record, deviceType)
}

/**
 * 시도 1건의 이벤트 기록기 — 1·2·4번이 호출하는 유일한 기록 인터페이스.
 *
 * 사용 순서 (4번):
 *   val rec = AttemptRecorder(config, store)
 *   // 카운트다운 종료 시점, 값 공개·오디오 재생·타이머 시작과 같은 프레임에서
 *   rec.start()
 *   ...
 *   rec.complete()  /  rec.timeout()  /  rec.invalidate("ADMIN_ABORT")
 *
 * 입력 담당 (1·2번):
 *   rec.inputDown(raw)      // 버튼 누름, 베젤 접촉, 슬라이더 손잡이 터치
 *   rec.valueChange(newVol, raw)   // 가상 음량이 실제로 변했을 때만
 *   rec.inputUp(raw)
 *
 * 규칙
 *  - 모든 메서드는 메인 스레드에서 호출해도 된다. 시각은 호출 즉시 단조 시계로 찍고,
 *    DB 저장은 백그라운드에서 수행하므로 측정 시간에 저장·통신 시간이 섞이지 않는다.
 *  - 저장은 단일 순서 큐로 처리한다: START → 각 이벤트(관찰 순서대로 즉시) → 종료.
 *    시행 중 앱이 강제 종료돼도 그때까지 큐에서 처리된 이벤트는 DB 에 남고,
 *    다음 실행 시 ExperimentRepository.invalidateInterruptedAttempts() 가 그 이벤트로 INVALID 기록을 만든다.
 *  - 값을 보정하거나 중간값을 생성하지 않는다. 전달된 값을 그대로 기록한다(범위 위반은 무결성 검사에서 표시).
 *  - 종료 후 들어온 이벤트는 기록하지 않고 Logcat 경고만 남긴다.
 */
class AttemptRecorder(
    val config: TrialConfig,
    private val store: AttemptStore,
    private val deviceType: DeviceType = config.device,
    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    private val clock: MonotonicClock = AndroidMonotonicClock,
    private val scope: CoroutineScope = defaultScope,
) {
    private val lock = Any()
    private val events = ArrayList<TrialEvent>(128)
    private var startNs = -1L
    private var startWallMs = 0L
    private var currentValue = config.startVolume
    private var finished = false
    private var ignoredLateEvents = 0
    private val result = CompletableDeferred<SaveOutcome>()

    /** 저장 작업 순서 큐 (단일 소비자 → 관찰 순서 = 저장 순서) */
    private val persistQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val persistJob: Job = scope.launch {
        for (task in persistQueue) {
            try {
                task()
            } catch (t: Throwable) {
                // 개별 이벤트 저장 실패는 종료 시 finalize 가 누락분을 다시 넣는다
                HciLog.e(TAG, "persist task failed (${config.attemptId})", t)
            }
        }
    }

    val isStarted: Boolean get() = synchronized(lock) { startNs >= 0 }
    val isFinished: Boolean get() = synchronized(lock) { finished }
    val value: Int get() = synchronized(lock) { currentValue }

    /** 저장 결과. 워치에서는 '로컬 저장 완료' 의미이며, 스마트폰 동기화 여부는 WearSyncManager 상태로 확인. */
    val saved: Deferred<SaveOutcome> get() = result

    init {
        require(deviceType == config.device) { "condition ${config.condition} must run on ${config.device}" }
    }

    /** 시행 시작(START). 반환값은 시작 시각(ns, elapsedRealtimeNanos) — 4번 타이머가 같은 기준을 쓰도록. */
    fun start(): Long {
        synchronized(lock) {
            check(startNs < 0) { "start() called twice for ${config.attemptId}" }
            startNs = clock.nowNs()
            startWallMs = clock.wallMs()
            events += TrialEvent(config.attemptId, 0, EventType.START, 0L, startWallMs, config.startVolume, mapOf(
                "input_policy" to InputResolution.VERSION,
                "volume_step" to InputResolution.step(config.condition).toString(),
            ))
            val partial = AttemptRecord(
                result = TrialResult(
                    config = config,
                    deviceType = deviceType,
                    deviceModel = deviceModel,
                    endStatus = EndStatus.IN_PROGRESS,
                    finalVolume = null,
                    elapsedMs = null,
                    startWallMs = startWallMs,
                    endWallMs = null,
                ),
                events = listOf(events[0]),
            )
            persistQueue.trySend { store.begin(partial, deviceType) }
            return startNs
        }
    }

    fun inputDown(raw: Map<String, String> = emptyMap()) = append(EventType.INPUT_DOWN, null, raw)

    fun inputUp(raw: Map<String, String> = emptyMap()) = append(EventType.INPUT_UP, null, raw)

    /** 가상 음량이 바뀐 직후 호출. newValue 는 실제 반영된 값(0~100, 조건별 1/5단위). */
    fun valueChange(newValue: Int, raw: Map<String, String> = emptyMap()) {
        if (append(EventType.VALUE_CHANGE, newValue, raw)) {
            synchronized(lock) { currentValue = newValue }
        }
    }

    fun complete(raw: Map<String, String> = emptyMap()): Deferred<SaveOutcome> =
        finish(EndStatus.COMPLETED, EventType.COMPLETE, null, raw)

    fun timeout(): Deferred<SaveOutcome> =
        finish(EndStatus.TIMEOUT, EventType.TIMEOUT, null, emptyMap())

    /** 관리자 긴급 중단 등. reason 예: ADMIN_ABORT, DEVICE_ERROR */
    fun invalidate(reason: String): Deferred<SaveOutcome> =
        finish(EndStatus.INVALID, EventType.INVALID, reason, mapOf("reason" to reason))

    // ------------------------------------------------------------------

    private fun append(type: EventType, value: Int?, raw: Map<String, String>): Boolean {
        val now = clock.nowNs()
        val wall = clock.wallMs()
        synchronized(lock) {
            if (startNs < 0) {
                HciLog.w(TAG, "$type before START ignored (${config.attemptId})")
                return false
            }
            if (finished) {
                ignoredLateEvents++
                HciLog.w(TAG, "$type after end ignored (${config.attemptId}) count=$ignoredLateEvents")
                return false
            }
            if (value != null && !InputResolution.valid(value, InputResolution.step(config.condition))) {
                HciLog.e(TAG, "VALUE_CHANGE out of rule: $value (${config.attemptId}) — recorded as-is")
            }
            val event = TrialEvent(config.attemptId, events.size, type, now - startNs, wall, value, raw)
            events += event
            persistQueue.trySend { store.appendEvent(event) } // 잠금 안에서 넣어 seq 순서 보장
            return true
        }
    }

    private fun finish(status: EndStatus, type: EventType, reason: String?, raw: Map<String, String>): Deferred<SaveOutcome> {
        val now = clock.nowNs()
        val wall = clock.wallMs()
        val record: AttemptRecord
        synchronized(lock) {
            if (finished) {
                HciLog.w(TAG, "finish($status) ignored: already finished (${config.attemptId})")
                return result
            }
            check(startNs >= 0) { "finish() before start() for ${config.attemptId}" }
            finished = true
            val elapsedNs = now - startNs
            events += TrialEvent(config.attemptId, events.size, type, elapsedNs, wall, currentValue, raw)
            val snapshot = events.toList()
            record = AttemptRecord(
                result = TrialResult(
                    config = config,
                    deviceType = deviceType,
                    deviceModel = deviceModel,
                    endStatus = status,
                    finalVolume = currentValue,
                    elapsedMs = TrialMetrics.nsToMs(elapsedNs),
                    startWallMs = startWallMs,
                    endWallMs = wall,
                    invalidReason = reason,
                    metrics = TrialMetrics.compute(config.startVolume, config.targetVolume, snapshot),
                ),
                events = snapshot,
            )
            persistQueue.trySend {
                try {
                    result.complete(store.finalize(record, deviceType))
                } catch (t: Throwable) {
                    HciLog.e(TAG, "finalize persist failed for ${config.attemptId}", t)
                    result.completeExceptionally(t)
                }
            }
            persistQueue.close() // 종료 후에는 더 이상 저장 작업 없음
        }
        return result
    }

    companion object {
        const val TAG = "HCI-Recorder"
        /** 화면 회전·Activity 종료와 무관하게 저장이 끝나도록 프로세스 범위 스코프 사용 */
        val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
