package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in smoke test against the real Baidu WS; run only when BAIDU_AK is present. */
class BaiduHorizonLiveTest {
    @Test(timeout = 45_000)
    fun `real horizon connection authenticates and returns a road limit`() {
        val envAk = System.getenv("BAIDU_AK")
        assumeTrue("BAIDU_AK not set", !envAk.isNullOrBlank())
        val ak = envAk ?: return
        val auth = CountDownLatch(1)
        val data = CountDownLatch(1)
        val position = AtomicReference<HorizonEvent.Position?>()
        val links = AtomicReference<HorizonEvent.Links?>()
        val failure = AtomicReference<Throwable?>()

        val client = BaiduHorizonClient(
            ak = ak,
            entityId = "velora-live-${System.currentTimeMillis()}",
            listener = object : BaiduHorizonClient.Listener {
                override fun onAuthenticated() = auth.countDown()
                override fun onEvent(event: HorizonEvent) {
                    when (event) {
                        is HorizonEvent.Position -> { position.set(event); checkReady() }
                        is HorizonEvent.Links -> { links.set(event); checkReady() }
                        else -> Unit
                    }
                }
                override fun onClosed(code: Int, reason: String) = Unit
                override fun onFailure(error: Throwable) { failure.set(error) }
                fun checkReady() {
                    if (position.get() != null && links.get()?.links?.any { (it.speedLimit ?: 0) > 0 } == true) {
                        data.countDown()
                    }
                }
            },
        )
        client.open()
        try {
            assertTrue("auth timed out: ${failure.get()?.message.orEmpty()}", auth.await(12, TimeUnit.SECONDS))
            val points = listOf(
                doubleArrayOf(121.625309, 31.263116, 13.9, 89.3),
                doubleArrayOf(121.625556, 31.26312, 18.3, 89.3),
                doubleArrayOf(121.625772, 31.263122, 15.8, 89.3),
                doubleArrayOf(121.625989, 31.263123, 20.0, 89.7),
                doubleArrayOf(121.626208, 31.263123, 19.2, 90.1),
                doubleArrayOf(121.626428, 31.263123, 20.6, 90.1),
                doubleArrayOf(121.626647, 31.263123, 20.3, 90.1),
                doubleArrayOf(121.626866, 31.263122, 20.6, 90.1),
            )
            for (point in points) {
                client.sendLoc(
                    GpsSample(
                        latitude = point[1],
                        longitude = point[0],
                        speedMps = point[2],
                        bearingDeg = point[3],
                        accuracyM = 5f,
                        timestampMs = System.currentTimeMillis(),
                        hasFix = true,
                    ),
                )
                Thread.sleep(1_000)
            }
            assertTrue("horizon data timed out: ${failure.get()?.message.orEmpty()}", data.await(12, TimeUnit.SECONDS))
            assertNotNull(position.get())
            assertTrue(links.get()!!.links.any { (it.speedLimit ?: 0) > 0 })
        } catch (t: Throwable) {
            fail("live horizon test failed: ${t.message}")
        } finally {
            client.close()
        }
    }
}
