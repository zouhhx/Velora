package com.zouhu.speedlimit.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VA-O04 / Q-21: relevance tier first, then distance within the tier. */
class SearchRankingTest {

    private fun place(name: String, distance: Int, uid: String = name, city: String = "南京") =
        SearchPlace(name = name, uid = uid, lat = 0.0, lng = 0.0, city = city, distanceM = distance)

    @Test
    fun `exact cross-city destination beats a nearer partial match`() {
        val local = listOf(place("上海外滩公园", 500))
        val remote = listOf(place("上海外滩", 8_000, city = "上海"))

        val ranked = SearchRanking.rank("上海外滩", local, remote)
        assertEquals("上海外滩", ranked.first().name)
    }

    @Test
    fun `category results are ordered by distance`() {
        val local = listOf(
            place("A健身房", 2_300),
            place("B健身房", 1_100),
        )
        val ranked = SearchRanking.rank("健身房", local, emptyList())
        assertEquals(listOf("B健身房", "A健身房"), ranked.map { it.name })
    }

    @Test
    fun `full-query match sorts before category match even if farther`() {
        val places = listOf(
            place("新能源服务站", 200),
            place("特来电充电站", 2_300),
        )
        val ranked = SearchRanking.rank("充电", places, emptyList())
        assertEquals("特来电充电站", ranked.first().name)
    }

    @Test
    fun `unknown distance sorts last within its tier`() {
        val places = listOf(
            place("A充电站", -1),
            place("B充电站", 3_000),
        )
        val ranked = SearchRanking.rank("充电", places, emptyList())
        assertEquals("B充电站", ranked.first().name)
    }

    @Test
    fun `local and remote duplicates collapse by uid`() {
        val local = listOf(place("同一地点", 500, uid = "same"))
        val remote = listOf(place("同一地点", 900, uid = "same"))
        val ranked = SearchRanking.rank("同一地点", local, remote)
        assertEquals(1, ranked.size)
    }

    @Test
    fun `limit is applied after ranking`() {
        val places = (1..40).map { place("充电站$it", it * 10) }
        val ranked = SearchRanking.rank("充电", places, emptyList())
        assertEquals(SearchRanking.LIMIT, ranked.size)
        assertTrue(ranked.first().distanceM <= ranked.last().distanceM)
    }
}
