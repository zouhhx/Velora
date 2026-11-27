package com.zouhu.speedlimit.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VA-F01: the 320 ms window between closing the preview and creating the guide.
 *
 * These tests drive the *production* [NavStartWindowEffect] with a controlled clock.
 * They are instrumentation tests because the behaviour depends on Compose's
 * LaunchedEffect cancellation, which a JVM test cannot reproduce.
 *
 * Note: while `autoAdvance` is false, `waitForIdle()` can block on the pending frame.
 * Every state change is therefore followed by `advanceTimeByFrame()` instead.
 */
@RunWith(AndroidJUnit4::class)
class NavStartWindowTest {

    @get:Rule
    val rule = createComposeRule()

    private class Harness {
        var destination by mutableStateOf("A")
        var showSearch by mutableStateOf(false)
        var live by mutableStateOf(true)
        var pending by mutableStateOf(0)
        val started = mutableListOf<String>()
        val gate = NavStartGate()

        fun request(name: String) {
            destination = name
            pending = gate.request()
        }
    }

    private fun setContent(harness: Harness) {
        rule.setContent {
            NavStartWindowEffect(
                pendingStart = harness.pending,
                live = harness.live,
                showSearch = harness.showSearch,
                consume = harness.gate::consume,
                onCancel = {
                    harness.gate.cancel()
                    harness.pending = 0
                },
                onStart = {
                    harness.started += harness.destination
                    harness.pending = 0
                },
            )
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun onlyTheLatestDestinationStartsWithinTheWindow() {
        val harness = Harness()
        setContent(harness)

        harness.request("A")
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(emptyList<String>(), harness.started)

        // The user picks B before A's 320 ms elapse; A must never start.
        harness.request("B")
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(400)

        assertEquals(listOf("B"), harness.started)
        assertEquals(0, harness.pending)
    }

    @Test
    fun reopeningSearchCancelsThePendingStart() {
        val harness = Harness()
        setContent(harness)

        harness.request("A")
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)

        harness.showSearch = true
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(600)

        assertEquals(emptyList<String>(), harness.started)
        assertEquals(0, harness.pending)
    }

    @Test
    fun leavingMapCancelsThePendingStart() {
        val harness = Harness()
        setContent(harness)

        harness.request("A")
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(120)

        harness.live = false
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(600)

        assertEquals(emptyList<String>(), harness.started)
        assertEquals(0, harness.pending)
    }

    @Test
    fun aConsumedTokenCannotStartTwice() {
        val harness = Harness()
        setContent(harness)

        harness.request("A")
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(listOf("A"), harness.started)
    }
}
