package com.taskflow.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.ActivityEvent
import com.taskflow.app.data.model.BulkUpdateTaskStatusRequest
import com.taskflow.app.data.model.Chore
import com.taskflow.app.data.model.CreateChoreRequest
import com.taskflow.app.data.model.CreateTaskRequest
import com.taskflow.app.data.model.GroupWithProgress
import com.taskflow.app.data.model.InviteByUsernameRequest
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.Patchable
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.UpdateMeRequest
import com.taskflow.app.data.model.UpdateOccurrenceRequest
import com.taskflow.app.data.model.UpdateTaskRequest
import com.taskflow.app.data.model.isOpen
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.time.format.DateTimeFormatter

/**
 * Membership roles, matching the backend's `group_members.role`.
 *
 * [ROLE_ADMIN] is no longer assignable. The manager role and the deadline
 * permission it carried were removed when the chore spec made editing
 * explicitly open to every member; the constant remains only so that groups
 * created before that still render their existing rows correctly.
 */
const val ROLE_OWNER = "owner"
const val ROLE_ADMIN = "admin"
const val ROLE_MEMBER = "member"

/** Everything the Group Detail screen renders. */
data class GroupDetailUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val group: GroupWithProgress? = null,
    val tasks: List<Task> = emptyList(),
    /**
     * The chore model's half of the board (F2). Occurrences and tasks are shown
     * together: tasks predate the chore model and are the spec's "one-off"
     * shape, so they stay on the board rather than being hidden or migrated.
     */
    val occurrences: List<Occurrence> = emptyList(),
    /** Chore definitions, for rendering a schedule and for editing. */
    val chores: List<Chore> = emptyList(),
    val members: List<MemberInfo> = emptyList(),
    /** The signed-in user's quiet-hours window (F3), for the settings dialog. */
    val quietFrom: String = "21:00",
    val quietTo: String = "09:00",
    val activity: List<ActivityEvent> = emptyList(),
    /** Fatal error for the whole screen — shows the retry state. */
    val error: String? = null,
    /** Transient message for a single failed/successful action. */
    val message: String? = null,
    /** Set after generating a shareable invite code, so the UI can display it. */
    val inviteCode: String? = null,
    /** Flipped once the user has left the group, so the screen can navigate away. */
    val hasLeft: Boolean = false,
)

/**
 * ViewModel for one group's detail screen.
 *
 * Owns every call in the group-scoped half of [com.taskflow.app.data.remote.GroupApiService]:
 * tasks CRUD, the bulk status update behind multi-select, members and invites,
 * leaving, and the activity feed that drives the awareness/feedback loop.
 *
 * Takes [groupId] as a constructor argument, hence [Factory] — Compose's
 * `viewModel()` cannot supply it otherwise, and there is no DI framework here.
 */
