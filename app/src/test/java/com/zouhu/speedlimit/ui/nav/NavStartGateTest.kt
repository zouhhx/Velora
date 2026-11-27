package com.zouhu.speedlimit.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStartGateTest {
    @Test
    fun `a token can be consumed only once`() {
        val gate = NavStartGate()
        val token = gate.request()

        assertTrue(gate.consume(token))
        assertFalse(gate.consume(token))
    }

    @Test
    fun `a newer request invalidates the older delayed start`() {
        val gate = NavStartGate()
        val first = gate.request()
        val second = gate.request()

        assertFalse(gate.consume(first))
        assertTrue(gate.consume(second))
    }

    @Test
    fun `reopening search cancels old intent and a later destination gets a fresh token`() {
        val gate = NavStartGate()
        val first = gate.request()
        // This is what MapChromeHost does when the user re-opens search during the
        // 320 ms wait: cancelPendingStart() before showing the search page again.
        gate.cancel()
        val second = gate.request()

        assertFalse(gate.consume(first))
        assertTrue(gate.consume(second))
    }

    @Test
    fun `a boundary recheck invalidates the pending token when search reopened`() {
        val gate = NavStartGate()
        val token = gate.request()

        assertFalse(gate.consume(token, canStart = false))
        // Even a later back/return cannot revive the old intent.
        assertFalse(gate.consume(token))
    }

    @Test
    fun `cancel rejects every pending token`() {
        val gate = NavStartGate()
        val token = gate.request()
        gate.cancel()

        assertFalse(gate.consume(token))
        assertFalse(gate.consume(1))
    }
}
