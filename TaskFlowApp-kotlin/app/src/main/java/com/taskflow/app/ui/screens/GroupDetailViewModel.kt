package com.taskflow.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.ActivityEvent
import com.taskflow.app.data.model.BulkUpdateTaskStatusRequest
import com.taskflow.app.data.model.CreateTaskRequest
import com.taskflow.app.data.model.GroupWithProgress
import com.taskflow.app.data.model.InviteByUsernameRequest
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Patchable
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.UpdateTaskRequest
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.time.format.DateTimeFormatter

/** Everything the Group Detail screen renders. */
data class GroupDetailUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val group: GroupWithProgress? = null,
    val tasks: List<Task> = emptyList(),
    val members: List<MemberInfo> = emptyList(),
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

            // Four independent endpoints — fire them together rather than
            // serially, so opening the screen costs one round trip, not four.
            val groupDeferred = async { runCatching { api.getGroup(groupId) } }
            val tasksDeferred = async { runCatching { api.listTasks(groupId) } }
            val membersDeferred = async { runCatching { api.listMembers(groupId) } }
            val activityDeferred = async { runCatching { api.listActivity(groupId) } }

            val groupRes = groupDeferred.await()
            val tasksRes = tasksDeferred.await()
            val membersRes = membersDeferred.await()
            val activityRes = activityDeferred.await()

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

            val activity = activityRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty()
            activityCursor = activity.firstOrNull()?.createdAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = null,
                group = group.body(),
                tasks = tasksRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                members = membersRes.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty(),
                activity = activity,
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
