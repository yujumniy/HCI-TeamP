package com.hci.teamp.wear.sync

import android.content.Context
import android.util.Log
import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.db.ReceivedConfigEntity
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.SyncState
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.recorder.AttemptRecorder
import com.hci.teamp.shared.recorder.RepositoryAttemptStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 워치 쪽 통신·저장 관리자.
 *
 * 워치 UI(4번)가 쓰는 API
 *   onAppStart()            : 앱 시작 시 1회 — 중단된 시도 INVALID 처리 + 미전송 재전송 시작
 *   pendingConfig           : 스마트폰에서 받은 아직 시작 안 한 시행 설정(Flow)
 *   newRecorder(config)     : 해당 설정으로 AttemptRecorder 생성 (저장 → 자동 전송까지 연결됨)
 *   abortRequests           : 관리자 긴급 중단 요청(Flow) → recorder.invalidate(reason) 호출
 *
 * 원칙
 *   - 시행 결과는 먼저 워치 DB 에 커밋된 뒤 전송된다. 연결이 끊겨도 진행 중 시행은 계속된다(13.2).
 *   - 스마트폰이 persisted=true ACK 를 보내기 전에는 SYNCED 로 바꾸지 않는다.
 *   - SYNCED 이후에도 워치 원본은 삭제하지 않는다.
 */
class WearSyncManager private constructor(context: Context) {

