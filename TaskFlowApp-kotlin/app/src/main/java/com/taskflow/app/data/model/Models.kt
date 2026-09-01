package com.taskflow.app.data.model

import com.google.gson.annotations.SerializedName
import java.time.OffsetDateTime

// ═══════════════════════════════════════════════════════════════
// Domain Models — mirror the Go structs in internal/models/models.go
// ═══════════════════════════════════════════════════════════════

data class User(
    val id: String,
    val username: String,
    val email: String,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    /**
     * Quiet hours, "HH:MM" (F3). A reminder that would land inside this window
     * waits until [quietTo]. Normally wraps midnight.
     *
     * Defaulted here as well as in the schema so a response from an older
     * server — or any path that omits them — still yields a usable window
     * rather than an empty string the scheduler would have to guess about.
     */
    @SerializedName("quiet_from")
    val quietFrom: String = "21:00",
    @SerializedName("quiet_to")
    val quietTo: String = "09:00",
)

data class Group(
    val id: String,
    val name: String,
    val description: String,
    @SerializedName("created_by")
    val createdBy: String,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
)

/** Returned by GET /groups/{group_id} — includes task progress stats. */
data class GroupWithProgress(
    val id: String,
    val name: String,
    val description: String,
    @SerializedName("created_by")
    val createdBy: String,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    @SerializedName("total_tasks")
    val totalTasks: Int = 0,
    @SerializedName("done_tasks")
    val doneTasks: Int = 0,
    val progress: Double = 0.0,
    /**
     * The signed-in user's own role in this group: "owner", "admin" or
     * "member". Returned by the backend so the client can gate its UI without
     * fetching the member list and working out which row is itself.
     */
    @SerializedName("my_role")
    val myRole: String = "member",
)

data class Task(
    val id: String,
    @SerializedName("group_id")
    val groupId: String,
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    val title: String,
    val description: String,
    val status: String,   // "todo", "in_progress", "done"
    @SerializedName("due_date")
    val dueDate: OffsetDateTime? = null,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    /**
     * Null for tasks written before the `updated_at` column existed — the
     * backend adds it via ALTER TABLE, which cannot backfill a timestamp.
     */
    @SerializedName("updated_at")
    val updatedAt: OffsetDateTime? = null,
    /**
     * Who marked it done, which is not necessarily [assignedTo] — anyone may
     * complete anything, and the board records what actually happened. Null
     * while the task is still open.
     */
    @SerializedName("done_by")
    val doneBy: String? = null,
    @SerializedName("done_at")
    val doneAt: OffsetDateTime? = null,
)

/** True when this task is still outstanding — the board's only real distinction. */
val Task.isOpen: Boolean get() = status != "done"


// ── Chores (F2) ──────────────────────────────────────────────────

/**
 * Schedule types, mirroring the constants in the backend's models.go. A chore
 * picks one at creation and cannot change category afterwards, only its
 * parameters.
 */
object ScheduleType {
    /** Every N days, counted from the last *completion* — done late, the whole schedule shifts. */
    const val INTERVAL = "interval"
    /** Specific weekdays or month days, because the world sets the deadline. */
    const val FIXED_DATE = "fixed_date"
    /** Rotates with no date at all; one standing occurrence always exists. */
    const val AS_NEEDED = "as_needed"
    /** No recurrence — a bill, a repair, a delivery. */
    const val ONE_OFF = "one_off"
}

/**
 * A chore *definition*: what it is, how often it comes round, and the turn
 * order it follows. Never shown on the board itself — its [Occurrence]s are.
 */
data class Chore(
    val id: String,
    @SerializedName("group_id")
    val groupId: String,
    val name: String,
    /** F4's "what done means" — one agreed line, capped at 140 chars server-side. */
    @SerializedName("done_line")
    val doneLine: String = "",
    @SerializedName("schedule_type")
    val scheduleType: String,
    @SerializedName("interval_days")
    val intervalDays: Int? = null,
    /** 0 = Sunday. Set only for [ScheduleType.FIXED_DATE]. */
    @SerializedName("fixed_weekdays")
    val fixedWeekdays: List<Int>? = null,
    /** 1..31. Set only for [ScheduleType.FIXED_DATE]. */
    @SerializedName("fixed_month_days")
    val fixedMonthDays: List<Int>? = null,
    @SerializedName("needed_by_time")
    val neededByTime: String? = null,
    /** Ordered turn list by user id, position 0 first. */
    val rotation: List<String> = emptyList(),
    @SerializedName("created_by")
    val createdBy: String,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    @SerializedName("updated_at")
    val updatedAt: OffsetDateTime? = null,
)

