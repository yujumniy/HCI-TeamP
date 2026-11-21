package com.hci.teamp.shared

import com.hci.teamp.shared.experiment.*
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.Ids
import org.junit.Assert.*
import org.junit.Test

class DemoPlanTest {
    @Test fun filmingHasThreeDistinctTasksPerConditionAndValidRetryIds() {
        val order = ConditionId.entries.toList()
        val stored = StudyPlan.taskOrders("P003")
        val demo = DemoPlan.configs("P003", order, stored)
        assertEquals(12, demo.size)
        assertEquals(12, demo.map { it.attemptId }.toSet().size)
        order.forEach { c ->
            val tasks = demo.filter { it.condition == c }
            assertEquals(DemoPlan.taskIds, tasks.map { it.taskId })
            assertEquals(listOf(1, 2, 3), tasks.map { it.orderIndex })
            assertTrue(tasks.any { it.targetVolume > it.startVolume })
            assertTrue(tasks.any { it.targetVolume < it.startVolume })
        }
        assertTrue(demo.all { Ids.isValidAttemptId(it.attemptId) })
        assertEquals("P003-C1-T01-A02", StudyPlan.retry(demo.first(), 2).attemptId)
        assertEquals(demo, DemoPlan.configs("P003", order, stored))
        assertEquals(40, StudyPlan.configs("P003", order, stored).size)
    }
}
