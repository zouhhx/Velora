package com.zouhu.speedlimit.data.nav

/** Minimal host lifecycle view for the guide-start decision; no Android dependency. */
enum class HostLifecycle {
    CREATED,
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    DESTROYED,
}

enum class GuideStartDecision {
    START,
    WAIT_FOR_LAYOUT,
    ABORT,
}

/**
 * VA-F10: do not advance the native guide lifecycle before the host is RESUMED.
 *
 * The native guide view can be attached and measured while the Activity is already
 * paused/stopped; calling onStart/onResume then leaves the SDK in a state the UI can
 * no longer explain. This object is deliberately pure so every branch is unit-tested.
 */
object GuideStartGate {
    fun decide(
        active: Boolean,
        viewMatches: Boolean,
        attached: Boolean,
        width: Int,
        height: Int,
        host: HostLifecycle,
    ): GuideStartDecision {
        if (!active || !viewMatches) return GuideStartDecision.ABORT
        if (host != HostLifecycle.RESUMED) return GuideStartDecision.ABORT
        if (!attached || width <= 0 || height <= 0) return GuideStartDecision.WAIT_FOR_LAYOUT
        return GuideStartDecision.START
    }
}
