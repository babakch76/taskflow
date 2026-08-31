package com.taskflow.app.reminders

import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * When an occurrence's reminders should fire.
 *
 * Pure arithmetic over times — no Android, no I/O — so the rules can be tested
 * directly. Whoever actually posts the notifications ([ReminderScheduler])
 * takes these times and nothing else.
 *
 * The spec's shape, and the reason the rules are so narrow:
 *
 *  - Exactly **two** reminders per occurrence, and only ever to its assignee.
 *  - Then, if it is still sitting there past its date, at most one further
 *    nudge every 48 hours — *persistent, never escalating*. Same wording on day
 *    one and day five.
 *  - Nothing is ever sent about somebody else's chore, and no reminder can be
 *    triggered by a person. Only the schedule speaks.
 */
object ReminderSchedule {

    /** How far ahead of the needed-by time the second reminder lands. */
    private val DUE_SOON_LEAD: Duration = Duration.ofHours(3)

    /**
     * When a chore has no needed-by time of its own, "approaching" means the
     * morning of the day it is due rather than three hours before midnight.
     */
    private val MORNING_OF: LocalTime = LocalTime.of(9, 0)

    /** The gap between nudges once an occurrence is past its date. */
    val STILL_WAITING_GAP: Duration = Duration.ofHours(48)

    enum class Kind {
        /** "Your turn: Kitchen." Fires when the occurrence becomes yours. */
        TURN_START,

        /** "Kitchen is due today." Fires as the needed-by time approaches. */
        DUE_SOON,

        /** A quiet, unchanging nudge for something still not done. */
        STILL_WAITING,
    }

    data class Reminder(
        val occurrenceId: String,
        val kind: Kind,
        val at: OffsetDateTime,
    )

    /**
     * The two scheduled reminders for one occurrence, already moved out of
     * quiet hours.
     *
     * [turnStartedAt] is when the occurrence became this person's — its
     * creation. [dueDate] null (an as-needed chore) yields the turn-start
     * reminder only: there is no date to remind against, and inventing one
     * would turn a standing turn into a deadline.
     *
     * [neededByTime] is the chore's own "HH:MM", if it set one.
     */
    fun scheduledFor(
        occurrenceId: String,
        turnStartedAt: OffsetDateTime,
        dueDate: OffsetDateTime?,
        neededByTime: String?,
        quiet: QuietHours,
    ): List<Reminder> {
        val reminders = mutableListOf(
            Reminder(occurrenceId, Kind.TURN_START, quiet.nextAllowed(turnStartedAt)),
        )

        if (dueDate != null) {
            val raw = if (neededByTime != null) {
                dueDate.minus(DUE_SOON_LEAD)
            } else {
                // No time was agreed, so the useful moment is the morning of
                // the day itself — not three hours before an implied midnight,
                // which would be 21:00 and inside the default quiet window.
                dueDate.with(MORNING_OF)
            }
            reminders += Reminder(occurrenceId, Kind.DUE_SOON, quiet.nextAllowed(raw))
        }

        return reminders
    }

    /**
     * How late a scheduled reminder may be and still be worth sending.
     *
     * Reminders are computed on the device from data it has just fetched, so a
     * moment has almost always already passed by the time it is seen: your turn
     * started when somebody else finished theirs, which could have been while
     * the app was closed. Arming only future times would mean the turn-start
     * reminder never fired at all.
     */
    val CATCH_UP_WINDOW: Duration = Duration.ofHours(24)

    /**
     * Adjusts a scheduled reminder for the fact that its moment may already
     * have gone by.
     *
     * Returns the reminder to arm, or **null** if it is too late to be worth
     * sending — the caller should record that one as spent so it never fires.
     *
     * The window is what stops a first run notifying about every chore already
     * on your row. A turn that started last week is not news; the board has
     * been showing it the whole time, and a burst of catch-up notifications is
     * exactly the pile-on this design avoids.
     */
    fun catchUp(reminder: Reminder, now: OffsetDateTime, quiet: QuietHours): Reminder? = when {
        reminder.at.isAfter(now) -> reminder
        reminder.at.isBefore(now.minus(CATCH_UP_WINDOW)) -> null
        // Recent enough to still matter: send at the next allowed moment.
        else -> reminder.copy(at = quiet.nextAllowed(now))
    }

