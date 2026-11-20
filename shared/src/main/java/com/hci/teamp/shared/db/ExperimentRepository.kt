package com.hci.teamp.shared.db

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.Ids
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.SyncState
import com.hci.teamp.shared.model.TrialEvent
import com.hci.teamp.shared.sync.AttemptUpload
import com.hci.teamp.shared.sync.ContentHash
import com.hci.teamp.shared.sync.SyncCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 3번 담당 저장소의 단일 진입점. 1·2·4번은 DAO 를 직접 쓰지 말고 이 클래스(또는 AttemptRecorder)만 사용한다.
 */
class ExperimentRepository(val db: ExperimentDatabase) {

    val dao: ExperimentDao = db.dao()

    // ------------------------------------------------------------------ 참가자

    /** 4번이 seq/참가자 ID 를 보고 그룹·조건 순서·과제 순서를 계산해 돌려준다. */
    data class ParticipantPlan(
        val groupId: String,
        val conditionOrder: List<ConditionId>,
        /** 조건별 본 실험 과제 순서 (예: C1 -> [T01, T02, T08, …]) */
        val taskOrders: Map<ConditionId, List<String>>,
    )

    /** 순차 ID 발급과 저장을 하나의 트랜잭션으로 처리해 중복 ID 를 막는다(12.1). */
    suspend fun registerParticipant(plan: (seq: Int, participantId: String) -> ParticipantPlan): ParticipantEntity =
        db.withTransaction {
            val seq = dao.maxParticipantSeq() + 1
            val pid = Ids.participantId(seq)
            val p = plan(seq, pid)
            require(p.conditionOrder.toSet() == ConditionId.entries.toSet()) { "conditionOrder must contain C1..C4 once" }
            val entity = ParticipantEntity(
                participantId = pid,
                seq = seq,
                groupId = p.groupId,
                conditionOrder = p.conditionOrder.joinToString(",") { it.name },
                taskOrderJson = SyncCodec.json.encodeToString(p.taskOrders.mapKeys { it.key.name }),
                createdAtWallMs = System.currentTimeMillis(),
            )
            dao.insertParticipant(entity)
            log("PARTICIPANT_REGISTERED", null, pid)
            entity
        }

    /** 저장된 과제 순서를 그대로 읽는다(재실행·복구 시 재생성 금지, 7.2). */
    suspend fun taskOrdersOf(participantId: String): Map<ConditionId, List<String>>? {
        val p = dao.participant(participantId) ?: return null
        val raw: Map<String, List<String>> = SyncCodec.json.decodeFromString(p.taskOrderJson)
        return raw.mapKeys { ConditionId.valueOf(it.key) }
    }

    /** 새 시도 번호 (A01, A02 …). 재시도·복구 시 4번이 호출. */
    suspend fun nextAttemptNo(participantId: String, condition: ConditionId, taskId: String): Int =
        dao.maxAttemptNo(Ids.logicalTaskId(participantId, condition, taskId)) + 1

    /** COMPLETED 또는 TIMEOUT 으로 끝난 논리 과제 ID 목록 — 4번이 재개 지점을 정할 때 사용(13.3). */
    suspend fun finishedLogicalTaskIds(participantId: String): Set<String> =
        dao.attemptsOf(participantId)
            .filter { it.endStatus == EndStatus.COMPLETED.name || it.endStatus == EndStatus.TIMEOUT.name }
            .map { it.logicalTaskId }
            .toSet()

    // ------------------------------------------------------------------ 시도 저장 (시행 기기 로컬)

    /** START 시점에 IN_PROGRESS 행을 남긴다 → 앱이 죽으면 복구 시 INVALID 로 기록하기 위함(13.3). */
    suspend fun beginLocalAttempt(partial: AttemptRecord, deviceType: DeviceType) {
        val syncState = if (deviceType == DeviceType.WATCH) SyncState.PENDING else SyncState.LOCAL_PHONE
        db.withTransaction {
            val existing = dao.attempt(partial.attemptId)
            if (existing != null) {
                // 같은 시도 ID 로 두 번 시작하려는 것은 4번 로직 오류. 기존 기록을 건드리지 않는다.
                log("BEGIN_REJECTED_EXISTS", partial.attemptId, existing.endStatus)
                throw IllegalStateException("attempt ${partial.attemptId} already exists (${existing.endStatus})")
            }
            dao.insertAttempt(partial.toEntity(syncState, deviceType.name, receivedAt = null))
            dao.insertEvents(partial.events.map { it.toEntity() })
        }
    }

