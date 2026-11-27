package com.zouhu.speedlimit.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Multi-source location.
 *
 * The old implementation subscribed to [LocationManager.GPS_PROVIDER] only, so indoors
 * — where the GNSS chip produces nothing at all — the app behaved as if the device had
 * no idea where it was, even though the platform already knew a rough position from
 * Wi-Fi/cell. This source registers every provider the device actually has and publishes
 * the best one available:
 *
 *  - `fused`   (API 31+, platform fusion; usually the best indoor result)
 *  - `gps`     (GNSS; the only source that carries a trustworthy speed)
 *  - `network` (Wi-Fi/cell; coarse but available indoors)
 *
 * Ranking is "tier first, then accuracy, then recency", with a sticky preference so two
 * providers reporting slightly different points at 1 Hz cannot make the map jitter.
 */
class MultiSourceLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Set while [samples] is being collected. A coarse→fine upgrade happens after the
     * flow already registered what it could, so the missing GPS/fused listener must be
     * added without tearing the flow down (VA-F07).
     */
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @SuppressLint("MissingPermission")
    fun samples(): Flow<GpsSample> = callbackFlow {
        val main = Looper.getMainLooper()
        val entries = LinkedHashMap<String, GpsSample>()
        val listeners = LinkedHashMap<String, LocationListener>()
        var preferred: String? = null
        var lastEmitted: GpsSample? = null

        fun nowElapsed() = SystemClock.elapsedRealtimeNanos()
        fun nowWall() = System.currentTimeMillis()

        fun tierRank(s: GpsSample) =
            LocationQuality.tierOf(s, nowElapsed(), nowWall()).ordinal

        fun accuracyRank(s: GpsSample) = LocationQuality.accuracyOf(s) ?: 9_999f

        /**
         * Publish the best live sample. Entries older than [STICKY_FRESH_MS] are ignored
         * so a dead provider stops winning; the repository keeps the last value and ages
         * it out on its own.
         */
        fun emitBest() {
            val live = entries.filterValues {
                LocationQuality.ageMs(it, nowElapsed(), nowWall()) <= STICKY_FRESH_MS
            }
            if (live.isEmpty()) return

            val bestKey = live.entries.maxWithOrNull(
                compareBy<Map.Entry<String, GpsSample>>(
                    { tierRank(it.value) },
                    { -accuracyRank(it.value) },
                    { it.value.elapsedRealtimeNanos },
                ),
            )?.key ?: return

            val stickyKey = preferred?.takeIf { live.containsKey(it) }
            val chosen = when {
                stickyKey == null -> bestKey
                // A different tier always wins: that is a real change in capability.
                tierRank(live.getValue(stickyKey)) != tierRank(live.getValue(bestKey)) -> bestKey
                // Same tier: only switch when the alternative is clearly more accurate.
                accuracyRank(live.getValue(stickyKey)) > accuracyRank(live.getValue(bestKey)) * 1.3f -> bestKey
                else -> stickyKey
            }
            preferred = chosen
            val sample = live.getValue(chosen)
            if (sample != lastEmitted) {
                lastEmitted = sample
                trySend(sample)
            }
        }

        fun register(provider: String, source: LocationSource, needsFine: Boolean) {
            if (lm.allProviders?.contains(provider) != true) return
            if (listeners.containsKey(provider)) return
            val granted = if (needsFine) hasFine() else (hasFine() || hasCoarse())
            if (!granted) return
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    entries[provider] = location.toSample(source)
                    emitBest()
                }

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    entries.remove(provider)
                    if (preferred == provider) preferred = null
                    emitBest()
                }

                @Deprecated("Android framework deprecated")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            val started = runCatching {
                lm.requestLocationUpdates(provider, 1000L, 0f, listener, main)
            }.isSuccess
            if (started) listeners[provider] = listener
        }

        // Seed from the platform cache so the map can appear before the first live fix.
        if (hasFine() || hasCoarse()) {
            val seeds = listOf(
                SEED_FUSED to LocationSource.FUSED,
                LocationManager.GPS_PROVIDER to LocationSource.GPS,
                LocationManager.NETWORK_PROVIDER to LocationSource.NETWORK,
            ).mapNotNull { (provider, source) ->
                if (provider == SEED_FUSED && Build.VERSION.SDK_INT < 31) return@mapNotNull null
                if (lm.allProviders?.contains(provider) != true) return@mapNotNull null
                runCatching { lm.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.toSample(source)
            }
            val usableSeed = seeds
                .filter { LocationQuality.canShowMap(it, nowElapsed(), nowWall()) }
                .maxByOrNull { it.elapsedRealtimeNanos }
            if (usableSeed != null) {
                lastEmitted = usableSeed
                trySend(usableSeed)
            }
        }

        // Fused first: on most devices it is the best answer available indoors.
        val registerAll = {
            if (Build.VERSION.SDK_INT >= 31) register(SEED_FUSED, LocationSource.FUSED, needsFine = true)
            register(LocationManager.GPS_PROVIDER, LocationSource.GPS, needsFine = true)
            register(LocationManager.NETWORK_PROVIDER, LocationSource.NETWORK, needsFine = false)
        }
        val refreshJob = launch { refreshRequests.collect { registerAll() } }
        registerAll()

        awaitClose {
            refreshJob.cancel()
            listeners.values.forEach { listener ->
                runCatching { lm.removeUpdates(listener) }
            }
            listeners.clear()
            entries.clear()
        }
    }

    /** Re-run registration after a runtime permission upgrade; no-op before collection. */
    fun refreshPermissions() {
        refreshRequests.tryEmit(Unit)
    }

    private fun hasFine(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCoarse(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toSample(source: LocationSource): GpsSample {
        val speedOk = hasSpeed()
        val bearingOk = hasBearing()
        return GpsSample(
            latitude = latitude,
            longitude = longitude,
            speedMps = if (speedOk) speed.toDouble() else 0.0,
            bearingDeg = if (bearingOk) bearing.toDouble() else 0.0,
            accuracyM = if (hasAccuracy()) accuracy else 0f,
            timestampMs = time,
            hasFix = true,
            speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else 0f,
            hasSpeedAccuracy = hasSpeedAccuracy(),
            hasAccuracy = hasAccuracy(),
            hasSpeed = speedOk,
            hasBearing = bearingOk,
            source = source,
            isMock = isMocked(),
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    private fun Location.isMocked(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }

    private companion object {
        /** Literal avoids a NewApi reference to LocationManager.FUSED_PROVIDER. */
        const val SEED_FUSED = "fused"

        /** A provider that has not spoken for this long stops competing. */
        const val STICKY_FRESH_MS = 10_000L
    }
}
