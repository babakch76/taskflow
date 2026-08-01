package com.taskflow.app.data.model

/**
 * Represents the three possible states of a field in a PATCH request,
 * matching the Go backend's NullableField unmarshaler semantics:
 *
 *  - [Absent]  → field is **omitted** from the JSON body entirely.
 *                The Go backend treats this as "do not change this field".
 *
 *  - [SetNull] → field is explicitly serialized as `null`.
 *                The Go backend treats this as "clear / unset this field".
 *
 *  - [Value]   → field is serialized with the given value.
 *                The Go backend treats this as "update the field to this value".
 *
 * This is critical for endpoints like PATCH /groups/{id}/tasks/{id} where
 * the user must be able to unassign a task (send `null`) without Gson
 * simply omitting the field.
 *
 * Serialization is handled by [com.taskflow.app.data.remote.UpdateTaskRequestTypeAdapter],
 * which writes the enclosing request object by hand so that [Absent] can omit
 * the key outright instead of relying on JsonWriter's null-dropping behaviour.
 */
sealed class Patchable<out T> {

    /** Field not included in the request — Go backend ignores it. */
    object Absent : Patchable<Nothing>() {
        override fun toString() = "Patchable.Absent"
    }

    /** Field explicitly set to null — Go backend clears the value. */
    object SetNull : Patchable<Nothing>() {
        override fun toString() = "Patchable.SetNull"
    }

    /** Field set to a concrete value — Go backend updates it. */
    data class Value<T>(val value: T) : Patchable<T>()
}
