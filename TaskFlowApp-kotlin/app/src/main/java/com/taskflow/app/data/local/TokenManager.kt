package com.taskflow.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages JWT token storage using EncryptedSharedPreferences.
 * The token is encrypted at rest with AES256-GCM via the Android Keystore.
 */
class TokenManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Stores the JWT and the signed-in user's id together.
     *
     * The id matters because several screens need to know which row in a list
     * is *you* — your own role in a group, which member card to mark, who not
     * to offer a demote button for. The id is inside the JWT's claims, but
     * decoding a token client-side to read them is more machinery (and more
     * ways to be wrong) than simply keeping the value the server already
     * handed us in the auth response.
     */
    fun saveSession(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_JWT_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_JWT_TOKEN, null)
    }

    /** Id of the signed-in user, or null if nobody is signed in. */
    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    /** Clears the whole session — token and identity together. */
    fun clearToken() {
        prefs.edit()
            .remove(KEY_JWT_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    /** Returns true if a JWT is currently stored. */
    fun isLoggedIn(): Boolean = getToken() != null

    companion object {
        private const val PREFS_FILE_NAME = "taskflow_secure_prefs"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
    }
}
