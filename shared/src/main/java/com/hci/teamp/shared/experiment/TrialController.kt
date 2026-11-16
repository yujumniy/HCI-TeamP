package com.hci.teamp.shared.experiment

enum class TrialPhase { READY, COUNTDOWN, ACTIVE, FINISHED }

data class TrialRecord(val result: TrialResult, val events: List<InputEvent>)

// Run on the condition's owning device using one monotonic clock for the full trial.
// TODO(team 4): connect lifecycle, completion rule, audio and timer to the UI.
class TrialController(
    private val attempt: Attempt,
    private val device: ExperimentDevice,
    private val timeoutMs: Long,
    private val nowMs: () -> Long,
) {
    init {
        require(attempt.condition.device == device)
        require(timeoutMs > 0)
    }

    var phase = TrialPhase.READY
        private set

    private var countdownAt = 0L
    private var startedAt = 0L
    private var value = attempt.task.start
    private val events = mutableListOf<InputEvent>()

    fun startCountdown() {
        check(phase == TrialPhase.READY)
        countdownAt = nowMs()
        phase = TrialPhase.COUNTDOWN
    }

    fun activate(): Boolean {
        check(phase == TrialPhase.COUNTDOWN)
        val now = nowMs()
        if (now - countdownAt < COUNTDOWN_MS) return false
        startedAt = now
        phase = TrialPhase.ACTIVE
        return true
    }

    fun record(event: InputEvent): Boolean {
        if (phase != TrialPhase.ACTIVE || event.attemptId != attempt.id || event.device != device) {
            return false
        }
        val earliest = events.lastOrNull()?.elapsedRealtimeMs ?: startedAt
        if (event.elapsedRealtimeMs < earliest || event.elapsedRealtimeMs > nowMs() ||
            event.elapsedRealtimeMs - startedAt >= timeoutMs || event.previous != value
        ) return false
        events.add(event)
        value = event.current
        return true
    }

    fun finish(outcome: Outcome): TrialRecord? {
        if (phase != TrialPhase.ACTIVE) return null
        val elapsed = nowMs() - startedAt
        if (outcome == Outcome.TIMEOUT && elapsed < timeoutMs) return null
        val finalOutcome = if (elapsed >= timeoutMs) Outcome.TIMEOUT else outcome
        phase = TrialPhase.FINISHED
        return TrialRecord(
            TrialResult(attempt, finalOutcome, elapsed.coerceAtMost(timeoutMs), value),
            events.toList(),
        )
    }
}