    /**
     * 종료된 시도를 확정 저장한다. IN_PROGRESS 행만 확정할 수 있다.
     * 이미 종료된 행이 있으면 덮어쓰지 않고 DUPLICATE / DATA_CONFLICT 를 반환한다.
     */
    suspend fun finalizeLocalAttempt(record: AttemptRecord, deviceType: DeviceType): SaveOutcome {
        require(record.result.endStatus != EndStatus.IN_PROGRESS) { "finalize requires an end status" }
        val syncState = if (deviceType == DeviceType.WATCH) SyncState.PENDING else SyncState.LOCAL_PHONE
        val hash = ContentHash.of(record)
        return db.withTransaction {
            val existing = dao.attempt(record.attemptId)
            when {
                existing == null -> {
                    dao.insertAttempt(record.toEntity(syncState, deviceType.name, null, hash))
                    dao.insertEvents(record.events.map { it.toEntity() })
                    SaveOutcome.SAVED
                }
                existing.endStatus == EndStatus.IN_PROGRESS.name -> {
                    val alreadyStored = dao.eventsOf(record.attemptId).map { it.seq }.toSet()
                    dao.updateAttempt(record.toEntity(syncState, deviceType.name, null, hash))
                    dao.insertEvents(record.events.filter { it.seq !in alreadyStored }.map { it.toEntity() })
                    SaveOutcome.SAVED
                }
                existing.contentHash == hash -> SaveOutcome.DUPLICATE
                else -> {
                    recordConflict(record, existing.contentHash, hash, "LOCAL_FINALIZE")
                    SaveOutcome.DATA_CONFLICT
                }
            }
        }.also { log("LOCAL_FINALIZE_$it", record.attemptId, record.result.endStatus.name) }
    }

    /**
     * 앱 시작 시 호출: 비정상 종료로 남은 IN_PROGRESS 시도를 INVALID 로 확정(13.3).
     * 메모리에만 있던 이벤트는 복구할 수 없으며, 관찰되지 않은 이벤트를 만들어 넣지 않는다.
     */
    suspend fun invalidateInterruptedAttempts(deviceType: DeviceType): List<String> {
        val ids = mutableListOf<String>()
        for (row in dao.inProgressAttempts()) {
            val partial: AttemptRecord = SyncCodec.json.decodeFromString(row.recordJson)
            val invalid = partial.copy(
                result = partial.result.copy(
                    endStatus = EndStatus.INVALID,
                    invalidReason = "APP_TERMINATED_DURING_TRIAL",
                    endWallMs = null,
                    elapsedMs = null,
                    finalVolume = null,
                    metrics = null,
                )
            )
            finalizeLocalAttempt(invalid, deviceType)
            ids += row.attemptId
        }
        if (ids.isNotEmpty()) log("RECOVERY_INVALIDATED", null, ids.joinToString(","))
        return ids
    }

    // ------------------------------------------------------------------ 수신 저장 (스마트폰)

    /**
     * 워치에서 받은 시도를 저장한다(12.3).
     *  새 ID → SAVED / 동일 ID·동일 내용 → DUPLICATE / 동일 ID·다른 내용 → DATA_CONFLICT(보존)
     * 반환 시점에는 트랜잭션 커밋이 끝나 있다 → 이후 ACK(persisted=true) 전송.
     */
    suspend fun saveReceivedAttempt(upload: AttemptUpload, sourceNode: String): Pair<SaveOutcome, String> {
        val record = upload.record
        val hash = ContentHash.of(record)
        if (hash != upload.contentHash) {
            log("UPLOAD_HASH_MISMATCH", record.attemptId, "sent=${upload.contentHash} computed=$hash")
        }
        val outcome = db.withTransaction {
            val existing = dao.attempt(record.attemptId)
            when {
                existing == null -> {
                    dao.insertAttempt(record.toEntity(SyncState.RECEIVED, "WATCH:$sourceNode", System.currentTimeMillis(), hash))
                    dao.insertEvents(record.events.map { it.toEntity() })
                    SaveOutcome.SAVED
                }
                existing.contentHash == hash -> SaveOutcome.DUPLICATE
                else -> {
                    recordConflict(record, existing.contentHash, hash, "WATCH:$sourceNode")
                    SaveOutcome.DATA_CONFLICT
                }
            }
        }
        log("RECEIVE_$outcome", record.attemptId, sourceNode)
        return outcome to hash
    }

