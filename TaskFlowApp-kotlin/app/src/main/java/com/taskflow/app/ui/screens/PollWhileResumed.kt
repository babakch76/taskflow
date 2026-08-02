package com.taskflow.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Calls [onTick] as soon as the screen is resumed, then every [intervalMillis]
 * for as long as it stays resumed.
 *
 * Two problems this solves at once:
 *
 *  - **Stale on return.** Coming back to the app showed whatever had been
 *    loaded before it was backgrounded, until the user hit refresh. Ticking on
 *    resume means what you see is current.
 *  - **Polling a screen nobody is looking at.** A plain
 *    `LaunchedEffect { while (true) { delay(…) } }` is scoped to composition,
 *    not lifecycle, so it keeps hitting the network while the app sits in the
 *    background — wasted battery and data for updates nobody can see.
 *    [repeatOnLifecycle] cancels the loop on pause and restarts it on resume.
 *
 * Note this is still per-screen polling: two screens, two endpoints, two
 * timers. Fine at this size, but the wrong shape long-term — the backend
 * already records the events a single push channel would carry. Worth revisiting
 * before a third poller appears.
 *
 * @param intervalMillis Gap between ticks. Pick it from how fast the data
 *   really changes: a group's activity feed is chatty, invites are rare.
 * @param key Restarts the loop when it changes (e.g. the group being viewed).
 */
@Composable
fun PollWhileResumed(
    intervalMillis: Long,
    key: Any? = Unit,
    onTick: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // The loop below outlives individual recompositions, so capture the latest
    // lambda rather than whichever one existed when it started.
    val currentOnTick by rememberUpdatedState(onTick)

    LaunchedEffect(lifecycleOwner, key, intervalMillis) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                currentOnTick()
                delay(intervalMillis)
            }
        }
    }
}
