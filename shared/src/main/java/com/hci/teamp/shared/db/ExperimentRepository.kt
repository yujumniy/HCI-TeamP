package com.hci.teamp.shared.db

import android.os.SystemClock
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
import com.hci.teamp.shared.sync.SyncPolicies
import com.hci.teamp.shared.sync.SyncPolicies.ReceiveDecision
import com.hci.teamp.shared.util.HciLog
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

    /** Preallocate the fixed roster atomically; never replace a started participant. */
    suspend fun ensureFixedRoster() = db.withTransaction {
        val expected = com.hci.teamp.shared.experiment.FixedRoster.slots
        check(dao.allParticipants().all { p -> expected.any { it.id == p.participantId } })
        for (slot in expected) {
            val existing = dao.participant(slot.id)
            if (existing != null) {
                check(existing.groupId == slot.group && existing.conditionOrder == slot.conditions.joinToString(",") { it.name })
                continue
            }
            val orders = com.hci.teamp.shared.experiment.StudyPlan.taskOrders(slot.id)
            dao.insertParticipant(ParticipantEntity(slot.id, slot.number, slot.group,
                slot.conditions.joinToString(",") { it.name },
                SyncCodec.json.encodeToString(orders.mapKeys { it.key.name }), System.currentTimeMillis()))
        }
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

    /**
     * START 시점에 IN_PROGRESS 행을 남긴다 → 앱이 죽으면 복구 시 INVALID 로 기록하기 위함(13.3).
     * 워치의 진행 중 시도는 NOT_READY: 종료(finalize) 전에는 절대 전송 대상이 되지 않는다.
     */
    suspend fun beginLocalAttempt(partial: AttemptRecord, deviceType: DeviceType) {
        val syncState = if (deviceType == DeviceType.WATCH) SyncState.NOT_READY else SyncState.LOCAL_PHONE
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
     * 시행 중 관찰된 이벤트 1건을 즉시 영구 저장한다 (강제 종료 시 입력 기록 보존).
     * IN_PROGRESS 시도에만 추가하며, 같은 seq 가 이미 있으면 다시 넣지 않는다.
     * @return 저장했으면 true
     */
    suspend fun appendLocalEvent(event: TrialEvent): Boolean = db.withTransaction {
        val row = dao.attempt(event.attemptId)
        when {
            row == null -> { HciLog.w(TAG, "appendEvent: no attempt row ${event.attemptId}#${event.seq}"); false }
            row.endStatus != EndStatus.IN_PROGRESS.name -> false
            dao.eventCount(event.attemptId, event.seq) > 0 -> false
            else -> { dao.insertEvent(event.toEntity()); true }
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
     * 시행 중 영구 저장된 이벤트를 그대로 포함하고, 관찰되지 않은 이벤트는 만들어 넣지 않는다.
     */
    suspend fun invalidateInterruptedAttempts(deviceType: DeviceType): List<String> {
        val ids = mutableListOf<String>()
        for (row in dao.inProgressAttempts()) {
            val partial: AttemptRecord = SyncCodec.json.decodeFromString(row.recordJson)
            val stored = dao.eventsOf(row.attemptId).map { it.toModel() }
            val invalid = SyncPolicies.buildInterruptedRecord(partial, stored)
            finalizeLocalAttempt(invalid, deviceType)
            ids += row.attemptId
        }
        if (ids.isNotEmpty()) log("RECOVERY_INVALIDATED", null, ids.joinToString(","))
        return ids
    }

    // ------------------------------------------------------------------ 수신 저장 (스마트폰)

    /**
     * 워치에서 받은 시도를 저장한다(12.3). 판단은 SyncPolicies.decideReceive 를 따른다.
     *  새 ID → SAVED / 동일 ID·동일 내용 → DUPLICATE / 동일 ID·다른 내용 → DATA_CONFLICT(보존)
     *  해시 불일치 → REJECTED_HASH_MISMATCH, 진행 중 기록 → REJECTED_IN_PROGRESS
     *    (둘 다 attempts 에 저장하지 않고 receive_errors 에 받은 내용 그대로 보존)
     * 반환 시점에는 트랜잭션 커밋이 끝나 있다 → 이후 ACK(persisted=true) 전송.
     * @return (결과, 스마트폰이 계산한 해시)
     */
    suspend fun saveReceivedAttempt(upload: AttemptUpload, sourceNode: String): Pair<SaveOutcome, String> {
        val record = upload.record
        val hash = ContentHash.of(record)
        val source = "WATCH:$sourceNode"
        val decision: ReceiveDecision = db.withTransaction {
            val existing = dao.attempt(record.attemptId)
            val d = SyncPolicies.decideReceive(
                incomingStatus = record.result.endStatus,
                sentHash = upload.contentHash,
                computedHash = hash,
                existing = existing?.let { SyncPolicies.Existing(it.endStatus, it.contentHash) },
            )
            when (d) {
                ReceiveDecision.SaveNew -> {
                    dao.insertAttempt(record.toEntity(SyncState.RECEIVED, source, System.currentTimeMillis(), hash))
                    dao.insertEvents(record.events.map { it.toEntity() })
                }
                ReceiveDecision.ReplaceInProgress -> {
                    // 이전 버전이 저장한 진행 중 행: 내용을 오류 기록으로 보존한 뒤 최종 기록으로 확정
                    existing!!
                    recordReceiveError(
                        attemptId = existing.attemptId, type = "IN_PROGRESS_SUPERSEDED",
                        sentHash = existing.contentHash, computedHash = existing.contentHash,
                        endStatus = existing.endStatus, payloadJson = existing.recordJson, source = existing.sourceDevice,
                        resolved = true, note = "superseded by final record $hash",
                    )
                    val stored = dao.eventsOf(record.attemptId).associateBy { it.seq }
                    val clash = record.events.any { e -> stored[e.seq]?.let { it.type != e.type.name || it.elapsedNs != e.elapsedNs } == true }
                    if (clash) {
                        // 같은 seq 에 다른 이벤트 → 덮어쓰지 않고 충돌로 보존
                        recordConflict(record, existing.contentHash, hash, source)
                        return@withTransaction ReceiveDecision.Conflict
                    }
                    dao.updateAttempt(record.toEntity(SyncState.RECEIVED, source, System.currentTimeMillis(), hash))
                    dao.insertEvents(record.events.filter { it.seq !in stored }.map { it.toEntity() })
                }
                ReceiveDecision.Duplicate -> Unit
                ReceiveDecision.Conflict -> recordConflict(record, existing!!.contentHash, hash, source)
                is ReceiveDecision.Reject -> recordReceiveError(
                    attemptId = record.attemptId,
                    type = if (d.outcome == SaveOutcome.REJECTED_HASH_MISMATCH) "HASH_MISMATCH" else "IN_PROGRESS_RECEIVED",
                    sentHash = upload.contentHash, computedHash = hash,
                    endStatus = record.result.endStatus.name,
                    payloadJson = SyncCodec.json.encodeToString(record), source = source,
                    resolved = false, note = d.detail,
                )
            }
            d
        }
        val outcome = SyncPolicies.outcomeOf(decision)
        log("RECEIVE_$outcome", record.attemptId, "$sourceNode decision=$decision")
        return outcome to hash
    }

    private suspend fun recordReceiveError(
        attemptId: String, type: String, sentHash: String, computedHash: String, endStatus: String,
        payloadJson: String, source: String, resolved: Boolean, note: String?,
    ) {
        if (dao.receiveErrorExists(attemptId, type, sentHash) > 0) return // 같은 내용 재수신은 한 번만 보관
        dao.insertReceiveError(
            ReceiveErrorEntity(
                attemptId = attemptId, errorType = type, sentHash = sentHash, computedHash = computedHash,
                endStatus = endStatus, payloadJson = payloadJson, source = source,
                detectedAtWallMs = System.currentTimeMillis(), resolved = resolved, resolutionNote = note,
            )
        )
        HciLog.w(TAG, "RECEIVE_ERROR $type $attemptId sent=$sentHash computed=$computedHash")
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
        HciLog.w(TAG, "DATA_CONFLICT ${record.attemptId} existing=$existingHash incoming=$incomingHash")
    }

    // ------------------------------------------------------------------ 워치 업로드 대기열

    /** 전송 대상: 종료된 시도만 (진행 중 시도는 SQL 과 정책 양쪽에서 이중으로 제외). */
    suspend fun pendingUploads(): List<AttemptUpload> =
        dao.uploadableAttempts()
            .filter { SyncPolicies.isUploadable(it.endStatus, it.syncState) }
            .map { AttemptUpload(SyncCodec.json.decodeFromString(it.recordJson), it.contentHash) }

    suspend fun upload(attemptId: String): AttemptUpload? =
        dao.attempt(attemptId)?.let { AttemptUpload(SyncCodec.json.decodeFromString(it.recordJson), it.contentHash) }

    // ------------------------------------------------------------------ 로그

    suspend fun log(type: String, attemptId: String?, detail: String?) {
        HciLog.i(TAG, "$type attempt=$attemptId $detail")
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
            HciLog.e(TAG, "sync_log insert failed", t)
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

internal fun EventEntity.toModel(): TrialEvent = TrialEvent(
    attemptId = attemptId,
    seq = seq,
    type = com.hci.teamp.shared.model.EventType.valueOf(type),
    elapsedNs = elapsedNs,
    wallMs = wallMs,
    value = value,
    raw = SyncCodec.json.decodeFromString<Map<String, String>>(rawJson),
)
