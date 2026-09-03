package com.taskflow.app.data.remote

import com.taskflow.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for all authenticated API endpoints.
 * The Bearer token is automatically injected by [AuthInterceptor].
 *
 * Mirrors Go routes from cmd/server/main.go:
 *   - Groups (authed, no membership guard)
 *   - Invites (user-scoped)
 *   - Group-scoped (membership guard on server side)
 *   - Tasks (membership guard on server side)
 */
interface GroupApiService {

    // ─── The signed-in user ───────────────────────────────────

    /** The caller's own account, including their quiet-hours window (F3). */
    @GET("me")
    suspend fun getMe(): Response<User>

    /** Move either end of the quiet-hours window. */
    @PATCH("me")
    suspend fun updateMe(@Body request: UpdateMeRequest): Response<User>

    // ─── Groups ───────────────────────────────────────────────

    @POST("groups")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<Group>

    @GET("groups")
    suspend fun listMyGroups(): Response<List<Group>>

    @GET("groups/{group_id}")
    suspend fun getGroup(@Path("group_id") groupId: String): Response<GroupWithProgress>

    @GET("groups/{group_id}/members")
    suspend fun listMembers(@Path("group_id") groupId: String): Response<List<MemberInfo>>

    /**
     * Remove the caller's own membership.
     *
     * Deletes the group outright if the caller was its last member. Returns
     * 409 if the caller is the owner and other members remain — ownership
     * transfer is not implemented.
     */
    @DELETE("groups/{group_id}/members/me")
    suspend fun leaveGroup(@Path("group_id") groupId: String): Response<MessageResponse>

    /**
     * Group audit trail, newest first — the polling target for the shared
     * awareness / feedback loop.
     *
     * @param since RFC 3339 timestamp; when set, only newer events are
     *   returned. Pass the `created_at` of the newest event you already have.
     */
    @GET("groups/{group_id}/activity")
    suspend fun listActivity(
        @Path("group_id") groupId: String,
        @Query("since") since: String? = null,
    ): Response<List<ActivityEvent>>

    // ─── Invites (direct) ─────────────────────────────────────

    /** Send a direct invite to a user by username (must be group member). */
    @POST("groups/{group_id}/invite")
    suspend fun inviteByUsername(
        @Path("group_id") groupId: String,
        @Body request: InviteByUsernameRequest,
    ): Response<Invite>

    /** Generate a shareable invite code for the group (must be group member). */
    @POST("groups/{group_id}/invite-code")
    suspend fun generateInviteCode(
        @Path("group_id") groupId: String,
    ): Response<InviteCodeResponse>

    // ─── Invites (user-scoped) ────────────────────────────────

    /** List all pending invites for the authenticated user. */
    @GET("invites")
    suspend fun listMyInvites(): Response<List<InviteInfo>>

    /** Accept or decline a direct invite. */
    @PATCH("invites/{invite_id}")
    suspend fun respondToInvite(
        @Path("invite_id") inviteId: String,
        @Body request: InviteActionRequest,
    ): Response<MessageResponse>

    /** Redeem a shareable invite code to join a group. */
    @POST("invites/redeem")
    suspend fun redeemInviteCode(@Body request: RedeemInviteRequest): Response<MessageResponse>

    // ─── Tasks ────────────────────────────────────────────────

    @POST("groups/{group_id}/tasks")
    suspend fun createTask(
        @Path("group_id") groupId: String,
        @Body request: CreateTaskRequest,
    ): Response<Task>

    @GET("groups/{group_id}/tasks")
    suspend fun listTasks(@Path("group_id") groupId: String): Response<List<Task>>

    /**
     * Partially update a task.
     * Uses [UpdateTaskRequest] with [Patchable] fields to support
     * explicit null (unassign / clear due date) via the custom Gson adapter.
     */
    @PATCH("groups/{group_id}/tasks/{task_id}")
    suspend fun updateTask(
        @Path("group_id") groupId: String,
        @Path("task_id") taskId: String,
        @Body request: UpdateTaskRequest,
    ): Response<Task>

    @DELETE("groups/{group_id}/tasks/{task_id}")
    suspend fun deleteTask(
        @Path("group_id") groupId: String,
        @Path("task_id") taskId: String,
    ): Response<Unit>

    // ─── Chores and occurrences (F2) ──────────────────────────

    /**
     * Define a chore. The backend also creates its first occurrence, assigned
     * to position 0 of the rotation, so the chore appears on the board at once.
     */
    @POST("groups/{group_id}/chores")
    suspend fun createChore(
        @Path("group_id") groupId: String,
        @Body request: CreateChoreRequest,
    ): Response<Chore>

