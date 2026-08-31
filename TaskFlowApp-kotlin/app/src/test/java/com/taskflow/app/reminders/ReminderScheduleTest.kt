package com.taskflow.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The quiet-hours arithmetic and the reminder rules.
 *
 * Worth testing directly because the default window wraps midnight, which is
 * the case a plain `from <= t < to` comparison gets silently wrong — it would
 * report 23:00 as *outside* a 21:00–09:00 window and deliver the 11pm reminder
 * the spec exists to prevent.
 */
class ReminderScheduleTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): OffsetDateTime =
        OffsetDateTime.of(2026, 9, day, hour, minute, 0, 0, ZoneOffset.UTC)

    private val default = QuietHours.DEFAULT

    // ── the wrapping window ──────────────────────────────────────

    @Test
    fun `a wrapping window covers both the late evening and the early morning`() {
        assertTrue("22:00 is inside 21:00-09:00", default.covers(LocalTime.of(22, 0)))
        assertTrue("23:59 is inside", default.covers(LocalTime.of(23, 59)))
        assertTrue("00:30 is inside", default.covers(LocalTime.of(0, 30)))
        assertTrue("08:59 is inside", default.covers(LocalTime.of(8, 59)))

        assertTrue("21:00 is the first quiet minute", default.covers(LocalTime.of(21, 0)))
        assertTrue("09:00 is the first allowed minute", !default.covers(LocalTime.of(9, 0)))
        assertTrue("midday is allowed", !default.covers(LocalTime.of(12, 0)))
        assertTrue("20:59 is allowed", !default.covers(LocalTime.of(20, 59)))
    }

    @Test
    fun `a window that does not wrap takes the plain reading`() {
        val night = QuietHours(LocalTime.of(1, 0), LocalTime.of(6, 0))

        assertTrue(night.covers(LocalTime.of(3, 0)))
        assertTrue(!night.covers(LocalTime.of(23, 0)))
        assertTrue(!night.covers(LocalTime.of(0, 30)))
        assertTrue(!night.covers(LocalTime.of(7, 0)))
    }

    @Test
    fun `an allowed time is left exactly as it is`() {
        val midday = at(2, 12)
        assertEquals(midday, default.nextAllowed(midday))
    }

    @Test
    fun `a late-evening reminder waits for the next morning`() {
        // 23:00 Tuesday → 09:00 Wednesday. This is the 11pm case: the reminder
        // is held rather than dropped, and arrives when it can be acted on.
        assertEquals(at(3, 9), default.nextAllowed(at(2, 23)))
    }

    @Test
    fun `an early-morning reminder waits for later the same day`() {
        // 02:00 Wednesday → 09:00 Wednesday, not the following day.
        assertEquals(at(3, 9), default.nextAllowed(at(3, 2)))
    }

    @Test
    fun `the boundaries land on the right side`() {
        // 21:00 exactly is quiet, and waits.
        assertEquals(at(3, 9), default.nextAllowed(at(2, 21)))
        // 09:00 exactly is allowed, and passes through untouched.
        assertEquals(at(2, 9), default.nextAllowed(at(2, 9)))
    }

    @Test
    fun `parsing falls back to the default rather than throwing`() {
        assertEquals(QuietHours.DEFAULT, QuietHours.parse(null, null))
        assertEquals(QuietHours.DEFAULT, QuietHours.parse("half nine", "09:00"))
        assertEquals(QuietHours.DEFAULT, QuietHours.parse("21:00", ""))
        // Equal ends are meaningless; the server refuses them, and a client
        // that somehow receives them must not schedule against a zero window.
        assertEquals(QuietHours.DEFAULT, QuietHours.parse("09:00", "09:00"))

        assertEquals(
            QuietHours(LocalTime.of(22, 30), LocalTime.of(7, 15)),
            QuietHours.parse("22:30", "07:15"),
        )
    }

    // ── the two scheduled reminders ──────────────────────────────

    @Test
    fun `a dated chore gets a turn-start and a due-soon reminder`() {
        val reminders = ReminderSchedule.scheduledFor(
            occurrenceId = "occ-1",
            turnStartedAt = at(1, 10),
            dueDate = at(4, 18),          // due Friday 18:00
            neededByTime = "18:00",
            quiet = default,
        )

        assertEquals(2, reminders.size)
        assertEquals(ReminderSchedule.Kind.TURN_START, reminders[0].kind)
        assertEquals(at(1, 10), reminders[0].at)

        assertEquals(ReminderSchedule.Kind.DUE_SOON, reminders[1].kind)
        assertEquals("three hours before the needed-by time", at(4, 15), reminders[1].at)
    }

    @Test
    fun `an as-needed chore gets only the turn-start reminder`() {
        val reminders = ReminderSchedule.scheduledFor(
            occurrenceId = "occ-1",
            turnStartedAt = at(1, 10),
            dueDate = null,
            neededByTime = null,
            quiet = default,
        )

        assertEquals(1, reminders.size)
        assertEquals(ReminderSchedule.Kind.TURN_START, reminders[0].kind)
    }

    @Test
    fun `with no needed-by time the due reminder lands on the morning of the day`() {
        // The backend dates such an occurrence at 23:59. Three hours before
        // that is 20:59 — nearly the quiet window, and useless besides. The
        // morning of the day is the moment you can act on it.
        val reminders = ReminderSchedule.scheduledFor(
            occurrenceId = "occ-1",
            turnStartedAt = at(1, 10),
            dueDate = at(4, 23, 59),
            neededByTime = null,
            quiet = default,
        )

        assertEquals(at(4, 9), reminders[1].at)
    }

    @Test
    fun `a turn starting at night is held until the morning`() {
        // Somebody completes their chore at 22:40, which starts your turn.
        val reminders = ReminderSchedule.scheduledFor(
            occurrenceId = "occ-1",
            turnStartedAt = at(1, 22, 40),
            dueDate = null,
            neededByTime = null,
            quiet = default,
        )

        assertEquals(at(2, 9), reminders[0].at)
    }

    @Test
    fun `an early needed-by time pushes the due reminder out of the quiet window`() {
        // Bins out by 08:00: three hours before is 05:00, inside quiet hours.
        // It waits for 09:00 — after the deadline, but the alternative is a
        // 5am alarm, and the board has shown it all along either way.
        val reminders = ReminderSchedule.scheduledFor(
            occurrenceId = "occ-1",
            turnStartedAt = at(1, 10),
            dueDate = at(4, 8),
            neededByTime = "08:00",
            quiet = default,
        )

        assertEquals(at(4, 9), reminders[1].at)
    }

    // ── catching up ──────────────────────────────────────────────

    @Test
    fun `a future reminder is left alone`() {
        val reminder = ReminderSchedule.Reminder("occ-1", ReminderSchedule.Kind.TURN_START, at(5, 12))
        assertEquals(reminder, ReminderSchedule.catchUp(reminder, at(4, 12), default))
    }

    @Test
    fun `a turn that started while the app was closed still notifies`() {
        // This is the case the whole catch-up rule exists for. TURN_START is
        // computed from the occurrence's creation, which is always in the past
        // by the time the device fetches it — arming only future times would
        // mean this reminder never fired at all.
        val reminder = ReminderSchedule.Reminder("occ-1", ReminderSchedule.Kind.TURN_START, at(4, 8))
        val caught = ReminderSchedule.catchUp(reminder, at(4, 12), default)!!

        assertEquals(at(4, 12), caught.at)
        assertEquals(ReminderSchedule.Kind.TURN_START, caught.kind)
    }

    @Test
    fun `catching up still respects quiet hours`() {
        val reminder = ReminderSchedule.Reminder("occ-1", ReminderSchedule.Kind.TURN_START, at(4, 21, 30))
        val caught = ReminderSchedule.catchUp(reminder, at(4, 22), default)!!

        assertEquals(at(5, 9), caught.at)
    }

    @Test
    fun `a reminder older than the window is dropped rather than fired late`() {
        // A turn that started last week is not news, and a first run must not
        // notify about every chore already sitting on your row.
        val reminder = ReminderSchedule.Reminder("occ-1", ReminderSchedule.Kind.TURN_START, at(1, 12))
        assertNull(ReminderSchedule.catchUp(reminder, at(4, 12), default))
    }

    // ── the still-waiting nudge ──────────────────────────────────

    @Test
    fun `nothing is owed before the due date passes`() {
        assertNull(
            ReminderSchedule.stillWaitingFor(
                occurrenceId = "occ-1",
                dueDate = at(5, 12),
                lastNudgeAt = null,
                quiet = default,
                now = at(4, 12),
            ),
        )
    }

    @Test
    fun `an undated occurrence is never late`() {
        assertNull(
            ReminderSchedule.stillWaitingFor(
                occurrenceId = "occ-1",
                dueDate = null,
                lastNudgeAt = null,
                quiet = default,
                now = at(9, 12),
            ),
        )
    }

    @Test
    fun `the first nudge is owed once the date has passed`() {
        val nudge = ReminderSchedule.stillWaitingFor(
            occurrenceId = "occ-1",
            dueDate = at(4, 12),
            lastNudgeAt = null,
            quiet = default,
            now = at(4, 14),
        )!!

        assertEquals(ReminderSchedule.Kind.STILL_WAITING, nudge.kind)
        assertEquals(at(4, 14), nudge.at)
    }

    @Test
    fun `the next nudge is 48 hours after the last, not sooner`() {
        val nudge = ReminderSchedule.stillWaitingFor(
            occurrenceId = "occ-1",
            dueDate = at(1, 12),
            lastNudgeAt = at(4, 10),
            quiet = default,
            now = at(4, 12),
        )!!

        assertEquals(at(6, 10), nudge.at)
    }

    @Test
    fun `a long silence owes one nudge now, not a backlog`() {
        // The device was off for a week. The spec's rule is *at most* one per
        // 48h — catching up with several at once would be exactly the
        // escalation it forbids.
        val nudge = ReminderSchedule.stillWaitingFor(
            occurrenceId = "occ-1",
            dueDate = at(1, 12),
            lastNudgeAt = at(1, 12),
            quiet = default,
            now = at(12, 14),
        )!!

        assertEquals(at(12, 14), nudge.at)
    }

    @Test
    fun `a nudge falling in the quiet window waits for morning`() {
        val nudge = ReminderSchedule.stillWaitingFor(
            occurrenceId = "occ-1",
            dueDate = at(1, 12),
            lastNudgeAt = at(2, 22),
            quiet = default,
            now = at(3, 12),
        )!!

        // 22:00 + 48h = 22:00 on the 4th, which is quiet → 09:00 on the 5th.
        assertEquals(at(5, 9), nudge.at)
    }
}
