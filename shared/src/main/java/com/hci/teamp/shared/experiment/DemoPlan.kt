package com.hci.teamp.shared.experiment

import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.TrialConfig

/** Filming only. Group marker is persisted and exported; never treat these as study participants. */
object DemoPlan {
    const val GROUP = "DEMO"
    val taskIds = listOf("T01", "T05", "T03")
    fun configs(participantId: String, conditions: List<ConditionId>, orders: Map<ConditionId, List<String>>): List<TrialConfig> {
        val full = StudyPlan.configs(participantId, conditions, orders)
        return conditions.flatMap { condition ->
            taskIds.mapIndexed { index, task ->
                full.single { it.condition == condition && it.taskId == task }.copy(orderIndex = index + 1)
            }
        }
    }
}
