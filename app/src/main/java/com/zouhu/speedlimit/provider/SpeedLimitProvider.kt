package com.zouhu.speedlimit.provider

import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.RoadData
import kotlinx.coroutines.flow.StateFlow

interface SpeedLimitProvider {
    val connectionStatus: StateFlow<ConnectionStatus>
    fun connect()
    fun disconnect()
    fun startCruise()
    fun stopCruise()
    fun updateLocation(sample: GpsSample)
    fun observeRoadData(): StateFlow<RoadData>

    /**
     * Permanent teardown of this instance. Unlike [disconnect] (which must stay
     * reconnectable), a closed provider is never started again and must release any
     * long-lived collector/scope it owns (VA-P04). Default: nothing to release.
     */
    fun close() = Unit
}