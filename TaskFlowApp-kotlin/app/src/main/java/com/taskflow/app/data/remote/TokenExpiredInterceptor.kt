package com.taskflow.app.data.remote

import com.taskflow.app.data.local.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp response-phase Interceptor that detects HTTP 401 responses,
 * indicating the JWT has expired or is invalid.
 *
 * When a 401 is received:
 *  1. Clears the stored token via [TokenManager]
 *  2. Emits a session-expired event via [SessionManager]
 *  3. Returns the response unchanged (Retrofit still sees the error)
 *
 * This interceptor must be added AFTER [AuthInterceptor] in the OkHttp
 * client chain so it runs on responses after the request has been sent.
 */
class TokenExpiredInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            val path = chain.request().url.encodedPath

            // Don't trigger session expiry for login/register failures —
            // those are expected 401s (wrong password), not expired tokens.
            if (!path.endsWith("/auth/login") && !path.endsWith("/auth/register")) {
                tokenManager.clearToken()
                SessionManager.notifySessionExpired()
            }
        }

        return response
    }
}
