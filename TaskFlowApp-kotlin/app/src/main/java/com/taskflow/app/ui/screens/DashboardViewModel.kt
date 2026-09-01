package com.taskflow.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Chore
import com.taskflow.app.data.model.Group
import com.taskflow.app.data.model.InviteActionRequest
import com.taskflow.app.data.model.InviteInfo
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.RedeemInviteRequest
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import com.taskflow.app.reminders.QuietHours
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Represents the three possible states of the Dashboard UI.
 */
sealed class DashboardUiState {
    /** Initial state while the group list is being fetched. */
    object Loading : DashboardUiState()

    /** Groups loaded successfully. [groups] may be empty. */
    data class Success(val groups: List<Group>) : DashboardUiState()

    /** Network or server error. [message] is user-friendly text. */
    data class Error(val message: String) : DashboardUiState()
}

/**
 * ViewModel for the Dashboard screen (group listing + inbound invites).
 *
 * Invites live here rather than in their own screen because both ways of
 * joining a group land on this list: accepting a direct invite, and redeeming a
 * shared code. Keeping them together means one refresh after either path.
 */
class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Pending invites addressed to this user, for the top-bar badge. */
    private val _invites = MutableStateFlow<List<InviteInfo>>(emptyList())
    val invites: StateFlow<List<InviteInfo>> = _invites.asStateFlow()

    /**
     * Emitted once when the user turns out to be in exactly one group, so the
     * app can go straight to its board — F1: "board is the app's default
     * screen after login", and the onboarding loop ends "land on the board".
     *
     * One-shot on purpose. Forwarding every time the group list appears would
     * make Back from the board bounce straight forward again, trapping the
     * user. [consumeAutoOpen] latches it, and the flag lives as long as this
     * ViewModel — i.e. as long as the Dashboard's place on the back stack — so
     * navigating back leaves you on the list, which is what you asked for by
     * pressing Back.
     */
    private val _autoOpenGroupId = MutableStateFlow<String?>(null)
    val autoOpenGroupId: StateFlow<String?> = _autoOpenGroupId.asStateFlow()
    private var autoOpenLatched = false

    fun consumeAutoOpen() {
        autoOpenLatched = true
        _autoOpenGroupId.value = null
    }

    /** One-shot feedback for an invite action; the screen shows it and clears it. */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val api get() = RetrofitClient.getInstance().groupApi

    init {
        fetchGroups()
        fetchInvites()
    }

    /**
     * Fetch the user's groups from the Go backend.
     * Called on init and when the user taps "Retry" or pulls to refresh.
     */
    fun fetchGroups() {
        viewModelScope.launch {
            // Only show full-screen loading on initial load, not on refresh
            if (_uiState.value !is DashboardUiState.Success) {
                _uiState.value = DashboardUiState.Loading
            }
            _isRefreshing.value = true

            try {
                val response = api.listMyGroups()

                if (response.isSuccessful) {
                    val groups = response.body() ?: emptyList()
                    _uiState.value = DashboardUiState.Success(groups)
                    if (!autoOpenLatched && groups.size == 1) {
                        _autoOpenGroupId.value = groups.first().id
                    }
                    loadReminderData(groups)
                } else {
                    _uiState.value = DashboardUiState.Error(ApiErrors.messageFor(response))
                }
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(
                    "Network error: ${e.localizedMessage ?: "Could not reach server"}"
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Everything one group needs for its reminders to be scheduled (F3, B-8).
     */
    data class GroupReminderData(
        val groupId: String,
        val occurrences: List<Occurrence>,
        val chores: List<Chore>,
    )

    /**
     * Board data for **every** group the user belongs to, plus their quiet
     * hours — enough for the screen to arm reminders across all of them.
     *
     * The board can only ever schedule for the group you happen to have open,
     * so a second household's turns went unmentioned until you visited it
     * (B-8). The dashboard is the one place that sees them all.
     */
    private val _reminderData = MutableStateFlow<List<GroupReminderData>>(emptyList())
    val reminderData: StateFlow<List<GroupReminderData>> = _reminderData.asStateFlow()

    private val _quietHours = MutableStateFlow(QuietHours.DEFAULT)
    val quietHours: StateFlow<QuietHours> = _quietHours.asStateFlow()

    /**
     * Fetches occurrences and chores for each group, in parallel.
     *
     * That is two requests per group — fine for a household app, where the
     * number of groups is one or two, and the alternative is an aggregate
     * endpoint that does not exist yet.
     *
     * Failures are silent by design. This drives reminders, not the screen: a
     * group whose board could not be fetched simply keeps whatever alarms it
     * already had, which is better than an error banner over a group list that
     * loaded perfectly well.
     */
    private suspend fun loadReminderData(groups: List<Group>) = coroutineScope {
        val me = runCatching { api.getMe() }.getOrNull()?.takeIf { it.isSuccessful }?.body()
        _quietHours.value = QuietHours.parse(me?.quietFrom, me?.quietTo)

        _reminderData.value = groups
            .map { group ->
                async {
                    val occurrences = runCatching { api.listOccurrences(group.id) }
                        .getOrNull()?.takeIf { it.isSuccessful }?.body()
                    val chores = runCatching { api.listChores(group.id) }
                        .getOrNull()?.takeIf { it.isSuccessful }?.body()
                    if (occurrences == null) null
                    else GroupReminderData(group.id, occurrences, chores.orEmpty())
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Load pending invites. Failures are silent: an unreachable invites endpoint
     * should not blank out a group list that loaded fine.
     */
    fun fetchInvites() {
        viewModelScope.launch {
            val response = runCatching { api.listMyInvites() }.getOrNull() ?: return@launch
            if (response.isSuccessful) {
                _invites.value = response.body().orEmpty()
            }
        }
    }

    /**
     * Accept or decline a direct invite.
     *
     * @param action "accept" or "decline" — the values the backend validates.
     */
    fun respondToInvite(inviteId: String, action: String, groupName: String) {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val response = api.respondToInvite(inviteId, InviteActionRequest(action))
                if (response.isSuccessful) {
                    _actionMessage.value =
                        if (action == "accept") "You joined $groupName" else "Invite declined"
                    // Accepting adds a membership, so both lists changed.
                    fetchInvites()
                    fetchGroups()
                } else {
                    _actionMessage.value = ApiErrors.messageFor(response)
                    // A 409/410 means our copy of the invite is stale — reload it.
                    fetchInvites()
                }
            } catch (e: Exception) {
                _actionMessage.value = networkMessage(e)
            } finally {
                _isWorking.value = false
            }
        }
    }

    /**
     * Join a group with a shared invite code.
     *
     * The backend distinguishes the failure modes for us — 404 unknown code,
     * 410 expired or used up, 409 already a member — and [ApiErrors] surfaces
     * whichever message came back rather than a generic "failed".
     */
    fun redeemCode(code: String) {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val response = api.redeemInviteCode(RedeemInviteRequest(code.trim()))
                if (response.isSuccessful) {
                    _actionMessage.value = response.body()?.message ?: "Joined group"
                    fetchGroups()
                    fetchInvites()
                } else {
                    _actionMessage.value = ApiErrors.messageFor(response)
                }
            } catch (e: Exception) {
                _actionMessage.value = networkMessage(e)
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    /**
     * Alias for [fetchGroups] — semantic name for pull-to-refresh / retry.
     * Also re-checks invites, since a teammate may have sent one meanwhile.
     */
    fun refresh() {
        fetchGroups()
        fetchInvites()
    }

    private fun networkMessage(e: Throwable) =
        "Network error: ${e.localizedMessage ?: "could not reach server"}"
}
