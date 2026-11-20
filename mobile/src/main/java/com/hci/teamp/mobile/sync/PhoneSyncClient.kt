package com.hci.teamp.mobile.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hci.teamp.shared.db.AttemptEntity
import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.sync.AbortRequest
import com.hci.teamp.shared.sync.ConfigAck
import com.hci.teamp.shared.sync.ConfigAckStatus
import com.hci.teamp.shared.sync.Ping
import com.hci.teamp.shared.sync.Pong
import com.hci.teamp.shared.sync.ResultAck
import com.hci.teamp.shared.sync.StatusRequest
import com.hci.teamp.shared.sync.SyncCodec
import com.hci.teamp.shared.sync.SyncPaths
import com.hci.teamp.shared.sync.WatchStatus
import com.hci.teamp.shared.sync.WearMessenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 스마트폰 쪽 통신 API — 4번(실험 진행)·관리자 화면이 호출한다.
 *
 *  sendConfig()                 : 워치 조건 시행 설정 전송 + 워치 확인(ACK) 대기
 *  checkReadyForNextWatchTrial(): 미전송/충돌/진행 중 기록이 없을 때만 다음 시행 허용 (13.2)
 *  awaitWatchResult()           : 워치 시행 결과가 스마트폰 DB 에 영구 저장될 때까지 대기
 *  sendAbort()                  : 관리자 긴급 중단 (8.3)
 *  requestFlush()               : 워치 미전송 기록 재전송 요청
 *  runPingTest()                : Phase 1 T3 통신 검증
 */
class PhoneSyncClient private constructor(context: Context) {

    private val app = context.applicationContext
    val repo: ExperimentRepository = ExperimentRepository.get(app)
    private val messenger = WearMessenger(app, SyncPaths.CAP_WATCH)

    private val configWaits = ConcurrentHashMap<String, CompletableDeferred<ConfigAck>>()
    private val statusWaits = ConcurrentHashMap<String, CompletableDeferred<WatchStatus>>()
    private val pongWaits = ConcurrentHashMap<Int, CompletableDeferred<Pong>>()
    private val pingIds = AtomicInteger(0)

    private val _watchReachable = MutableStateFlow<Boolean?>(null)
    /** null = 아직 확인 안 함 */
    val watchReachable: StateFlow<Boolean?> = _watchReachable.asStateFlow()

    data class ReceivedResult(val attemptId: String, val outcome: SaveOutcome, val endStatus: String)

    private val _watchResults = MutableSharedFlow<ReceivedResult>(extraBufferCapacity = 64)
    /** 워치 시도 수신(저장 완료 후) 알림. 4번 UI 가 '워치 시행 종료'를 알기 위해 구독. */
    val watchResults: SharedFlow<ReceivedResult> = _watchResults.asSharedFlow()

    // ------------------------------------------------------------------ 연결 상태

    suspend fun refreshReachability(): Boolean {
        val ok = messenger.reachablePeer() != null
        setReachable(ok)
        return ok
    }

    internal suspend fun setReachable(ok: Boolean) {
        val prev = _watchReachable.value
        _watchReachable.value = ok
        if (prev != ok) {
            repo.log(if (ok) "WATCH_CONNECTED" else "WATCH_DISCONNECTED", null, null)
            if (ok && prev == false) requestFlush() // 재연결 시 미전송 기록 자동 재전송(13.2)
        }
    }

    // ------------------------------------------------------------------ 시행 설정 전송

    sealed class ConfigSendResult {
        data class Acked(val ack: ConfigAck) : ConfigSendResult() {
            val accepted: Boolean get() = ack.status == ConfigAckStatus.ACCEPTED || ack.status == ConfigAckStatus.DUPLICATE
        }
        data object NoWatch : ConfigSendResult()
        data object Timeout : ConfigSendResult()
        data class Failed(val reason: String) : ConfigSendResult()
    }

