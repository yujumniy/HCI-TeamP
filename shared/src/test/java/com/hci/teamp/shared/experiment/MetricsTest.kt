package com.hci.teamp.shared.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {

    private fun samples(vararg v: Int) = v.mapIndexed { i, x -> ValueSample((i + 1) * 100L, x) }

    @Test
    fun `명세 10_4 예시 - 목표 70, 60-70-75-80-65-75 이면 초과 진입 2회`() {
        val m = MetricsCalculator.compute(60, 70, samples(70, 75, 80, 65, 75), firstInputMs = 50)
        assertEquals(2, m.overshootCount)
        assertEquals(10, m.maxOvershoot)
        assertTrue(m.reachedTarget)
        assertEquals(100L, m.firstReachMs)
        assertEquals(75, m.finalValue)
        assertEquals(5, m.finalAbsError)
    }

    @Test
    fun `감소 과제는 목표보다 낮아진 거리를 초과로 본다`() {
        // T07: 60 → 45
        val m = MetricsCalculator.compute(60, 45, samples(55, 50, 45, 40, 35, 40, 45), firstInputMs = 10)
        assertEquals(10, m.maxOvershoot)
        assertEquals(1, m.overshootCount)
        assertEquals(1, m.directionChanges)
        assertEquals(0, m.finalAbsError)
        assertEquals(35, m.totalDistance) // 60→35 (25) + 35→45 (10)
    }

    @Test
    fun `목표에 못 미친 시행과 정확히 맞춘 시행을 구별한다`() {
        val under = MetricsCalculator.compute(10, 80, samples(20, 30, 40), firstInputMs = 10)
        assertEquals(0, under.maxOvershoot)
        assertFalse(under.reachedTarget)
        assertNull(under.firstReachMs)

        val exact = MetricsCalculator.compute(10, 80, samples(40, 80), firstInputMs = 10)
        assertEquals(0, exact.maxOvershoot)
        assertTrue(exact.reachedTarget)
    }

    @Test
    fun `조작이 없던 시행`() {
        val m = MetricsCalculator.compute(40, 55, emptyList(), firstInputMs = null)
        assertEquals(40, m.finalValue)
        assertEquals(15, m.finalAbsError)
        assertEquals(0, m.overshootCount)
        assertEquals(0, m.directionChanges)
        assertNull(m.firstInputMs)
    }

    @Test
    fun `슬라이더가 여러 단계를 한 번에 건너뛰어도 실제 관찰값만 사용한다`() {
        // 5 → 20 으로 한 번에 변하면 이동 거리 15, 방향 전환 없음
        val m = MetricsCalculator.compute(5, 20, samples(20), firstInputMs = 0)
        assertEquals(15, m.totalDistance)
        assertEquals(0, m.directionChanges)
    }
}
