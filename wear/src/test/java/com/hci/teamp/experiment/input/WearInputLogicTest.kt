package com.hci.teamp.experiment.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearInputLogicTest {

    // ───────── C3 베젤 ─────────

    @Test
    fun `PER_EVENT - 시계 방향 이벤트 1개는 +1단계, 반시계는 -1단계`() {
        val s = BezelStepper()
        assertEquals(1, s.feed(42f, 1_000).steps)
        assertEquals(1, s.feed(42f, 1_050).steps)
        assertEquals(-1, s.feed(-42f, 1_100).steps)
    }

    @Test
    fun `원시값 부호가 반대인 기기는 설정으로 방향을 뒤집는다`() {
        val s = BezelStepper(BezelConfig(clockwiseIsPositive = false))
        assertEquals(-1, s.feed(42f, 0).steps)
        assertEquals(1, s.feed(-42f, 50).steps)
    }

    @Test
    fun `회전량 0인 이벤트는 값을 바꾸지 않는다`() {
        val r = BezelStepper().feed(0f, 0)
        assertEquals(0, r.steps)
        assertEquals(0, r.direction)
    }

    @Test
    fun `ACCUMULATE - 모자란 회전량은 모았다가 기준을 넘으면 1단계`() {
        val s = BezelStepper(BezelConfig(mode = BezelConfig.Mode.ACCUMULATE, pixelsPerStep = 48f))
        assertEquals(0, s.feed(20f, 0).steps)
        assertEquals(0, s.feed(20f, 30).steps)
        val r = s.feed(20f, 60) // 누적 60 → 1단계, 12 남음
        assertEquals(1, r.steps)
        assertEquals(12f, r.accumulated, 1e-3f)
    }

    @Test
    fun `ACCUMULATE - 한 번에 크게 돌면 여러 단계 (관찰된 값만 반영)`() {
        val s = BezelStepper(BezelConfig(mode = BezelConfig.Mode.ACCUMULATE, pixelsPerStep = 48f))
        assertEquals(2, s.feed(100f, 0).steps)
    }

    @Test
    fun `재접촉 - 간격이 길면 새 동작이고 남은 회전량을 버린다`() {
        val s = BezelStepper(BezelConfig(mode = BezelConfig.Mode.ACCUMULATE, pixelsPerStep = 48f, gestureGapMs = 300))
        val first = s.feed(40f, 0)
        assertTrue(first.newGesture)
        assertEquals(0, first.steps)
        // 500ms 뒤 다시 댐: 이전에 남은 40px이 합쳐져 갑자기 1단계가 되면 안 됨
        val again = s.feed(20f, 500)
        assertTrue(again.newGesture)
        assertEquals(500L, again.gapMs)
        assertEquals(0, again.steps)
        assertEquals(20f, again.accumulated, 1e-3f)
        // 이어서 빠르게 돌리면 같은 동작
        assertFalse(s.feed(30f, 550).newGesture)
    }

    @Test
    fun `방향을 바꾸면 반대 방향으로 남은 회전량을 버린다`() {
        val s = BezelStepper(BezelConfig(mode = BezelConfig.Mode.ACCUMULATE, pixelsPerStep = 48f))
        s.feed(40f, 0) // +40 남음
        val r = s.feed(-30f, 50)
        assertEquals(0, r.steps)
        assertEquals(-30f, r.accumulated, 1e-3f)
    }

    @Test
    fun `설정을 바꾸거나 reset하면 다음 이벤트는 새 동작`() {
        val s = BezelStepper()
        s.feed(10f, 0)
        s.reset()
        assertTrue(s.feed(10f, 10).newGesture)
        s.config = s.config.copy(gestureGapMs = 500)
        assertTrue(s.feed(10f, 20).newGesture)
    }

    @Test
    fun `단계 적용은 0과 100에서 멈춘다`() {
        assertEquals(75, BezelStepper.apply(70, 1))
        assertEquals(100, BezelStepper.apply(95, 2))
        assertEquals(0, BezelStepper.apply(5, -3))
    }

    // ───────── C4 슬라이더 ─────────

    @Test
    fun `슬라이더 위치는 5단위로 맞춰지고 범위를 벗어나지 않는다`() {
        assertEquals(0, WatchSliderMath.valueAt(-50f, 0f, 200f))
        assertEquals(100, WatchSliderMath.valueAt(260f, 0f, 200f))
        assertEquals(40, WatchSliderMath.valueAt(81f, 0f, 200f))
        assertEquals(45, WatchSliderMath.valueAt(87f, 0f, 200f))
    }

    @Test
    fun `손잡이 터치 영역 안만 잡히고 트랙 탭은 차단`() {
        val tx = WatchSliderMath.thumbX(50, 10f, 210f) // 110
        assertEquals(110f, tx, 1e-3f)
        assertTrue(WatchSliderMath.hitsThumb(130f, 50f, tx, 50f, 48f))
        assertFalse(WatchSliderMath.hitsThumb(140f, 50f, tx, 50f, 48f)) // 가로로 벗어남
        assertFalse(WatchSliderMath.hitsThumb(110f, 80f, tx, 50f, 48f)) // 세로로 벗어남
    }
}
