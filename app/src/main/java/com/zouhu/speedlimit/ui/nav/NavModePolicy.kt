package com.zouhu.speedlimit.ui.nav

/**
 * VA-F02: real navigation and the demo simulator are mutually exclusive.
 *
 * This is intentionally a tiny state machine, not a navigation session model. The UI
 * still owns Compose state; this object answers the two contested transitions:
 * enabling demo while a real guide is active, and activating a real guide while demo
 * is running.
 */
class NavModePolicy {
    enum class Mode { CRUISE, DEMO, REAL }

    var mode: Mode = Mode.CRUISE
        private set

    /** Returns false when a real guide owns the session; demo must not start. */
    fun enableDemo(): Boolean {
        if (mode == Mode.REAL) return false
        mode = Mode.DEMO
        return true
    }

    fun disableDemo() {
        if (mode == Mode.DEMO) mode = Mode.CRUISE
    }

    /**
     * Called whenever [navActive] changes. Returns true when an active real guide
     * displaced a running demo, so the caller can clear its Compose demo flag.
     */
    fun setRealActive(active: Boolean): Boolean {
        if (active) {
            val displacedDemo = mode == Mode.DEMO
            mode = Mode.REAL
            return displacedDemo
        }
        if (mode == Mode.REAL) mode = Mode.CRUISE
        return false
    }

    fun exit() {
        mode = Mode.CRUISE
    }
}
