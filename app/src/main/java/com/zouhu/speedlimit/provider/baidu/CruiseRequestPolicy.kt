package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Request budget for the HTTP cruise API.
 *
 * The old loop decided only `moving && 30 s`; in practice the provider restarted its
 * epoch every 10 s, so `first=true` turned into a detection request every 10 s even at
 * 0 km/h. This gate is independent of that loop bookkeeping: it only asks whether the
 * *car* has moved far enough, or enough time has passed, since the last request.
 *
 * Rules:
 *  - first request of a session: allow;
 *  - moving (>= 5 km/h): at most one request per 30 s;
 *  - stationary with a valid road result: no request until moved >= 25 m;
 *  - stationary without a valid result: retry at most every 30 s;
 *  - HTTP status 302 (daily quota): block this process for 15 minutes.
 */
internal class CruiseRequestPolicy(
    private val movingSpeedKmh: Double = 5.0,
    private val movingIntervalMs: Long = 30_000L,
    private val stationaryIntervalMs: Long = 30_000L,
    private val movementThresholdM: Double = 25.0,
    private val quotaBackoffMs: Long = 15 * 60_000L,
) {
    data class Decision(
        val request: Boolean,
        val detection: Boolean,
        val reason: String,
    )

    private var first = true
    private var lastRequestAt = 0L
    private var lastRequestLat: Double? = null
    private var lastRequestLng: Double? = null
    private var lastSuccessAt = 0L
    private var quotaBlockedUntil = 0L

    /** New cruise session: allow the first request immediately, keep any quota block. */
    fun resetSession() {
        first = true
        lastRequestAt = 0L
        lastRequestLat = null
        lastRequestLng = null
        lastSuccessAt = 0L
    }

    fun shouldRequest(nowMs: Long, sample: GpsSample, hasValidRoadData: Boolean): Decision {
        if (nowMs < quotaBlockedUntil) {
            return Decision(false, false, "quota")
        }
        val moving = sample.speedKmh >= movingSpeedKmh
        val distance = distanceFromLastRequest(sample)
        val due = when {
            first -> true
            moving -> nowMs - lastRequestAt >= movingIntervalMs
            distance >= movementThresholdM -> true
            !hasValidRoadData -> nowMs - lastRequestAt >= stationaryIntervalMs
            else -> false
        }
        if (!due) {
            return Decision(false, false, if (moving) "interval" else "stationary")
        }
        first = false
        lastRequestAt = nowMs
        lastRequestLat = sample.latitude
        lastRequestLng = sample.longitude
        val detection = lastSuccessAt == 0L || nowMs - lastSuccessAt > 60_000L
        return Decision(true, detection, "due")
    }

    /**
     * Feed the API status back. Returns true when this response tripped the quota block.
     * Only status 0 counts as success; 302 is a daily quota error, not an empty result.
     */
    fun onResponse(nowMs: Long, status: Int): Boolean {
        if (status == 302) {
            quotaBlockedUntil = nowMs + quotaBackoffMs
            return true
        }
        if (status == 0) {
            lastSuccessAt = nowMs
        }
        return false
    }

    fun quotaRemainingMs(nowMs: Long): Long =
        (quotaBlockedUntil - nowMs).coerceAtLeast(0L)

    private fun distanceFromLastRequest(sample: GpsSample): Double {
        val lat0 = lastRequestLat ?: return Double.MAX_VALUE
        val lng0 = lastRequestLng ?: return Double.MAX_VALUE
        val r = 6_371_000.0
        val dLat = Math.toRadians(sample.latitude - lat0)
        val dLng = Math.toRadians(sample.longitude - lng0)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat0)) * cos(Math.toRadians(sample.latitude)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
