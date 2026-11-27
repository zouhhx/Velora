package com.zouhu.speedlimit.data.map.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class HorizonLocStampTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `preserves the real fix timestamp`() {
        val fix = now - 12_000L
        assertEquals(fix, HorizonLocStamp.resolve(now, fix))
    }

    @Test
    fun `rejects stale fix instead of stamping it with now`() {
        assertNull(HorizonLocStamp.resolve(now, now - 60_001L))
    }

    @Test
    fun `rejects missing or far-future timestamps`() {
        assertNull(HorizonLocStamp.resolve(now, 0L))
        assertNull(HorizonLocStamp.resolve(now, now + 5_001L))
    }

    @Test
    fun `accepts small future skew within bound`() {
        val fix = now + 2_000L
        assertEquals(fix, HorizonLocStamp.resolve(now, fix))
    }
}

class HorizonLocMessageTest {
    @Test
    fun `loc ts keeps fix time while envelope ts is send time`() {
        val now = 1_800_000_000_000L
        val fix = now - 12_000L
        val sample = com.zouhu.speedlimit.domain.model.GpsSample(
            latitude = 32.0,
            longitude = 118.0,
            hasFix = true,
            timestampMs = fix,
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            buildHorizonLocMessage(sample, now, 7L)!!,
        ).jsonObject
        assertEquals(now, json["ts"]!!.jsonPrimitive.long)
        assertEquals(
            fix,
            json["data"]!!.jsonObject["loc"]!!.jsonObject["ts"]!!.jsonPrimitive.long,
        )
    }

    @Test
    fun `stale fix produces no message`() {
        val now = 1_800_000_000_000L
        val sample = com.zouhu.speedlimit.domain.model.GpsSample(
            latitude = 32.0,
            longitude = 118.0,
            hasFix = true,
            timestampMs = now - 60_001L,
        )
        assertNull(buildHorizonLocMessage(sample, now, 8L))
    }
}
