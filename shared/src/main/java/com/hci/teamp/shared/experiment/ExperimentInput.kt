package com.hci.teamp.shared.experiment

interface ExperimentInput {
    val condition: Condition

    // start and emitted events run on the owning device's UI thread.
    fun start(attempt: Attempt, onChange: (InputEvent) -> Unit)
    fun stop()
}