class GroupDetailViewModel(private val groupId: String) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private val api get() = RetrofitClient.getInstance().groupApi

    /**
     * `created_at` of the newest activity event already held, sent back as
     * `?since=` so each poll transfers only what's new. Null until the first
     * load, which fetches the whole (capped) feed.
     */
    private var activityCursor: String? = null

    init {
        refresh()
    }

    // ─── Loading ────────────────────────────────────────────────

    /** Full reload of every section. Used on first open and on pull-to-retry. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Six independent endpoints — fire them together rather than
            // serially, so opening the screen costs one round trip, not six.
            val groupDeferred = async { runCatching { api.getGroup(groupId) } }
            val tasksDeferred = async { runCatching { api.listTasks(groupId) } }
            val occurrencesDeferred = async { runCatching { api.listOccurrences(groupId) } }
            val choresDeferred = async { runCatching { api.listChores(groupId) } }
            val membersDeferred = async { runCatching { api.listMembers(groupId) } }
            val activityDeferred = async { runCatching { api.listActivity(groupId) } }
            // Quiet hours, for the on-device reminder scheduler (F3).
            val meDeferred = async { runCatching { api.getMe() } }

            val groupRes = groupDeferred.await()
            val tasksRes = tasksDeferred.await()
            val occurrencesRes = occurrencesDeferred.await()
            val choresRes = choresDeferred.await()
            val membersRes = membersDeferred.await()
            val activityRes = activityDeferred.await()
            val meRes = meDeferred.await()

            // The group call is the one that must succeed — without it there is
            // no screen to show. A 404 here also means the membership guard
            // rejected us (the backend returns 404, never 403, to non-members).
            val group = groupRes.getOrNull()
            if (group == null || !group.isSuccessful || group.body() == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = groupRes.exceptionOrNull()?.let { networkMessage(it) }
                        ?: group?.let { ApiErrors.messageFor(it) }
                        ?: "Could not load this group",
                )
                return@launch
            }

            val me = meRes.getOrNull()?.takeIf { it.isSuccessful }?.body()
            val activity = activityRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty()
            activityCursor = activity.firstOrNull()?.createdAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = null,
                group = group.body(),
                tasks = tasksRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                occurrences = occurrencesRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                chores = choresRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                members = membersRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                activity = activity,
                // Falls back to the current state, which starts at the spec's
                // default — a failed /me must not silently widen the window.
                quietFrom = me?.quietFrom ?: _uiState.value.quietFrom,
                quietTo = me?.quietTo ?: _uiState.value.quietTo,
            )
        }
    }

    /**
     * Incremental activity poll — the CSCW feedback loop.
     *
     * Sends the newest timestamp already held as `?since=`, so a teammate's
     * change shows up here within one poll interval without re-fetching the
     * whole feed. Failures are swallowed: a dropped poll is not worth an error
     * banner, and the next tick retries.
     */
    fun pollActivity() {
        viewModelScope.launch {
            val response = runCatching { api.listActivity(groupId, activityCursor) }.getOrNull() ?: return@launch
            if (!response.isSuccessful) return@launch
            val fresh = response.body().orEmpty()
            if (fresh.isEmpty()) return@launch

            activityCursor = fresh.first().createdAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ?: activityCursor
            _uiState.value = _uiState.value.copy(activity = fresh + _uiState.value.activity)

            // Someone changed something — the task list and progress are now
            // stale, so pull them again.
            refreshTasksAndProgress()
        }
    }

    private suspend fun refreshTasksAndProgress() {
        runCatching { api.listTasks(groupId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()
            ?.let { _uiState.value = _uiState.value.copy(tasks = it) }
        // The board is both halves, so a poll that only refreshed tasks would
        // leave a housemate's completed chore sitting on the screen as open.
        runCatching { api.listOccurrences(groupId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()
            ?.let { _uiState.value = _uiState.value.copy(occurrences = it) }
        runCatching { api.listChores(groupId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()
            ?.let { _uiState.value = _uiState.value.copy(chores = it) }
        runCatching { api.getGroup(groupId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()
            ?.let { _uiState.value = _uiState.value.copy(group = it) }
    }

    // ─── Tasks ──────────────────────────────────────────────────

    fun createTask(title: String, description: String, assignedTo: String?) {
        runAction(successMessage = "Task added") {
            api.createTask(
                groupId,
                CreateTaskRequest(
                    title = title.trim(),
                    description = description.trim(),
                    assignedTo = assignedTo,
                ),
            )
        }
    }

    /**
     * Edit a task's title and/or description.
     *
     * Pass null for a field that didn't change — it stays out of the JSON, so
     * this can't clobber a concurrent edit to the other field. If nothing
     * changed, no request is made at all: an empty patch would serialise to
     * `{}` and the backend rejects that with "no fields to update".
     */
    fun updateTaskText(taskId: String, title: String?, description: String?) {
        if (title == null && description == null) return
        runAction(successMessage = "Task updated") {
            api.updateTask(
                groupId,
                taskId,
                UpdateTaskRequest(title = title, description = description),
            )
        }
    }

    fun setTaskStatus(taskId: String, status: String) {
        // Only `status` is set; assignedTo/dueDate stay Patchable.Absent and are
        // therefore omitted from the JSON entirely, so this cannot disturb the
        // assignee or the deadline.
        runAction(successMessage = "Moved to ${statusLabelFor(status)}") {
            api.updateTask(groupId, taskId, UpdateTaskRequest(status = status))
        }
    }

    /**
     * Assign or unassign a task.
     *
     * [userId] null means unassign, which must reach the backend as an explicit
     * `"assigned_to": null` — [Patchable.SetNull] — because an omitted key means
     * "leave it alone". This is the distinction the whole Patchable type exists
     * for.
     */
    fun setTaskAssignee(taskId: String, userId: String?) {
        val assignee = if (userId == null) Patchable.SetNull else Patchable.Value(userId)
        val note = if (userId == null) "Assignee removed" else "Task assigned"
        runAction(successMessage = note) {
            api.updateTask(groupId, taskId, UpdateTaskRequest(assignedTo = assignee))
        }
    }

    fun deleteTask(taskId: String) {
        runAction(successMessage = "Task deleted") { api.deleteTask(groupId, taskId) }
    }

    /**
     * Set or clear a task's deadline.
     *
     * Owner/manager only — the backend answers 403 otherwise, and [ApiErrors]
     * surfaces its explanation. The UI hides the control for plain members, but
     * this is not relying on that: the server is the authority, and a stale
     * `my_role` after a demotion would otherwise let a request through.
     *
     * @param dueDateIso RFC 3339 instant, or null to clear. Null becomes
     *   [Patchable.SetNull] so the key is sent as an explicit `null` — an
     *   omitted key would mean "leave the deadline alone", which is the exact
     *   opposite of clearing it.
     */
    fun setTaskDueDate(taskId: String, dueDateIso: String?) {
        val due = if (dueDateIso == null) Patchable.SetNull else Patchable.Value(dueDateIso)
        val note = if (dueDateIso == null) "Deadline removed" else "Deadline set"
        runAction(successMessage = note) {
            api.updateTask(groupId, taskId, UpdateTaskRequest(dueDate = due))
        }
    }

    /** Multi-select: one status for many tasks, in a single backend transaction. */
    fun bulkSetStatus(taskIds: Set<String>, status: String) {
        if (taskIds.isEmpty()) return
        val note = if (taskIds.size == 1) "1 task moved" else "${taskIds.size} tasks moved"
        runAction(successMessage = note) {
            api.bulkUpdateTaskStatus(
                groupId,
                BulkUpdateTaskStatusRequest(taskIds = taskIds.toList(), status = status),
            )
        }
    }

    // ─── Invites & membership ───────────────────────────────────

    fun generateInviteCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, message = null)
            try {
                val response = api.generateInviteCode(groupId)
                val body = response.body()
                _uiState.value = if (response.isSuccessful && body != null) {
                    _uiState.value.copy(isWorking = false, inviteCode = body.code)
                } else {
                    _uiState.value.copy(isWorking = false, message = ApiErrors.messageFor(response))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isWorking = false, message = networkMessage(e))
            }
        }
    }

    fun inviteByUsername(username: String) {
        runAction(
            successMessage = "Invite sent to $username",
        ) { api.inviteByUsername(groupId, InviteByUsernameRequest(username.trim())) }
    }

    /**
     * Leave the group.
     *
     * Three outcomes from the backend, all worth surfacing differently: 200 for
     * a normal leave, 200 with a different message when the caller was the last
     * member (the group is deleted), and 409 when the owner tries to leave while
     * others remain — that one is not a failure to retry, it's a rule.
     */
    fun leaveGroup() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, message = null)
            try {
                val response = api.leaveGroup(groupId)
                _uiState.value = if (response.isSuccessful) {
                    _uiState.value.copy(isWorking = false, hasLeft = true)
                } else {
                    _uiState.value.copy(isWorking = false, message = ApiErrors.messageFor(response))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isWorking = false, message = networkMessage(e))
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun dismissInviteCode() {
        _uiState.value = _uiState.value.copy(inviteCode = null)
    }

    // ─── Plumbing ───────────────────────────────────────────────

    /**
     * Runs a mutating call, then reloads.
     *
     * Everything is re-read from the server afterwards rather than patched
     * locally: this screen is shared by several people, so the authoritative
     * state is the backend's, and a local edit would paper over a teammate's
     * concurrent change.
     */
    // ─── Chores and occurrences (F2) ────────────────────────────

    /**
     * Define a chore. The backend creates its first occurrence too, so it
     * appears on the board immediately, on the row of whoever is first in
     * [rotation].
     */
    fun createChore(
        name: String,
        doneLine: String,
        scheduleType: String,
        intervalDays: Int?,
        fixedWeekdays: List<Int>?,
        rotation: List<String>,
    ) {
        runAction(successMessage = "Chore added") {
            api.createChore(
                groupId,
                CreateChoreRequest(
                    name = name.trim(),
                    doneLine = doneLine.trim(),
                    scheduleType = scheduleType,
                    intervalDays = intervalDays,
                    fixedWeekdays = fixedWeekdays,
                    rotation = rotation,
                ),
            )
        }
    }

    /**
     * One-tap completion, and its undo.
     *
     * Anyone may tick anything — done_by records who actually did it. Undo goes
     * back to the same endpoint with "open"; the server refuses it with 403
     * unless the caller marked it and is inside the ten-minute window, so a
     * stale screen produces a clear message rather than a silent no-op.
     */
    fun toggleOccurrenceDone(occurrence: Occurrence) {
        val target = if (occurrence.isOpen) "done" else "open"
        runAction(successMessage = if (target == "done") "Marked done" else "Completion undone") {
            api.updateOccurrence(groupId, occurrence.id, UpdateOccurrenceRequest(status = target))
        }
    }

    /**
     * Move the quiet-hours window (F3).
     *
     * Not routed through [runAction]: that refreshes the board and reloads the
     * activity feed, and this changes neither. The new window is folded into
     * state directly, which is also what re-triggers rescheduling on the screen.
     */
    fun setQuietHours(from: String, to: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, message = null)
            val response = runCatching { api.updateMe(UpdateMeRequest(quietFrom = from, quietTo = to)) }
                .getOrElse {
                    _uiState.value = _uiState.value.copy(isWorking = false, message = networkMessage(it))
                    return@launch
                }

            val updated = response.body()
            if (!response.isSuccessful || updated == null) {
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    message = ApiErrors.messageFor(response),
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isWorking = false,
                quietFrom = updated.quietFrom,
                quietTo = updated.quietTo,
                message = "Quiet hours updated",
            )
        }
    }

    fun deleteChore(choreId: String) {
        runAction(successMessage = "Chore deleted") { api.deleteChore(groupId, choreId) }
    }

    private fun runAction(
        successMessage: String? = null,
        block: suspend () -> Response<*>,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, message = null)
            try {
                val response = block()
                if (response.isSuccessful) {
                    refreshTasksAndProgress()
                    reloadActivityFromScratch()
                    _uiState.value = _uiState.value.copy(isWorking = false, message = successMessage)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        message = ApiErrors.messageFor(response),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isWorking = false, message = networkMessage(e))
            }
        }
    }

    /**
     * Re-reads the feed from the top after our own write.
     *
     * The cursor is reset because our new event may share a timestamp with ones
     * already held, and a `since` poll is strictly-greater-than.
     */
    private suspend fun reloadActivityFromScratch() {
        val fresh = runCatching { api.listActivity(groupId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: return
        activityCursor = fresh.firstOrNull()?.createdAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        _uiState.value = _uiState.value.copy(activity = fresh)
    }

    /** Mirrors the UI's status labels so snackbars read the same as the chips. */
    private fun statusLabelFor(status: String) = when (status) {
        "todo" -> "To do"
        "in_progress" -> "In progress"
        "done" -> "Done"
        else -> status
    }

    private fun networkMessage(e: Throwable) =
        "Network error: ${e.localizedMessage ?: "could not reach server"}"

    /** Supplies [groupId] to the ViewModel; no DI framework in this project. */
    class Factory(private val groupId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GroupDetailViewModel(groupId) as T
    }
}
