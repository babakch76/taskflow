package com.taskflow.app.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import java.time.OffsetDateTime

/**
 * Posts one reminder when its alarm fires.
 *
 * The wording is the whole design here. Every message is about the chore and
 * the clock, never about a person and never about how late something is:
 *
 *  - No "you are late", no day count, no "X is waiting for you".
 *  - The still-waiting nudge reads exactly the same on day five as on day one.
 *    Escalating wording is the nagging the app exists to absorb, and it would
 *    make the app the nag instead of the flatmate.
 *
 * Nothing here can be triggered by another member — there is no path that lets
 * one person cause a notification on another's phone.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: return
        val choreName = intent.getStringExtra(EXTRA_CHORE_NAME).orEmpty().ifBlank { "A chore" }

        val kind = runCatching { ReminderSchedule.Kind.valueOf(kindName) }.getOrNull() ?: return

        val store = ReminderStore(context)
        when (kind) {
            // The two scheduled ones fire once and are then spent, which is
            // what keeps "exactly two per occurrence" true across the
            // rescheduling that happens on every refresh.
            ReminderSchedule.Kind.TURN_START,
            ReminderSchedule.Kind.DUE_SOON,
            -> store.markFired(occurrenceId, kind)

            // The nudge repeats, so instead of being spent it records when it
            // last spoke; the next one is due 48 hours after that.
            ReminderSchedule.Kind.STILL_WAITING ->
                store.recordNudge(occurrenceId, OffsetDateTime.now())
        }

        show(context, occurrenceId, kind, choreName)
    }

    private fun show(
        context: Context,
        occurrenceId: String,
        kind: ReminderSchedule.Kind,
        choreName: String,
    ) {
        // On Android 13+ the user can refuse notifications outright. Posting
        // without the permission throws, and a crash in a broadcast receiver
        // would take out the whole process.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val (title, body) = when (kind) {
            ReminderSchedule.Kind.TURN_START ->
                "Your turn: $choreName" to "It's on your row now."
            ReminderSchedule.Kind.DUE_SOON ->
                "$choreName is coming up" to "Due today."
            // Deliberately flat. Same words however long it has been.
            ReminderSchedule.Kind.STILL_WAITING ->
                "$choreName is still on your row" to "Whenever you get to it."
        }

        val open = PendingIntent.getActivity(
            context,
            occurrenceId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(
                "$occurrenceId:$kind".hashCode(),
                notification,
            )
        }
    }

    companion object {
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_CHORE_NAME = "chore_name"
    }
}

/**
 * Alarms do not survive a reboot, so they are re-armed from what was stored.
 *
 * Without this, turning your phone off and on again silently cancels every
 * reminder you have — and you would have no way of noticing, because the
 * failure looks exactly like nothing happening.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderScheduler.rearmStored(context)
    }
}
