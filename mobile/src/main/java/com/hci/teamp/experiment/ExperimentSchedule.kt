package com.hci.teamp.experiment

import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.Task

class ExperimentSchedule(private val taskOrder: TaskOrder) {
    // Balanced Latin square for four conditions.
    private val rows = listOf(
        listOf(Condition.C1, Condition.C2, Condition.C4, Condition.C3),
        listOf(Condition.C2, Condition.C3, Condition.C1, Condition.C4),
        listOf(Condition.C3, Condition.C4, Condition.C2, Condition.C1),
        listOf(Condition.C4, Condition.C1, Condition.C3, Condition.C2),
    )

    fun conditionsFor(participantNumber: Int): List<Condition> {
        require(participantNumber > 0)
        return rows[(participantNumber - 1) % rows.size]
    }

    fun tasksFor(participant: ParticipantId, condition: Condition): List<Task> =
        taskOrder.forCondition(participant, condition).also { tasks ->
            require(tasks.size == 8 && tasks.map { it.id }.distinct().size == 8)
        }
}

fun interface TaskOrder {
    // TODO(team 4): implement using the eight tasks and ordering rule in specification v1.
    fun forCondition(participant: ParticipantId, condition: Condition): List<Task>
}
