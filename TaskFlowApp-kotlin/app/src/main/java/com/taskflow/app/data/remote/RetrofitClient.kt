package com.taskflow.app.data.remote

import com.google.gson.GsonBuilder
import com.taskflow.app.BuildConfig
import com.taskflow.app.data.local.TokenManager
import com.taskflow.app.data.model.UpdateTaskRequest
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Singleton that wires together the complete networking stack:
 *
 *  Gson (with OffsetDateTimeAdapter + UpdateTaskRequestTypeAdapter)
 *    └─► GsonConverterFactory
 *          └─► Retrofit (base URL: BuildConfig.BASE_URL)
 *                └─► OkHttpClient
 *                      ├── AuthInterceptor       (injects Bearer token)
 *                      ├── TokenExpiredInterceptor (detects 401 → session expiry)
 *                      └── HttpLoggingInterceptor  (debug builds only)
 *
 * Usage:
 *   val client = RetrofitClient.init(tokenManager)
 *   val authApi = client.authApi
 *   val groupApi = client.groupApi
 */
class RetrofitClient private constructor(tokenManager: TokenManager) {

    /**
     * Gson instance configured with:
     *  - OffsetDateTimeAdapter for Go's time.Time (RFC 3339)
     *  - UpdateTaskRequestTypeAdapter for PATCH explicit-null semantics
     *
     * Do NOT enable serializeNulls here: it would make every omitted field in
     * every request DTO serialize as an explicit null. The PATCH adapter is
     * independent of the flag, but the rest of the DTOs are not.
     */
    private val gson = GsonBuilder()
        .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
        .registerTypeAdapter(UpdateTaskRequest::class.java, UpdateTaskRequestTypeAdapter())
        .create()

    /**
     * OkHttp client with the interceptor chain:
     *  1. AuthInterceptor — adds Authorization header (request phase)
     *  2. TokenExpiredInterceptor — detects 401 and emits session expiry (response phase)
     *  3. HttpLoggingInterceptor — request/response logging, debug builds only
     */
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenManager))
        .addInterceptor(TokenExpiredInterceptor(tokenManager))
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                // BODY dumps full request/response payloads, which include JWTs
                // in auth responses. Release builds must never emit that to
                // logcat, where any app with READ_LOGS or an attached adb can
                // read it.
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                // Replaces the header value with "██" in every log line, so the
                // Bearer token never appears even during debugging.
                redactHeader("Authorization")
                // NOTE: BODY logging in debug builds still prints the login and
                // register request bodies, which contain the plaintext password.
                // That is accepted for local development only — drop the level
                // to BASIC if you need to share a debug logcat.
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    /** Public auth endpoints (register, login) — no token required. */
    val authApi: AuthApiService = retrofit.create(AuthApiService::class.java)

    /** All authenticated endpoints (groups, invites, tasks). */
    val groupApi: GroupApiService = retrofit.create(GroupApiService::class.java)

    companion object {
        /**
         * Backend base URL, injected at build time from `local.properties`
         * (key `taskflow.baseUrl`), defaulting to the Android emulator's host
         * alias `http://10.0.2.2:8080/`.
         *
         * See app/build.gradle.kts and README.md for the physical-device setup.
         */
        private val BASE_URL: String = BuildConfig.BASE_URL

        @Volatile
        private var INSTANCE: RetrofitClient? = null

        /**
         * Initialize the singleton. Call once from Application.onCreate()
         * or MainActivity before making any API calls.
         */
        fun init(tokenManager: TokenManager): RetrofitClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RetrofitClient(tokenManager).also { INSTANCE = it }
            }
        }

        /**
         * Get the existing instance. Throws if [init] hasn't been called.
         */
        fun getInstance(): RetrofitClient {
            return INSTANCE
                ?: throw IllegalStateException("RetrofitClient not initialized. Call init() first.")
        }
    }
}
