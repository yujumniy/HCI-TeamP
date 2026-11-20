package com.hci.teamp.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainCurveTest {
    @Test
    fun `모든 곡선은 0에서 무음, 100에서 최대, 단조 증가`() {
        for (c in GainCurve.entries) {
            assertEquals(0f, c.gain(0), 0f)
            assertEquals(1f, c.gain(100), 1e-6f)
            var prev = 0f
            for (v in 5..100 step 5) {
                val g = c.gain(v)
                assertTrue("$c $v", g > prev)
                prev = g
            }
        }
    }

    @Test
    fun `선형은 기존 방식과 같다`() {
        assertEquals(0.5f, GainCurve.LINEAR.gain(50), 1e-6f)
    }

    @Test
    fun `로그 40dB는 50에서 -20dB`() {
        assertEquals(0.1f, GainCurve.DB40.gain(50), 1e-4f)
    }

    @Test
    fun `저장된 이름이 없거나 잘못되면 선형`() {
        assertEquals(GainCurve.LINEAR, GainCurve.fromName(null))
        assertEquals(GainCurve.LINEAR, GainCurve.fromName("???"))
        assertEquals(GainCurve.DB40, GainCurve.fromName("DB40"))
    }
}