    /**
     * 같은 attemptId 로 재전송해도 워치는 중복 저장하지 않는다(DUPLICATE 응답).
     * Acked(accepted=true) 를 받은 뒤에만 4번이 워치 쪽 준비 화면을 진행시킨다.
     */
    suspend fun sendConfig(config: TrialConfig, timeoutMs: Long = 3_000, retries: Int = 3): ConfigSendResult {
        require(config.device == com.hci.teamp.shared.model.DeviceType.WATCH) { "only watch conditions are sent" }
        val payload = SyncCodec.encode(config)
        var last: ConfigSendResult = ConfigSendResult.Timeout
        repeat(retries) { i ->
            val wait = CompletableDeferred<ConfigAck>()
            configWaits[config.attemptId] = wait
            when (val r = messenger.send(SyncPaths.CONFIG, payload)) {
                is WearMessenger.SendResult.NoPeer -> { setReachable(false); last = ConfigSendResult.NoWatch }
                is WearMessenger.SendResult.TooLarge -> return ConfigSendResult.Failed("config too large: ${r.bytes}")
                is WearMessenger.SendResult.Failed -> last = ConfigSendResult.Failed(r.error.toString())
                is WearMessenger.SendResult.Sent -> {
                    setReachable(true)
                    val ack = withTimeoutOrNull(timeoutMs) { wait.await() }
                    if (ack != null) {
                        configWaits.remove(config.attemptId)
                        repo.log("CONFIG_ACK_${ack.status}", config.attemptId, ack.reason)
                        return ConfigSendResult.Acked(ack)
                    }
                    last = ConfigSendResult.Timeout
                }
            }
            repo.log("CONFIG_SEND_RETRY", config.attemptId, "try=${i + 1} last=$last")
        }
        configWaits.remove(config.attemptId)
        return last
    }

    // ------------------------------------------------------------------ 상태 / 게이트

    suspend fun requestWatchStatus(timeoutMs: Long = 3_000): WatchStatus? {
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<WatchStatus>()
        statusWaits[id] = wait
        try {
            val r = messenger.send(SyncPaths.STATUS_REQ, SyncCodec.encode(StatusRequest(id)))
            if (r !is WearMessenger.SendResult.Sent) { setReachable(false); return null }
            setReachable(true)
            return withTimeoutOrNull(timeoutMs) { wait.await() }
        } finally {
            statusWaits.remove(id)
        }
    }

    data class SyncGate(val ready: Boolean, val reasons: List<String>, val watchStatus: WatchStatus?)

    /**
     * 명세서 13.2/13.3: 동기화 확인 전에는 다음 시행을 시작하지 않는다.
     * 4번은 워치 조건의 다음 시행(및 조건 전환) 전에 반드시 호출하고 ready=false 면 관리자에게 reasons 를 표시.
     */
    suspend fun checkReadyForNextWatchTrial(): SyncGate {
        val reasons = mutableListOf<String>()
        val status = requestWatchStatus()
        if (status == null) {
            reasons += "워치 응답 없음(연결 확인 필요)"
        } else {
            if (status.pendingCount > 0) reasons += "워치 미전송 기록 ${status.pendingCount}건"
            if (status.conflictCount > 0) reasons += "워치 측 충돌 기록 ${status.conflictCount}건"
            if (status.tooLargeCount > 0) reasons += "워치 전송 불가(용량 초과) 기록 ${status.tooLargeCount}건"
            if (status.inProgressAttemptId != null) reasons += "워치 진행 중 시도 ${status.inProgressAttemptId}"
            if (status.pendingCount > 0) requestFlush()
        }
        val phoneConflicts = repo.dao.unresolvedConflicts().size
        if (phoneConflicts > 0) reasons += "미해결 DATA_CONFLICT ${phoneConflicts}건"
        val gate = SyncGate(reasons.isEmpty(), reasons, status)
        if (!gate.ready) repo.log("SYNC_GATE_BLOCKED", null, reasons.joinToString(" | "))
        return gate
    }

