package com.hci.teamp.shared

import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.EventType
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialEvent
import com.hci.teamp.shared.model.TrialPhase
import com.hci.teamp.shared.recorder.AttemptRecorder
import com.hci.teamp.shared.recorder.AttemptStore
import com.hci.teamp.shared.recorder.MonotonicClock
import com.hci.teamp.shared.sync.SyncPolicies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** C. 시행 중 강제 종료 — 기록기가 관찰 이벤트를 시행 중에 순서대로 영구 저장하는지 검증 */
class AttemptRecorderPersistenceTest {

    private class FakeClock : MonotonicClock {
        var ns = 5_000_000_000L
        override fun nowNs() = ns
        override fun wallMs() = ns / 1_000_000
        fun advanceMs(ms: Long) { ns += ms * 1_000_000 }
    }

    /** 실제 DB 대신 호출 순서·내용을 기록. appendDelayMs 로 느린 저장소를 흉내 낸다. */
    private class FakeStore(private val appendDelayMs: Long = 0) : AttemptStore {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val appended: MutableList<TrialEvent> = Collections.synchronizedList(mutableListOf())
        @Volatile var begun: AttemptRecord? = null
        @Volatile var finalized: AttemptRecord? = null

        override suspend fun begin(partial: AttemptRecord, deviceType: DeviceType) {
            begun = partial; calls += "begin"
        }
        override suspend fun appendEvent(event: TrialEvent) {
            if (appendDelayMs > 0) delay(appendDelayMs)
            appended += event; calls += "append:${event.seq}"
        }
        override suspend fun finalize(record: AttemptRecord, deviceType: DeviceType): SaveOutcome {
            finalized = record; calls += "finalize"; return SaveOutcome.SAVED
        }
    }

    private val cfg = TrialConfig("P001-C3-T04-A01", "P001", ConditionId.C3, "T04", 1, TrialPhase.MAIN, 50, 70, 3)

