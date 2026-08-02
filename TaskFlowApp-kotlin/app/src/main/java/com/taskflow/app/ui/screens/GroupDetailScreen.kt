package com.taskflow.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    // Task pending deletion — deleting is irreversible, so it goes via a confirm.
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    // Detail sheet holds an *id*, not a Task. The Task is looked up from state
    // on each recomposition, so an edit (ours or a teammate's) is reflected
    // instead of the sheet showing the snapshot it opened with.
    var detailTaskId by remember { mutableStateOf<String?>(null) }
    val detailTask = detailTaskId?.let { id -> state.tasks.firstOrNull { it.id == id } }

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

    // Back should cancel the selection, not abandon the screen — that's what
    // every other multi-select on the platform does.
    BackHandler(enabled = inSelectionMode) {
        selectedTasks = emptySet()
    }

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
                            onOpenDetail = { detailTaskId = it.id },
                            onCreate = { showCreateTask = true },
                        )

                        1 -> ActivityList(state.activity)

                        2 -> MemberList(state.members)
                    }
                }
            }
        }
    }

    // Detail sheet. Closes itself if the task disappears — deleted here, or by
    // a teammate while it was open.
    detailTask?.let { task ->
        TaskDetailSheet(
            task = task,
            members = state.members,
            onDismiss = { detailTaskId = null },
            onSaveText = { title, description ->
                viewModel.updateTaskText(task.id, title, description)
            },
            onSetStatus = { viewModel.setTaskStatus(task.id, it) },
            onSetAssignee = { viewModel.setTaskAssignee(task.id, it) },
            onDelete = { taskToDelete = task },
        )
    }
    LaunchedEffect(detailTaskId, state.tasks) {
        if (detailTaskId != null && state.tasks.none { it.id == detailTaskId }) {
            detailTaskId = null
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

    // Deleting a task is irreversible — there is no undelete endpoint, so a
    // snackbar-with-Undo would be a lie. Confirm up front instead.
    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("Delete this task?", fontWeight = FontWeight.Bold) },
            text = {
                Text("\"${task.title}\" will be removed for everyone in the group. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    taskToDelete = null
                    // If the confirm came from inside the detail sheet, that
                    // sheet is now showing a task that no longer exists.
                    detailTaskId = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) { Text("Cancel") }
            },
        )
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
    onOpenDetail: (Task) -> Unit,
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
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // Clears the FAB so the last row is reachable.
            bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                assigneeName = members.firstOrNull { it.id == task.assignedTo }?.username,
                isSelected = task.id in selected,
                anySelected = selected.isNotEmpty(),
                onToggleSelect = { onToggleSelect(task.id) },
                onOpenDetail = { onOpenDetail(task) },
            )
        }
    }
}

/**
 * One row in the task list.
 *
 * A tap opens the detail sheet. It used to cycle the status in place, which was
 * undiscoverable — nothing on the card said it was tappable, let alone what
 * tapping would do — and a mis-tap silently marked work as done with no undo.
 * Editing now happens somewhere the user can see what they're changing.
 *
 * Delete lives in the sheet too: a destructive control on every row of a list
 * is a lot of accidental-tap surface for an action with no undo.
 */
@Composable
private fun TaskCard(
    task: Task,
    assigneeName: String?,
    isSelected: Boolean,
    anySelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            // Once anything is selected, a plain tap extends the selection
            // rather than opening one task — standard multi-select behaviour.
            .clickable { if (anySelected) onToggleSelect() else onOpenDetail() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
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
                        Text(
                            text = assigneeName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Affordance that the row leads somewhere.
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f),
            )
        }
    }
}

