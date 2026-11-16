package com.hci.teamp.shared.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {

    private fun ids(p: Int, c: Condition) = Counterbalance.taskOrder(p, c).map { it.id }

    @Test
    fun `P001 과제 순서가 명세 7_2 예시표와 같다`() {
        assertEquals(listOf("T01", "T02", "T08", "T03", "T07", "T04", "T06", "T05"), ids(1, Condition.C1))
        assertEquals(listOf("T03", "T04", "T02", "T05", "T01", "T06", "T08", "T07"), ids(1, Condition.C2))
        assertEquals(listOf("T05", "T06", "T04", "T07", "T03", "T08", "T02", "T01"), ids(1, Condition.C3))
        assertEquals(listOf("T07", "T08", "T06", "T01", "T05", "T02", "T04", "T03"), ids(1, Condition.C4))
    }

    @Test
    fun `모든 참가자 조건에서 8개 과제가 한 번씩 나온다`() {
        for (p in 1..16) for (c in Condition.entries) {
            assertEquals(Tasks.MAIN.map { it.id }.toSet(), ids(p, c).toSet())
            assertEquals(8, ids(p, c).size)
        }
    }

    @Test
    fun `참가자 8명 단위로 조건별 과제 위치가 균형을 이룬다`() {
        for (c in Condition.entries) for (pos in 0 until 8) {
            val atPos = (1..8).map { p -> ids(p, c)[pos] }.toSet()
            assertEquals("조건 $c 위치 $pos", 8, atPos.size)
        }
    }

    @Test
    fun `그룹은 G1부터 순환 배정된다`() {
        assertEquals(Group.G1, Counterbalance.groupFor(1))
        assertEquals(Group.G2, Counterbalance.groupFor(2))
        assertEquals(Group.G3, Counterbalance.groupFor(3))
        assertEquals(Group.G4, Counterbalance.groupFor(4))
        assertEquals(Group.G1, Counterbalance.groupFor(5))
        assertEquals(listOf(Condition.C1, Condition.C2, Condition.C4, Condition.C3), Group.G1.order)
    }

    @Test
    fun `과제 숫자가 명세 6장과 같다`() {
        val expected = mapOf(
            "PR01" to (25 to 65), "PR02" to (75 to 35),
            "T01" to (10 to 80), "T02" to (20 to 75), "T03" to (40 to 55), "T04" to (50 to 70),
            "T05" to (90 to 20), "T06" to (80 to 25), "T07" to (60 to 45), "T08" to (70 to 50),
        )
        assertEquals(expected, Tasks.ALL.associate { it.id to (it.start to it.target) })
    }

    @Test
    fun `ID 형식`() {
        val t04 = Tasks.byId("T04")
        assertEquals("P001", Ids.participant(1))
        assertEquals("P001-C3-T04", Ids.task(1, Condition.C3, t04))
        assertEquals("P001-C3-T04-A02", Ids.attempt(1, Condition.C3, t04, 2))
        assertEquals("P001-C3-PR01-A01", Ids.attempt(1, Condition.C3, Tasks.byId("PR01"), 1))
    }

    @Test
    fun `가상 음량은 0~100 5단위이고 경계에서 멈춘다`() {
        assertEquals(100, VolumeRules.step(100, +1))
        assertEquals(0, VolumeRules.step(0, -1))
        assertEquals(55, VolumeRules.step(50, +1))
        assertEquals(45, VolumeRules.normalize(44))
        assertTrue(VolumeRules.isValid(35))
        assertFalse(VolumeRules.isValid(33))
        assertFalse(VolumeRules.isValid(105))
    }
}
