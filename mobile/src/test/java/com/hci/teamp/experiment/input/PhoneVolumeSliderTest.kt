package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.Task
import com.hci.teamp.shared.experiment.VirtualVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVolumeSliderTest {

    private fun attempt(start: Int) = Attempt(
        "P001-C2-T03-A01", ParticipantId("P001"), Condition.C2,
        Task("T03", VirtualVolume(start), VirtualVolume(55)),
    )

    @Test
    fun `실제로 값이 바뀔 때만 이벤트, 건너뛴 중간값은 만들지 않음`() {
        val events = mutableListOf<InputEvent>()
        val s = PhoneVolumeSlider(clock = { 100L })
        s.start(attempt(40)) { events += it }
        s.onDragValue(40)   // 그대로
        s.onDragValue(42)
        s.onDragValue(45)
        s.onDragValue(60)   // 45 → 60 한 번에
        s.onDragValue(58)
        assertEquals(listOf(40 to 42, 42 to 45, 45 to 60, 60 to 58), events.map { it.previous.percent to it.current.percent })
        assertEquals(58, s.value?.percent)
    }

    @Test
    fun `시행 중이 아니면 드래그와 트랙 탭 모두 무시`() {
        val events = mutableListOf<InputEvent>()
        val s = PhoneVolumeSlider(clock = { 0L })
        s.onDragValue(50)
        s.onTrackTap(10f)
        s.start(attempt(40)) { events += it }
        s.stop()
        s.onDragValue(80)
        assertTrue(events.isEmpty())
        assertEquals(0, s.trackTapBlocked)
        assertFalse(s.isActive)
    }

    @Test
    fun `트랙 탭은 값을 바꾸지 않고 차단 횟수만 증가`() {
        val events = mutableListOf<InputEvent>()
        val s = PhoneVolumeSlider(clock = { 0L })
        s.start(attempt(40)) { events += it }
        s.onTrackTap(900f)
        s.onTrackTap(10f)
        assertTrue(events.isEmpty())
        assertEquals(2, s.trackTapBlocked)
        assertEquals(40, s.value?.percent)
    }

    @Test
    fun `좌표 계산`() {
        // 트랙 0~1000px
        assertEquals(0, SliderMath.valueAt(-50f, 0f, 1000f))
        assertEquals(100, SliderMath.valueAt(1200f, 0f, 1000f))
        assertEquals(50, SliderMath.valueAt(500f, 0f, 1000f))
        assertEquals(53, SliderMath.valueAt(530f, 0f, 1000f))
        assertEquals(52, SliderMath.valueAt(520f, 0f, 1000f))
        assertEquals(450f, SliderMath.thumbX(45, 0f, 1000f))
        assertTrue(SliderMath.hitsThumb(470f, 450f, 56f))
        assertFalse(SliderMath.hitsThumb(480f, 450f, 56f))
    }
}
