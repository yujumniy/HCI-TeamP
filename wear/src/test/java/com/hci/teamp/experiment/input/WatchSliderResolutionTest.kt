package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.*
import org.junit.Assert.*
import org.junit.Test

class WatchSliderResolutionTest {
    @Test fun oneUnitChangesArePreservedAndClampedWithoutInterpolation() {
        val events = mutableListOf<InputEvent>()
        val slider = WatchVolumeSlider(clock = { 100L })
        slider.start(Attempt("P001-C4-T01-A01", ParticipantId("P001"), Condition.C4,
            Task("T01", VirtualVolume(40), VirtualVolume(80)))) { events.add(it) }
        listOf(41, 42, 57, 101, 101).forEach(slider::onDragValue)
        assertEquals(listOf(41, 42, 57, 100), events.map { it.current.percent })
        slider.stop()
        slider.onDragValue(99)
        assertEquals(4, events.size)
        for (v in 0..100) assertEquals(v, WatchSliderMath.valueAt(v * 10f, 0f, 1000f))
    }
}