    private suspend fun recordConflict(record: AttemptRecord, existingHash: String, incomingHash: String, source: String) {
        if (dao.conflictExists(record.attemptId, incomingHash) > 0) return // 같은 충돌 재수신은 한 번만 보관
        dao.insertConflict(
            ConflictEntity(
                attemptId = record.attemptId,
                existingHash = existingHash,
                incomingHash = incomingHash,
                incomingRecordJson = SyncCodec.json.encodeToString(record),
                source = source,
                detectedAtWallMs = System.currentTimeMillis(),
            )
        )
        Log.w(TAG, "DATA_CONFLICT ${record.attemptId} existing=$existingHash incoming=$incomingHash")
    }

    // ------------------------------------------------------------------ 워치 업로드 대기열

    suspend fun pendingUploads(): List<AttemptUpload> =
        dao.attemptsBySyncState(SyncState.PENDING.name).map {
            AttemptUpload(SyncCodec.json.decodeFromString(it.recordJson), it.contentHash)
        }

    suspend fun upload(attemptId: String): AttemptUpload? =
        dao.attempt(attemptId)?.let { AttemptUpload(SyncCodec.json.decodeFromString(it.recordJson), it.contentHash) }

    // ------------------------------------------------------------------ 로그

    suspend fun log(type: String, attemptId: String?, detail: String?) {
        Log.i(TAG, "$type attempt=$attemptId $detail")
        try {
            dao.insertSyncLog(
                SyncLogEntity(
                    wallMs = System.currentTimeMillis(),
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    type = type,
                    attemptId = attemptId,
                    detail = detail,
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sync_log insert failed", t)
        }
    }

    companion object {
        const val TAG = "HCI-Data"

        @Volatile private var instance: ExperimentRepository? = null
        fun get(context: android.content.Context): ExperimentRepository =
            instance ?: synchronized(this) {
                instance ?: ExperimentRepository(ExperimentDatabase.get(context)).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------- 변환

internal fun AttemptRecord.toEntity(
    syncState: SyncState,
    source: String,
    receivedAt: Long?,
    hash: String = ContentHash.of(this),
): AttemptEntity {
    val r = result
    val c = r.config
    val m = r.metrics
    return AttemptEntity(
        attemptId = c.attemptId,
        logicalTaskId = c.logicalTaskId,
        participantId = c.participantId,
        conditionId = c.condition.name,
        taskId = c.taskId,
        attemptNo = c.attemptNo,
        phase = c.phase.name,
        orderIndex = c.orderIndex,
        deviceType = r.deviceType.name,
        deviceModel = r.deviceModel,
        startVolume = c.startVolume,
        targetVolume = c.targetVolume,
        finalVolume = r.finalVolume,
        endStatus = r.endStatus.name,
        elapsedMs = r.elapsedMs,
        startWallMs = r.startWallMs,
        endWallMs = r.endWallMs,
        invalidReason = r.invalidReason,
        maxOvershoot = m?.maxOvershoot,
        overshootCount = m?.overshootCount,
        reachedTarget = m?.reachedTarget,
        firstReachMs = m?.firstReachMs,
        finalAbsError = m?.finalAbsError,
        directionChanges = m?.directionChanges,
        pathDistance = m?.pathDistance,
        firstInputMs = m?.firstInputMs,
        recordJson = SyncCodec.json.encodeToString(this),
        contentHash = hash,
        sourceDevice = source,
        syncState = syncState.name,
        receivedAtWallMs = receivedAt,
    )
}

internal fun TrialEvent.toEntity(): EventEntity = EventEntity(
    attemptId = attemptId,
    seq = seq,
    type = type.name,
    elapsedNs = elapsedNs,
    wallMs = wallMs,
    value = value,
    rawJson = SyncCodec.json.encodeToString<Map<String, String>>(raw.toSortedMap()),
)
