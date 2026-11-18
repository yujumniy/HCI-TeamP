package com.hci.teamp.experiment.input.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialStatsTest {

    @Test
    fun `명세 10_4 예시 - 목표 70에서 60-70-75-80-65-75 는 초과 진입 2회, 최대 초과 10`() {
        val s = TrialStats.compute(60, 70, listOf(70, 75, 80, 65, 75))
        assertEquals(2, s.overshootCount)
        assertEquals(10, s.maxOvershoot)
        assertEquals(75, s.finalValue)
        assertEquals(5, s.absError)
        assertTrue(s.reachedTarget)
        assertEquals(5, s.valueChanges)
    }

    @Test
    fun `감소 과제는 목표보다 낮아진 거리를 초과로 계산`() {
        // T07 60 → 45
        val s = TrialStats.compute(60, 45, listOf(55, 50, 45, 40, 35, 40, 45))
        assertEquals(10, s.maxOvershoot)
        assertEquals(1, s.overshootCount)
        assertEquals(0, s.absError)
    }

    @Test
    fun `목표 미도달과 정확히 맞춤을 구별`() {
        val under = TrialStats.compute(10, 80, listOf(20, 40, 60))
        assertEquals(0, under.maxOvershoot)
        assertFalse(under.reachedTarget)
        val exact = TrialStats.compute(10, 80, listOf(50, 80))
        assertEquals(0, exact.maxOvershoot)
        assertTrue(exact.reachedTarget)
    }

    @Test
    fun `조작이 없던 시행`() {
        val s = TrialStats.compute(40, 55, emptyList())
        assertEquals(40, s.finalValue)
        assertEquals(15, s.absError)
        assertEquals(0, s.overshootCount)
        assertEquals(0, s.valueChanges)
    }

    @Test
    fun `조작 횟수 - 여러 칸 움직여도 한 번 잡고 놓으면 1회`() {
        val m = MoveCounter()
        m.down(); repeat(6) { m.valueChanged() }; m.up()   // 드래그 1: 6칸
        m.down(); m.valueChanged(); m.up()                   // 드래그 2: 1칸
        m.down(); m.up()                                     // 잡기만 하고 놓음
        assertEquals(2, m.moves)
        assertEquals(1, m.touchesWithoutChange)
    }

    @Test
    fun `조작 횟수 - 누른 채로 시행이 끝나도 1회로 마무리`() {
        val m = MoveCounter()
        m.down(); m.valueChanged()
        m.close()
        assertEquals(1, m.moves)
    }
}
