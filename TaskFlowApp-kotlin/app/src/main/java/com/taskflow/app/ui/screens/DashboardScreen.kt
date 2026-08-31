package com.taskflow.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.data.model.CreateGroupRequest
import com.taskflow.app.data.model.Group
import com.taskflow.app.data.model.InviteInfo
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * How often to look for new invites while the Dashboard is on screen.
 * Deliberately slower than the group activity feed: invites are occasional,
 * and each poll is a request for every user sitting on this screen.
 */
private const val INVITE_POLL_MILLIS = 20_000L

/**
 * Dashboard screen displaying the user's groups in a scrollable list.
 *
 * Features:
 *  - Material 3 Scaffold with TopAppBar and FAB
 *  - Three UI states: Loading, Success (group list or empty), Error (with retry)
 *  - "Create Group" dialog triggered by the FAB
 *  - Pull-to-refresh via retry button
 *
 * @param onGroupClick Navigates to GroupDetail with the group's ID.
 * @param onLogout Clears the token and navigates to login.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onGroupClick: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val invites by viewModel.invites.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showInvites by remember { mutableStateOf(false) }
    var showJoinByCode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // In exactly one household, the group list is a menu with one item — skip
    // it and land on the board. Fires once; Back then leaves you here.
    val autoOpenGroupId by viewModel.autoOpenGroupId.collectAsState()
    LaunchedEffect(autoOpenGroupId) {
        autoOpenGroupId?.let { id ->
            viewModel.consumeAutoOpen()
            onGroupClick(id)
        }
    }

    // An invite that arrives while you're sitting here should appear on its
    // own. A slower cadence than the activity feed on purpose — invites are
    // rare, and this also refreshes immediately whenever the app is resumed.
    PollWhileResumed(intervalMillis = INVITE_POLL_MILLIS) {
        viewModel.fetchInvites()
    }

    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    // Close the invites sheet once the last one is dealt with.
    LaunchedEffect(invites) {
        if (invites.isEmpty()) showInvites = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "My Groups",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    // Only shown when something is waiting — an empty inbox
                    // icon is noise on a screen this small.
                    if (invites.isNotEmpty()) {
                        IconButton(onClick = { showInvites = true }) {
                            BadgedBox(
                                badge = { Badge { Text("${invites.size}") } },
                            ) {
                                Icon(
                                    Icons.Default.MailOutline,
                                    contentDescription = "${invites.size} pending invites",
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Join with a code") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                            onClick = { showMenu = false; showJoinByCode = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                            onClick = { showMenu = false; onLogout() },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Group") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // Pull-to-refresh: the toolbar icon stays for discoverability, but
            // the swipe is what people actually reach for on a list.
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = uiState) {
                    is DashboardUiState.Loading -> LoadingState()
                    is DashboardUiState.Error -> ErrorState(
                        message = state.message,
                        onRetry = { viewModel.refresh() },
                    )
                    is DashboardUiState.Success -> {
                        if (state.groups.isEmpty()) {
                            EmptyState(
                                onCreateGroup = { showCreateDialog = true },
                                onJoinWithCode = { showJoinByCode = true },
                            )
                        } else {
                            GroupList(
                                groups = state.groups,
                                onGroupClick = onGroupClick,
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── Create Group Dialog ───
    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onGroupCreated = {
                showCreateDialog = false
                viewModel.refresh()
            },
        )
    }

    // ─── Pending invites ───
    if (showInvites && invites.isNotEmpty()) {
        PendingInvitesDialog(
            invites = invites,
            onDismiss = { showInvites = false },
            onRespond = { invite, action ->
                viewModel.respondToInvite(invite.id, action, invite.groupName)
            },
        )
    }

    // ─── Join with a shared code ───
    if (showJoinByCode) {
        JoinWithCodeDialog(
            onDismiss = { showJoinByCode = false },
            onJoin = { code ->
                showJoinByCode = false
                viewModel.redeemCode(code)
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Sub-components
// ═══════════════════════════════════════════════════════════════

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading your groups…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EmptyState(onCreateGroup: () -> Unit, onJoinWithCode: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No groups yet",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Create a group or ask a teammate for an invite.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onCreateGroup) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create your first group")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // The other half of "ask a teammate for an invite" — without this,
            // someone handed a code has nowhere to type it.
            TextButton(onClick = onJoinWithCode) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Join with a code")
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Group>,
    onGroupClick: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // Clears the FAB so the last row is reachable.
            bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(groups, key = { it.id }) { group ->
            GroupCard(
                group = group,
                onClick = { onGroupClick(group.id) },
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: Group,
    onClick: () -> Unit,
) {
    // Deterministic color from group ID for the avatar
    val avatarColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    val avatarColor = avatarColors[group.id.hashCode().and(0x7FFFFFFF) % avatarColors.size]

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ─── Group Avatar ───
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarColor.copy(alpha = .15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Group,
                    contentDescription = null,
                    tint = avatarColor,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // ─── Text ───
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (group.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatCreatedAt(group.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                )
            }

            // ─── Chevron ───
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f),
            )
        }
    }
}

/**
 * Format an OffsetDateTime to a human-friendly string.
 */
private fun formatCreatedAt(date: OffsetDateTime?): String {
    if (date == null) return ""
    return try {
        date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    } catch (_: Exception) {
        ""
    }
}

// ═══════════════════════════════════════════════════════════════
// Invites
// ═══════════════════════════════════════════════════════════════

/**
 * Pending direct invites, with accept/decline per row.
 *
 * Stays open across responses so a user with several invites can work through
 * them; the caller closes it once the list empties.
 */
@Composable
private fun PendingInvitesDialog(
    invites: List<InviteInfo>,
    onDismiss: () -> Unit,
    onRespond: (InviteInfo, String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitations", fontWeight = FontWeight.Bold) },
        text = {
            // Scrollable and height-capped. Without this, AlertDialog clips its
            // content and everything past roughly the sixth invite becomes
            // unreachable — no scroll, no indication anything is missing.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                invites.forEach { invite ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = invite.groupName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            text = "Invited by ${invite.invitedBy}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRespond(invite, "accept") }) {
                                Text("Accept")
                            }
                            TextButton(onClick = { onRespond(invite, "decline") }) {
                                Text(
                                    text = "Decline",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Redeem a shared invite code.
 *
 * Codes are 12 hex characters from the backend's `generateCode`. Input is
 * lower-cased and trimmed because they get copied out of chat apps, which love
 * to capitalise the first letter.
 */
@Composable
private fun JoinWithCodeDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a group", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste the invite code a teammate shared with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim().lowercase() },
                    label = { Text("Invite code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onJoin(code) },
                enabled = code.isNotBlank(),
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Create Group Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onGroupCreated: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Create Group", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )
                AnimatedVisibility(visible = error != null) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Group name is required"
                        return@Button
                    }
                    isLoading = true
                    error = null
                    scope.launch {
                        try {
                            val response = RetrofitClient.getInstance()
                                .groupApi
                                .createGroup(CreateGroupRequest(name.trim(), description.trim()))
                            if (response.isSuccessful) {
                                onGroupCreated()
                            } else {
                                // Same treatment as the auth screens: show the
                                // backend's message, not the raw JSON body.
                                error = ApiErrors.messageFor(response)
                            }
                        } catch (e: Exception) {
                            error = "Network error: ${e.localizedMessage}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text("Cancel")
            }
        },
    )
}
