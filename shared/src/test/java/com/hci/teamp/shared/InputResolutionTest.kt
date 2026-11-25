package com.hci.teamp.shared

import com.hci.teamp.shared.experiment.VirtualVolume
import com.hci.teamp.shared.model.*
import org.junit.Assert.*
import org.junit.Test

class InputResolutionTest {
    @Test fun slidersAllowEveryIntegerButKeysAndBezelStillRequireFiveUnits() {
        for (c in ConditionId.entries) {
            for (v in 0..100) assertEquals(c == ConditionId.C2 || c == ConditionId.C4 || v % 5 == 0,
                InputResolution.valid(v, InputResolution.step(c)))
        }
        assertFalse(InputResolution.valid(-1, 1))
        assertFalse(InputResolution.valid(101, 1))
        assertEquals(41, VirtualVolume(41).percent)
    }
}
