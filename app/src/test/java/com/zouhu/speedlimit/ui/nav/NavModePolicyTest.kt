package com.zouhu.speedlimit.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavModePolicyTest {
    @Test
    fun `demo can start only while real navigation is inactive`() {
        val policy = NavModePolicy()
        assertTrue(policy.enableDemo())
        assertEquals(NavModePolicy.Mode.DEMO, policy.mode)

        assertTrue(policy.setRealActive(true))
        assertEquals(NavModePolicy.Mode.REAL, policy.mode)
        assertFalse(policy.enableDemo())
        assertEquals(NavModePolicy.Mode.REAL, policy.mode)
    }

    @Test
    fun `activating real navigation clears a running demo`() {
        val policy = NavModePolicy()
        policy.enableDemo()

        assertTrue(policy.setRealActive(true))
        assertEquals(NavModePolicy.Mode.REAL, policy.mode)
    }

    @Test
    fun `ending real navigation returns to cruise and permits demo`() {
        val policy = NavModePolicy()
        policy.setRealActive(true)
        assertFalse(policy.setRealActive(false))
        assertEquals(NavModePolicy.Mode.CRUISE, policy.mode)
        assertTrue(policy.enableDemo())
    }

    @Test
    fun `exit resets both modes`() {
        val policy = NavModePolicy()
        policy.enableDemo()
        policy.exit()
        assertEquals(NavModePolicy.Mode.CRUISE, policy.mode)
        policy.setRealActive(true)
        policy.exit()
        assertEquals(NavModePolicy.Mode.CRUISE, policy.mode)
    }
}
