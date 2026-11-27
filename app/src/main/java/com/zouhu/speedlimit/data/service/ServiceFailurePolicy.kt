package com.zouhu.speedlimit.data.service

import com.zouhu.speedlimit.data.map.baidu.HorizonAuthException

enum class ServiceFailureKind {
    NONE,
    TRANSIENT,
    AUTH,
    QUOTA,
}

/**
 * Classifies only bounded service states. We intentionally do not infer a permanent
 * permission loss from an ordinary network error: that would disable a working
 * service after one tunnel. Auth messages are recognised by Baidu status/message
 * patterns; unknown non-zero statuses stay transient and keep the existing backoff.
 */
object ServiceFailurePolicy {
    /** Stop hammering a service whose entitlement is gone. */
    const val AUTH_PAUSE_MS = 30 * 60_000L
    /** Match the existing HTTP 302 quota window. */
    const val QUOTA_PAUSE_MS = 15 * 60_000L
    const val MAX_TRANSIENT_BACKOFF_MS = 30_000L

    private val quotaWords = listOf(
        "302",
        "quota",
        "配额",
        "超限",
        "天配额",
    )
    private val authWords = listOf(
        "权限",
        "未开通",
        "未授权",
        "无权限",
        "过期",
        "到期",
        "回收",
        "失效",
        "鉴权失败",
        "auth failed",
        "not authorized",
        "unauthorized",
        "forbidden",
        "permission denied",
        "invalid ak",
        "invalid key",
        "expired",
    )

    fun classify(status: Int?, message: String?): ServiceFailureKind {
        if (status == 0) return ServiceFailureKind.NONE
        if (status == 302) return ServiceFailureKind.QUOTA
        if (status == 401 || status == 403) return ServiceFailureKind.AUTH
        val text = message.orEmpty().lowercase()
        if (authWords.any { text.contains(it) }) return ServiceFailureKind.AUTH
        if (quotaWords.any { text.contains(it) }) return ServiceFailureKind.QUOTA
        return if (status == null) ServiceFailureKind.NONE else ServiceFailureKind.TRANSIENT
    }

    fun classify(error: Throwable): ServiceFailureKind {
        if (error is HorizonAuthException) return ServiceFailureKind.AUTH
        return classify(null, error.message).let {
            if (it == ServiceFailureKind.NONE) ServiceFailureKind.TRANSIENT else it
        }
    }

    fun retryDelayMs(kind: ServiceFailureKind, attempt: Int): Long = when (kind) {
        ServiceFailureKind.AUTH -> AUTH_PAUSE_MS
        ServiceFailureKind.QUOTA -> QUOTA_PAUSE_MS
        ServiceFailureKind.TRANSIENT -> {
            val exponent = attempt.coerceIn(0, 4)
            (2_000L shl exponent).coerceAtMost(MAX_TRANSIENT_BACKOFF_MS)
        }
        ServiceFailureKind.NONE -> 0L
    }

    fun isServicePaused(kind: ServiceFailureKind): Boolean =
        kind == ServiceFailureKind.AUTH || kind == ServiceFailureKind.QUOTA
}
