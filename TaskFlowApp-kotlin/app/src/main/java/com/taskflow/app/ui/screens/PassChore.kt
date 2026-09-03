package com.taskflow.app.ui.screens

import android.content.Context
import com.taskflow.app.data.model.Chore
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.ScheduleType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Who a busy pass would hand this occurrence to.
 *
 * The same walk the server does: the next name in the rotation after the
 * current holder, skipping anyone who is away, wrapping round. It is computed
 * here only so the confirmation can name the person before the request is sent
 * — the server remains the authority, and it recomputes this on arrival.
 *
 * Null when there is nobody to pass to: a rotation of one, or a household where
 * everybody else is away. The button is hidden in that case rather than offering
 * a pass that would come straight back.
 */
fun nextInRotationAfter(
    chore: Chore?,
    occurrence: Occurrence,
    members: List<MemberInfo>,
): MemberInfo? {
    val rotation = chore?.rotation.orEmpty()
    if (rotation.size < 2) return null

    // Count on from whoever last covered, not from whoever is holding it.
    //
    // This mirrors nextCoverer on the server, and it has to: the dialog names
    // the receiver before the request is sent, so if the two disagree the
    // dialog promises one person and the board shows another. Counting from
    // the holder is what made every skip by a serial passer land on the same
    // neighbour, and it is the version this used to have.
    val from = occurrence.resumeAfter?.takeIf { it in rotation } ?: occurrence.assignedTo
    val start = rotation.indexOf(from)
    if (start < 0) return null

    val away = members.filter { it.away }.map { it.id }.toSet()
    for (step in 1..rotation.size) {
        val candidate = rotation[(start + step) % rotation.size]
        // Handing a chore back to yourself is not a pass, so step over the
        // person doing the passing rather than stopping at them.
        if (candidate == occurrence.assignedTo) continue
        if (candidate !in away) {
            return members.firstOrNull { it.id == candidate }
        }
    }
    return null
}

/**
 * The second line of the pass confirmation: what the receiver will be told.
 *
 * Three cases, because promising the wrong date is worse than saying less:
 *
 *  - The chore was already due, so the pass refreshes it to tomorrow. The
 *    server does that, and this is the only case where "due tomorrow" is true.
 *  - It has a date still ahead of it, which the pass leaves alone.
 *  - It is an as-needed chore and has no date at all, so there is nothing to
 *    promise except that it is now their turn.
 */
fun passNoticeLine(occurrence: Occurrence, chore: Chore?, receiver: String): String {
    val due = occurrence.dueDate
    return when {
        chore?.scheduleType == ScheduleType.AS_NEEDED || due == null ->
            "$receiver'll be told it's their turn. Nobody else is notified."

        due.isBefore(OffsetDateTime.now()) ->
            "$receiver'll be told it's due tomorrow. Nobody else is notified."

        else ->
            "$receiver'll be told it's due ${formatDueDate(due)}. Nobody else is notified."
    }
}

/** True when the pass will move the date, which is what "due tomorrow" means. */
fun passRefreshesDate(occurrence: Occurrence): Boolean {
    val due = occurrence.dueDate ?: return false
    return due.isBefore(OffsetDateTime.now())
}

/** Tomorrow, as the receiver will see it. Only used when the pass re-dates. */
fun tomorrowLabel(): String =
    LocalDate.now(ZoneId.systemDefault()).plusDays(1).let { it.toString() }

/**
 * One-off local flags that are nobody's business but this device's.
 *
 * The swipe hint is shown once and then never again, which needs a memory that
 * survives the process but has no place on the server: whether this person has
 * discovered a gesture is not part of the household's data, and syncing it
 * would mean a new device re-teaching someone who already knows.
 */
object LocalHints {
    private const val FILE = "taskflow_hints"
    private const val SWIPE_SEEN = "swipe_hint_dismissed"

    fun swipeHintDismissed(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SWIPE_SEEN, false)

    fun dismissSwipeHint(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SWIPE_SEEN, true)
            .apply()
    }
}