    private fun recorder(store: AttemptStore, clock: FakeClock) = AttemptRecorder(
        config = cfg, store = store, deviceType = DeviceType.WATCH, deviceModel = "test",
        clock = clock, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!cond()) delay(5) }
    }

    @Test fun C3_eventsArePersistedDuringTrial_beforeAnyEnd() {
        val clock = FakeClock(); val store = FakeStore()
        val rec = recorder(store, clock)
        rec.start()
        clock.advanceMs(300); rec.inputDown(mapOf("dir" to "cw"))
        clock.advanceMs(100); rec.valueChange(55, mapOf("delta" to "15"))
        clock.advanceMs(100); rec.valueChange(60)
        clock.advanceMs(50); rec.inputUp()

        // 종료(complete/timeout/invalidate)를 호출하지 않은 상태 = 앱이 여기서 강제 종료된 상황
        waitUntil { store.appended.size == 4 }
        assertNotNull(store.begun)
        assertNull(store.finalized)
        assertEquals(listOf("begin", "append:1", "append:2", "append:3", "append:4"), store.calls.toList())
        assertEquals(listOf(EventType.INPUT_DOWN, EventType.VALUE_CHANGE, EventType.VALUE_CHANGE, EventType.INPUT_UP), store.appended.map { it.type })
        assertEquals(listOf(300L, 400L, 500L, 550L), store.appended.map { it.elapsedNs / 1_000_000 })
        assertEquals(mapOf("delta" to "15"), store.appended[1].raw)

        // 다음 실행 시 복구: DB 에 남은 이벤트로 INVALID 기록 생성
        val recovered = SyncPolicies.buildInterruptedRecord(store.begun!!, store.begun!!.events + store.appended)
        assertEquals(EndStatus.INVALID, recovered.result.endStatus)
        assertEquals(listOf(0, 1, 2, 3, 4), recovered.events.map { it.seq })
        assertTrue(recovered.events.none { it.type == EventType.COMPLETE })
    }

    @Test fun C4_normalTrial_orderIsBeginEventsFinalize_andFinalRecordComplete() = runBlocking {
        val clock = FakeClock(); val store = FakeStore()
        val rec = recorder(store, clock)
        rec.start()
        clock.advanceMs(200); rec.valueChange(55)
        clock.advanceMs(200); rec.valueChange(70)
        clock.advanceMs(500)
        val outcome = withTimeout(3_000) { rec.complete().await() }

        assertEquals(SaveOutcome.SAVED, outcome)
        assertEquals(listOf("begin", "append:1", "append:2", "finalize"), store.calls.toList())
        val fin = store.finalized!!
        assertEquals(listOf(EventType.START, EventType.VALUE_CHANGE, EventType.VALUE_CHANGE, EventType.COMPLETE), fin.events.map { it.type })
        assertEquals(900.0, fin.result.elapsedMs!!, 1e-9)
        assertEquals(70, fin.result.finalVolume)
        // 시행 중 저장한 이벤트와 최종 기록의 이벤트가 같음
        assertEquals(store.appended.toList(), fin.events.subList(1, 3))
    }

    @Test fun C5_slowStorageDoesNotDelayTimestampsOrCallers() = runBlocking {
        val clock = FakeClock(); val store = FakeStore(appendDelayMs = 200)
        val rec = recorder(store, clock)
        rec.start()
        val t0 = System.nanoTime()
        clock.advanceMs(100); rec.valueChange(55)
        clock.advanceMs(100); rec.valueChange(60)
        clock.advanceMs(100); val done = rec.complete()
        val callerMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("입력 호출이 저장을 기다리면 안 됨 (${callerMs}ms)", callerMs < 150)

        withTimeout(5_000) { done.await() }
        val fin = store.finalized!!
        // 시각은 호출 시점의 단조 시계 기준 (저장 지연 200ms 가 섞이지 않음)
        assertEquals(listOf(0L, 100L, 200L, 300L), fin.events.map { it.elapsedNs / 1_000_000 })
        assertEquals(listOf("begin", "append:1", "append:2", "finalize"), store.calls.toList())
    }

    @Test fun C6_eventsAfterEndAreNotPersisted() = runBlocking {
        val clock = FakeClock(); val store = FakeStore()
        val rec = recorder(store, clock)
        rec.start()
        clock.advanceMs(100); rec.valueChange(55)
        withTimeout(3_000) { rec.timeout().await() }
        rec.valueChange(60)                         // 종료 후 입력
        delay(100)
        assertEquals(listOf(1), store.appended.map { it.seq })
        assertEquals(EndStatus.TIMEOUT, store.finalized!!.result.endStatus)
    }

    @Test fun sliderOneUnitValuesPersistRoundTripAndRetainPolicy() = runBlocking {
        val clock = FakeClock(); val store = FakeStore()
        val c = cfg.copy(attemptId = "P001-C2-T04-A01", condition = ConditionId.C2, startVolume = 40, targetVolume = 55)
        val rec = AttemptRecorder(c, store, DeviceType.PHONE, "test", clock,
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        rec.start()
        listOf(41, 54, 56, 55).forEach { clock.advanceMs(10); rec.valueChange(it) }
        clock.advanceMs(10)
        withTimeout(3000) { rec.complete().await() }
        val record = store.finalized!!
        assertEquals(listOf(41, 54, 56, 55), record.events.filter { it.type == EventType.VALUE_CHANGE }.map { it.value })
        assertEquals(com.hci.teamp.shared.model.InputResolution.VERSION, com.hci.teamp.shared.model.InputResolution.version(record))
        assertEquals("1", record.events.first().raw["volume_step"])
        assertEquals(1, record.result.metrics!!.maxOvershoot)
        assertEquals(17, record.result.metrics!!.pathDistance)
        assertEquals(0, record.result.metrics!!.finalAbsError)
        val encoded = com.hci.teamp.shared.sync.SyncCodec.encode(record)
        val decoded = com.hci.teamp.shared.sync.SyncCodec.decode<AttemptRecord>(encoded)
        assertEquals(record, decoded)
        assertEquals(com.hci.teamp.shared.sync.ContentHash.of(record), com.hci.teamp.shared.sync.ContentHash.of(decoded))
    }
}
