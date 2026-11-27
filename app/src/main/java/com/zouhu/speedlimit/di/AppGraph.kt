package com.zouhu.speedlimit.di

import android.content.Context
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.location.LocationRepository
import com.zouhu.speedlimit.data.log.CameraTrace
import com.zouhu.speedlimit.data.map.baidu.BaiduCruiseApi
import com.zouhu.speedlimit.data.service.ServiceStatusStore
import com.zouhu.speedlimit.data.settings.SettingsRepository
import com.zouhu.speedlimit.provider.NoOpProvider
import com.zouhu.speedlimit.provider.SpeedLimitProvider
import com.zouhu.speedlimit.provider.baidu.BaiduCruiseProvider
import com.zouhu.speedlimit.provider.baidu.BaiduHorizonProvider
import com.zouhu.speedlimit.provider.baidu.MergedBaiduProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object AppGraph {
    val providerFlow = MutableStateFlow<SpeedLimitProvider>(NoOpProvider())
    val provider: SpeedLimitProvider get() = providerFlow.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationRepo: LocationRepository? = null
    private var settingsRepo: SettingsRepository? = null
    private var initialized = false
    private var uiOwners = 0
    private var serviceActive = false
    private val active: Boolean get() = uiOwners > 0 || serviceActive

    fun locationRepository(context: Context): LocationRepository =
        locationRepo ?: LocationRepository(context.applicationContext).also { locationRepo = it }

    fun settingsRepository(context: Context): SettingsRepository =
        settingsRepo ?: SettingsRepository(context.applicationContext).also { settingsRepo = it }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // Debug-only; no-op in release. Kept here so the trace file exists before the
        // first cruise request and survives provider switches.
        CameraTrace.init(context.applicationContext)
        scope.launch {
            // Migrate the old plaintext no-backup AK before the settings flow starts.
            settingsRepository(context).migrateLegacySecrets()
            // Direct-only by product decision: no gateway/remote provider path.
            settingsRepository(context).settings
                .map { it.directAk }
                .distinctUntilChanged()
                .collect { ak -> useDirect(ak) }
        }
    }

    private fun startProvider() {
        provider.connect()
        provider.startCruise()
        if (serviceActive) locationRepo?.latest?.value?.let { provider.updateLocation(it) }
    }

    private fun stopIfUnused() {
        if (!active) {
            provider.stopCruise()
            provider.disconnect()
        }
    }

    fun attachUi(context: Context) {
        uiOwners++
        initialize(context.applicationContext)
        startProvider()
    }

    fun detachUi() {
        uiOwners = (uiOwners - 1).coerceAtLeast(0)
        stopIfUnused()
    }

    fun attachService(context: Context) {
        serviceActive = true
        initialize(context.applicationContext)
        startProvider()
    }

    fun detachService() {
        serviceActive = false
        // Invalidate live road data when GPS stops, even if an activity still owns the provider.
        provider.updateLocation(com.zouhu.speedlimit.domain.model.GpsSample())
        stopIfUnused()
    }

    private fun switchProvider(next: SpeedLimitProvider) {
        val old = provider
        if (old === next) return
        old.stopCruise()
        old.disconnect()
        // `disconnect` stays reconnectable; `close` terminates the merged collectors so
        // an AK switch does not leak the previous provider pair (VA-P04).
        old.close()
        providerFlow.value = next
        // Reset after the old provider's stop callbacks, otherwise a stale
        // "IDLE" can overwrite the new "CONNECTING"/"UNCONFIGURED" state.
        ServiceStatusStore.reset(configured = next !is NoOpProvider)
        if (active) startProvider()
    }

    fun useDirect(ak: String) {
        if (ak.isBlank()) {
            android.util.Log.w("VeloraProvider", "cruise AK is empty; limits/lanes/lights disabled")
            switchProvider(NoOpProvider())
            return
        }
        android.util.Log.i("VeloraProvider", "direct cruise AK configured")
        // Debug builds only: release never initialises the trace, so passing null here
        // keeps the production data path byte-for-byte identical.
        val trace = CameraTrace.takeIf { BuildConfig.DEBUG }
        val http = BaiduCruiseProvider(BaiduCruiseApi(ak, trace = trace), trace = trace)
        val horizon = BaiduHorizonProvider(ak)
        switchProvider(MergedBaiduProvider(http, horizon))
    }

}
