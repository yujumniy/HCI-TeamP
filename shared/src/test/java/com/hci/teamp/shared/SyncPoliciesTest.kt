package com.hci.teamp.shared

import com.hci.teamp.shared.metrics.TrialMetrics
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.EventType
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.SyncState
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialEvent
import com.hci.teamp.shared.model.TrialPhase
import com.hci.teamp.shared.model.TrialResult
import com.hci.teamp.shared.sync.AttemptUpload
import com.hci.teamp.shared.sync.ContentHash
import com.hci.teamp.shared.sync.ResultAck
import com.hci.teamp.shared.sync.SyncCodec
import com.hci.teamp.shared.sync.SyncPolicies
import com.hci.teamp.shared.sync.SyncPolicies.ReceiveDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 저장·동기화 수정 검증 (코드 검토 요청 3건)
 *  A. 재연결 중 진행 기록 전송
 *  B. 해시 불일치
 *  C. 시행 중 강제 종료 후 복구 기록
 */
class SyncPoliciesTest {

    private val cfg = TrialConfig("P001-C3-T04-A01", "P001", ConditionId.C3, "T04", 1, TrialPhase.MAIN, 50, 70, 3)

    private fun ev(seq: Int, type: EventType, ms: Long, v: Int? = null) =
        TrialEvent(cfg.attemptId, seq, type, ms * 1_000_000, 1_000 + ms, v)

    private val startEv = ev(0, EventType.START, 0, 50)
    private val midEvents = listOf(
        ev(1, EventType.INPUT_DOWN, 300),
        ev(2, EventType.VALUE_CHANGE, 400, 55),
        ev(3, EventType.VALUE_CHANGE, 500, 60),
    )

    private fun inProgress(): AttemptRecord = AttemptRecord(
        TrialResult(cfg, DeviceType.WATCH, "SM-R870", EndStatus.IN_PROGRESS, null, null, 1_000, null),
        listOf(startEv),
    )

    private fun completed(final: Int = 70): AttemptRecord {
        val events = listOf(startEv) + midEvents +
            listOf(ev(4, EventType.VALUE_CHANGE, 700, final), ev(5, EventType.COMPLETE, 900, final))
        return AttemptRecord(
            TrialResult(
                cfg, DeviceType.WATCH, "SM-R870", EndStatus.COMPLETED, final, 900.0, 1_000, 1_900,
                metrics = TrialMetrics.compute(50, 70, events),
            ),
            events,
        )
    }

    // ================================================================== A. 재연결 중 진행 기록 전송

    @Test fun A1_inProgressAttemptIsNeverUploadable() {
        assertFalse(SyncPolicies.isUploadable("IN_PROGRESS", "PENDING"))   // v1 버그 상태여도 제외
        assertFalse(SyncPolicies.isUploadable("IN_PROGRESS", "NOT_READY"))
        assertFalse(SyncPolicies.isUploadable("COMPLETED", "NOT_READY"))
        assertTrue(SyncPolicies.isUploadable("COMPLETED", "PENDING"))
        assertTrue(SyncPolicies.isUploadable("TIMEOUT", "PENDING"))
        assertTrue(SyncPolicies.isUploadable("INVALID", "PENDING"))
        assertFalse(SyncPolicies.isUploadable("COMPLETED", "SYNCED"))
    }

    @Test fun A2_phoneRejectsInProgressRecordEvenIfSent() {
        val r = inProgress()
        val h = ContentHash.of(r)
        val d = SyncPolicies.decideReceive(r.result.endStatus, h, h, existing = null)
        assertTrue(d is ReceiveDecision.Reject)
        assertEquals(SaveOutcome.REJECTED_IN_PROGRESS, SyncPolicies.outcomeOf(d))
    }

    @Test fun A3_reconnectMidTrial_finalRecordIsSavedNotConflict() {
        // 1) 시행 중 재연결 → (구버전처럼) 진행 기록이 오더라도 거부되어 attempts 에 저장되지 않음
        val partial = inProgress()
        val ph = ContentHash.of(partial)
        val first = SyncPolicies.decideReceive(partial.result.endStatus, ph, ph, existing = null)
        assertTrue(first is ReceiveDecision.Reject)
        // 2) 시행 종료 후 같은 ID 최종 기록 → 기존 행이 없으므로 정상 저장 (DATA_CONFLICT 아님)
        val final = completed()
        val fh = ContentHash.of(final)
        val second = SyncPolicies.decideReceive(final.result.endStatus, fh, fh, existing = null)
        assertEquals(ReceiveDecision.SaveNew, second)
        assertEquals(SaveOutcome.SAVED, SyncPolicies.outcomeOf(second))
    }

    @Test fun A4_legacyInProgressRowOnPhoneIsFinalizedNotConflict() {
        // v1 이 이미 폰에 IN_PROGRESS 를 저장해 둔 경우
        val legacy = inProgress()
        val final = completed()
        val fh = ContentHash.of(final)
        val d = SyncPolicies.decideReceive(
            final.result.endStatus, fh, fh,
            SyncPolicies.Existing(EndStatus.IN_PROGRESS.name, ContentHash.of(legacy)),
        )
        assertEquals(ReceiveDecision.ReplaceInProgress, d)
        assertEquals(SaveOutcome.SAVED, SyncPolicies.outcomeOf(d))
    }

    @Test fun A5_watchAckForInProgressKeepsItOutOfQueue() {
        val ack = ResultAck(cfg.attemptId, SaveOutcome.REJECTED_IN_PROGRESS, "h", persisted = true)
        assertEquals(SyncState.NOT_READY, SyncPolicies.watchStateAfterAck("IN_PROGRESS", "h", ack))
        // 워치에서는 이미 종료됐는데 폰이 진행 중이라고 판단 → 자동 재전송하지 않고 관리자 확인
        assertEquals(SyncState.UPLOAD_REJECTED, SyncPolicies.watchStateAfterAck("COMPLETED", "h", ack))
    }

