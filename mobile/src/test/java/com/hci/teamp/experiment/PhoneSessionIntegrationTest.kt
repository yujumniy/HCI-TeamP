package com.hci.teamp.experiment

import com.hci.teamp.shared.experiment.*
import org.junit.Assert.*
import org.junit.Test

class PhoneSessionIntegrationTest {
    private class Input(override val condition: Condition) : ExperimentInput {
        var attempt: Attempt? = null
        var callback: ((InputEvent) -> Unit)? = null
        var stops = 0
        override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
            this.attempt = attempt
            callback = onChange
        }
        override fun stop() { stops++ }
        fun emit(now: Long, previous: Int, current: Int) {
            callback?.invoke(InputEvent(
                checkNotNull(attempt).id, ExperimentDevice.PHONE, now,
                VirtualVolume(previous), VirtualVolume(current),
            ))
        }
    }

    private class Audio : ExperimentAudio {
        var value: Int? = null
        var stops = 0
        override fun start(value: VirtualVolume) { this.value = value.percent }
        override fun setVolume(value: VirtualVolume) { this.value = value.percent }
        override fun stop() { stops++ }
    }

    @Test fun startsAfterCountdownAndRecordsOnlyActiveInput() {
        var now = 100L
        val input = Input(Condition.C1)
        val audio = Audio()
        val session = RehearsalSession(ParticipantId("P001"), audio, { input }) { now }
        session.next()
        session.begin()
        now += 2999
        session.tick()
        assertNull(input.attempt)
        assertNull(audio.value)
        now++
        session.tick()
        assertEquals("P001-C1-PR01-A01", input.attempt?.id)
        input.emit(now, 25, 30)
        assertEquals(30, session.value.percent)
        assertEquals(30, audio.value)
        session.submit()
        assertEquals(Outcome.COMPLETED, session.lastRecord?.result?.outcome)
        assertEquals(1, input.stops)
        assertEquals(1, audio.stops)
        assertEquals(1, session.lastRecord?.events?.size)
        input.emit(now, 30, 35)
        assertEquals(30, session.value.percent)
    }

    @Test fun timeoutRejectsLateInputAndInvalidRetryGetsNewId() {
        var now = 0L
        val input = Input(Condition.C1)
        val audio = Audio()
        val session = RehearsalSession(ParticipantId("P001"), audio, { input }) { now }
        fun start() {
            session.begin()
            now += COUNTDOWN_MS
            session.tick()
        }
        session.next()
        start()
        session.interrupt()
        assertEquals(Outcome.INVALID, session.lastRecord?.result?.outcome)
        session.next()
        start()
        assertEquals("P001-C1-PR01-A02", input.attempt?.id)
        now += TRIAL_TIMEOUT_MS
        input.emit(now, 25, 30)
        assertEquals(Outcome.TIMEOUT, session.lastRecord?.result?.outcome)
        assertEquals(25, session.lastRecord?.result?.finalValue?.percent)
        assertEquals(0, session.lastRecord?.events?.size)
        assertEquals(2, input.stops)
    }
}
