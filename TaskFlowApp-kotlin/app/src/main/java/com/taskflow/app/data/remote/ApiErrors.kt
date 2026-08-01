package com.taskflow.app.data.remote

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.taskflow.app.data.model.ErrorResponse
import retrofit2.Response
import java.io.IOException

/**
 * Turns a failed Retrofit [Response] into text a person can read.
 *
 * Every error path in the Go backend answers with `{"error":"..."}` — both the
 * handlers (via `jsonError`) and the auth/membership middleware (via
 * `http.Error` with a JSON literal). Showing that `error` string beats dumping
 * the raw body into the UI, which is what the screens used to do.
 */
object ApiErrors {

    private val gson = Gson()

    /**
     * Reads the error body **once** and returns the backend's message, or a
     * status-code fallback when the body is missing or isn't the expected shape
     * (proxy HTML, an empty 502, a truncated response…).
     *
     * Only call this on a response where `isSuccessful` is false — `errorBody()`
     * is a one-shot stream.
     */
    fun messageFor(response: Response<*>): String {
        val raw = try {
            response.errorBody()?.string()
        } catch (e: IOException) {
            null
        }
        return parse(raw) ?: "Request failed (HTTP ${response.code()})"
    }

    /** Extracts `error` from an `{"error":"..."}` body, or null if it isn't one. */
    fun parse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            gson.fromJson(raw, ErrorResponse::class.java)
                ?.error
                ?.takeIf { it.isNotBlank() }
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
