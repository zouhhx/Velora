package com.zouhu.speedlimit.data.location

import android.content.Context
import android.os.SystemClock
import com.zouhu.speedlimit.domain.engine.SpeedFilter
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the UI and the data providers are allowed to use right now.
 *
 * There is deliberately no "unknown" wording anywhere: the app expresses this state by
 * rendering less (no gauge, no limit) or nothing (blank map) instead of a label.
 */
data class LocationStatus(
    val tier: LocationTier = LocationTier.NONE,
    /** Any usable position → the map may show. */
    val hasPosition: Boolean = false,
    /** Current, link-accurate position → limit / camera / lights may show. */
    val cruiseData: Boolean = false,
    /** Live satellite speed → the speedometer may render. */
    val speed: Boolean = false,
    val ageMs: Long = 0L,
    val accuracyM: Float? = null,
    val source: LocationSource = LocationSource.UNKNOWN,
)

class LocationRepository(context: Context) {
    private val source = MultiSourceLocationSource(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var statusJob: Job? = null

    private val _latest = MutableStateFlow(GpsSample())
    val latest: StateFlow<GpsSample> = _latest

    private val _status = MutableStateFlow(LocationStatus())
    val status: StateFlow<LocationStatus> = _status

    val filteredSpeedKmh: Double
        get() = SpeedFilter.filter(_latest.value.speedKmh)

    // A capability that just dropped stays true briefly, so a two-second bridge shadow
    // cannot make the gauge blink out and back in. Recovery is immediate.
    private var mapHoldUntil = 0L
    private var dataHoldUntil = 0L
    private var speedHoldUntil = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            source.samples().collect { _latest.value = it }
        }
        statusJob = scope.launch {
            while (isActive) {
                tick()
                delay(STATUS_TICK_MS)
            }
        }
    }

    /** Coarse→fine upgrade: the running flow adds the listeners it previously skipped. */
    fun refreshPermissions() {
        source.refreshPermissions()
    }

    fun stop() {
        job?.cancel()
        job = null
        statusJob?.cancel()
        statusJob = null
        _latest.value = GpsSample()
        mapHoldUntil = 0L
        dataHoldUntil = 0L
        speedHoldUntil = 0L
        _status.value = LocationStatus()
    }

    private fun tick() {
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val nowWall = System.currentTimeMillis()
        val sample = _latest.value

        mapHoldUntil = hold(mapHoldUntil, LocationQuality.canShowMap(sample, nowElapsed, nowWall), nowElapsed)
        dataHoldUntil = hold(dataHoldUntil, LocationQuality.canFeedRoadData(sample, nowElapsed, nowWall), nowElapsed)
        speedHoldUntil = hold(speedHoldUntil, LocationQuality.canShowSpeed(sample, nowElapsed, nowWall), nowElapsed)

        val next = LocationStatus(
            tier = LocationQuality.tierOf(sample, nowElapsed, nowWall),
            hasPosition = nowElapsed < mapHoldUntil,
            cruiseData = nowElapsed < dataHoldUntil,
            speed = nowElapsed < speedHoldUntil,
            ageMs = LocationQuality.ageMs(sample, nowElapsed, nowWall),
            accuracyM = LocationQuality.accuracyOf(sample),
            source = sample.source,
        )
        if (next != _status.value) _status.value = next
    }

    private fun hold(until: Long, raw: Boolean, nowElapsed: Long): Long = when {
        raw -> nowElapsed + HOLD_NANOS
        until > nowElapsed -> until
        else -> 0L
    }

    private companion object {
        const val STATUS_TICK_MS = 500L

        /** 2.5 s expressed in elapsedRealtimeNanos. */
        const val HOLD_NANOS = 2_500_000_000L
    }
}
