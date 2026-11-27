package com.zouhu.speedlimit.data.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Read-only signing certificate fingerprint for the current installed package. */
object AppSigningInfo {
    fun sha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val packageName = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching "—"
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        digest.joinToString(":") { "%02X".format(it) }
    }.getOrDefault("—")
}
