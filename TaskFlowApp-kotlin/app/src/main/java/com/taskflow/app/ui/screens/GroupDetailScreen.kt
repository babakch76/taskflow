package com.taskflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.data.model.ActivityEvent
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Task
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** How often the activity feed is polled while this screen is on screen. */
private const val ACTIVITY_POLL_MILLIS = 5_000L

private val TASK_STATUSES = listOf("todo", "in_progress", "done")

/**
 * Group detail: tasks, activity feed, members and invites for one group.
 *
 * Three tabs rather than one long scroll, because the activity feed is
 * append-only and would otherwise push the task list off screen.
 *
 * @param groupId From the navigation arguments.
 * @param onBack Returns to the dashboard. Also called after leaving the group,
 *   since the group may no longer exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: String,
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(factory = GroupDetailViewModel.Factory(groupId)),
) {
    val state by viewModel.uiState.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateTask by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // Multi-select is pure UI state, so it lives here rather than in the VM.
    var selectedTasks by remember { mutableStateOf(setOf<String>()) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Poll the activity feed while this screen is composed. Tying it to the
    // composition means it stops automatically on navigate-away; it does keep
    // running if the app is backgrounded with this screen on top, which is
    // acceptable for a demo but would want a lifecycle-aware scope in anger.
    LaunchedEffect(groupId) {
        while (true) {
            delay(ACTIVITY_POLL_MILLIS)
            viewModel.pollActivity()
        }
    }

    // Leaving may have deleted the group, so get off this screen.
    LaunchedEffect(state.hasLeft) {
        if (state.hasLeft) onBack()
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val inSelectionMode = selectedTasks.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (inSelectionMode) "${selectedTasks.size} selected"
                        else state.group?.name ?: "Group",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (inSelectionMode) selectedTasks = emptySet() else onBack() }
                    ) {
                        Icon(
                            if (inSelectionMode) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (inSelectionMode) "Clear selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (!inSelectionMode) {
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
                                text = { Text("Invite by username") },
                                leadingIcon = { Icon(Icons.Default.PersonAdd, null) },
                                onClick = { showMenu = false; showInvite = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Create invite code") },
                                leadingIcon = { Icon(Icons.Default.Link, null) },
                                onClick = { showMenu = false; viewModel.generateInviteCode() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Leave group") },
                                onClick = { showMenu = false; confirmLeave = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (selectedTab == 0 && !inSelectionMode && state.group != null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateTask = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Task") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                state.isLoading -> LoadingBlock("Loading group…")

                state.error != null -> GroupErrorBlock(
                    message = state.error ?: "",
                    onRetry = { viewModel.refresh() },
                )

                else -> {
                    state.group?.let { ProgressHeader(it.totalTasks, it.doneTasks, it.progress) }

                    // Bulk action bar replaces the tab row while selecting.
                    if (inSelectionMode) {
                        BulkActionBar(
                            onApply = { status ->
                                viewModel.bulkSetStatus(selectedTasks, status)
                                selectedTasks = emptySet()
                            },
                        )
                    } else {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Tasks (${state.tasks.size})") },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Activity") },
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("Members (${state.members.size})") },
                            )
                        }
                    }

                    when (selectedTab) {
                        0 -> TaskList(
                            tasks = state.tasks,
                            members = state.members,
                            selected = selectedTasks,
                            onToggleSelect = { id ->
                                selectedTasks = if (id in selectedTasks) selectedTasks - id
                                else selectedTasks + id
                            },
                            onCycleStatus = { task ->
                                val next = TASK_STATUSES[
                                    (TASK_STATUSES.indexOf(task.status) + 1) % TASK_STATUSES.size
                                ]
                                viewModel.setTaskStatus(task.id, next)
                            },
                            onUnassign = { viewModel.setTaskAssignee(it.id, null) },
                            onDelete = { viewModel.deleteTask(it.id) },
                            onCreate = { showCreateTask = true },
                        )

                        1 -> ActivityList(state.activity)

                        2 -> MemberList(state.members)
                    }
                }
            }
        }
    }

    if (showCreateTask) {
        CreateTaskDialog(
            members = state.members,
            onDismiss = { showCreateTask = false },
            onCreate = { title, desc, assignee ->
                showCreateTask = false
                viewModel.createTask(title, desc, assignee)
            },
        )
    }

    if (showInvite) {
        InviteByUsernameDialog(
            onDismiss = { showInvite = false },
            onInvite = { showInvite = false; viewModel.inviteByUsername(it) },
        )
    }

    state.inviteCode?.let { code ->
        InviteCodeDialog(code = code, onDismiss = { viewModel.dismissInviteCode() })
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You'll lose access to its tasks. If you're the last member, " +
                        "the group and everything in it is deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; viewModel.leaveGroup() }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Header / states
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ProgressHeader(total: Int, done: Int, progress: Double) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (total == 0) "No tasks yet" else "$done of $total done",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun LoadingBlock(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupErrorBlock(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "Couldn't load this group",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EmptyBlock(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let { Spacer(Modifier.height(24.dp)); it() }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Tasks
// ═══════════════════════════════════════════════════════════════

@Composable
private fun BulkActionBar(onApply: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Move to:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TASK_STATUSES.forEach { status ->
                FilledTonalButton(
                    onClick = { onApply(status) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(statusLabel(status), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    members: List<MemberInfo>,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onCycleStatus: (Task) -> Unit,
    onUnassign: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onCreate: () -> Unit,
) {
    if (tasks.isEmpty()) {
        EmptyBlock(
            title = "No tasks yet",
            subtitle = "Add the first one and it'll show up for everyone in the group.",
        ) {
            FilledTonalButton(onClick = onCreate) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New task")
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                assigneeName = members.firstOrNull { it.id == task.assignedTo }?.username,
                isSelected = task.id in selected,
                anySelected = selected.isNotEmpty(),
                onToggleSelect = { onToggleSelect(task.id) },
                onCycleStatus = { onCycleStatus(task) },
                onUnassign = { onUnassign(task) },
                onDelete = { onDelete(task) },
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    assigneeName: String?,
    isSelected: Boolean,
    anySelected: Boolean,
    onToggleSelect: () -> Unit,
    onCycleStatus: () -> Unit,
    onUnassign: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            // Once anything is selected, a plain tap extends the selection
            // rather than acting on one task — standard multi-select behaviour.
            .clickable { if (anySelected) onToggleSelect() else onCycleStatus() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusChip(task.status)
                    if (assigneeName != null) {
                        AssistChip(
                            onClick = onUnassign,
                            label = { Text(assigneeName, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, "Unassign", Modifier.size(14.dp))
                            },
                        )
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete task",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = .8f),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        "done" -> MaterialTheme.colorScheme.tertiary
        "in_progress" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = .15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun statusLabel(status: String) = when (status) {
    "todo" -> "To do"
    "in_progress" -> "In progress"
    "done" -> "Done"
    else -> status
}

// ═══════════════════════════════════════════════════════════════
// Activity feed
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ActivityList(events: List<ActivityEvent>) {
    if (events.isEmpty()) {
        EmptyBlock(
            title = "Nothing has happened yet",
            subtitle = "Task changes, joins and invites show up here as they happen.",
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(events, key = { it.id }) { event ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(eventColor(event.eventType)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = describeEvent(event),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (event.detail.isNotBlank()) {
                        Text(
                            text = event.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = relativeTime(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
        }
    }
}

/** Event types come from handlers/activity.go — keep these in sync. */
private fun describeEvent(e: ActivityEvent): String = when (e.eventType) {
    "task_created" -> "${e.actorUsername} added a task"
    "task_updated" -> "${e.actorUsername} updated a task"
    "task_deleted" -> "${e.actorUsername} deleted a task"
    "tasks_bulk_updated" -> "${e.actorUsername} moved several tasks"
    "member_joined" -> "${e.actorUsername} joined the group"
    "member_left" -> "${e.actorUsername} left the group"
    "invite_accepted" -> "${e.actorUsername} accepted an invite"
    else -> "${e.actorUsername}: ${e.eventType}"
}

