package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent

class PhoneVolumeSlider : ExperimentInput {
    override val condition = Condition.C2

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        // TODO(team 1): Wire a touch slider that emits only actual 5-point virtual volume changes.
        error("${condition.name} input is not configured")
    }

    override fun stop() = Unit
}
