package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent

class WatchVolumeSlider : ExperimentInput {
    override val condition = Condition.C4

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        // TODO(team 2): Wire a touch slider and verify behavior on the round display.
        error("${condition.name} input is not configured")
    }

    override fun stop() = Unit
}
