package com.zouhu.speedlimit.ui.nav

/**
 * One-shot ownership for "start navigation after the preview map is disposed".
 *
 * The 320 ms delay is not a transaction. A user can re-open search, pick another
 * destination, or start the demo in that window; only the token that is still active
 * when the delay expires may start a guide. This is deliberately smaller than a
 * navigation state machine.
 */
class NavStartGate {
    private var nextToken = 0
    var activeToken: Int = 0
        private set

    fun request(): Int {
        activeToken = ++nextToken
        return activeToken
    }

    fun cancel() {
        activeToken = 0
    }

    /**
     * Returns true exactly once for the active token; stale tokens are rejected.
     * [canStart] lets the caller re-check the UI window at consumption time; when false,
     * the token is invalidated so a later back/return cannot revive the old intent.
     */
    fun consume(token: Int, canStart: Boolean = true): Boolean {
        if (token == 0 || token != activeToken) return false
        if (!canStart) {
            activeToken = 0
            return false
        }
        activeToken = 0
        return true
    }
}