    /** The chore *definitions* and their rotation lists. The board reads occurrences instead. */
    @GET("groups/{group_id}/chores")
    suspend fun listChores(@Path("group_id") groupId: String): Response<List<Chore>>

    /**
     * Edit a chore. Open to every member by design; the whole group sees a diff
     * of what changed in the activity feed.
     */
    @PATCH("groups/{group_id}/chores/{chore_id}")
    suspend fun updateChore(
        @Path("group_id") groupId: String,
        @Path("chore_id") choreId: String,
        @Body request: UpdateChoreRequest,
    ): Response<Chore>

    @DELETE("groups/{group_id}/chores/{chore_id}")
    suspend fun deleteChore(
        @Path("group_id") groupId: String,
        @Path("chore_id") choreId: String,
    ): Response<Unit>

    // ─── History (F6), read-only ──────────────────────────────

    /** One chore's completions, newest first, with the absences around them. */
    @GET("groups/{group_id}/chores/{chore_id}/history")
    suspend fun choreHistory(
        @Path("group_id") groupId: String,
        @Path("chore_id") choreId: String,
    ): Response<ChoreHistory>

    /**
     * Completions per person over a window ("week", "month" or "quarter").
     *
     * Comes back in join order, with every member present. Do not re-sort it.
     */
    @GET("groups/{group_id}/history")
    suspend fun groupHistory(
        @Path("group_id") groupId: String,
        @Query("window") window: String,
    ): Response<GroupHistory>

    /** Everything the board shows: open occurrences first, oldest due date first. */
    @GET("groups/{group_id}/occurrences")
    suspend fun listOccurrences(@Path("group_id") groupId: String): Response<List<Occurrence>>

    /**
     * Mark an occurrence done, or undo that.
     *
     * Any member may complete any occurrence. Undo is refused with 403 unless
     * the caller is the person who marked it and is inside the ten-minute
     * window — the client greys the control out on the same rule, but the
     * server is what enforces it.
     */
    @PATCH("groups/{group_id}/occurrences/{occurrence_id}")
    suspend fun updateOccurrence(
        @Path("group_id") groupId: String,
        @Path("occurrence_id") occurrenceId: String,
        @Body request: UpdateOccurrenceRequest,
    ): Response<Occurrence>

    /**
     * Busy — pass an open occurrence of yours to the next person in the
     * rotation (F5).
     *
     * The turn comes back to you next cycle: passing defers it, it never
     * deletes it. Refused with 403 if it isn't yours and 409 if there is nobody
     * available to take it.
     */
    @POST("groups/{group_id}/occurrences/{occurrence_id}/pass")
    suspend fun passOccurrence(
        @Path("group_id") groupId: String,
        @Path("occurrence_id") occurrenceId: String,
    ): Response<Occurrence>

    /**
     * Take back a pass you have just made.
     *
     * For a mis-swipe, not for a change of mind: the server allows it only for
     * a couple of minutes after the pass, only for the person who passed it,
     * and only while it is still open. Refused with 403 if you were the
     * receiver rather than the passer, and 409 once the window has closed, the
     * chore has been done, or it was never passed at all.
     *
     * The due date the pass may have moved is restored too, so an overdue chore
     * comes back overdue rather than with tomorrow's deadline attached.
     */
    @DELETE("groups/{group_id}/occurrences/{occurrence_id}/pass")
    suspend fun undoPass(
        @Path("group_id") groupId: String,
        @Path("occurrence_id") occurrenceId: String,
    ): Response<Occurrence>

    /**
     * Bring an occurrence's day forward.
     *
     * The one place a chore's own date moves by hand, and only earlier. It is
     * for the case where everybody was busy at once: the chore came back to
     * whoever asked first, dated as late as the chore's own rhythm allows, and
     * they are choosing the day it will actually happen.
     *
     * Refused with 403 unless you are holding it, and 400 if the date is later
     * than the one it already has.
     */
    @PUT("groups/{group_id}/occurrences/{occurrence_id}/due-date")
    suspend fun setOccurrenceDueDate(
        @Path("group_id") groupId: String,
        @Path("occurrence_id") occurrenceId: String,
        @Body request: Map<String, String>,
    ): Response<Occurrence>

    /** Declare yourself away from this household, or back (F5). */
    @PUT("groups/{group_id}/members/me/away")
    suspend fun setAway(
        @Path("group_id") groupId: String,
        @Body request: SetAwayRequest,
    ): Response<Unit>
}
