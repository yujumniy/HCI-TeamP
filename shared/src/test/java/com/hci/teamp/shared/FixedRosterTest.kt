package com.hci.teamp.shared

import com.hci.teamp.shared.experiment.*
import com.hci.teamp.shared.model.ConditionId
import org.junit.Assert.*
import org.junit.Test

class FixedRosterTest {
    @Test fun exactlySixteenStudyAndTwoPilotSlotsHaveStableIdsAndBalancedOrders() {
        val slots = FixedRoster.slots
        assertEquals(18, slots.size)
        assertEquals((1..18).map { "P%03d".format(it) }, slots.map { it.id })
        assertEquals(18, slots.map { it.id }.toSet().size)
        assertEquals(listOf("P017", "P018"), slots.filter { it.pilot }.map { it.id })
        assertEquals(listOf(4,4,4,4), slots.filterNot { it.pilot }.groupBy { it.group }.values.map { it.size })
        slots.forEach { s ->
            assertEquals(ConditionId.entries.toSet(), s.conditions.toSet())
            assertEquals(40, StudyPlan.configs(s.id, s.conditions, StudyPlan.taskOrders(s.id)).size)
            assertEquals(s.pilot, FixedRoster.pilot(s.group))
        }
        assertEquals("PILOT", FixedRoster.mode(slots[16].group))
        assertEquals("STUDY", FixedRoster.mode(slots[0].group))
        assertEquals("DEMO", FixedRoster.mode(DemoPlan.GROUP))
    }
}
