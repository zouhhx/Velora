package com.zouhu.speedlimit.data.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRedactionTest {
    @Test
    fun `auth payload never logs ak sk token or uid`() {
        val payload = """{"status":0,"ak":"AK_SECRET","sk":"SK_SECRET","token":"TOKEN_SECRET","uid":"UID_SECRET"}"""
        val safe = redactAuthMessage(payload)

        assertFalse(safe.contains("AK_SECRET"))
        assertFalse(safe.contains("SK_SECRET"))
        assertFalse(safe.contains("TOKEN_SECRET"))
        assertFalse(safe.contains("UID_SECRET"))
        assertTrue(safe.contains("\"ak\":\"***\""))
        assertTrue(safe.contains("\"token\":\"***\""))
    }

    @Test
    fun `null and blank messages stay empty`() {
        assertTrue(redactAuthMessage(null).isEmpty())
        assertTrue(redactAuthMessage("  ").isEmpty())
    }
}
