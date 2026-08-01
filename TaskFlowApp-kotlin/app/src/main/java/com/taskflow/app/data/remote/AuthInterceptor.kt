package com.taskflow.app.data.remote

import com.taskflow.app.data.local.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor that attaches the stored JWT to every outgoing request.
 *
 * Skips injection for public auth endpoints (/auth/login and /auth/register)
 * since those are called before the user has a token.
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        // Don't attach a token to public auth endpoints
        if (path.endsWith("/auth/login") || path.endsWith("/auth/register")) {
            return chain.proceed(originalRequest)
        }

        val token = tokenManager.getToken()
        if (token.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
