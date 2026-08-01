package com.taskflow.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Group
import com.taskflow.app.data.model.InviteActionRequest
import com.taskflow.app.data.model.InviteInfo
import com.taskflow.app.data.model.RedeemInviteRequest
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
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
