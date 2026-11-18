package com.hci.teamp.experiment

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.CONDITION_REST_MS
import com.hci.teamp.shared.experiment.COUNTDOWN_MS
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.Outcome
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.TRIAL_TIMEOUT_MS
import com.hci.teamp.shared.experiment.Task
import com.hci.teamp.shared.experiment.TrialController
import com.hci.teamp.shared.experiment.TrialPhase
import com.hci.teamp.shared.experiment.TrialRecord
import com.hci.teamp.shared.experiment.V1Protocol
import com.hci.teamp.shared.experiment.VirtualVolume

internal enum class RehearsalPhase { INSTRUCTIONS, READY, COUNTDOWN, ACTIVE, RESULT, MAIN_GATE, REST, DONE }

internal class RehearsalSession(
    private val participant: ParticipantId,
    private val audio: ExperimentAudio,
    private val nowMs: () -> Long,
) {
    private val schedule = ExperimentSchedule()
    val conditions = schedule.conditionsFor(participant.value.drop(1).toInt())
    private val phoneConditions = conditions.filter { it.device == ExperimentDevice.PHONE }
    private val tasks = phoneConditions.associateWith {
        V1Protocol.practiceTasks + schedule.tasksFor(participant, it)
    }
    private var conditionIndex = 0
    val condition get() = phoneConditions[conditionIndex]
    var taskIndex = 0
        private set
    val isPractice get() = taskIndex < V1Protocol.practiceTasks.size
    val taskNumber get() = if (isPractice) taskIndex + 1 else taskIndex - V1Protocol.practiceTasks.size + 1

    private var attemptNumber = 1
    private var attempt = newAttempt()
    private var trial = newController()
    private var deadline = 0L
    private val records = mutableListOf<TrialRecord>()

    var phase = RehearsalPhase.INSTRUCTIONS
        private set
    var value = attempt.task.start
        private set
    val target get() = attempt.task.target
    val lastRecord get() = records.lastOrNull()

    fun remainingMs() = (deadline - nowMs()).coerceAtLeast(0L)

    fun next() {
        when (phase) {
            RehearsalPhase.INSTRUCTIONS, RehearsalPhase.MAIN_GATE -> phase = RehearsalPhase.READY
            RehearsalPhase.RESULT -> advanceFromResult()
            RehearsalPhase.REST -> if (remainingMs() == 0L) {
                conditionIndex++
                taskIndex = 0
                resetAttempt()
                phase = RehearsalPhase.INSTRUCTIONS
            }
            else -> Unit
        }
    }

    fun begin() {
        if (phase != RehearsalPhase.READY) return
        trial.startCountdown()
        deadline = nowMs() + COUNTDOWN_MS
        phase = RehearsalPhase.COUNTDOWN
    }

    fun tick() {
        if (phase == RehearsalPhase.COUNTDOWN && trial.activate()) {
            phase = RehearsalPhase.ACTIVE
            audio.start(value)
        }
        if (phase == RehearsalPhase.ACTIVE) finish(Outcome.TIMEOUT)
    }

    fun adjust(delta: Int) {
        if (phase != RehearsalPhase.ACTIVE) return
        val next = VirtualVolume((value.percent + delta).coerceIn(0, 100))
        if (next == value) return
        if (trial.record(InputEvent(attempt.id, ExperimentDevice.PHONE, nowMs(), value, next))) {
            value = next
            audio.setVolume(value)
        } else {
            finish(Outcome.TIMEOUT)
        }
    }

    fun submit() = finish(Outcome.COMPLETED)

    fun interrupt() {
        when (phase) {
            RehearsalPhase.COUNTDOWN -> {
                trial = newController()
                phase = RehearsalPhase.READY
            }
            RehearsalPhase.ACTIVE -> finish(Outcome.INVALID)
            else -> Unit
        }
    }

    private fun finish(outcome: Outcome) {
        if (phase != RehearsalPhase.ACTIVE) return
        val record = trial.finish(outcome) ?: return
        records.add(record)
        phase = RehearsalPhase.RESULT
        audio.stop()
    }

    private fun advanceFromResult() {
        if (lastRecord?.result?.outcome == Outcome.INVALID) {
            attemptNumber++
            attempt = newAttempt()
            trial = newController()
            value = attempt.task.start
            phase = RehearsalPhase.READY
            return
        }
        if (taskIndex + 1 == tasks.getValue(condition).size) {
            if (conditionIndex + 1 == phoneConditions.size) {
                phase = RehearsalPhase.DONE
            } else {
                deadline = nowMs() + CONDITION_REST_MS
                phase = RehearsalPhase.REST
            }
            return
        }
        taskIndex++
        resetAttempt()
        phase = if (taskIndex == V1Protocol.practiceTasks.size) {
            RehearsalPhase.MAIN_GATE
        } else {
            RehearsalPhase.READY
        }
    }

    private fun resetAttempt() {
        attemptNumber = 1
        attempt = newAttempt()
        trial = newController()
        value = attempt.task.start
    }

    private fun newAttempt(): Attempt {
        val task: Task = tasks.getValue(condition)[taskIndex]
        return Attempt(V1Protocol.attemptId(participant, condition, task, attemptNumber), participant, condition, task)
    }

    private fun newController() = TrialController(attempt, ExperimentDevice.PHONE, TRIAL_TIMEOUT_MS, nowMs)
}
