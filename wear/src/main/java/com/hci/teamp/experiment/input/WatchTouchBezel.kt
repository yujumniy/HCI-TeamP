package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent

class WatchTouchBezel : ExperimentInput {
    override val condition = Condition.C3

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        // TODO(team 2): Verify rotary events and focus behavior on Galaxy Watch4.
        error("${condition.name} input is not configured")
    }

    override fun stop() = Unit
}
