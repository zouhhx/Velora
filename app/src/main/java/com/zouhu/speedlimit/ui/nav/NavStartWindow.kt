package com.zouhu.speedlimit.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * The 320 ms hand-off between closing the search preview and creating the guide view.
 *
 * Extracted from `CruiseScreen` so the timing rule can be exercised by a Compose UI test
 * with a controlled clock. The delay itself is not a transaction: while it runs the user
 * can re-open search, pick another destination, or leave the map. Only the token that
 * is still active when the delay expires may start a guide.
 *
 * [consume] must return true exactly once for the active token; [onCancel] is called
 * when the window is invalidated by `live == false` or `showSearch == true`.
 */
@Composable
fun NavStartWindowEffect(
    pendingStart: Int,
    live: Boolean,
    showSearch: Boolean,
    consume: (Int) -> Boolean,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    delayMs: Long = 320L,
) {
    LaunchedEffect(pendingStart, showSearch, live, delayMs) {
        if (pendingStart == 0) return@LaunchedEffect
        if (!live || showSearch) {
            onCancel()
            return@LaunchedEffect
        }
        val token = pendingStart
        delay(delayMs)
        // Re-check at the boundary: the user may have re-opened search (or left the
        // screen) during the 320 ms. Invalidating here prevents an old pendingStart
        // from being revived by a later back/return.
        if (showSearch || !live) {
            onCancel()
            return@LaunchedEffect
        }
        if (!consume(token)) return@LaunchedEffect
        onStart()
    }
}
