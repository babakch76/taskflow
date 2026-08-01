package com.taskflow.app

import android.app.Application
import com.taskflow.app.data.local.TokenManager
import com.taskflow.app.data.remote.RetrofitClient

/**
 * Process-wide holder for the app's two long-lived singletons.
 *
 * [TokenManager] wraps EncryptedSharedPreferences, which unlocks an Android
 * Keystore master key and runs an AES-GCM handshake on construction — tens of
 * milliseconds, sometimes more on cold start. It used to be built in three
 * places (MainActivity, AppNavGraph, and inside LoginScreen's composition, where
 * a `remember` still re-ran across configuration changes and process death).
 * Building it once here removes that cost from the UI thread's critical path and
 * guarantees a single view of the stored JWT.
 *
 * Deliberately no DI framework: one Application-scoped instance, passed down
 * explicitly to the screens that need it.
 */
class TaskFlowApp : Application() {

    /** The single TokenManager for the process. */
    lateinit var tokenManager: TokenManager
        private set

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        RetrofitClient.init(tokenManager)
    }
}
