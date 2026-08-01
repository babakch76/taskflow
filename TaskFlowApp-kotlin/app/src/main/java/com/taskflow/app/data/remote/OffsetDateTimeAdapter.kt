package com.taskflow.app.data.remote

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Gson TypeAdapter for java.time.OffsetDateTime.
 *
 * Go's time.Time serializes to RFC 3339 / ISO-8601 strings:
 *   "2026-05-27T16:43:00Z"
 *   "2026-05-27T18:43:00+02:00"
 *
 * Without this adapter, Gson has no idea how to parse these strings
 * and will throw JsonSyntaxException on every API response.
 *
 * Registered on the GsonBuilder in RetrofitClient.
 */
class OffsetDateTimeAdapter : TypeAdapter<OffsetDateTime>() {

    override fun write(out: JsonWriter, value: OffsetDateTime?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
    }

    override fun read(reader: JsonReader): OffsetDateTime? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val raw = reader.nextString()
        return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
