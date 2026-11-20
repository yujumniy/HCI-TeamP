package com.hci.teamp.shared.experiment

import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.Ids
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialPhase as DataPhase

/** Builds a fixed plan once. Resumption must use the stored order, not regenerate it. */
object StudyPlan {
    fun taskOrders(participantId: String): Map<ConditionId, List<String>> =
        ConditionId.entries.associateWith { condition ->
            V1Protocol.mainTasksFor(ParticipantId(participantId), Condition.valueOf(condition.name)).map { it.id }
        }

    fun configs(
        participantId: String,
        conditions: List<ConditionId>,
        storedTaskOrders: Map<ConditionId, List<String>>,
    ): List<TrialConfig> {
        require(conditions.size == 4 && conditions.toSet() == ConditionId.entries.toSet())
        require(storedTaskOrders.keys == ConditionId.entries.toSet())
        return conditions.flatMap { condition ->
            val definitions = V1Protocol.mainTasksFor(
                ParticipantId(participantId), Condition.valueOf(condition.name),
            ).associateBy { it.id }
            val order = storedTaskOrders.getValue(condition)
            require(order.size == 8 && order.toSet() == definitions.keys)
            (V1Protocol.practiceTasks + order.map { definitions.getValue(it) }).mapIndexed { index, task ->
                val practice = index < 2
                TrialConfig(
                    Ids.attemptId(participantId, condition, task.id, 1), participantId, condition,
                    task.id, 1, if (practice) DataPhase.PRACTICE else DataPhase.MAIN,
                    task.start.percent, task.target.percent, if (practice) index + 1 else index - 1,
                )
            }
        }
    }

    fun retry(config: TrialConfig, number: Int) = config.copy(
        attemptId = Ids.attemptId(config.participantId, config.condition, config.taskId, number),
        attemptNo = number,
    )
}
