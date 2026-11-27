package com.zouhu.speedlimit.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    private val newAk = "5QQB" + "tTestAndroidAk9999"
    private val newServerAk = "ONEH" + "10TestServerAk9999"
    private val newSk = "sk-" + "testMimoSecret1234567890"
    private val newServerSk = "NEW_SERVER_SK_VALUE"

    @Test
    fun `query json bearer and known prefixes are redacted`() {
        val text = """
            https://api.map.baidu.com/x?ak=$newServerAk&sk=$newServerSk&token=TOKEN
            {"ak":"$newAk","sk":"$newServerSk"}
            Authorization: Bearer $newSk
        """.trimIndent()

        val safe = SecretRedaction.redact(text)

        assertFalse(safe.contains(newAk))
        assertFalse(safe.contains(newServerAk))
        assertFalse(safe.contains(newSk))
        assertFalse(safe.contains(newServerSk))
        assertFalse(safe.contains("TOKEN"))
        assertTrue(safe.contains("ak=***"))
        assertTrue(safe.contains("\"ak\":\"***\""))
        assertTrue(safe.contains("Bearer ***"))
    }

    @Test
    fun `mask never returns the full key`() {
        val masked = SecretRedaction.mask(newServerAk)
        assertFalse(masked.contains(newServerAk))
        assertTrue(masked.startsWith("ONEH"))
        assertTrue(masked.endsWith("99"))
        assertEquals("", SecretRedaction.mask(""))
        assertEquals("***", SecretRedaction.mask("short"))
    }

    @Test
    fun `blank messages stay blank`() {
        assertEquals("", SecretRedaction.redact(null))
        assertEquals("", SecretRedaction.redact("   "))
    }
}
