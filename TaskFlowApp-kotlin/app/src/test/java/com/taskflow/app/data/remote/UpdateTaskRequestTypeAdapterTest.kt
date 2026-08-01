package com.taskflow.app.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.taskflow.app.data.model.Patchable
import com.taskflow.app.data.model.UpdateTaskRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests (no Android dependencies) pinning the exact JSON produced for
 * a PATCH task body.
 *
 * Every case is asserted twice:
 *
 *  1. `gson.toJson(...)`     — the streaming path Retrofit uses in production.
 *  2. `gson.toJsonTree(...)` — the `JsonTreeWriter` path. This is the one the
 *     old `PatchableTypeAdapterFactory` got wrong: `JsonTreeWriter.nullValue()`
 *     ignores `serializeNulls`, so an `Absent` field came out as an explicit
 *     `null` and the Go backend cleared `assigned_to` / `due_date`.
 *
 * A third Gson instance with `serializeNulls()` enabled is used to prove the
 * adapter no longer depends on that builder flag either.
 */
class UpdateTaskRequestTypeAdapterTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(UpdateTaskRequest::class.java, UpdateTaskRequestTypeAdapter())
        .create()

    /** Same registration, but with the flag the old implementation silently relied on. */
    private val gsonSerializingNulls: Gson = GsonBuilder()
        .registerTypeAdapter(UpdateTaskRequest::class.java, UpdateTaskRequestTypeAdapter())
        .serializeNulls()
        .create()

    /**
     * Asserts the request serializes to exactly [expected] on the streaming path,
     * the tree path, and the streaming path of a serializeNulls-enabled Gson.
     */
    private fun assertSerializesTo(expected: String, request: UpdateTaskRequest) {
        assertEquals("toJson", expected, gson.toJson(request))
        assertEquals("toJsonTree", expected, gson.toJsonTree(request).toString())
        assertEquals(
            "toJson with serializeNulls enabled",
            expected,
            gsonSerializingNulls.toJson(request),
        )
        assertEquals(
            "toJsonTree with serializeNulls enabled",
            expected,
            gsonSerializingNulls.toJsonTree(request).toString(),
        )
    }

    // ─── 1. Everything absent ────────────────────────────────────────────

    /**
     * An all-absent patch must produce a literally empty object — no keys at all.
     *
     * GUARD: `{}` is a bug at the call site, not a valid request. The backend
     * rejects it with `400 {"error":"no fields to update"}`. The ViewModel must
     * only build an UpdateTaskRequest from fields the user actually changed and
     * must skip the network call entirely when nothing changed. The serializer
     * emits `{}` faithfully rather than papering over it, so the mistake shows
     * up as a 400 instead of silently clearing a field.
     */
    @Test
    fun allFieldsAbsent_serializesToEmptyObject() {
        assertSerializesTo("{}", UpdateTaskRequest())
    }

    // ─── 2. Only a plain field set ───────────────────────────────────────

    @Test
    fun statusOnly_writesNoPatchableKeys() {
        val request = UpdateTaskRequest(status = "done")

        assertSerializesTo("""{"status":"done"}""", request)

        // Spelled out, because this is the exact regression that motivated the rewrite.
        val json = gson.toJsonTree(request).asJsonObject
        assertFalse("assigned_to must not appear", json.has("assigned_to"))
        assertFalse("due_date must not appear", json.has("due_date"))
    }

    // ─── 3. Explicit null (clear the field) ──────────────────────────────

    @Test
    fun assignedToSetNull_writesExplicitNull() {
        val request = UpdateTaskRequest(assignedTo = Patchable.SetNull)

        assertSerializesTo("""{"assigned_to":null}""", request)

        val json = gson.toJsonTree(request).asJsonObject
        assertTrue("assigned_to key must be present", json.has("assigned_to"))
        assertTrue("assigned_to must be JSON null", json.get("assigned_to").isJsonNull)
        assertFalse("due_date must stay absent", json.has("due_date"))
    }

    // ─── 4. Value + SetNull together ─────────────────────────────────────

    @Test
    fun valueAndSetNull_writeBothKeysWithCorrectValues() {
        val request = UpdateTaskRequest(
            assignedTo = Patchable.Value("u1"),
            dueDate = Patchable.SetNull,
        )

        assertSerializesTo("""{"assigned_to":"u1","due_date":null}""", request)

        val json = gson.toJsonTree(request).asJsonObject
        assertEquals("u1", json.get("assigned_to").asString)
        assertTrue(json.get("due_date").isJsonNull)
    }

    // ─── 5. Tree path parity across a fuller request ─────────────────────

    /**
     * The tree path must agree with the streaming path key-for-key. This is the
     * case the old code failed: `toJsonTree` used to add `"due_date":null` for
     * an Absent due date, which would have cleared the task's deadline.
     */
    @Test
    fun toJsonTreeMatchesToJson_forAMixedRequest() {
        val request = UpdateTaskRequest(
            title = "Write the report",
            description = "",
            status = "in_progress",
            assignedTo = Patchable.Value("user-42"),
            dueDate = Patchable.Absent,
        )

        val expected =
            """{"title":"Write the report","description":"","status":"in_progress","assigned_to":"user-42"}"""

        assertSerializesTo(expected, request)

        val tree = gson.toJsonTree(request).asJsonObject
        assertFalse(
            "Absent due_date must not reach the tree writer as an explicit null",
            tree.has("due_date"),
        )
    }

    // ─── Extra: escaping still goes through Gson ─────────────────────────

    @Test
    fun stringValuesAreEscapedByGson() {
        val request = UpdateTaskRequest(title = "a \"quoted\" \n title")
        assertSerializesTo("""{"title":"a \"quoted\" \n title"}""", request)
    }
}
