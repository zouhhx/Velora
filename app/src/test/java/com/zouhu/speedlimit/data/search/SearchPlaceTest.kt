package com.zouhu.speedlimit.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlaceTest {
    @Test
    fun regionDropsBlankParts() {
        assertEquals("南京市·栖霞区", SearchPlace("a", "u", 0.0, 0.0, city = "南京市", area = "栖霞区").region)
        assertEquals("南京市", SearchPlace("a", "u", 0.0, 0.0, city = "南京市").region)
        assertEquals("", SearchPlace("a", "u", 0.0, 0.0).region)
    }

    @Test
    fun distanceFormatting() {
        assertEquals("", formatDistance(-1))
        assertEquals("0m", formatDistance(0))
        assertEquals("320m", formatDistance(320))
        assertEquals("1.8km", formatDistance(1800))
        assertEquals("12.0km", formatDistance(12_000))
    }

    @Test
    fun distanceIsRoughlyRight() {
        // ~1.1 km between two points 0.01° apart in latitude.
        val d = distanceMeters(32.0, 118.0, 32.01, 118.0)
        assertTrue("expected ~1112m, got $d", d in 1_050..1_180)
        // Identical points → 0, invalid input → -1 (renders as blank).
        assertEquals(0, distanceMeters(32.0, 118.0, 32.0, 118.0))
        assertEquals(-1, distanceMeters(Double.NaN, 118.0, 32.0, 118.0))
    }

    @Test
    fun placeSurvivesJsonRoundTrip() {
        val place = SearchPlace(
            name = "中国农业银行(马群支行)",
            uid = "abc123",
            lat = 32.06,
            lng = 118.88,
            address = "马群街道1号",
            city = "南京市",
            area = "栖霞区",
            distanceM = 860,
        )
        val json = kotlinx.serialization.json.Json
        val text = json.encodeToString(SearchPlace.serializer(), place)
        val back = json.decodeFromString(SearchPlace.serializer(), text)
        assertEquals(place, back)
        assertEquals("南京市·栖霞区", back.region)
    }
}
