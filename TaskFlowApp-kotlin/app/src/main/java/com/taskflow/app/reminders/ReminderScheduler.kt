package com.taskflow.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.taskflow.app.data.model.Chore
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.isOpen
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Turns the times [ReminderSchedule] works out into actual alarms.
 *
 * Reminders are scheduled **on the device**, from data the app has already
 * fetched. There is no push service and no server-side scheduler, which suits
 * the shape of the rules: every reminder is to the assignee, about their own
 * chore, and a device can decide all of that alone. It also means nothing about
 * anyone's chores leaves the phone.
 *
 * The cost of that choice, stated plainly: a turn that starts while the app is
 * closed is not noticed until the app next syncs. The board is always right the
 * moment you open it; the *notification* about a turn starting can be late.
 *
 * Two invariants this class exists to keep:
 *
 *  - **Exactly two reminders per occurrence.** Rescheduling happens on every
 *    refresh, so without a record of what has already fired, every poll would
 *    re-notify. [ReminderStore] is that record.
 *  - **Only ever the assignee's own chores.** Occurrences belonging to anyone
 *    else are dropped before a time is even computed.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    const val CHANNEL_ID = "chore_reminders"

    /**
     * How far out a reminder whose moment has just passed is armed. Far enough
     * that the alarm is genuinely in the future when it is set, short enough
     * that it still reads as "now" to the person receiving it.
     */
    private const val FIRE_SOON_MILLIS = 2_000L

    /** Creates the notification channel. Safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chore reminders",
            // DEFAULT, not HIGH: these are reminders, not alerts. Nothing here
            // is urgent enough to interrupt, and the spec's whole posture is
            // that the schedule speaks quietly.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Your turn starting, and chores coming up."
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Recomputes and re-arms this user's reminders from the board.
     *
     * Called after every board load, so it has to be idempotent: anything
     * already delivered is skipped, and anything still pending is simply
     * re-armed at the same time.
     */
    fun reschedule(
        context: Context,
        occurrences: List<Occurrence>,
        chores: List<Chore>,
        myUserId: String?,
        quiet: QuietHours,
        now: OffsetDateTime = OffsetDateTime.now(),
    ) {
        if (myUserId == null) return
        ensureChannel(context)

        val store = ReminderStore(context)
        val choresById = chores.associateBy { it.id }
        val pending = mutableListOf<PendingReminder>()

        // Only my own open occurrences. A reminder about someone else's chore
        // is exactly what constraint 2 forbids, so it is filtered here rather
        // than relied upon further down.
        val mine = occurrences.filter { it.isOpen && it.assignedTo == myUserId }

        for (occurrence in mine) {
            val chore = choresById[occurrence.choreId]
            // A one-off is somebody's single errand; it still gets its two
            // reminders. Only the as-needed case loses the due one, and that
            // falls out of dueDate being null.
            val neededBy = chore?.neededByTime

            val scheduled = ReminderSchedule.scheduledFor(
                occurrenceId = occurrence.id,
                turnStartedAt = occurrence.createdAt ?: now,
                dueDate = occurrence.dueDate,
                neededByTime = neededBy,
                quiet = quiet,
            )

            for (reminder in scheduled) {
                if (store.hasFired(reminder.occurrenceId, reminder.kind)) continue

                val adjusted = ReminderSchedule.catchUp(reminder, now, quiet)
                if (adjusted == null) {
                    // Too late to be worth sending. Recorded as spent so it
                    // never fires — otherwise it would be reconsidered, and
                    // rejected again, on every single refresh.
                    store.markFired(reminder.occurrenceId, reminder.kind)
                    continue
                }

                pending += PendingReminder(
                    occurrenceId = adjusted.occurrenceId,
                    kind = adjusted.kind,
                    choreName = occurrence.choreName,
                    atEpochMillis = adjusted.at.toInstant().toEpochMilli(),
                )
            }

            ReminderSchedule.stillWaitingFor(
                occurrenceId = occurrence.id,
                dueDate = occurrence.dueDate,
                lastNudgeAt = store.lastNudge(occurrence.id),
                quiet = quiet,
                now = now,
            )?.let { nudge ->
                pending += PendingReminder(
                    occurrenceId = nudge.occurrenceId,
                    kind = nudge.kind,
                    choreName = occurrence.choreName,
                    atEpochMillis = nudge.at.toInstant().toEpochMilli(),
                )
            }
        }

        // Anything for an occurrence that is no longer mine and open — done,
        // passed on, or deleted — must stop. Cancelling before arming keeps
        // this a full replacement rather than an accumulation.
        cancelAll(context, store.pending())
        store.savePending(pending)
        arm(context, pending)

        Log.d(TAG, "armed ${pending.size} reminder(s) for ${mine.size} open occurrence(s)")
    }

    /** Re-arms whatever was already stored. Used after a reboot. */
    fun rearmStored(context: Context) {
        ensureChannel(context)
        val store = ReminderStore(context)
        arm(context, store.pending())
    }

    private fun arm(context: Context, reminders: List<PendingReminder>) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        val now = System.currentTimeMillis()

        for (reminder in reminders) {
            // Clamped, not skipped. A catch-up reminder is computed as "the
            // next allowed moment", which is often *now* — and by the time this
            // loop runs, now has already gone by. Skipping past times would
            // therefore drop exactly the reminders the catch-up rule exists to
            // rescue, and it would do it silently.
            //
            // Nothing genuinely stale reaches here: ReminderSchedule.catchUp
            // has already discarded anything older than its window, and the
            // still-waiting nudge is never computed in the past.
            val fireAt = maxOf(reminder.atEpochMillis, now + FIRE_SOON_MILLIS)
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAt,
                pendingIntentFor(context, reminder, PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
    }

    private fun cancelAll(context: Context, reminders: List<PendingReminder>) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        for (reminder in reminders) {
            alarms.cancel(pendingIntentFor(context, reminder, PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun pendingIntentFor(
        context: Context,
        reminder: PendingReminder,
        flags: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            // The request code alone decides which alarm this replaces, so it
            // must be stable per (occurrence, kind) and differ between them.
            action = "${reminder.occurrenceId}:${reminder.kind}"
            putExtra(ReminderReceiver.EXTRA_OCCURRENCE_ID, reminder.occurrenceId)
            putExtra(ReminderReceiver.EXTRA_KIND, reminder.kind.name)
            putExtra(ReminderReceiver.EXTRA_CHORE_NAME, reminder.choreName)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.requestCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** One armed alarm, in the form that survives a reboot. */
data class PendingReminder(
    val occurrenceId: String,
    val kind: ReminderSchedule.Kind,
    val choreName: String,
    val atEpochMillis: Long,
) {
    /** Stable per occurrence and kind, so re-arming replaces rather than duplicates. */
    fun requestCode(): Int = "$occurrenceId:$kind".hashCode()

    fun toJson(): JSONObject = JSONObject().apply {
        put("occurrence_id", occurrenceId)
        put("kind", kind.name)
        put("chore_name", choreName)
        put("at", atEpochMillis)
    }

    companion object {
        fun fromJson(o: JSONObject): PendingReminder? = runCatching {
            PendingReminder(
                occurrenceId = o.getString("occurrence_id"),
                kind = ReminderSchedule.Kind.valueOf(o.getString("kind")),
                choreName = o.optString("chore_name"),
                atEpochMillis = o.getLong("at"),
            )
        }.getOrNull()
    }
}

/**
 * What has already been delivered, and what is currently armed.
 *
 * Plain SharedPreferences rather than the encrypted store: none of this is
 * secret, and it must be readable from a boot receiver without unlocking a
 * Keystore key.
 */
class ReminderStore(context: Context) {

    private val prefs = context.getSharedPreferences("chore_reminders", Context.MODE_PRIVATE)

    fun hasFired(occurrenceId: String, kind: ReminderSchedule.Kind): Boolean =
        prefs.getBoolean(firedKey(occurrenceId, kind), false)

    fun markFired(occurrenceId: String, kind: ReminderSchedule.Kind) {
        prefs.edit().putBoolean(firedKey(occurrenceId, kind), true).apply()
    }

    /** When the last still-waiting nudge for this occurrence was delivered. */
    fun lastNudge(occurrenceId: String): OffsetDateTime? {
        val millis = prefs.getLong(nudgeKey(occurrenceId), 0L)
        if (millis == 0L) return null
        return OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis),
            java.time.ZoneId.systemDefault(),
        )
    }

    fun recordNudge(occurrenceId: String, at: OffsetDateTime) {
        prefs.edit().putLong(nudgeKey(occurrenceId), at.toInstant().toEpochMilli()).apply()
    }

    fun pending(): List<PendingReminder> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { PendingReminder.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun savePending(reminders: List<PendingReminder>) {
        val array = JSONArray()
        reminders.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PENDING, array.toString()).apply()
    }

    private fun firedKey(occurrenceId: String, kind: ReminderSchedule.Kind) = "fired:$occurrenceId:$kind"
    private fun nudgeKey(occurrenceId: String) = "nudge:$occurrenceId"

    private companion object {
        const val KEY_PENDING = "pending"
    }
}
