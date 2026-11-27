package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.data.map.baidu.BaiduCamera
import com.zouhu.speedlimit.data.map.baidu.BaiduResponse
import com.zouhu.speedlimit.data.map.baidu.BaiduResult
import com.zouhu.speedlimit.data.map.baidu.BaiduRoute
import com.zouhu.speedlimit.data.map.baidu.CruiseRoadApi
import com.zouhu.speedlimit.data.service.ServiceHealth
import com.zouhu.speedlimit.data.service.ServiceStatus
import com.zouhu.speedlimit.data.service.ServiceStatusSink
import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import com.zouhu.speedlimit.domain.model.RoadData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BaiduCruiseProviderLifecycleTest {
    private fun response(vararg cameras: BaiduCamera) = BaiduResponse(
        status = 0,
        result = BaiduResult(
            routes = listOf(BaiduRoute(camera_list = cameras.toList())),
        ),
    )

    private fun speedCamera(limit: Int, position: String = "121.0044,30.9979") = BaiduCamera(
        type = 1,
        position = position,
        linkid = 10L,
        offset = 100,
        speed_limit = limit,
    )

    private fun intervalCamera(limit: Int) = BaiduCamera(
        type = 5,
        position = "121.0005,31.0005",
        linkid = 11L,
        offset = 200,
        speed_limit = limit,
    )

    private fun validSample(clock: Long) = GpsSample(
        latitude = 31.0000,
        longitude = 121.0000,
        speedMps = 10.0,
        bearingDeg = 90.0,
        accuracyM = 5f,
        timestampMs = clock,
        hasFix = true,
        hasAccuracy = true,
        hasSpeed = true,
        hasBearing = true,
        source = LocationSource.GPS,
    )

    private fun unusableSample(clock: Long) = GpsSample(
        latitude = 31.0000,
        longitude = 121.0000,
        speedMps = 0.0,
        accuracyM = 500f,
        timestampMs = clock,
        hasFix = true,
        hasAccuracy = true,
        source = LocationSource.NETWORK,
    )

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            Thread.sleep(10)
        }
        assertTrue("condition not reached in ${timeoutMs}ms", condition())
    }

    private class FakeApi(
        private val handler: (List<GpsSample>) -> BaiduResponse,
    ) : CruiseRoadApi {
        val requestStarted = CountDownLatch(1)
        val responseDelivered = CountDownLatch(1)
        val cancelCount = AtomicInteger(0)

        override fun detection(track: List<GpsSample>): BaiduResponse {
            requestStarted.countDown()
            val result = handler(track)
            responseDelivered.countDown()
            return result
        }

        override fun refresh(track: List<GpsSample>): BaiduResponse = handler(track)

        override fun cancelCurrent() {
            cancelCount.incrementAndGet()
        }
    }

    private fun provider(
        api: CruiseRoadApi,
        scope: CoroutineScope,
        clock: () -> Long,
    ) = BaiduCruiseProvider(
        api = api,
        scope = scope,
        nowMs = clock,
        pollIntervalMs = 20L,
    )

    @Test
    fun `late response after stop is rejected and cannot repopulate cleared road`() {
        val gate = CountDownLatch(1)
        val oldResponse = response(speedCamera(120))
        val api = FakeApi {
            gate.await(2, TimeUnit.SECONDS)
            oldResponse
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = System.currentTimeMillis()
        val provider = provider(api, scope) { clock }
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            assertTrue(api.requestStarted.await(2, TimeUnit.SECONDS))

            provider.stopCruise()
            assertEquals(RoadData(), provider.observeRoadData().value)
            assertEquals(ConnectionStatus.OFFLINE, provider.connectionStatus.value)

            gate.countDown()
            assertTrue(api.responseDelivered.await(2, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(RoadData(), provider.observeRoadData().value)
            assertTrue(api.cancelCount.get() >= 1)
        } finally {
            gate.countDown()
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `stop and restart rejects old response while keeping the new session result`() {
        val oldGate = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val oldResponse = response(speedCamera(120))
        val newResponse = response(speedCamera(70))
        val api = object : CruiseRoadApi {
            val cancelCount = AtomicInteger(0)
            override fun detection(track: List<GpsSample>): BaiduResponse {
                return if (calls.incrementAndGet() == 1) {
                    oldGate.await(2, TimeUnit.SECONDS)
                    oldResponse
                } else {
                    secondDone.countDown()
                    newResponse
                }
            }
            override fun refresh(track: List<GpsSample>): BaiduResponse = detection(track)
            override fun cancelCurrent() { cancelCount.incrementAndGet() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = System.currentTimeMillis()
        val provider = provider(api, scope) { clock }
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            waitUntil { calls.get() >= 1 }

            provider.stopCruise()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            assertTrue(secondDone.await(2, TimeUnit.SECONDS))
            waitUntil { provider.observeRoadData().value.cameraSpeedLimit == 70 }

            oldGate.countDown()
            Thread.sleep(80)
            assertEquals(70, provider.observeRoadData().value.cameraSpeedLimit)
            assertNotEquals(120, provider.observeRoadData().value.cameraSpeedLimit)
        } finally {
            oldGate.countDown()
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `short location gap keeps road but data older than 5s is cleared`() {
        var clock = System.currentTimeMillis()
        val api = FakeApi { response(speedCamera(60)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = provider(api, scope) { clock }
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            waitUntil { provider.observeRoadData().value.cameraSpeedLimit == 60 }

            provider.updateLocation(unusableSample(clock))
            Thread.sleep(50)
            assertEquals(60, provider.observeRoadData().value.cameraSpeedLimit)

            clock += 6_000L
            provider.updateLocation(unusableSample(clock))
            waitUntil { provider.observeRoadData().value == RoadData() }
            assertEquals(RoadData(), provider.observeRoadData().value)
        } finally {
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `explicit reset clears data and gps recompute preserves separated limits`() {
        var clock = System.currentTimeMillis()
        val api = FakeApi { response(speedCamera(60), intervalCamera(80)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = provider(api, scope) { clock }
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            waitUntil { provider.observeRoadData().value.cameraSpeedLimit == 60 }

            val before = provider.observeRoadData().value
            assertEquals(null, before.speedLimit)
            assertEquals(60, before.cameraSpeedLimit)
            assertEquals(80, before.intervalSpeedLimit)

            clock += 1_000L
            provider.updateLocation(
                validSample(clock).copy(latitude = 31.0001, longitude = 121.0001),
            )
            waitUntil { provider.observeRoadData().value.distanceToCameraM != before.distanceToCameraM }
            val afterGps = provider.observeRoadData().value
            assertEquals(60, afterGps.cameraSpeedLimit)
            assertEquals(80, afterGps.intervalSpeedLimit)

            provider.updateLocation(GpsSample())
            assertEquals(RoadData(), provider.observeRoadData().value)
            assertEquals(ConnectionStatus.OFFLINE, provider.connectionStatus.value)
        } finally {
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `new session does not inherit a previous road snapshot`() {
        var clock = System.currentTimeMillis()
        val api = FakeApi { response(speedCamera(60)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = provider(api, scope) { clock }
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            waitUntil { provider.observeRoadData().value.cameraSpeedLimit == 60 }
            val oldRoad = provider.observeRoadData().value
            assertNotNull(oldRoad)

            provider.stopCruise()
            provider.startCruise()
            assertEquals(RoadData(), provider.observeRoadData().value)
        } finally {
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `disabled provider performs no request`() {
        val calls = AtomicInteger(0)
        val api = object : CruiseRoadApi {
            override fun detection(track: List<GpsSample>): BaiduResponse {
                calls.incrementAndGet()
                return response(speedCamera(60))
            }
            override fun refresh(track: List<GpsSample>): BaiduResponse = detection(track)
            override fun cancelCurrent() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = System.currentTimeMillis()
        val provider = BaiduCruiseProvider(
            api = api,
            scope = scope,
            nowMs = { clock },
            pollIntervalMs = 20L,
            enabled = false,
        )
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            Thread.sleep(120)
            assertEquals(0, calls.get())
            assertEquals(RoadData(), provider.observeRoadData().value)
        } finally {
            provider.stopCruise()
            scope.cancel()
        }
    }

    @Test
    fun `auth failure clears stale road data and pauses requests`() {
        val calls = AtomicInteger(0)
        val api = object : CruiseRoadApi {
            override fun detection(track: List<GpsSample>): BaiduResponse {
                calls.incrementAndGet()
                return BaiduResponse(status = 403, message = "服务未开通或已过期")
            }
            override fun refresh(track: List<GpsSample>): BaiduResponse = detection(track)
            override fun cancelCurrent() = Unit
        }
        var status = ServiceStatus()
        val sink = object : ServiceStatusSink {
            override fun update(transform: (ServiceStatus) -> ServiceStatus) {
                status = transform(status)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = System.currentTimeMillis()
        val provider = BaiduCruiseProvider(
            api = api,
            scope = scope,
            nowMs = { clock },
            pollIntervalMs = 20L,
            statusSink = sink,
            authPauseMs = 60_000L,
        )
        try {
            provider.connect()
            provider.startCruise()
            provider.updateLocation(validSample(clock))
            waitUntil { calls.get() == 1 }
            waitUntil { status.cruise == ServiceHealth.EXPIRED }
            assertEquals(RoadData(), provider.observeRoadData().value)

            Thread.sleep(150)
            assertEquals(1, calls.get())
        } finally {
            provider.stopCruise()
            scope.cancel()
        }
    }
}
