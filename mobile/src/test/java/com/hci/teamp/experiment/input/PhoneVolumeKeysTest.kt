package com.hci.teamp.experiment.input

import android.view.KeyEvent
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.Task
import com.hci.teamp.shared.experiment.VirtualVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneVolumeKeysTest {

    /** 시간을 직접 돌리는 가짜 타이머 */
    private class FakeScheduler(private val clock: FakeClock) : RepeatScheduler {
        var dueAt: Long? = null
        var block: (() -> Unit)? = null
        override fun schedule(delayMs: Long, block: () -> Unit) { dueAt = clock.now + delayMs; this.block = block }
        override fun cancel() { dueAt = null; block = null }
        fun advance(ms: Long) {
            val end = clock.now + ms
            while (true) {
                val due = dueAt ?: break
                if (due > end) break
                clock.now = due
                val b = block!!
                dueAt = null; block = null
                b()
            }
            clock.now = end
        }
    }

    private class FakeClock { var now = 1_000L }

    private val UP = KeyEvent.KEYCODE_VOLUME_UP
    private val DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
    private val PRESS = KeyEvent.ACTION_DOWN
    private val RELEASE = KeyEvent.ACTION_UP

    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeScheduler
    private lateinit var keys: PhoneVolumeKeys
    private val events = mutableListOf<InputEvent>()
    private val raws = mutableListOf<RawInput>()

    private fun attempt(start: Int, target: Int = 70) = Attempt(
        "P001-C1-T01-A01", ParticipantId("P001"), Condition.C1,
        Task("T01", VirtualVolume(start), VirtualVolume(target)),
    )

    @Before
    fun setUp() {
        clock = FakeClock()
        scheduler = FakeScheduler(clock)
        keys = PhoneVolumeKeys(clock = { clock.now }, scheduler = scheduler)
        keys.rawListener = { raws += it }
        events.clear(); raws.clear()
    }

    private fun values() = events.map { it.previous.percent to it.current.percent }

    @Test
    fun `짧게 누르면 한 번에 정확히 5씩`() {
        keys.start(attempt(50)) { events += it }
        keys.handleKey(UP, PRESS, 0); keys.handleKey(UP, RELEASE, 0)
        keys.handleKey(UP, PRESS, 0); keys.handleKey(UP, RELEASE, 0)
        keys.handleKey(DOWN, PRESS, 0); keys.handleKey(DOWN, RELEASE, 0)
        assertEquals(listOf(50 to 55, 55 to 60, 60 to 55), values())
        assertEquals(listOf("short", "short", "short"), raws.filter { it.kind == RawInput.Kind.UP }.map { it.detail.substringAfter("press=").substringBefore(";") })
    }

    @Test
    fun `길게 누르면 시작 지연 뒤 반복 간격마다 5씩, 떼면 즉시 멈춤`() {
        keys.initialDelayMs = 400; keys.intervalMs = 150
        keys.start(attempt(10)) { events += it }
        keys.handleKey(UP, PRESS, 0)          // 15
        scheduler.advance(399)
        assertEquals(1, events.size)
        scheduler.advance(1)                  // 400ms → 20
        scheduler.advance(150)                // 550ms → 25
        scheduler.advance(150)                // 700ms → 30
        keys.handleKey(UP, RELEASE, 0)
        scheduler.advance(1000)               // 뗀 뒤에는 변화 없음
        assertEquals(listOf(10 to 15, 15 to 20, 20 to 25, 25 to 30), values())
        val up = raws.last { it.kind == RawInput.Kind.UP }
        assertTrue(up.detail.contains("press=long"))
        assertTrue(up.detail.contains("auto_steps=3"))
    }

    @Test
    fun `시스템 키 반복은 값을 바꾸지 않고 횟수만 기록`() {
        keys.start(attempt(50)) { events += it }
        keys.handleKey(UP, PRESS, 0)
        keys.handleKey(UP, PRESS, 1)
        keys.handleKey(UP, PRESS, 2)
        keys.handleKey(UP, RELEASE, 0)
        assertEquals(listOf(50 to 55), values())
        assertTrue(raws.last().detail.contains("system_repeats=2"))
    }

    @Test
    fun `0과 100 경계에서는 이벤트 없이 멈추고 원시 기록만 남김`() {
        keys.start(attempt(100)) { events += it }
        keys.handleKey(UP, PRESS, 0); keys.handleKey(UP, RELEASE, 0)
        assertTrue(events.isEmpty())
        assertEquals(1, raws.count { it.kind == RawInput.Kind.BOUNDARY })

        keys.start(attempt(5)) { events += it }
        keys.handleKey(DOWN, PRESS, 0)
        scheduler.advance(2000)
        keys.handleKey(DOWN, RELEASE, 0)
        assertEquals(listOf(5 to 0), values())
    }

    @Test
    fun `다른 키를 누르고 있는 동안 두 번째 키는 무시`() {
        keys.start(attempt(50)) { events += it }
        keys.handleKey(UP, PRESS, 0)
        keys.handleKey(DOWN, PRESS, 0)
        keys.handleKey(DOWN, RELEASE, 0)
        keys.handleKey(UP, RELEASE, 0)
        assertEquals(listOf(50 to 55), values())
        assertEquals(1, raws.count { it.kind == RawInput.Kind.IGNORED })
    }

    @Test
    fun `시행 전이나 stop 뒤에도 볼륨 키는 소비하지만 이벤트는 없음`() {
        assertTrue(keys.handleKey(UP, PRESS, 0))
        keys.start(attempt(50)) { events += it }
        keys.handleKey(UP, PRESS, 0)
        keys.stop()                           // 누른 채로 종료 → 반복도 멈춤
        scheduler.advance(2000)
        assertTrue(keys.handleKey(UP, PRESS, 0))
        assertEquals(listOf(50 to 55), values())
        assertFalse(keys.isActive)
    }

    @Test
    fun `볼륨 키가 아니면 소비하지 않음`() {
        assertFalse(keys.handleKey(KeyEvent.KEYCODE_BACK, PRESS, 0))
    }

    @Test
    fun `이벤트 시각은 단조 시계, 시도 ID와 기기 포함`() {
        keys.start(attempt(50)) { events += it }
        clock.now = 5_000
        keys.handleKey(UP, PRESS, 0)
        val e = events.single()
        assertEquals(5_000L, e.elapsedRealtimeMs)
        assertEquals("P001-C1-T01-A01", e.attemptId)
        assertEquals(com.hci.teamp.shared.experiment.ExperimentDevice.PHONE, e.device)
    }
}
