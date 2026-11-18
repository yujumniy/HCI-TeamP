package com.hci.teamp.experiment

import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.Task
import com.hci.teamp.shared.experiment.V1Protocol

class ExperimentSchedule(private val taskOrder: TaskOrder = TaskOrder(V1Protocol::mainTasksFor)) {
    fun conditionsFor(participantNumber: Int): List<Condition> =
        V1Protocol.conditionsFor(participantNumber)

    fun tasksFor(participant: ParticipantId, condition: Condition): List<Task> =
        taskOrder.forCondition(participant, condition).also { tasks ->
            require(tasks.size == 8 && tasks.map { it.id }.distinct().size == 8)
        }
}

fun interface TaskOrder {
    fun forCondition(participant: ParticipantId, condition: Condition): List<Task>
}
