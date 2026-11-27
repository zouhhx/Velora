package com.zouhu.speedlimit.data.map.baidu

import android.content.Context
import com.baidu.mapapi.SDKInitializer
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.settings.SecretStore

object BaiduMapSdk {
    @Volatile
    private var ready = false

    val available: Boolean get() = ready

    fun initialize(context: Context) {
        if (ready) return
        // A settings override is applied before initialization; otherwise fall back
        // to local.properties. SDKInitializer.setApiKey(String) is present in the
        // bundled 9.0.2 SDK. The manifest value remains the final fallback.
        val key = SecretStore.get(context).current().mapAk.ifBlank { BuildConfig.BAIDU_MAP_AK }
        if (key.isBlank()) return
        runCatching {
            // The owner authorized this integration; the app shows the map only on explicit open.
            SDKInitializer.setAgreePrivacy(context.applicationContext, true)
            SDKInitializer.setApiKey(key)
            SDKInitializer.initialize(context.applicationContext)
            ready = true
        }
    }
}
