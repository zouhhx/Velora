package com.zouhu.speedlimit.data.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideStartGateTest {
    @Test
    fun `resumed attached view starts`() {
        assertEquals(
            GuideStartDecision.START,
            GuideStartGate.decide(true, true, true, 1080, 2340, HostLifecycle.RESUMED),
        )
    }

    @Test
    fun `paused started or stopped host aborts even with a laid out view`() {
        for (host in listOf(
            HostLifecycle.CREATED,
            HostLifecycle.STARTED,
            HostLifecycle.PAUSED,
            HostLifecycle.STOPPED,
            HostLifecycle.DESTROYED,
        )) {
            assertEquals(
                GuideStartDecision.ABORT,
                GuideStartGate.decide(true, true, true, 1080, 2340, host),
            )
        }
    }

    @Test
    fun `resumed but not laid out view waits`() {
        assertEquals(
            GuideStartDecision.WAIT_FOR_LAYOUT,
            GuideStartGate.decide(true, true, false, 0, 0, HostLifecycle.RESUMED),
        )
        assertEquals(
            GuideStartDecision.WAIT_FOR_LAYOUT,
            GuideStartGate.decide(true, true, true, 0, 2340, HostLifecycle.RESUMED),
        )
    }

    @Test
    fun `inactive or stale view aborts`() {
        assertEquals(
            GuideStartDecision.ABORT,
            GuideStartGate.decide(false, true, true, 1080, 2340, HostLifecycle.RESUMED),
        )
        assertEquals(
            GuideStartDecision.ABORT,
            GuideStartGate.decide(true, false, true, 1080, 2340, HostLifecycle.RESUMED),
        )
    }
}
