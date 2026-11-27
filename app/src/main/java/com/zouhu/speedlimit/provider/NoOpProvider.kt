package com.zouhu.speedlimit.provider

import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.RoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Placeholder used before a direct AK is available (and after it is cleared).
 *
 * This replaces `RemoteProvider(null)`, which only existed to model the retired
 * gateway path (VA-E01). It never produces data and never falls back to simulation.
 */
class NoOpProvider : SpeedLimitProvider {
    private val road = MutableStateFlow(RoadData())
    private val status = MutableStateFlow(ConnectionStatus.OFFLINE)
    override val connectionStatus: StateFlow<ConnectionStatus> = status

    override fun observeRoadData(): StateFlow<RoadData> = road
    override fun connect() {
        status.value = ConnectionStatus.OFFLINE
    }

    override fun disconnect() = Unit
    override fun startCruise() = Unit
    override fun stopCruise() {
        road.value = RoadData()
        status.value = ConnectionStatus.OFFLINE
    }

    override fun updateLocation(sample: GpsSample) = Unit
}
