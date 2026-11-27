package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in smoke test for the production provider, not just the raw client. */
class BaiduHorizonProviderLiveTest {
    @Test(timeout = 45_000)
    fun `provider emits a real road speed limit`() {
        val envAk = System.getenv("BAIDU_AK")
        assumeTrue("BAIDU_AK not set", !envAk.isNullOrBlank())
        val ak = envAk ?: return
        val provider = BaiduHorizonProvider(ak)
        provider.connect()
        provider.startCruise()
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
        try {
            runBlocking {
                launch(Dispatchers.IO) {
                    for (point in points) {
                        provider.updateLocation(
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
                        delay(1_000)
                    }
                }
                val road = withTimeout(25_000) {
                    provider.observeRoadData().first { it.isValid && (it.speedLimit ?: 0) > 0 }
                }
                assertTrue((road.speedLimit ?: 0) > 0)
            }
        } finally {
            provider.stopCruise()
        }
    }
}
