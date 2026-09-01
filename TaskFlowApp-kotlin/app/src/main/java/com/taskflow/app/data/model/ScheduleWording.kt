package com.taskflow.app.data.model

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a chore's schedule reads to a housemate: "weekly", "every 3 days",
 * "Tuesdays", "as needed".
 *
 * F4 puts the frequency next to the done-line, on the same footing and with the
 * same visibility, because the two together are the agreement: *what* counts as
 * done and *how often* it is expected. Half of that on screen is not the treaty.
 *
 * The backend has its own version of this, used to phrase the edit diff that
 * goes to the whole group ("Kitchen: schedule: weekly → every 3 days"). They are
 * deliberately separate — one is UI copy that will want translating, the other
 * is part of an activity-feed message — but they should stay in step in the
 * words they choose, since a member may well read both about the same change.
 */
fun Chore.scheduleWording(): String = when (scheduleType) {
    ScheduleType.INTERVAL -> when (intervalDays) {
        null -> "unscheduled"
        1 -> "daily"
        7 -> "weekly"
        14 -> "fortnightly"
        30 -> "monthly"
        else -> "every $intervalDays days"
    }

    ScheduleType.FIXED_DATE -> {
        val weekdays = fixedWeekdays.orEmpty()
        val monthDays = fixedMonthDays.orEmpty()
        when {
            weekdays.isNotEmpty() -> weekdays.joinToString(", ") { weekdayName(it) + "s" }
            monthDays.isNotEmpty() -> monthDays.joinToString(", ") { "the ${ordinal(it)}" }
            else -> "on set days"
        }
    }

    ScheduleType.AS_NEEDED -> "as needed"
    else -> "one-off"
}

/**
 * The needed-by time as a sentence fragment, or null when the chore has none.
 *
 * Worth showing because it is what the second reminder is measured against, so
 * a member who wonders why they were nudged at a particular hour can see why.
 */
fun Chore.neededByWording(): String? = neededByTime?.let { "needed by $it" }

/** 0 = Sunday, matching the backend's storage. */
private fun weekdayName(day: Int): String {
    // java.time counts Monday=1..Sunday=7; the wire uses Sunday=0..Saturday=6.
    val dayOfWeek = if (day == 0) DayOfWeek.SUNDAY else DayOfWeek.of(day)
    return dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}

private fun ordinal(n: Int): String {
    // 11th, 12th and 13th are the exceptions to the 1st/2nd/3rd rule.
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
