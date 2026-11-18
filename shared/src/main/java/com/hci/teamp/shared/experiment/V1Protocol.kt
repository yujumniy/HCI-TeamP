package com.hci.teamp.shared.experiment

const val TRIAL_TIMEOUT_MS = 30_000L
const val CONDITION_REST_MS = 30_000L

object V1Protocol {
    private val conditionRows = listOf(
        listOf(Condition.C1, Condition.C2, Condition.C4, Condition.C3),
        listOf(Condition.C2, Condition.C3, Condition.C1, Condition.C4),
        listOf(Condition.C3, Condition.C4, Condition.C2, Condition.C1),
        listOf(Condition.C4, Condition.C1, Condition.C3, Condition.C2),
    )

    val practiceTasks: List<Task>
        get() = listOf(task("PR01", 25, 65), task("PR02", 75, 35))

    private val mainTasks = listOf(
        task("T01", 10, 80), task("T02", 20, 75), task("T03", 40, 55), task("T04", 50, 70),
        task("T05", 90, 20), task("T06", 80, 25), task("T07", 60, 45), task("T08", 70, 50),
    )
    private val baseOrder = listOf(1, 2, 8, 3, 7, 4, 6, 5)

    fun conditionsFor(participantNumber: Int): List<Condition> {
        require(participantNumber > 0)
        return conditionRows[(participantNumber - 1) % conditionRows.size].toList()
    }

    fun mainTasksFor(participant: ParticipantId, condition: Condition): List<Task> {
        val number = participant.value.drop(1).toInt()
        val offset = ((number - 1) % 8 + 2 * condition.ordinal) % 8
        return baseOrder.map { mainTasks[(it - 1 + offset) % mainTasks.size] }
    }

    fun attemptId(participant: ParticipantId, condition: Condition, task: Task, number: Int): String {
        require(number > 0)
        return "${participant.value}-${condition.name}-${task.id}-A${number.toString().padStart(2, '0')}"
    }

    private fun task(id: String, start: Int, target: Int) =
        Task(id, VirtualVolume(start), VirtualVolume(target))
}