/**
 * Detail view for a single task, as a modal bottom sheet.
 *
 * This is where a task is actually read and edited: the full description
 * instead of two truncated lines, an explicit status control instead of a
 * hidden tap-to-cycle, assignment, and delete.
 *
 * Text edits are staged and saved with a button, because typing a title should
 * not fire a request per keystroke. Status and assignee apply immediately —
 * they're single, cheap, obviously-reversible choices.
 *
 * [task] is re-derived from the ViewModel's list by the caller, so the sheet
 * reflects a teammate's concurrent change rather than a stale snapshot taken
 * when it opened.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailSheet(
    task: Task,
    members: List<MemberInfo>,
    onDismiss: () -> Unit,
    onSaveText: (title: String?, description: String?) -> Unit,
    onSetStatus: (String) -> Unit,
    onSetAssignee: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Re-seed the draft when a different task is opened, or when this one is
    // changed underneath us by someone else.
    var draftTitle by remember(task.id, task.title) { mutableStateOf(task.title) }
    var draftDescription by remember(task.id, task.description) { mutableStateOf(task.description) }

    val titleChanged = draftTitle.trim() != task.title && draftTitle.isNotBlank()
    val descriptionChanged = draftDescription.trim() != task.description
    val dirty = titleChanged || descriptionChanged

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = draftTitle,
                onValueChange = { draftTitle = it },
                label = { Text("Title") },
                isError = draftTitle.isBlank(),
                supportingText = if (draftTitle.isBlank()) {
                    { Text("Title can't be empty") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draftDescription,
                onValueChange = { draftDescription = it },
                label = { Text("Description") },
                placeholder = { Text("Add more detail…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(visible = dirty) {
                Button(
                    onClick = {
                        onSaveText(
                            draftTitle.trim().takeIf { titleChanged },
                            draftDescription.trim().takeIf { descriptionChanged },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save changes") }
            }

            HorizontalDivider()

            Text("Status", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TASK_STATUSES.forEach { status ->
                    FilterChip(
                        selected = task.status == status,
                        onClick = { if (task.status != status) onSetStatus(status) },
                        label = { Text(statusLabel(status)) },
                    )
                }
            }

            Text("Assigned to", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = task.assignedTo == null,
                    onClick = { if (task.assignedTo != null) onSetAssignee(null) },
                    label = { Text("Nobody") },
                )
                members.forEach { member ->
                    FilterChip(
                        selected = task.assignedTo == member.id,
                        onClick = { if (task.assignedTo != member.id) onSetAssignee(member.id) },
                        label = { Text(member.username) },
                    )
                }
            }

            HorizontalDivider()

            // updated_at is null for rows written before that column existed,
            // hence the fallback rather than an empty line.
            Text(
                text = buildString {
                    append("Created ${formatStamp(task.createdAt)}")
                    task.updatedAt?.let { append("  ·  updated ${formatStamp(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete task")
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
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // Clears the FAB so the last row is reachable.
            bottom = 88.dp,
        ),
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
                    humaniseDetail(event)?.let { detail ->
                        Text(
                            text = detail,
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

/**
 * Turns the backend's `detail` string into something a person would say.
 *
 * The backend writes `detail` for machines as much as humans — `task_updated`
 * carries a comma-separated list of changed fields like
 * `status=done, assigned_to=cleared`, and the bulk event carries
 * `2 task(s) → done`. Rendering that raw made the feed read like a debug log.
 *
 * For created/deleted events `detail` is just the task title, which already
 * reads fine and is passed through untouched.
 *
 * Returns null when there is nothing worth showing on a second line.
 */
private fun humaniseDetail(e: ActivityEvent): String? {
    val detail = e.detail.trim()
    if (detail.isEmpty()) return null

    return when (e.eventType) {
        "task_created", "task_deleted" -> detail

        "task_updated" -> detail
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { field ->
                when {
                    field == "title" -> "renamed it"
                    field == "description" -> "edited the description"
                    field == "assigned_to" -> "changed who it's assigned to"
                    field == "assigned_to=cleared" -> "removed the assignee"
                    field == "due_date" -> "changed the deadline"
                    field == "due_date=cleared" -> "removed the deadline"
                    field.startsWith("status=") ->
                        "moved it to ${statusLabel(field.removePrefix("status="))}"
                    else -> null
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.replaceFirstChar { it.uppercase() }

        "tasks_bulk_updated" -> {
            // "2 task(s) → done"
            val count = detail.substringBefore(" ").toIntOrNull()
            val status = detail.substringAfterLast("→").trim()
            if (count != null && status.isNotEmpty()) {
                "${if (count == 1) "1 task" else "$count tasks"} → ${statusLabel(status)}"
            } else {
                detail
            }
        }

        else -> detail
    }
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
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // Clears the FAB so the last row is reachable.
            bottom = 88.dp,
        ),
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
                    RoleChip(member.role)
                }
            }
        }
    }
}

/**
 * Role badge. Separate from [StatusChip] because roles and task statuses are
 * different vocabularies — sharing the chip meant "owner" rendered raw and
 * lowercase, styled as if it were a task state.
 */
@Composable
private fun RoleChip(role: String) {
    val color = when (role) {
        "owner" -> MaterialTheme.colorScheme.primary
        "admin" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = .15f), shape = MaterialTheme.shapes.small) {
        Text(
            text = role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Dialogs
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
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
            // Scrollable: a large group's assignee chips can otherwise push the
            // buttons off the bottom of the dialog.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                    // FlowRow, not a fixed Row: this used to render only the
                    // first three members, which made everyone else in a larger
                    // group silently unassignable.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = assignee == null,
                            onClick = { assignee = null },
                            label = { Text("Nobody") },
                        )
                        members.forEach { member ->
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

/**
 * Shows a generated invite code with copy and share actions.
 *
 * The code is 12 hex characters. Displaying it as plain text meant the user had
 * to read it off the screen and retype it into a chat app — error-prone, and
 * the whole point of the code is that it travels.
 */
@Composable
private fun InviteCodeDialog(code: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

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
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) "Copied" else "Copy")
                    }
                    FilledTonalButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join my TaskFlow group with this code: $code",
                            )
                        }
                        context.startActivity(Intent.createChooser(share, "Share invite code"))
                    }) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

/** Absolute date for the detail sheet, where "23h ago" is less useful than a date. */
private fun formatStamp(time: OffsetDateTime?): String {
    if (time == null) return "unknown"
    return try {
        time.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
    } catch (_: Exception) {
        "unknown"
    }
}

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
