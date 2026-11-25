package com.hci.teamp.shared

import com.hci.teamp.shared.metrics.TrialMetrics
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.EventType
import com.hci.teamp.shared.model.Ids
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialEvent
import com.hci.teamp.shared.model.TrialPhase
import com.hci.teamp.shared.model.TrialResult
import com.hci.teamp.shared.sync.AttemptUpload
import com.hci.teamp.shared.sync.ContentHash
import com.hci.teamp.shared.sync.SyncCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM 단위 테스트 (./gradlew :shared:test). Android 기기 없이 실행된다. */
class SharedLogicTest {

    private fun vc(seq: Int, v: Int, ms: Long) =
        TrialEvent("P001-C1-T04-A01", seq, EventType.VALUE_CHANGE, ms * 1_000_000, 0L, v)

    @Test fun overshootExampleFromSpec_10_4() {
        // 목표 70: 60 → 70 → 75 → 80 → 65 → 75  ⇒ 초과 진입 2회, 최대 초과 10
        val ev = listOf(vc(1, 70, 100), vc(2, 75, 200), vc(3, 80, 300), vc(4, 65, 400), vc(5, 75, 500))
        val m = TrialMetrics.compute(60, 70, ev)
        assertEquals(2, m.overshootCount)
        assertEquals(10, m.maxOvershoot)
        assertTrue(m.reachedTarget)
        assertEquals(100.0, m.firstReachMs!!, 1e-9)
        assertEquals(2, m.directionChanges)
        assertEquals(45, m.pathDistance)
        assertEquals(5, m.finalAbsError)
    }

    @Test fun decreasingTask() {
        // 목표 20 (90 → 20 감소 과제), 15 로 내려갔다가 20 복귀
        val ev = listOf(vc(1, 50, 100), vc(2, 15, 200), vc(3, 20, 300))
        val m = TrialMetrics.compute(90, 20, ev)
        assertEquals(5, m.maxOvershoot)
        assertEquals(1, m.overshootCount)
        assertEquals(0, m.finalAbsError)
    }

    @Test fun noInputTrial() {
        val m = TrialMetrics.compute(10, 80, emptyList())
        assertEquals(0, m.maxOvershoot)
        assertEquals(0, m.overshootCount)
        assertFalse(m.reachedTarget)
        assertEquals(70, m.finalAbsError)
        assertEquals(null, m.firstInputMs)
    }

    @Test fun jumpIsNotInterpolated() {
        // 5 → 20 한 번에: 이동 거리 15, 중간값 생성 없음 → 목표 10 '정확히 도달' 아님
        val m = TrialMetrics.compute(5, 10, listOf(vc(1, 20, 50)))
        assertFalse(m.reachedTarget)
        assertEquals(15, m.pathDistance)
        assertEquals(10, m.maxOvershoot)
    }

    @Test fun ids() {
        assertEquals("P001", Ids.participantId(1))
        assertEquals("P001-C3-T04-A01", Ids.attemptId("P001", ConditionId.C3, "T04", 1))
        assertEquals("P012-C1-PR02-A02", Ids.attemptId("P012", ConditionId.C1, "PR02", 2))
        val p = Ids.parseAttemptId("P001-C3-T04-A02")
        assertEquals(ConditionId.C3, p.condition)
        assertEquals(2, p.attemptNo)
        assertEquals(TrialPhase.MAIN, p.phase)
        assertEquals(TrialPhase.PRACTICE, Ids.parseAttemptId("P001-C3-PR01-A01").phase)
        assertFalse(Ids.isValidAttemptId("P001-C5-T04-A01"))
    }

    private fun record(raw: Map<String, String>, finalVolume: Int = 70): AttemptRecord {
        val cfg = TrialConfig("P001-C3-T04-A01", "P001", ConditionId.C3, "T04", 1, TrialPhase.MAIN, 50, 70, 3)
        val ev = listOf(
            TrialEvent(cfg.attemptId, 0, EventType.START, 0, 1000, 50),
            TrialEvent(cfg.attemptId, 1, EventType.VALUE_CHANGE, 5_000_000, 1005, 70, raw),
            TrialEvent(cfg.attemptId, 2, EventType.COMPLETE, 9_000_000, 1009, 70),
        )
        val res = TrialResult(cfg, DeviceType.WATCH, "SM-R870", EndStatus.COMPLETED, finalVolume, 9.0, 1000, 1009,
            metrics = TrialMetrics.compute(50, 70, ev))
        return AttemptRecord(res, ev)
    }

    @Test fun contentHashIsKeyOrderIndependentAndDetectsChange() {
        val a = record(linkedMapOf("dir" to "cw", "delta" to "15"))
        val b = record(linkedMapOf("delta" to "15", "dir" to "cw"))
        assertEquals(ContentHash.of(a), ContentHash.of(b))
        assertNotEquals(ContentHash.of(a), ContentHash.of(record(mapOf("dir" to "cw", "delta" to "15"), finalVolume = 65)))
    }

    @Test fun uploadRoundTripKeepsHash() {
        val r = record(mapOf("dir" to "cw"))
        val up = AttemptUpload(r, ContentHash.of(r))
        val decoded = SyncCodec.decodeUpload(SyncCodec.encodeUpload(up))
        assertEquals(up.contentHash, ContentHash.of(decoded.record))
        assertEquals(r, decoded.record)
    }

    @Test fun completionTimeOnlyForCompleted() {
        val r = record(emptyMap())
        assertEquals(9.0, r.result.completionTimeMs!!, 1e-9)
        assertEquals(null, r.result.copy(endStatus = EndStatus.TIMEOUT).completionTimeMs)
    }

    @Test fun legacyRecordsRemainFiveUnitWhileTaggedSliderRecordsAreOneUnit() {
        val old = record(emptyMap())
        val oldHash = ContentHash.of(old)
        assertEquals(com.hci.teamp.shared.model.InputResolution.LEGACY,
            com.hci.teamp.shared.model.InputResolution.version(old))
        assertEquals(5, com.hci.teamp.shared.model.InputResolution.recordedStep(old))
        val cfg = old.result.config.copy(attemptId = "P001-C2-T04-A01", condition = ConditionId.C2)
        val updated = old.copy(result = old.result.copy(config = cfg, deviceType = DeviceType.PHONE),
            events = old.events.map { e -> e.copy(attemptId = cfg.attemptId, raw = if (e.type == EventType.START)
                mapOf("input_policy" to com.hci.teamp.shared.model.InputResolution.VERSION, "volume_step" to "1") else e.raw) })
        assertEquals(1, com.hci.teamp.shared.model.InputResolution.recordedStep(updated))
        assertEquals(oldHash, ContentHash.of(old))
    }
}