    private val app = context.applicationContext
    val repo: ExperimentRepository = ExperimentRepository.get(app)
    private val messenger = WearMessenger(app, SyncPaths.CAP_PHONE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val configMutex = Mutex()
    private var retryJob: Job? = null

    private val _abortRequests = MutableSharedFlow<AbortRequest>(replay = 1, extraBufferCapacity = 8)
    val abortRequests: SharedFlow<AbortRequest> = _abortRequests.asSharedFlow()

    val pendingConfig: Flow<TrialConfig?> =
        repo.dao.observeLatestUnstartedConfig().map { e -> e?.let { SyncCodec.json.decodeFromString(TrialConfig.serializer(), it.configJson) } }

    val pendingUploadCount: Flow<Int> = repo.dao.observeCountBySyncState(SyncState.PENDING.name)

    /** 워치 시행용 저장소: 로컬 커밋 후 곧바로 스마트폰 전송을 시도한다. */
    private val store = object : RepositoryAttemptStore(repo) {
        override suspend fun begin(partial: AttemptRecord, deviceType: DeviceType) {
            super.begin(partial, deviceType)
            repo.dao.setConfigState(partial.attemptId, "STARTED")
        }

        override suspend fun finalize(record: AttemptRecord, deviceType: DeviceType): SaveOutcome {
            val outcome = super.finalize(record, deviceType)
            repo.dao.setConfigState(record.attemptId, "FINISHED")
            scope.launch { flushPending(); ensureRetryLoop() }
            return outcome
        }
    }

    fun newRecorder(config: TrialConfig): AttemptRecorder = AttemptRecorder(config, store, DeviceType.WATCH)

    fun onAppStart() {
        scope.launch {
            val invalidated = repo.invalidateInterruptedAttempts(DeviceType.WATCH)
            if (invalidated.isNotEmpty()) Log.w(TAG, "invalidated after restart: $invalidated")
            flushPending()
            ensureRetryLoop()
        }
    }

    // ------------------------------------------------------------------ 업로드

    /** PENDING 기록 전체를 전송. ACK 는 비동기로 onResultAck 에서 처리된다. */
    suspend fun flushPending() = flushMutex.withLock {
        val pending = repo.pendingUploads()
        if (pending.isEmpty()) return@withLock
        val peer = messenger.reachablePeer()
        if (peer == null) {
            repo.log("FLUSH_SKIPPED_NO_PHONE", null, "pending=${pending.size}")
            return@withLock
        }
        for (upload in pending) {
            val bytes = SyncCodec.encodeUpload(upload)
            repo.dao.markSyncTried(upload.record.attemptId, System.currentTimeMillis())
            when (val r = messenger.send(SyncPaths.RESULT, bytes, peer.id)) {
                is WearMessenger.SendResult.Sent -> repo.log("RESULT_SENT", upload.record.attemptId, "${r.bytes} bytes")
                is WearMessenger.SendResult.TooLarge -> {
                    // 자동 축소·분할하지 않는다. 관리자 확인 대상으로 표시.
                    repo.dao.setSyncState(upload.record.attemptId, SyncState.TOO_LARGE.name)
                    repo.log("RESULT_TOO_LARGE", upload.record.attemptId, "${r.bytes} bytes")
                }
                else -> {
                    repo.log("RESULT_SEND_FAILED", upload.record.attemptId, r.toString())
                    return@withLock // 연결 문제 → 다음 재시도 주기에
                }
            }
        }
    }

    /** 미전송 기록이 남아 있는 동안 지수 백오프(2s → 최대 30s)로 재전송. */
    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var waitMs = 2_000L
            while (isActive) {
                delay(waitMs)
                if (repo.dao.countBySyncState(SyncState.PENDING.name) == 0) break
                flushPending()
                waitMs = (waitMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun onResultAck(ack: ResultAck) {
        val local = repo.dao.attempt(ack.attemptId) ?: run {
            repo.log("ACK_UNKNOWN_ATTEMPT", ack.attemptId, ack.outcome.name); return
        }
        val newState = when {
            !ack.persisted -> null
            ack.receivedHash != local.contentHash -> SyncState.CONFLICT
            ack.outcome == SaveOutcome.SAVED || ack.outcome == SaveOutcome.DUPLICATE -> SyncState.SYNCED
            else -> SyncState.CONFLICT
        }
        if (newState != null) repo.dao.setSyncState(ack.attemptId, newState.name)
        repo.log("RESULT_ACK_${ack.outcome}", ack.attemptId, "state=$newState hashMatch=${ack.receivedHash == local.contentHash}")
    }

    // ------------------------------------------------------------------ 설정 수신

    private suspend fun onConfig(data: ByteArray): ConfigAck = configMutex.withLock {
        val config = try {
            SyncCodec.decode<TrialConfig>(data)
        } catch (t: Throwable) {
            repo.log("CONFIG_INVALID", null, t.toString())
            return@withLock ConfigAck("", ConfigAckStatus.REJECTED_INVALID, t.message)
        }
        if (config.device != DeviceType.WATCH) {
            return@withLock ConfigAck(config.attemptId, ConfigAckStatus.REJECTED_INVALID, "not a watch condition")
        }
        val json = String(data, Charsets.UTF_8)
        val existing = repo.dao.config(config.attemptId)
        if (existing != null) {
            val same = SyncCodec.json.decodeFromString(TrialConfig.serializer(), existing.configJson) == config
            return@withLock if (same) ConfigAck(config.attemptId, ConfigAckStatus.DUPLICATE)
            else ConfigAck(config.attemptId, ConfigAckStatus.REJECTED_CONFLICT, "same attemptId, different config")
        }
        if (repo.dao.attempt(config.attemptId) != null) {
            return@withLock ConfigAck(config.attemptId, ConfigAckStatus.REJECTED_CONFLICT, "attempt already recorded")
        }
        repo.dao.inProgressAttempts().firstOrNull()?.let {
            return@withLock ConfigAck(config.attemptId, ConfigAckStatus.REJECTED_IN_PROGRESS, it.attemptId)
        }
        val pending = repo.dao.countBySyncState(SyncState.PENDING.name)
        if (pending > 0) {
            scope.launch { flushPending() }
            return@withLock ConfigAck(config.attemptId, ConfigAckStatus.REJECTED_PENDING_SYNC, "pending=$pending")
        }
        repo.dao.insertConfig(ReceivedConfigEntity(config.attemptId, json, System.currentTimeMillis(), "RECEIVED"))
        repo.log("CONFIG_ACCEPTED", config.attemptId, null)
        ConfigAck(config.attemptId, ConfigAckStatus.ACCEPTED)
    }

    private suspend fun status(req: StatusRequest): WatchStatus = WatchStatus(
        requestId = req.requestId,
        pendingCount = repo.dao.countBySyncState(SyncState.PENDING.name),
        conflictCount = repo.dao.countBySyncState(SyncState.CONFLICT.name),
        tooLargeCount = repo.dao.countBySyncState(SyncState.TOO_LARGE.name),
        inProgressAttemptId = repo.dao.inProgressAttempts().firstOrNull()?.attemptId,
        lastSyncedAttemptId = repo.dao.lastSyncedAttemptId(),
    )

    // ------------------------------------------------------------------ 수신 라우팅 (WearSyncService 에서 호출)

    internal suspend fun onMessage(path: String, data: ByteArray, sourceNodeId: String) {
        when (path) {
            SyncPaths.CONFIG -> {
                val ack = onConfig(data)
                messenger.send(SyncPaths.CONFIG_ACK, SyncCodec.encode(ack), sourceNodeId)
            }
            SyncPaths.RESULT_ACK -> onResultAck(SyncCodec.decode(data))
            SyncPaths.STATUS_REQ -> {
                val st = status(SyncCodec.decode(data))
                messenger.send(SyncPaths.STATUS, SyncCodec.encode(st), sourceNodeId)
            }
            SyncPaths.FLUSH_REQ -> { flushPending(); ensureRetryLoop() }
            SyncPaths.ABORT -> {
                val req = SyncCodec.decode<AbortRequest>(data)
                repo.log("ABORT_RECEIVED", req.attemptId, req.reason)
                _abortRequests.emit(req)
            }
            SyncPaths.PING -> {
                val ping = SyncCodec.decode<Ping>(data)
                val bytes = ping.payload.toByteArray(Charsets.UTF_8)
                val pong = Pong(ping.id, SyncCodec.sha256Hex(bytes), ping.payload.length)
                messenger.send(SyncPaths.PONG, SyncCodec.encode(pong), sourceNodeId)
            }
            else -> Log.w(TAG, "unknown path $path")
        }
    }

    internal fun onPhoneReachabilityChanged(reachable: Boolean) {
        scope.launch {
            repo.log(if (reachable) "PHONE_CONNECTED" else "PHONE_DISCONNECTED", null, null)
            if (reachable) { flushPending(); ensureRetryLoop() }
        }
    }

    companion object {
        const val TAG = "HCI-WearSync"
        @Volatile private var instance: WearSyncManager? = null
        fun get(context: Context): WearSyncManager =
            instance ?: synchronized(this) { instance ?: WearSyncManager(context).also { instance = it } }
    }
}
