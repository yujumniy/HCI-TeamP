package com.hci.teamp.experiment.input

import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent

class PhoneVolumeKeys : ExperimentInput {
    override val condition = Condition.C1

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        // TODO(team 1): Verify volume-key interception and repeat behavior on the experiment phone.
        error("${condition.name} input is not configured")
    }

    override fun stop() = Unit
}
