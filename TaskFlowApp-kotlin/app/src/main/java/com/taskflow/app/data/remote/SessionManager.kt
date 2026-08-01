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
     * Called by [TokenExpiredInterceptor] when a 401 response is received.
     * Clears any buffered events and emits a single session-expired signal.
     */
    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}
