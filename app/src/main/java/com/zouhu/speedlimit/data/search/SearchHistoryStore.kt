package com.zouhu.speedlimit.data.search

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One POI, flattened from Baidu's `PoiInfo`.
 *
 * `PoiInfo` does expose `address` / `province` / `city` / `area` / `distance`, so the list
 * can say where a place actually is instead of only its name — which is what makes
 * same-named places distinguishable. `distance` is only filled for nearby searches, so
 * we fall back to a local great-circle distance when it is 0.
 */
@Serializable
data class SearchPlace(
    val name: String,
    val uid: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    val city: String = "",
    val area: String = "",
    val distanceM: Int = -1,
) {
    /** "南京市·栖霞区" — blank parts dropped. */
    val region: String
        get() = listOf(city, area).filter { it.isNotBlank() }.joinToString("·")
}

/**
 * Recently used destinations, most recent first.
 *
 * Stores the resolved POI rather than the typed query: tapping a history row can plan the
 * route immediately, with no second search and no chance of resolving to a different place
 * than the one the user picked before.
 */
class SearchHistoryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("velora_search", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SearchPlace> = runCatching {
        json.decodeFromString<List<SearchPlace>>(prefs.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun remember(place: SearchPlace): List<SearchPlace> {
        val next = (listOf(place) + load().filterNot { it.uid == place.uid }).take(MAX)
        runCatching {
            val editor = prefs.edit()
            editor.putString(KEY, json.encodeToString(next))
            editor.apply()
        }
        return next
    }

    fun clear(): List<SearchPlace> {
        runCatching { prefs.edit().remove(KEY).apply() }
        return emptyList()
    }

    private companion object {
        const val KEY = "recent_places"
        const val MAX = 10
    }
}

/** Great-circle distance in metres; used when the SDK does not fill `PoiInfo.distance`. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
    if (!lat1.isFinite() || !lng1.isFinite() || !lat2.isFinite() || !lng2.isFinite()) return -1
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return (2 * r * Math.asin(kotlin.math.sqrt(a))).toInt()
}

/** "320m" / "1.8km"; blank when unknown. */
fun formatDistance(meters: Int): String = when {
    meters < 0 -> ""
    meters < 1000 -> "${meters}m"
    else -> "%.1fkm".format(meters / 1000.0)
}
