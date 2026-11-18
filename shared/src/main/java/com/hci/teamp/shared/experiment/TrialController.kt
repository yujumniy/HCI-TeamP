package com.hci.teamp.shared.experiment

enum class TrialPhase { READY, COUNTDOWN, ACTIVE, FINISHED }

enum class TrialEventType { START, VALUE_CHANGE, COMPLETE, TIMEOUT, INVALID }

data class TrialEvent(
    val attemptId: String,
    val sequence: Int,
    val elapsedRealtimeMs: Long,
    val type: TrialEventType,
    val input: InputEvent? = null,
)

data class TrialRecord(
    val result: TrialResult,
    val events: List<InputEvent>,
    val startedElapsedRealtimeMs: Long,
    val startedEpochMs: Long,
    val endedElapsedRealtimeMs: Long,
) {
    fun eventLog(): List<TrialEvent> = buildList {
        val id = result.attempt.id
        add(TrialEvent(id, 1, startedElapsedRealtimeMs, TrialEventType.START))
        for (event in events) {
            add(TrialEvent(id, size + 1, event.elapsedRealtimeMs, TrialEventType.VALUE_CHANGE, event))
        }
        val endType = when (result.outcome) {
            Outcome.COMPLETED -> TrialEventType.COMPLETE
            Outcome.TIMEOUT -> TrialEventType.TIMEOUT
            Outcome.INVALID -> TrialEventType.INVALID
        }
        add(TrialEvent(id, size + 1, endedElapsedRealtimeMs, endType))
    }
}

// Run on the condition's owning device using one monotonic clock for the full trial.
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
    private var startedEpochMs = 0L
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
        startedEpochMs = System.currentTimeMillis()
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
        val endedAt = nowMs()
        val elapsed = endedAt - startedAt
        if (outcome == Outcome.TIMEOUT && elapsed < timeoutMs) return null
        val finalOutcome = if (elapsed >= timeoutMs) Outcome.TIMEOUT else outcome
        phase = TrialPhase.FINISHED
        return TrialRecord(
            TrialResult(attempt, finalOutcome, elapsed.coerceAtMost(timeoutMs), value),
            events.toList(),
            startedAt,
            startedEpochMs,
            endedAt,
        )
    }
}
