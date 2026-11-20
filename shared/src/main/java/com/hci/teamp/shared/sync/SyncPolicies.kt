package com.hci.teamp.shared.sync

import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.SyncState
import com.hci.teamp.shared.model.TrialEvent

/**
 * 저장·동기화 판단 규칙 (순수 함수 — Room/기기 없이 JVM 단위 테스트로 검증).
 * ExperimentRepository · WearSyncManager 는 판단을 모두 이 객체에 위임한다.
 */
object SyncPolicies {

    const val INTERRUPTED_REASON = "APP_TERMINATED_DURING_TRIAL"

    // ------------------------------------------------------------------ 워치: 전송 대상

    /** 종료된(IN_PROGRESS 가 아닌) 시도이면서 PENDING 인 경우에만 전송한다. */
    fun isUploadable(endStatus: String, syncState: String): Boolean =
        endStatus != EndStatus.IN_PROGRESS.name && syncState == SyncState.PENDING.name

    // ------------------------------------------------------------------ 스마트폰: 수신 판단

    /** 스마트폰 DB 에 이미 있는 같은 ID 기록의 요약 */
    data class Existing(val endStatus: String, val contentHash: String)

    sealed interface ReceiveDecision {
        /** 새 기록 저장 */
        data object SaveNew : ReceiveDecision
        /** 동일 ID·동일 내용 — 다시 저장하지 않음 */
        data object Duplicate : ReceiveDecision
        /** 동일 ID·다른 내용 — 기존 기록 유지, 충돌 보존 */
        data object Conflict : ReceiveDecision
        /** 이전 버전에서 잘못 저장된 IN_PROGRESS 행을 최종 기록으로 확정 (이전 행은 오류 기록으로 보존) */
        data object ReplaceInProgress : ReceiveDecision
        /** 정상 수신이 아님 — attempts 에 저장하지 않고 receive_errors 에 원본 보존 */
        data class Reject(val outcome: SaveOutcome, val detail: String) : ReceiveDecision
    }

    /**
     * 판단 순서
     *  1) 해시 불일치 → 내용을 신뢰할 수 없으므로 어떤 저장·충돌 판정도 하지 않고 거부
     *  2) 진행 중(IN_PROGRESS) 기록 → 최종 결과가 아니므로 거부
     *  3) 기존 기록과 비교 (신규 / 중복 / 기존 IN_PROGRESS 확정 / 충돌)
     */
    fun decideReceive(
        incomingStatus: EndStatus,
        sentHash: String,
        computedHash: String,
        existing: Existing?,
    ): ReceiveDecision = when {
        sentHash != computedHash ->
            ReceiveDecision.Reject(SaveOutcome.REJECTED_HASH_MISMATCH, "sent=$sentHash computed=$computedHash")
        incomingStatus == EndStatus.IN_PROGRESS ->
            ReceiveDecision.Reject(SaveOutcome.REJECTED_IN_PROGRESS, "in-progress record is not a final result")
        existing == null -> ReceiveDecision.SaveNew
        existing.contentHash == computedHash -> ReceiveDecision.Duplicate
        existing.endStatus == EndStatus.IN_PROGRESS.name -> ReceiveDecision.ReplaceInProgress
        else -> ReceiveDecision.Conflict
    }

    fun outcomeOf(d: ReceiveDecision): SaveOutcome = when (d) {
        ReceiveDecision.SaveNew, ReceiveDecision.ReplaceInProgress -> SaveOutcome.SAVED
        ReceiveDecision.Duplicate -> SaveOutcome.DUPLICATE
        ReceiveDecision.Conflict -> SaveOutcome.DATA_CONFLICT
        is ReceiveDecision.Reject -> d.outcome
    }

    // ------------------------------------------------------------------ 워치: ACK 처리

    /**
     * ACK 를 받은 뒤 워치 기록의 새 동기화 상태. null = 상태 유지(재전송 대상 그대로).
     * SYNCED 는 스마트폰이 영구 저장했고(persisted) 해시가 일치할 때만.
     */
    fun watchStateAfterAck(localEndStatus: String, localHash: String, ack: ResultAck): SyncState? = when {
        !ack.persisted -> null
        ack.outcome == SaveOutcome.REJECTED_IN_PROGRESS ->
            if (localEndStatus == EndStatus.IN_PROGRESS.name) SyncState.NOT_READY else SyncState.UPLOAD_REJECTED
        ack.outcome == SaveOutcome.REJECTED_HASH_MISMATCH -> SyncState.UPLOAD_REJECTED
        ack.receivedHash != localHash -> SyncState.CONFLICT
        ack.outcome == SaveOutcome.SAVED || ack.outcome == SaveOutcome.DUPLICATE -> SyncState.SYNCED
        else -> SyncState.CONFLICT
    }

    // ------------------------------------------------------------------ 강제 종료 복구

    /**
     * 시행 중 앱이 종료된 시도를 INVALID 로 확정한 기록.
     * 시행 중 영구 저장된 이벤트를 그대로 포함하며, 관찰되지 않은 종료 이벤트는 만들지 않는다.
     */
    fun buildInterruptedRecord(partial: AttemptRecord, storedEvents: List<TrialEvent>): AttemptRecord {
        val events = (if (storedEvents.isNotEmpty()) storedEvents else partial.events).sortedBy { it.seq }
        return partial.copy(
            result = partial.result.copy(
                endStatus = EndStatus.INVALID,
                invalidReason = INTERRUPTED_REASON,
                endWallMs = null,
                elapsedMs = null,
                finalVolume = null,
                metrics = null,
            ),
            events = events,
        )
    }
}
