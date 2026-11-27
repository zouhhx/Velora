package com.zouhu.speedlimit.data.map.baidu

/**
 * VA-G03: the `loc.ts` sent to Horizon must be the fix time, not the time the
 * WebSocket happened to flush the message. A stale fix re-sent with `now` looks like a
 * fresh position on the server and can move the matched link/light under the car.
 *
 * The envelope `ts` remains the send time (heartbeat semantics); only `loc.ts` is
 * preserved. Returns null when the fix cannot be trusted at all.
 */
internal object HorizonLocStamp {
    const val MAX_AGE_MS = 60_000L
    const val MAX_FUTURE_MS = 5_000L

    fun resolve(nowMs: Long, sampleTimestampMs: Long): Long? {
        if (sampleTimestampMs <= 0L) return null
        if (sampleTimestampMs > nowMs + MAX_FUTURE_MS) return null
        if (nowMs - sampleTimestampMs > MAX_AGE_MS) return null
        return sampleTimestampMs
    }
}
