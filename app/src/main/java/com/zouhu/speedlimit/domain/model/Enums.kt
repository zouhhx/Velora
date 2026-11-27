package com.zouhu.speedlimit.domain.model

enum class OverSpeedState { NORMAL, OVER, SEVERE, UNKNOWN }

enum class IntervalPhase { NONE, APPROACH, INSIDE }

enum class ConnectionStatus { OFFLINE, CONNECTING, ONLINE, SIMULATION }

enum class CameraType {
    SPEED, MOBILE, INTERVAL, OTHER;

    val isSpeedEnforcement: Boolean get() = this != OTHER

    companion object {
        // 官方电子眼类型码：测速类才触发"测速必报"。
        fun fromBaiduCode(code: Int): CameraType = when (code) {
            1, 40, 41, 53, 58 -> SPEED   // 限速/可变限速/隐蔽限速/测慢速/车道级测速
            26 -> MOBILE                  // 移动测速
            5, 9, 54, 55 -> INTERVAL      // 区间限速/区间测慢速 起终点
            else -> OTHER                 // 闯红灯/违章/压线/公交/非机动车道等非测速
        }
    }
}

enum class LaneDirection {
    EMPTY, STRAIGHT, LEFT, RIGHT, UTURN, SLIGHT_LEFT, SLIGHT_RIGHT, BUS, VARIABLE, HOV, TIDAL;

    val isSpecial: Boolean
        get() = this == BUS || this == VARIABLE || this == HOV || this == TIDAL

    companion object {
        // 仅映射官方已确认的码值；右转/掉头/稍左稍右等待 Phase 4 真实响应核实。
        fun fromBaiduCode(code: Int): LaneDirection = when (code) {
            0 -> EMPTY
            1 -> STRAIGHT
            4 -> LEFT
            5 -> BUS
            6 -> VARIABLE
            7 -> HOV
            8 -> TIDAL
            else -> EMPTY
        }
    }
}