    /**
     * The next "still waiting" nudge for an occurrence that is open past its
     * date, or null if none is owed yet.
     *
     * [lastNudgeAt] is when the previous one of these was sent, if any. The
     * first is owed as soon as the date has passed; each subsequent one no
     * sooner than [STILL_WAITING_GAP] after the last.
     *
     * This is the only reminder that repeats, and it deliberately does not
     * change with time. An occurrence that has been open for a week gets the
     * same message it got on day one — the persistence is the point, and
     * escalation is what the spec is avoiding.
     */
    fun stillWaitingFor(
        occurrenceId: String,
        dueDate: OffsetDateTime?,
        lastNudgeAt: OffsetDateTime?,
        quiet: QuietHours,
        now: OffsetDateTime,
    ): Reminder? {
        // No date means nothing to be past. An as-needed chore is never late.
        if (dueDate == null) return null
        if (!now.isAfter(dueDate)) return null

        val earliest = if (lastNudgeAt == null) dueDate else lastNudgeAt.plus(STILL_WAITING_GAP)
        // Never in the past: a device that has been offline for a week owes one
        // nudge now, not seven backdated ones.
        val target = if (earliest.isBefore(now)) now else earliest

        return Reminder(occurrenceId, Kind.STILL_WAITING, quiet.nextAllowed(target))
    }
}

/**
 * A per-user quiet window, "HH:MM" to "HH:MM".
 *
 * The default (21:00–09:00) **wraps midnight**, which is the case the
 * comparison has to get right: "inside" is not a single `from <= t < to` test.
 * A window that does not wrap (say 01:00–06:00) is legal too and takes the
 * simple reading.
 *
 * The rule from the spec: a reminder that would land inside the window is
 * delivered at the next allowed moment, not dropped. It comes straight from the
 * 11pm finding — a reminder you cannot act on is one you will forget.
 */
data class QuietHours(
    val from: LocalTime,
    val to: LocalTime,
) {
    /** True if [time] falls inside the quiet window. */
    fun covers(time: LocalTime): Boolean = if (wrapsMidnight) {
        // e.g. 21:00–09:00: late evening OR early morning.
        time >= from || time < to
    } else {
        // e.g. 01:00–06:00: a plain interval.
        time >= from && time < to
    }

    private val wrapsMidnight: Boolean get() = from > to

    /**
     * [instant] if it is already outside the window; otherwise the moment the
     * window next opens.
     */
    fun nextAllowed(instant: OffsetDateTime): OffsetDateTime {
        if (!covers(instant.toLocalTime())) return instant

        val sameDay = instant.with(to)
        // Inside a wrapping window there are two cases: it is late evening, and
        // the window opens tomorrow morning; or it is already past midnight,
        // and it opens later today.
        return if (sameDay.isAfter(instant)) sameDay else sameDay.plusDays(1)
    }

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val DEFAULT = QuietHours(LocalTime.of(21, 0), LocalTime.of(9, 0))

        /**
         * Parses the server's two "HH:MM" strings, falling back to [DEFAULT] if
         * either is missing or malformed. A reminder arriving at a slightly
         * wrong hour is a nuisance; one that crashes the scheduler stops every
         * reminder the user has, so this never throws.
         */
        fun parse(from: String?, to: String?): QuietHours {
            val parsedFrom = runCatching { LocalTime.parse(from, FORMAT) }.getOrNull()
            val parsedTo = runCatching { LocalTime.parse(to, FORMAT) }.getOrNull()
            if (parsedFrom == null || parsedTo == null || parsedFrom == parsedTo) {
                return DEFAULT
            }
            return QuietHours(parsedFrom, parsedTo)
        }
    }
}
