package com.zouhu.speedlimit.data.nav

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ownership token for route-plan requests (VA-N05).
 *
 * The Baidu plan API delivers SUCCESS / TO_NAVI / FAILED through a Handler that the app
 * supplies per request. Nothing in the SDK guarantees that a superseded request stops
 * calling back, so every callback must prove it still belongs to the newest request
 * before it writes routeReady/route/status.
 *
 * Deliberately just a monotonic counter: no queue, no state machine. Runtime semantics
 * of `removeRequestByHandler` still need device evidence; this only makes "late result
 * cannot win" testable.
 */
internal class PlanRequestOwner {
    private val seq = AtomicInteger(0)

    /** Starts a new request and invalidates every earlier token. */
    fun begin(): Int = seq.incrementAndGet()

    /** Invalidates the current request without starting a new one (stop/detach). */
    fun cancel() {
        seq.incrementAndGet()
    }

    fun isCurrent(token: Int): Boolean = token == seq.get()
}
