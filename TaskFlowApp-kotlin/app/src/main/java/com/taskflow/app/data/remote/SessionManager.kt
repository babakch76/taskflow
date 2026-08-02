package com.taskflow.app.data.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central session state holder.
 *
 * When any OkHttp response returns HTTP 401, [TokenExpiredInterceptor] calls
 * [notifySessionExpired], which emits on the [sessionExpired] flow.
 * MainActivity collects this flow and redirects the user to the login screen.
 *
 * Using SharedFlow with DROP_OLDEST ensures that:
 *  - Multiple rapid 401s don't queue up redundant navigation events
 *  - No suspend calls are needed (tryEmit is non-blocking)
 */
object SessionManager {

    private val _sessionExpired = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect this in your Activity/NavHost to react to session expiry. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    /**
     * Set when a 401 forced a sign-out, cleared once the Login screen has said
     * so. Separate from [sessionExpired] because the flow is a navigation
     * trigger with no replay — by the time Login is composed the event is gone,
     * and the user would be staring at a sign-in form with no idea why they
     * were thrown out of what they were doing.
     */
    @Volatile
    private var expiryNoticePending = false

    /**
     * Called by [TokenExpiredInterceptor] when a 401 response is received.
     * Clears any buffered events and emits a single session-expired signal.
     */
    fun notifySessionExpired() {
        expiryNoticePending = true
        _sessionExpired.tryEmit(Unit)
    }

    /**
     * Returns true once if the last trip to Login was caused by an expired
     * session, then resets. Reading it consumes it, so the message doesn't
     * reappear on a later manual sign-out.
     */
    fun consumeExpiryNotice(): Boolean {
        val pending = expiryNoticePending
        expiryNoticePending = false
        return pending
    }
}
