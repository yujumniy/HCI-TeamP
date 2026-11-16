package com.hci.teamp.shared.experiment

interface ExperimentStore {
    // Implementations should make retries safe and acknowledge only durable writes.
    suspend fun save(result: TrialResult, events: List<InputEvent>)
}

interface ExperimentSync {
    suspend fun sendAttempt(attempt: Attempt)
    suspend fun sendResult(result: TrialResult, events: List<InputEvent>)
}

// TODO(team 3): persist locally, then add Data Layer delivery, retries and CSV/ZIP export.
class ExperimentDataStub : ExperimentStore, ExperimentSync {
    override suspend fun save(result: TrialResult, events: List<InputEvent>): Unit =
        error("Experiment storage is not configured")

    override suspend fun sendAttempt(attempt: Attempt): Unit =
        error("Experiment sync is not configured")

    override suspend fun sendResult(result: TrialResult, events: List<InputEvent>): Unit =
        error("Experiment sync is not configured")
}
