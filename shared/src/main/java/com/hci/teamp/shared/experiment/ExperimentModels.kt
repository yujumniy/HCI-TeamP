package com.hci.teamp.shared.experiment

const val COUNTDOWN_MS = 3_000L

enum class ExperimentDevice { PHONE, WEAR }

enum class Condition(val device: ExperimentDevice) {
    C1(ExperimentDevice.PHONE), // Physical volume keys
    C2(ExperimentDevice.PHONE), // Touch slider
    C3(ExperimentDevice.WEAR),  // Touch bezel
    C4(ExperimentDevice.WEAR),  // Touch slider
}

@JvmInline
value class VirtualVolume(val percent: Int) {
    init {
        require(percent in 0..100 && percent % 5 == 0)
    }
}

@JvmInline
value class ParticipantId(val value: String) {
    init {
        require(value.matches(Regex("P[0-9]{3,}")) && value.drop(1).any { it != '0' })
    }
}

// IDs come from the experiment protocol. Keep them intact across retries and exports.
data class Task(val id: String, val start: VirtualVolume, val target: VirtualVolume) {
    init { require(id.isNotBlank()) }
}

data class Attempt(
    val id: String,
    val participant: ParticipantId,
    val condition: Condition,
    val task: Task,
) {
    init { require(id.isNotBlank()) }
}

enum class Outcome { COMPLETED, TIMEOUT, INVALID }
enum class EventType { VALUE_CHANGE }

data class InputEvent(
    val attemptId: String,
    val device: ExperimentDevice,
    val elapsedRealtimeMs: Long,
    val previous: VirtualVolume,
    val current: VirtualVolume,
    val type: EventType = EventType.VALUE_CHANGE,
) {
    init {
        require(attemptId.isNotBlank() && elapsedRealtimeMs >= 0 && previous != current)
    }
}

data class TrialResult(
    val attempt: Attempt,
    val outcome: Outcome,
    val durationMs: Long,
    val finalValue: VirtualVolume,
)