    /** 워치 시도 결과가 스마트폰 DB 에 저장될 때까지 대기. 저장 행을 반환, 시간 초과 시 null. */
    suspend fun awaitWatchResult(attemptId: String, timeoutMs: Long = 10_000): AttemptEntity? {
        repo.dao.attempt(attemptId)?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val got = withTimeoutOrNull(minOf(remaining, 2_000)) {
                watchResults.first { it.attemptId == attemptId }
            }
            repo.dao.attempt(attemptId)?.let { return it }
            if (got == null) requestFlush()
        }
        return repo.dao.attempt(attemptId)
    }

    suspend fun requestFlush(): Boolean {
        val r = messenger.send(SyncPaths.FLUSH_REQ, ByteArray(0))
        return r is WearMessenger.SendResult.Sent
    }

    suspend fun sendAbort(attemptId: String, reason: String = "ADMIN_ABORT"): Boolean {
        val r = messenger.send(SyncPaths.ABORT, SyncCodec.encode(AbortRequest(attemptId, reason)))
        repo.log("ABORT_SENT", attemptId, "$reason result=$r")
        return r is WearMessenger.SendResult.Sent
    }

    // ------------------------------------------------------------------ 수신 처리 (PhoneSyncService 에서 호출)

    internal suspend fun onMessage(path: String, data: ByteArray, sourceNodeId: String) {
        setReachable(true)
        when (path) {
            SyncPaths.CONFIG_ACK -> {
                val ack = SyncCodec.decode<ConfigAck>(data)
                configWaits[ack.attemptId]?.complete(ack)
            }
            SyncPaths.STATUS -> {
                val st = SyncCodec.decode<WatchStatus>(data)
                statusWaits[st.requestId]?.complete(st)
            }
            SyncPaths.PONG -> {
                val pong = SyncCodec.decode<Pong>(data)
                pongWaits[pong.id]?.complete(pong)
            }
            SyncPaths.RESULT -> handleResult(data, sourceNodeId)
            else -> Log.w(TAG, "unknown path $path")
        }
    }

    private suspend fun handleResult(data: ByteArray, sourceNodeId: String) {
        val upload = try {
            SyncCodec.decodeUpload(data)
        } catch (t: Throwable) {
            // 시도 ID 를 알 수 없으므로 ACK 를 보내지 않는다 → 워치는 PENDING 유지 후 재전송.
            repo.log("RESULT_DECODE_FAILED", null, "${data.size} bytes: $t")
            return
        }
        // saveReceivedAttempt 는 트랜잭션 커밋 후 반환한다 → 그 다음에만 persisted=true ACK.
        val (outcome, hash) = repo.saveReceivedAttempt(upload, sourceNodeId)
        val ack = ResultAck(upload.record.attemptId, outcome, hash, persisted = true)
        val sent = messenger.send(SyncPaths.RESULT_ACK, SyncCodec.encode(ack), sourceNodeId)
        repo.log("RESULT_ACK_SENT", ack.attemptId, "$outcome send=$sent")
        _watchResults.tryEmit(ReceivedResult(ack.attemptId, outcome, upload.record.result.endStatus.name))
    }

    // ------------------------------------------------------------------ Phase 1 T3: 통신 검증

    data class PingSample(val id: Int, val ok: Boolean, val rttMs: Double?, val bytes: Int, val error: String?)
    data class PingReport(val samples: List<PingSample>) {
        val success: Int get() = samples.count { it.ok }
        val fail: Int get() = samples.size - success
        val meanRttMs: Double? get() = samples.mapNotNull { it.rttMs }.takeIf { it.isNotEmpty() }?.average()
        val maxRttMs: Double? get() = samples.mapNotNull { it.rttMs }.maxOrNull()
    }

    /**
     * 송수신 + 데이터 일치 검증. payload 를 보내고 워치가 계산한 SHA-256 과 길이를 비교한다.
     * 기본 20회 × (1KB, 60KB 교대) — 실제 시도 업로드 크기 범위를 포함하도록.
     */
    suspend fun runPingTest(count: Int = 20, timeoutMs: Long = 3_000, onEach: (PingSample) -> Unit = {}): PingReport {
        val samples = mutableListOf<PingSample>()
        repeat(count) { i ->
            val size = if (i % 2 == 0) 1_024 else 60_000
            val payload = buildString(size) { repeat(size) { append(('a'.code + (it * 7 + i) % 26).toChar()) } }
            val expected = SyncCodec.sha256Hex(payload.toByteArray(Charsets.UTF_8))
            val id = pingIds.incrementAndGet()
            val wait = CompletableDeferred<Pong>()
            pongWaits[id] = wait
            val t0 = SystemClock.elapsedRealtimeNanos()
            val sample = when (val r = messenger.send(SyncPaths.PING, SyncCodec.encode(Ping(id, payload)))) {
                is WearMessenger.SendResult.Sent -> {
                    val pong = withTimeoutOrNull(timeoutMs) { wait.await() }
                    val rtt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
                    when {
                        pong == null -> PingSample(id, false, null, r.bytes, "timeout")
                        pong.payloadSha256 != expected || pong.payloadLength != payload.length ->
                            PingSample(id, false, rtt, r.bytes, "data mismatch")
                        else -> PingSample(id, true, rtt, r.bytes, null)
                    }
                }
                else -> PingSample(id, false, null, 0, r.toString())
            }
            pongWaits.remove(id)
            samples += sample
            Log.i(TAG, "PING $sample")
            repo.log("PING_${if (sample.ok) "OK" else "FAIL"}", null, sample.toString())
            onEach(sample)
        }
        return PingReport(samples)
    }

    companion object {
        const val TAG = "HCI-PhoneSync"
        @Volatile private var instance: PhoneSyncClient? = null
        fun get(context: Context): PhoneSyncClient =
            instance ?: synchronized(this) { instance ?: PhoneSyncClient(context).also { instance = it } }
    }
}
