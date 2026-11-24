package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.*
import org.junit.Assert.*
import org.junit.Test

class WatchBezelHapticTest {
    private fun attempt(start: Int = 50, target: Int = 55) = Attempt(
        "P001-C3-T01-A01", ParticipantId("P001"), Condition.C3,
        Task("T01", VirtualVolume(start), VirtualVolume(target)),
    )

    @Test fun onlyActualActiveChangesVibrateIncludingTargetWithoutSpecialEffect() {
        val effects = mutableListOf<BezelConfig.Haptic>()
        val bezel = WatchTouchBezel(clock = { 1000L }, hapticPlayer = { effects.add(it); true })
        bezel.onRotary(42f, 1000L)
        assertTrue(effects.isEmpty())
        bezel.start(attempt()) {}
        bezel.onRotary(42f, 1000L) // target
        bezel.onRotary(42f, 1001L) // beyond target
        assertEquals(listOf(BezelConfig.Haptic.CLICK, BezelConfig.Haptic.CLICK), effects)
        bezel.stop()
        bezel.onRotary(42f, 1002L)
        assertEquals(2, effects.size)
    }

    @Test fun boundaryAndOffNeverRequestVibration() {
        var calls = 0
        val bezel = WatchTouchBezel(clock = { 1000L }, hapticPlayer = { calls++; true })
        bezel.start(attempt(100)) {}
        bezel.onRotary(42f, 1000L)
        assertEquals(0, calls)
        bezel.stepper.config = bezel.stepper.config.copy(haptic = BezelConfig.Haptic.OFF)
        bezel.onRotary(-42f, 1001L)
        assertEquals(95, bezel.value?.percent)
        assertEquals(0, calls)
    }

    @Test fun callbackStoppingTrialSuppressesLateHaptic() {
        var calls = 0
        val bezel = WatchTouchBezel(clock = { 1000L }, hapticPlayer = { calls++; true })
        bezel.start(attempt()) { bezel.stop() }
        bezel.onRotary(42f, 1000L)
        assertEquals(0, calls)
    }

    @Test fun vibratorFailureIsLoggedAndDoesNotBreakInput() {
        val raw = mutableListOf<WatchRawInput>()
        val bezel = WatchTouchBezel(clock = { 1000L }, hapticPlayer = { throw SecurityException("test") })
        bezel.rawListener = { raw.add(it) }
        bezel.start(attempt()) {}
        bezel.onRotary(42f, 1000L)
        assertEquals(55, bezel.value?.percent)
        assertTrue(raw.single { it.kind == WatchRawInput.Kind.HAPTIC }.detail.contains("played=false"))
    }
}
