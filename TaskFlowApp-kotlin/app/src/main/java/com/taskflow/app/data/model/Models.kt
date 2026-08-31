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