    // ================================================================== B. 해시 불일치

    @Test fun B1_hashMismatchIsRejected_notSaved() {
        val r = completed()
        val sentHash = ContentHash.of(r)
        val tampered = completed(final = 65)                  // 전송 중 내용이 달라진 상황
        val computed = ContentHash.of(tampered)
        val d = SyncPolicies.decideReceive(tampered.result.endStatus, sentHash, computed, existing = null)
        assertTrue(d is ReceiveDecision.Reject)
        assertEquals(SaveOutcome.REJECTED_HASH_MISMATCH, SyncPolicies.outcomeOf(d))
    }

    @Test fun B2_hashMismatchIsCheckedBeforeDuplicateOrConflict() {
        val r = completed()
        val h = ContentHash.of(r)
        // 같은 ID 가 이미 있어도, 해시가 안 맞는 내용은 중복/충돌 판정 없이 거부
        val d = SyncPolicies.decideReceive(r.result.endStatus, "bogus", h, SyncPolicies.Existing("COMPLETED", h))
        assertEquals(SaveOutcome.REJECTED_HASH_MISMATCH, SyncPolicies.outcomeOf(d))
    }

    @Test fun B3_hashMismatchSurvivesRealPayloadRoundTrip() {
        val r = completed()
        val good = ContentHash.of(r)
        val bad = (if (good[0] == '0') "1" else "0") + good.drop(1)                 // 해시 1글자 손상
        val upload = AttemptUpload(r, bad)
        val decoded = SyncCodec.decodeUpload(SyncCodec.encodeUpload(upload))
        val computed = ContentHash.of(decoded.record)
        val d = SyncPolicies.decideReceive(decoded.record.result.endStatus, decoded.contentHash, computed, null)
        assertEquals(SaveOutcome.REJECTED_HASH_MISMATCH, SyncPolicies.outcomeOf(d))
    }

    @Test fun B4_watchStopsAutoResendOnHashMismatch() {
        val ack = ResultAck(cfg.attemptId, SaveOutcome.REJECTED_HASH_MISMATCH, "computed", persisted = true)
        assertEquals(SyncState.UPLOAD_REJECTED, SyncPolicies.watchStateAfterAck("COMPLETED", "local", ack))
    }

    @Test fun B5_watchMarksSyncedOnlyWhenHashMatches() {
        val ok = ResultAck(cfg.attemptId, SaveOutcome.SAVED, "h1", persisted = true)
        assertEquals(SyncState.SYNCED, SyncPolicies.watchStateAfterAck("COMPLETED", "h1", ok))
        assertEquals(SyncState.CONFLICT, SyncPolicies.watchStateAfterAck("COMPLETED", "h2", ok))
        val notPersisted = ok.copy(persisted = false)
        assertNull(SyncPolicies.watchStateAfterAck("COMPLETED", "h1", notPersisted))
        val conflict = ResultAck(cfg.attemptId, SaveOutcome.DATA_CONFLICT, "h1", persisted = true)
        assertEquals(SyncState.CONFLICT, SyncPolicies.watchStateAfterAck("COMPLETED", "h1", conflict))
    }

    @Test fun B6_normalDuplicateAndConflictStillWork() {
        val r = completed()
        val h = ContentHash.of(r)
        assertEquals(ReceiveDecision.SaveNew, SyncPolicies.decideReceive(r.result.endStatus, h, h, null))
        assertEquals(ReceiveDecision.Duplicate, SyncPolicies.decideReceive(r.result.endStatus, h, h, SyncPolicies.Existing("COMPLETED", h)))
        assertEquals(ReceiveDecision.Conflict, SyncPolicies.decideReceive(r.result.endStatus, h, h, SyncPolicies.Existing("COMPLETED", "other")))
    }

    // ================================================================== C. 시행 중 강제 종료 복구

    @Test fun C1_interruptedRecordKeepsPersistedEventsWithoutFabricatingEnd() {
        val stored = listOf(startEv) + midEvents
        val rec = SyncPolicies.buildInterruptedRecord(inProgress(), stored)
        assertEquals(EndStatus.INVALID, rec.result.endStatus)
        assertEquals(SyncPolicies.INTERRUPTED_REASON, rec.result.invalidReason)
        assertEquals(listOf(0, 1, 2, 3), rec.events.map { it.seq })
        assertEquals(listOf(55, 60), rec.events.filter { it.type == EventType.VALUE_CHANGE }.map { it.value })
        // 관찰되지 않은 종료 이벤트를 만들지 않음
        assertTrue(rec.events.none { it.type in setOf(EventType.COMPLETE, EventType.TIMEOUT, EventType.INVALID) })
        assertNull(rec.result.finalVolume)
        assertNull(rec.result.elapsedMs)
    }

    @Test fun C2_interruptedRecordIsUploadableAndHashStable() {
        val rec = SyncPolicies.buildInterruptedRecord(inProgress(), (listOf(startEv) + midEvents).shuffled())
        assertEquals(listOf(0, 1, 2, 3), rec.events.map { it.seq })        // 저장 순서와 무관하게 seq 정렬
        assertTrue(SyncPolicies.isUploadable(rec.result.endStatus.name, SyncState.PENDING.name))
        val h = ContentHash.of(rec)
        val decoded = SyncCodec.decodeUpload(SyncCodec.encodeUpload(AttemptUpload(rec, h)))
        assertEquals(h, ContentHash.of(decoded.record))
    }
}