/**
 * One cycle of a chore, assigned to exactly one member. This is what the board
 * shows and what gets marked done.
 *
 * [assignedTo] is non-null by design: everything on the board always has
 * exactly one name on it — there are no unassigned chores and no claim pool.
 * [doneBy] may differ from it, which is what makes a cover visible in the
 * record without anyone having to announce it.
 */
data class Occurrence(
    val id: String,
    @SerializedName("chore_id")
    val choreId: String,
    @SerializedName("group_id")
    val groupId: String,
    @SerializedName("assigned_to")
    val assignedTo: String,
    /** "open" or "done". There is deliberately no "missed" state. */
    val status: String,
    @SerializedName("due_date")
    val dueDate: OffsetDateTime? = null,
    @SerializedName("done_by")
    val doneBy: String? = null,
    @SerializedName("done_at")
    val doneAt: OffsetDateTime? = null,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    /**
     * Who passed this occurrence away, if anyone (F5). The debt stays with them
     * through a chain of passes, so the next turn returns here.
     */
    @SerializedName("passed_from")
    val passedFrom: String? = null,
    /** When it last changed hands — when it became the current holder's turn. */
    @SerializedName("passed_at")
    val passedAt: OffsetDateTime? = null,
    /** Joined in by the backend so a row renders without a second round trip. */
    @SerializedName("chore_name")
    val choreName: String = "",
    @SerializedName("done_line")
    val doneLine: String = "",
)

/**
 * True while this occurrence is still outstanding.
 *
 * An open occurrence never expires and never disappears — it stays on its
 * assignee's row, day after day, until it is completed or passed.
 */
val Occurrence.isOpen: Boolean get() = status == "open"

/**
 * One entry in a group's audit trail, from GET /groups/{id}/activity.
 *
 * [eventType] is one of the constants in the backend's handlers/activity.go:
 * `task_created`, `task_updated`, `task_deleted`, `tasks_bulk_updated`,
 * `member_joined`, `member_left`, `invite_accepted`.
 */
data class ActivityEvent(
    val id: String,
    @SerializedName("group_id")
    val groupId: String,
    @SerializedName("actor_id")
    val actorId: String,
    @SerializedName("actor_username")
    val actorUsername: String,
    @SerializedName("event_type")
    val eventType: String,
    @SerializedName("task_id")
    val taskId: String? = null,
    val detail: String = "",
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
)

data class Invite(
    val id: String,
    @SerializedName("group_id")
    val groupId: String,
    @SerializedName("invited_by")
    val invitedBy: String,
    @SerializedName("invited_user")
    val invitedUser: String? = null,
    val status: String,
    @SerializedName("invite_code")
    val inviteCode: String? = null,
    @SerializedName("max_uses")
    val maxUses: Int = 0,
    @SerializedName("use_count")
    val useCount: Int = 0,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    @SerializedName("expires_at")
    val expiresAt: OffsetDateTime? = null,
)

/** Returned by GET /invites — enriched with group/inviter names. */
data class InviteInfo(
    val id: String,
    @SerializedName("group_id")
    val groupId: String,
    @SerializedName("group_name")
    val groupName: String,
    @SerializedName("invited_by")
    val invitedBy: String,
    val status: String,
    @SerializedName("created_at")
    val createdAt: OffsetDateTime? = null,
    @SerializedName("expires_at")
    val expiresAt: OffsetDateTime? = null,
)

/** Returned by GET /groups/{group_id}/members. */
data class MemberInfo(
    val id: String,
    val username: String,
    val email: String,
    val role: String,   // "owner", "admin", "member"
    @SerializedName("joined_at")
    val joinedAt: OffsetDateTime? = null,
    /**
     * Away right now (F5) — lifted out of every rotation in this household
     * until they're back.
     *
     * The server reports this rather than raw dates, so a period that has run
     * out already reads as present. It is shown wherever the member's name
     * appears, deliberately: the app can't tell whether someone is really gone,
     * so the spec makes the claim impossible to hide instead.
     */
    val away: Boolean = false,
    @SerializedName("away_until")
    val awayUntil: OffsetDateTime? = null,
)

// ═══════════════════════════════════════════════════════════════
// Request DTOs — sent TO the Go backend
// ═══════════════════════════════════════════════════════════════

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
)

data class LoginRequest(
    val email: String,
    val password: String,
)

data class CreateGroupRequest(
    val name: String,
    val description: String = "",
)

data class CreateTaskRequest(
    val title: String,
    val description: String = "",
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    @SerializedName("due_date")
    val dueDate: String? = null,   // RFC 3339 string
)

