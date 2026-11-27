package com.zouhu.speedlimit.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceHealth {
    UNCONFIGURED,
    IDLE,
    CONNECTING,
    ACTIVE,
    UNAVAILABLE,
    EXPIRED,
    QUOTA,
}

data class ServiceStatus(
    val horizon: ServiceHealth = ServiceHealth.UNCONFIGURED,
    val lamp: ServiceHealth = ServiceHealth.UNCONFIGURED,
    val cruise: ServiceHealth = ServiceHealth.UNCONFIGURED,
)

/**
 * Small process-wide status board for the optional Baidu services. It is read by
 * Settings; providers write only a bounded enum, never a raw AK/SK or server body.
 */
interface ServiceStatusSink {
    fun update(transform: (ServiceStatus) -> ServiceStatus)
}

object ServiceStatusStore : ServiceStatusSink {
    private val _state = MutableStateFlow(ServiceStatus())
    val state: StateFlow<ServiceStatus> = _state.asStateFlow()

    override fun update(transform: (ServiceStatus) -> ServiceStatus) {
        _state.update(transform)
    }

    fun reset(configured: Boolean) {
        _state.value = if (configured) {
            ServiceStatus(
                horizon = ServiceHealth.CONNECTING,
                lamp = ServiceHealth.CONNECTING,
                cruise = ServiceHealth.CONNECTING,
            )
        } else {
            ServiceStatus()
        }
    }

    fun idle() {
        _state.value = ServiceStatus(
            horizon = ServiceHealth.IDLE,
            lamp = ServiceHealth.IDLE,
            cruise = ServiceHealth.IDLE,
        )
    }

    fun setHorizon(health: ServiceHealth) = update { it.copy(horizon = health) }
    fun setLamp(health: ServiceHealth) = update { it.copy(lamp = health) }
    fun setCruise(health: ServiceHealth) = update { it.copy(cruise = health) }
}

fun ServiceHealth.label(): String = when (this) {
    ServiceHealth.UNCONFIGURED -> "未配置"
    ServiceHealth.IDLE -> "未连接"
    ServiceHealth.CONNECTING -> "连接中"
    ServiceHealth.ACTIVE -> "正常"
    ServiceHealth.UNAVAILABLE -> "暂不可用"
    ServiceHealth.EXPIRED -> "未开通或已过期"
    ServiceHealth.QUOTA -> "配额已用尽"
}
