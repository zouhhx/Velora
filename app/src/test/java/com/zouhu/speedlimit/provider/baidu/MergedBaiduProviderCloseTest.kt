package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.provider.SpeedLimitProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VA-P04: disconnect is reconnectable, close is terminal. Before this, every AK switch
 * left two `combine` collectors alive and kept the previous provider pair reachable.
 */
class MergedBaiduProviderCloseTest {

    private class FakeProvider : SpeedLimitProvider {
        val road = MutableStateFlow(RoadData())
        val status = MutableStateFlow(ConnectionStatus.OFFLINE)
        override val connectionStatus: StateFlow<ConnectionStatus> = status
        override fun observeRoadData(): StateFlow<RoadData> = road
        var starts = 0
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun startCruise() {
            starts++
        }
        override fun stopCruise() = Unit
        override fun updateLocation(sample: GpsSample) = Unit
    }

    @Test
    fun disconnectKeepsMergingButCloseStopsIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val http = FakeProvider()
        val horizon = FakeProvider()
        val merged = MergedBaiduProvider(http, horizon, scope)
        runCurrent()

        http.road.value = RoadData(isValid = true, speedLimit = 80, timestampMs = 1)
        runCurrent()
        assertEquals(80, merged.observeRoadData().value.speedLimit)

        // A network drop must not kill the merge collectors: reconnect still works.
        merged.disconnect()
        http.road.value = RoadData(isValid = true, speedLimit = 60, timestampMs = 2)
        runCurrent()
        assertEquals(60, merged.observeRoadData().value.speedLimit)

        merged.close()
        http.road.value = RoadData(isValid = true, speedLimit = 40, timestampMs = 3)
        runCurrent()
        assertEquals(60, merged.observeRoadData().value.speedLimit)

        // A closed instance must not start its children again.
        val startsBefore = http.starts
        merged.startCruise()
        assertEquals(startsBefore, http.starts)
    }
}
