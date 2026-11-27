package com.zouhu.speedlimit.feature.accel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelAssistPolicyTest {
    @Test
    fun `sensors register only with assist, foreground and active unfinished session`() {
        assertTrue(
            AccelAssistPolicy.shouldRegister(
                assistEnabled = true,
                foreground = true,
                sessionActive = true,
                sessionFinished = false,
                sensorAvailable = true,
            ),
        )
        assertFalse(
            AccelAssistPolicy.shouldRegister(true, false, true, false, true),
        )
        assertFalse(
            AccelAssistPolicy.shouldRegister(true, true, false, false, true),
        )
        assertFalse(
            AccelAssistPolicy.shouldRegister(true, true, true, true, true),
        )
    }

    @Test
    fun `disabled or unavailable assist never registers`() {
        assertFalse(AccelAssistPolicy.shouldRegister(false, true, true, false, true))
        assertFalse(AccelAssistPolicy.shouldRegister(true, true, true, false, false))
    }

    @Test
    fun `assist switch is locked during a running or finishing run`() {
        assertFalse(AccelAssistPolicy.canToggle(sessionRunning = true))
        assertTrue(AccelAssistPolicy.canToggle(sessionRunning = false))
    }
}