@Composable
private fun eventColor(type: String) = when (type) {
    "task_created" -> MaterialTheme.colorScheme.primary
    "task_deleted", "member_left" -> MaterialTheme.colorScheme.error
    "member_joined", "invite_accepted" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

// ═══════════════════════════════════════════════════════════════
// Members
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MemberList(members: List<MemberInfo>) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(members, key = { it.id }) { member ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = member.username.take(1).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.username,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            text = member.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(member.role)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Dialogs
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CreateTaskDialog(
    members: List<MemberInfo>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf<MemberInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Task", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (members.isNotEmpty()) {
                    Text("Assign to", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = assignee == null,
                            onClick = { assignee = null },
                            label = { Text("Nobody") },
                        )
                        members.take(3).forEach { member ->
                            FilterChip(
                                selected = assignee?.id == member.id,
                                onClick = { assignee = member },
                                label = { Text(member.username) },
                            )
                        }
                    }
                }
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
            Button(onClick = {
                if (title.isBlank()) {
                    error = "Title is required"
                    return@Button
                }
                onCreate(title, description, assignee?.id)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InviteByUsernameDialog(onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var username by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite a teammate", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onInvite(username) },
                enabled = username.isNotBlank(),
            ) { Text("Send invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InviteCodeDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite code", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Anyone with this code can join the group. It expires in 48 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

/** Coarse "how long ago" for the activity feed. */
private fun relativeTime(time: OffsetDateTime?): String {
    if (time == null) return ""
    return try {
        val seconds = Duration.between(time, OffsetDateTime.now()).seconds
        when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            seconds < 604_800 -> "${seconds / 86_400}d"
            else -> time.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    } catch (_: Exception) {
        ""
    }
}