/**
 * PATCH request for updating tasks.
 *
 * [assignedTo] and [dueDate] use [Patchable] to distinguish between:
 *  - Absent  → field omitted from JSON (Go ignores it)
 *  - SetNull → field sent as `null` (Go clears the value)
 *  - Value   → field sent with a value (Go updates it)
 *
 * Simple nullable fields (title, description, status) are safe to use
 * regular nullability because Gson omitting them means "no change",
 * which is the correct behavior.
 */
data class UpdateTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    @SerializedName("assigned_to")
    val assignedTo: Patchable<String> = Patchable.Absent,
    @SerializedName("due_date")
    val dueDate: Patchable<String> = Patchable.Absent,
)

/**
 * Body for PATCH /groups/{group_id}/tasks — one status applied to a set of
 * tasks in a single round trip. Backs the multi-select UI.
 *
 * No [Patchable] fields here: both keys are always sent, and the backend
 * rejects an empty [taskIds] with 400.
 */
data class BulkUpdateTaskStatusRequest(
    @SerializedName("task_ids")
    val taskIds: List<String>,
    val status: String,   // "todo", "in_progress", "done"
)

/**
 * Body for POST /groups/{group_id}/chores.
 *
 * [rotation] must hold at least one member — an occurrence always has exactly
 * one name on it, so a chore with an empty rotation could never spawn one.
 * Only the fields belonging to the chosen [scheduleType] may be set; the
 * backend rejects any other combination.
 */
data class CreateChoreRequest(
    val name: String,
    @SerializedName("done_line")
    val doneLine: String = "",
    @SerializedName("schedule_type")
    val scheduleType: String,
    @SerializedName("interval_days")
    val intervalDays: Int? = null,
    @SerializedName("fixed_weekdays")
    val fixedWeekdays: List<Int>? = null,
    @SerializedName("fixed_month_days")
    val fixedMonthDays: List<Int>? = null,
    @SerializedName("needed_by_time")
    val neededByTime: String? = null,
    val rotation: List<String>,
    /** One-off chores only — every other type derives its date from the schedule. */
    @SerializedName("due_date")
    val dueDate: String? = null,
)

/**
 * Body for PATCH /groups/{group_id}/chores/{chore_id}.
 *
 * Open to any member: the spec replaces an approval flow with a diff broadcast
 * to the whole group. `schedule_type` is absent on purpose — a chore cannot
 * change category, only its parameters.
 */
data class UpdateChoreRequest(
    val name: String? = null,
    @SerializedName("done_line")
    val doneLine: String? = null,
    @SerializedName("interval_days")
    val intervalDays: Int? = null,
    @SerializedName("fixed_weekdays")
    val fixedWeekdays: List<Int>? = null,
    @SerializedName("fixed_month_days")
    val fixedMonthDays: List<Int>? = null,
    val rotation: List<String>? = null,
)

/**
 * Body for PATCH /groups/{group_id}/occurrences/{occurrence_id} — the board's
 * one-tap completion, and its undo.
 *
 * Status is the only field: an occurrence's assignee comes from the rotation
 * and its due date from the schedule, never from the client.
 */
data class UpdateOccurrenceRequest(
    val status: String,   // "open" or "done"
)

/**
 * Body for PATCH /me. Either end of the quiet-hours window can be moved without
 * restating the other; the server refuses a patch that leaves both equal.
 */
data class UpdateMeRequest(
    @SerializedName("quiet_from")
    val quietFrom: String? = null,
    @SerializedName("quiet_to")
    val quietTo: String? = null,
)

/**
 * Body for PUT /groups/{group_id}/members/me/away (F5).
 *
 * [until] is optional: no end date means open-ended, the honest default for
 * "I don't know when I'm back". Ignored when [away] is false.
 */
data class SetAwayRequest(
    val away: Boolean,
    val until: String? = null,   // RFC 3339
)

data class InviteByUsernameRequest(
    val username: String,
)

data class RedeemInviteRequest(
    val code: String,
)

/** Used for PATCH /invites/{invite_id} */
data class InviteActionRequest(
    val action: String,   // "accept" or "decline"
)

// ═══════════════════════════════════════════════════════════════
// Response DTOs — received FROM the Go backend
// ═══════════════════════════════════════════════════════════════

data class AuthResponse(
    val token: String,
    val user: User,
)

/** Generic { "message": "..." } responses (join, accept, decline, etc.) */
data class MessageResponse(
    val message: String,
)

/** Generic { "error": "..." } error responses. */
data class ErrorResponse(
    val error: String,
)

/** Returned by POST /groups/{group_id}/invite-code */
data class InviteCodeResponse(
    val code: String,
    @SerializedName("expires_at")
    val expiresAt: String,
)
