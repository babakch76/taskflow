package com.taskflow.app.data.remote

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.taskflow.app.data.model.Patchable
import com.taskflow.app.data.model.UpdateTaskRequest

/**
 * Hand-written Gson [TypeAdapter] for [UpdateTaskRequest].
 *
 * ## Why this is written by hand
 *
 * The PATCH contract with the Go backend has three states per field
 * (see [Patchable] and Go's `models.NullableField`):
 *
 *  | Kotlin                | JSON              | Backend meaning        |
 *  |-----------------------|-------------------|------------------------|
 *  | [Patchable.Absent]    | key omitted       | leave the field alone  |
 *  | [Patchable.SetNull]   | `"key": null`     | clear the field        |
 *  | [Patchable.Value]     | `"key": "..."`    | set the field          |
 *
 * The previous implementation was a `TypeAdapterFactory` that emitted
 * `out.nullValue()` for `Absent` and relied on [JsonWriter]'s *deferred name*
 * behaviour: when `serializeNulls` is false the streaming writer throws away the
 * pending key instead of writing `key: null`.
 *
 * That is an implementation detail of the streaming writer, and it does not hold
 * everywhere:
 *
 *  - `Gson.toJsonTree(...)` (and anything else built on `JsonTreeWriter`, which
 *    includes most unit tests) overrides `nullValue()` to append `JsonNull`
 *    unconditionally — it never consults `serializeNulls`. `Absent` therefore
 *    turned into an explicit `null`, which tells the backend to **clear**
 *    `assigned_to` / `due_date`. Silent data loss.
 *  - Enabling `serializeNulls` on the `GsonBuilder` — a one-line change someone
 *    could plausibly make — breaks it on the Retrofit path too.
 *
 * Writing the object by hand removes the dependency on writer internals: a key
 * that should be absent is simply never named.
 *
 * ## Write-only by design
 *
 * [read] throws. This adapter is only ever reached when Gson is asked to
 * serialize an [UpdateTaskRequest], and [UpdateTaskRequest] is a request DTO —
 * it is never returned by the backend. Responses to PATCH are `Task` objects,
 * which contain no [Patchable] fields and are handled by Gson's normal
 * reflective adapter. A `read()` that throws is therefore unreachable in
 * practice and makes the intent explicit rather than pretending to round-trip.
 */
class UpdateTaskRequestTypeAdapter : TypeAdapter<UpdateTaskRequest>() {

    override fun write(out: JsonWriter, value: UpdateTaskRequest?) {
        if (value == null) {
            out.nullValue()
            return
        }

        out.beginObject()

        // Plain nullable fields: null means "no change", so just skip the key.
        value.title?.let { out.name("title").value(it) }
        value.description?.let { out.name("description").value(it) }
        value.status?.let { out.name("status").value(it) }

        // Tri-state fields. Names must match the @SerializedName values on
        // UpdateTaskRequest and the json tags on Go's models.UpdateTaskRequest.
        writePatchable(out, "assigned_to", value.assignedTo)
        writePatchable(out, "due_date", value.dueDate)

        out.endObject()
    }

    /**
     * Writes one tri-state field.
     *
     * NOTE: an [UpdateTaskRequest] with every field absent serializes to `{}`.
     * The backend answers that with `400 "no fields to update"`, so the
     * ViewModel must never issue such a patch — build the request from the
     * fields the user actually edited and skip the call when nothing changed.
     * The serializer deliberately does not "helpfully" invent keys to avoid it;
     * emitting `{}` faithfully is what makes that bug visible instead of
     * turning it into an accidental field clear.
     */
    private fun writePatchable(out: JsonWriter, name: String, field: Patchable<String>) {
        when (field) {
            // Absent: never name the key. This is the whole point of the rewrite.
            is Patchable.Absent -> Unit

            is Patchable.SetNull -> {
                out.name(name)
                // JsonWriter drops a deferred name on nullValue() when
                // serializeNulls is false, so force it on for this one write.
                // JsonTreeWriter ignores the flag entirely and writes JsonNull
                // either way — harmless, and the finally still restores state.
                val wasSerializingNulls = out.serializeNulls
                out.serializeNulls = true
                try {
                    out.nullValue()
                } finally {
                    out.serializeNulls = wasSerializingNulls
                }
            }

            is Patchable.Value -> {
                out.name(name)
                val inner: String = field.value
                out.value(inner)
            }
        }
    }

    override fun read(reader: JsonReader): UpdateTaskRequest {
        throw UnsupportedOperationException(
            "UpdateTaskRequest is a write-only request DTO; the backend never returns one."
        )
    }
}
