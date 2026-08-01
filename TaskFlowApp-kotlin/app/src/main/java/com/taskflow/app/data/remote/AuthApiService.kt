package com.taskflow.app.data.remote

import com.taskflow.app.data.model.AuthResponse
import com.taskflow.app.data.model.LoginRequest
import com.taskflow.app.data.model.RegisterRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the public authentication endpoints.
 * These do NOT require a Bearer token.
 *
 * Mirrors Go routes:
 *   POST /auth/register  → Register
 *   POST /auth/login     → Login
 */
interface AuthApiService {

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>
}
