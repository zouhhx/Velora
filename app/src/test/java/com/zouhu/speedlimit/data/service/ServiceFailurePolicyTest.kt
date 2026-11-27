package com.zouhu.speedlimit.data.service

import com.zouhu.speedlimit.data.map.baidu.HorizonAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceFailurePolicyTest {
    @Test
    fun `success and unknown empty status do not pause`() {
        assertEquals(ServiceFailureKind.NONE, ServiceFailurePolicy.classify(0, "ok"))
        assertEquals(ServiceFailureKind.NONE, ServiceFailurePolicy.classify(null, null))
        assertFalse(ServiceFailurePolicy.isServicePaused(ServiceFailureKind.NONE))
    }

    @Test
    fun `quota status is a long quota pause`() {
        assertEquals(ServiceFailureKind.QUOTA, ServiceFailurePolicy.classify(302, "天配额超限"))
        assertEquals(ServiceFailurePolicy.QUOTA_PAUSE_MS, ServiceFailurePolicy.retryDelayMs(ServiceFailureKind.QUOTA, 0))
        assertTrue(ServiceFailurePolicy.isServicePaused(ServiceFailureKind.QUOTA))
    }

    @Test
    fun `auth status and expired wording are terminal for this pause window`() {
        assertEquals(ServiceFailureKind.AUTH, ServiceFailurePolicy.classify(401, ""))
        assertEquals(ServiceFailureKind.AUTH, ServiceFailurePolicy.classify(403, ""))
        assertEquals(ServiceFailureKind.AUTH, ServiceFailurePolicy.classify(1, "服务未开通或已过期"))
        assertEquals(ServiceFailureKind.AUTH, ServiceFailurePolicy.classify(1, "invalid ak"))
        assertEquals(ServiceFailureKind.AUTH, ServiceFailurePolicy.classify(HorizonAuthException(1, "denied")))
        assertEquals(ServiceFailurePolicy.AUTH_PAUSE_MS, ServiceFailurePolicy.retryDelayMs(ServiceFailureKind.AUTH, 0))
        assertTrue(ServiceFailurePolicy.isServicePaused(ServiceFailureKind.AUTH))
    }

    @Test
    fun `ordinary non zero status stays transient`() {
        assertEquals(ServiceFailureKind.TRANSIENT, ServiceFailurePolicy.classify(500, "server error"))
        assertEquals(2_000L, ServiceFailurePolicy.retryDelayMs(ServiceFailureKind.TRANSIENT, 0))
        assertEquals(4_000L, ServiceFailurePolicy.retryDelayMs(ServiceFailureKind.TRANSIENT, 1))
        assertEquals(30_000L, ServiceFailurePolicy.retryDelayMs(ServiceFailureKind.TRANSIENT, 9))
        assertFalse(ServiceFailurePolicy.isServicePaused(ServiceFailureKind.TRANSIENT))
    }
}
