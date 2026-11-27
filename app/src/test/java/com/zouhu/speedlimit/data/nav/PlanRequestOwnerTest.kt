package com.zouhu.speedlimit.data.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VA-N05: late route-plan callbacks must not be able to win over a newer request. */
class PlanRequestOwnerTest {

    @Test
    fun aNewRequestInvalidatesThePreviousToken() {
        val owner = PlanRequestOwner()
        val first = owner.begin()
        assertTrue(owner.isCurrent(first))

        val second = owner.begin()
        assertFalse(owner.isCurrent(first))
        assertTrue(owner.isCurrent(second))
    }

    @Test
    fun cancelInvalidatesWithoutStartingANewRequest() {
        val owner = PlanRequestOwner()
        val token = owner.begin()
        owner.cancel()

        assertFalse(owner.isCurrent(token))
    }

    @Test
    fun tokensAreMonotonicAcrossCancel() {
        val owner = PlanRequestOwner()
        val first = owner.begin()
        owner.cancel()
        val second = owner.begin()

        assertTrue(second > first)
        assertFalse(owner.isCurrent(first))
        assertTrue(owner.isCurrent(second))
    }
}
