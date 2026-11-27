package com.zouhu.speedlimit.data.nav

/**
 * G0 finding: navi speed/limit callbacks use sentinels (limit=500 / speed=-1 / 0)
 * for "unknown". Map them to null so the UI never shows a fake 500 km/h limit.
 */
object NavSentinels {
    fun limit(raw: Int): Int? = if (raw <= 0 || raw >= 500) null else raw
    fun speed(raw: Int): Int? = if (raw < 0) null else raw
}