package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BaiduCruiseApiTest {
    @Test
    fun `cancelCurrent aborts the in-flight synchronous call`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBodyDelay(3, TimeUnit.SECONDS)
                .setBody("""{"status":-1}"""),
        )
        server.start()
        val api = BaiduCruiseApi("test-ak", server.url("/").toString().trimEnd('/'))
        val executor = Executors.newSingleThreadExecutor()
        val now = System.currentTimeMillis()
        val sample = GpsSample(
            latitude = 31.0,
            longitude = 121.0,
            speedMps = 10.0,
            bearingDeg = 90.0,
            accuracyM = 5f,
            timestampMs = now,
            hasFix = true,
            hasAccuracy = true,
            hasSpeed = true,
            hasBearing = true,
        )

        try {
            val future = executor.submit<BaiduResponse> { api.detection(listOf(sample)) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            api.cancelCurrent()
            val failure = runCatching { future.get(2, TimeUnit.SECONDS) }.exceptionOrNull()

            assertTrue("cancelled call should fail", failure is ExecutionException)
            assertTrue("cancellation must not wait for the body delay", failure?.cause !is TimeoutException)
        } finally {
            executor.shutdownNow()
            server.shutdown()
        }
    }
}
