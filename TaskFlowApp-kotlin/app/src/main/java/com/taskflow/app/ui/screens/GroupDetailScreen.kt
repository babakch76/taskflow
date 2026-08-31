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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.taskflow.app.TaskFlowApp
import com.taskflow.app.data.model.ActivityEvent
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.ScheduleType
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.isOpen
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** How often the activity feed is polled while this screen is on screen. */
private const val ACTIVITY_POLL_MILLIS = 5_000L

private val TASK_STATUSES = listOf("todo", "in_progress", "done")

/**
 * Group detail: tasks, activity feed, members and invites for one group.
 *
 * Four tabs rather than one long scroll: the activity feed is append-only and
 * would otherwise push the task list off screen, and the calendar needs the
 * full width to be readable.
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
    var showCreateChore by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // Task pending deletion — deleting is irreversible, so it goes via a confirm.
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    // Detail sheet holds an *id*, not a Task. The Task is looked up from state
    // on each recomposition, so an edit (ours or a teammate's) is reflected
    // instead of the sheet showing the snapshot it opened with.
    var detailTaskId by remember { mutableStateOf<String?>(null) }
    val detailTask = detailTaskId?.let { id -> state.tasks.firstOrNull { it.id == id } }

    // Which member row is "me" — needed so the owner isn't offered a demote
    // button on their own row.
    val context = LocalContext.current
    val myUserId = remember {
        (context.applicationContext as? TaskFlowApp)?.tokenManager?.getUserId()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Activity feed poll — the awareness loop. Lifecycle-aware, so it pauses
    // when the app is backgrounded and ticks immediately on return rather than
    // showing stale data for up to a full interval.
    PollWhileResumed(intervalMillis = ACTIVITY_POLL_MILLIS, key = groupId) {
        viewModel.pollActivity()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.group?.name ?: "Group",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    run {
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
            if (selectedTab == 0 && state.group != null) {
                // A chore is the app's main subject now, so it gets the primary
                // action; one-off tasks keep a smaller button beside it.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallFloatingActionButton(
                        onClick = { showCreateTask = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New one-off task")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { showCreateChore = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("New Chore") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
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

                    run {
                        // Scrollable, not fixed: four labels with counts don't
                        // fit a phone's width, and a fixed TabRow wraps
                        // "Members (4)" onto two lines rather than shrinking.
                        ScrollableTabRow(
                            selectedTabIndex = selectedTab,
                            edgePadding = 12.dp,
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                // Both halves of the board, or the count would
                                // disagree with the number of rows below it.
                                text = { Text("Board (${state.tasks.size + state.occurrences.size})") },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Calendar") },
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("Activity") },
                            )
                            Tab(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                text = { Text("Members (${state.members.size})") },
                            )
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    when (selectedTab) {
                        0 -> TaskList(
                            // Chore occurrences first: they are the model the
                            // app is moving to, and tasks are the legacy
                            // one-off shape still carried alongside them.
                            rows = state.occurrences.map { BoardRow.OccurrenceRow(it) } +
                                state.tasks.map { BoardRow.TaskRow(it) },
                            members = state.members,
                            myUserId = myUserId,
                            onOpenDetail = { row ->
                                // Only tasks have a detail sheet so far. The
                                // occurrence sheet arrives with F4's done-line,
                                // which is what there would be to show.
                                if (row is BoardRow.TaskRow) detailTaskId = row.task.id
                            },
                            onToggleDone = { row ->
                                when (row) {
                                    is BoardRow.TaskRow -> viewModel.setTaskStatus(
                                        row.task.id,
                                        if (row.isOpen) "done" else "todo",
                                    )
                                    is BoardRow.OccurrenceRow ->
                                        viewModel.toggleOccurrenceDone(row.occurrence)
                                }
                            },
                            onCreate = { showCreateChore = true },
                        )

                        1 -> CalendarTab(
                            tasks = state.tasks,
                            members = state.members,
                            myUserId = myUserId,
                            onOpenDetail = { detailTaskId = it.id },
                            onToggleDone = { task ->
                                viewModel.setTaskStatus(
                                    task.id,
                                    if (task.isOpen) "done" else "todo",
                                )
                            },
                        )

                        2 -> ActivityList(state.activity)

                        3 -> MemberList(
                            members = state.members,
                            myUserId = myUserId,
                        )
                    }
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
            onSetDueDate = { viewModel.setTaskDueDate(task.id, it) },
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

    if (showCreateChore) {
        CreateChoreDialog(
            members = state.members,
            onDismiss = { showCreateChore = false },
            onCreate = { name, doneLine, scheduleType, intervalDays, weekdays, rotation ->
                showCreateChore = false
                viewModel.createChore(name, doneLine, scheduleType, intervalDays, weekdays, rotation)
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

/**
 * One row on the board, from either of the two shapes it can hold.
 *
 * F2 introduces chore occurrences, but tasks predate them and are already the
 * spec's "one-off" type, so both live on the board together rather than one
 * being hidden or migrated away. The board's question — "what still needs
 * doing and whose is it" — is the same for both, so the sections and the row
 * are shared and only the source differs.
 */
private sealed interface BoardRow {
    val key: String
    val title: String
    val assignedTo: String?
    val dueDate: OffsetDateTime?
    val isOpen: Boolean
    val doneBy: String?
    val doneAt: OffsetDateTime?

    data class TaskRow(val task: Task) : BoardRow {
        override val key get() = "task-${task.id}"
        override val title get() = task.title
        override val assignedTo get() = task.assignedTo
        override val dueDate get() = task.dueDate
        override val isOpen get() = task.isOpen
        override val doneBy get() = task.doneBy
        override val doneAt get() = task.doneAt
    }

    data class OccurrenceRow(val occurrence: Occurrence) : BoardRow {
        override val key get() = "occ-${occurrence.id}"
        override val title get() = occurrence.choreName
        // Never null: every occurrence has exactly one name on it.
        override val assignedTo get() = occurrence.assignedTo
        override val dueDate get() = occurrence.dueDate
        override val isOpen get() = occurrence.isOpen
        override val doneBy get() = occurrence.doneBy
        override val doneAt get() = occurrence.doneAt
    }
}

/**
 * The Board — F1, now reading the chore model as well.
 *
 * Three sections, in this order: **Yours**, **Others**, **Done**. Grouping by
 * ownership rather than by status is the whole point: the question the board
 * answers is "what still needs doing and whose is it", so the first thing you
 * should see is your own name's worth of work.
 *
 * Unassigned tasks sit under Others. The chore model has no such thing —
 * "everything on the board always has exactly one name on it" — but tasks
 * created before that rule exists still can, and hiding them would lose them.
 */
@Composable
private fun TaskList(
    rows: List<BoardRow>,
    members: List<MemberInfo>,
    myUserId: String?,
    onOpenDetail: (BoardRow) -> Unit,
    onToggleDone: (BoardRow) -> Unit,
    onCreate: () -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyBlock(
            title = "Nothing on the board",
            subtitle = "Add the first chore and it'll show up for everyone in the group.",
        ) {
            FilledTonalButton(onClick = onCreate) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New chore")
            }
        }
        return
    }

    val sections = remember(rows, myUserId) {
        val open = rows.filter { it.isOpen }
        listOf(
            "Yours" to open.filter { it.assignedTo != null && it.assignedTo == myUserId },
            "Others" to open.filter { it.assignedTo == null || it.assignedTo != myUserId },
            "Done" to rows.filterNot { it.isOpen },
        ).filter { it.second.isNotEmpty() }
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
        sections.forEach { (label, rows) ->
            item(key = "header-$label") {
                Text(
                    text = "$label · ${rows.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(rows, key = { it.key }) { row ->
                TaskCard(
                    row = row,
                    assigneeName = members.firstOrNull { it.id == row.assignedTo }?.username,
                    doneByName = members.firstOrNull { it.id == row.doneBy }?.username,
                    myUserId = myUserId,
                    onOpenDetail = { onOpenDetail(row) },
                    onToggleDone = { onToggleDone(row) },
                )
            }
        }
    }
}

/**
 * How long after marking something done you can still take it back. Spec F1:
 * "Undo within 10 minutes by the person who marked it."
 */
private val UNDO_WINDOW: Duration = Duration.ofMinutes(10)

/**
 * One row on the board.
 *
 * The checkbox marks done in a single tap, per F1 — and **anyone** may tick
 * **anything**, because "I just did it myself" is the common reality and the
 * board records it rather than fighting it. The assignee's name stays on the
 * row; who actually did it is kept separately.
 *
 * Tapping the row opens the detail sheet. Past-due-and-open gets a small amber
 * dot and the date as it is — never red, never a day count, never a badge
 * (hard constraint 3). The date is information enough.
 */
@Composable
private fun TaskCard(
    row: BoardRow,
    assigneeName: String?,
    doneByName: String?,
    myUserId: String?,
    onOpenDetail: () -> Unit,
    onToggleDone: () -> Unit,
) {
    val overdue = isOverdue(row)
    // Undo is offered only to the person who ticked it, and only briefly.
    // Anything older is history, and history is not editable from the board.
    val canUndo = !row.isOpen &&
        row.doneBy != null && row.doneBy == myUserId &&
        row.doneAt?.let { Duration.between(it, OffsetDateTime.now()) < UNDO_WINDOW } == true

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = !row.isOpen,
                // Ticking is always allowed; unticking only inside the window.
                enabled = row.isOpen || canUndo,
                onCheckedChange = { onToggleDone() },
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Dimmed rather than struck through: done work stays
                    // readable, it just stops asking for attention.
                    color = if (row.isOpen) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (overdue) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(overdueColor()),
                        )
                    }
                    row.dueDate?.let {
                        Text(
                            text = formatDueDate(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overdue) overdueColor()
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val who = if (row.isOpen) assigneeName else doneByName
                    who?.let {
                        Text(
                            text = if (row.isOpen) it else "done by $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row is BoardRow.TaskRow && row.task.status == "in_progress") {
                        StatusChip(row.task.status)
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
    onSetDueDate: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        DueDatePickerDialog(
            initial = task.dueDate,
            onDismiss = { showDatePicker = false },
            onPicked = { iso ->
                showDatePicker = false
                onSetDueDate(iso)
            },
        )
    }

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

            // ─── Deadline ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Deadline", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = task.dueDate?.let { formatDueDate(it) } ?: "No deadline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            task.dueDate == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            isOverdue(task) -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Row {
                    if (task.dueDate != null) {
                        IconButton(onClick = { onSetDueDate(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove deadline")
                        }
                    }
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = if (task.dueDate == null) "Set deadline"
                            else "Change deadline",
                        )
                    }
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

/**
 * Amber for past-due, in both themes.
 *
 * Hard constraint 3: "No red overdue states, late counters, or shame badges —
 * dim/amber at most." Material 3's scheme has no warning slot, so these are
 * explicit; `colorScheme.error` is deliberately not used anywhere overdue is
 * shown, because red reads as failure and lateness here is not a failure.
 */
private val AmberOnLight = androidx.compose.ui.graphics.Color(0xFF9A6700)
private val AmberOnDark = androidx.compose.ui.graphics.Color(0xFFE3B341)

@Composable
private fun overdueColor(): androidx.compose.ui.graphics.Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) AmberOnDark else AmberOnLight

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
// Calendar
// ═══════════════════════════════════════════════════════════════

/**
 * Month view of the group's deadlines.
 *
 * A month grid rather than an agenda list, because the question this answers is
 * "how is the work spread out" — where the crunch weeks are, which is exactly
 * what a list of dates in order hides.
 *
 * Days carry a dot per task, coloured by state: overdue in error, done in
 * tertiary, otherwise primary. Tapping a day lists its tasks underneath.
 * Tasks with no deadline aren't on the calendar at all, so their count is
 * reported at the bottom instead of being silently dropped.
 */
@Composable
private fun CalendarTab(
    tasks: List<Task>,
    members: List<MemberInfo>,
    myUserId: String?,
    onOpenDetail: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    // Deadline day (in the viewer's zone) → tasks due that day.
    val byDay = remember(tasks, zone) {
        tasks.filter { it.dueDate != null }
            .groupBy { it.dueDate!!.atZoneSameInstant(zone).toLocalDate() }
    }
    val undated = remember(tasks) { tasks.count { it.dueDate == null } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 88.dp),
    ) {
        // ─── Month switcher ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { visibleMonth = visibleMonth.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous month")
            }
            Text(
                text = visibleMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            IconButton(onClick = { visibleMonth = visibleMonth.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next month")
            }
        }

        // ─── Weekday headings ───
        // Starts on the locale's own first day, so this reads correctly whether
        // the user's week begins on Monday or Sunday.
        val firstDayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                Text(
                    text = firstDayOfWeek.plus(i.toLong())
                        .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ─── Day grid ───
        val firstOfMonth = visibleMonth.atDay(1)
        // How many blanks before the 1st, given where this locale's week starts.
        val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val totalCells = leadingBlanks + visibleMonth.lengthOfMonth()
        val rows = (totalCells + 6) / 7

        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cell = row * 7 + col
                    val dayOfMonth = cell - leadingBlanks + 1
                    if (dayOfMonth < 1 || dayOfMonth > visibleMonth.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(48.dp))
                    } else {
                        val date = visibleMonth.atDay(dayOfMonth)
                        CalendarDayCell(
                            date = date,
                            tasksDue = byDay[date].orEmpty(),
                            isToday = date == today,
                            isSelected = date == selectedDay,
                            onClick = { selectedDay = if (selectedDay == date) null else date },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ─── Selected day, or a summary of the month ───
        val selected = selectedDay
        if (selected == null) {
            val monthTasks = byDay.filterKeys { YearMonth.from(it) == visibleMonth }
                .values.flatten()
            Text(
                text = when {
                    monthTasks.isEmpty() -> "Nothing due in ${visibleMonth.format(DateTimeFormatter.ofPattern("MMMM"))}."
                    monthTasks.size == 1 -> "1 task due this month. Tap a day to see it."
                    else -> "${monthTasks.size} tasks due this month. Tap a day to see them."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            val dayTasks = byDay[selected].orEmpty()
            if (dayTasks.isEmpty()) {
                Text(
                    text = "Nothing due on this day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                dayTasks.forEach { task ->
                    // The calendar still shows tasks only — chore occurrences
                    // derive their dates from a schedule, and a month grid is
                    // not where that is read.
                    TaskCard(
                        row = BoardRow.TaskRow(task),
                        assigneeName = members.firstOrNull { it.id == task.assignedTo }?.username,
                        doneByName = members.firstOrNull { it.id == task.doneBy }?.username,
                        myUserId = myUserId,
                        onOpenDetail = { onOpenDetail(task) },
                        onToggleDone = { onToggleDone(task) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (undated > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (undated == 1) "1 task has no deadline and isn't shown here."
                else "$undated tasks have no deadline and aren't shown here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
            )
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    tasksDue: List<Task>,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasOverdue = tasksDue.any { isOverdue(it) }

    Box(
        modifier = modifier
            .height(48.dp)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    isToday -> MaterialTheme.colorScheme.surfaceVariant
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (tasksDue.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Cap the dots — a day with nine deadlines should look busy,
                    // not overflow its cell.
                    tasksDue.take(3).forEach { task ->
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        !task.isOpen -> MaterialTheme.colorScheme.tertiary
                                        isOverdue(task) -> overdueColor()
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                        )
                    }
                    if (tasksDue.size > 3) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasOverdue) overdueColor()
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
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
    "member_role_changed" -> "${e.actorUsername} changed a member's role"
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

        // "username → admin" from the backend; "admin" is the stored value for
        // what users are shown as "Manager".
        "member_role_changed" -> detail
            .replace(" → admin", " is now a manager")
            .replace(" → member", " is now a member")

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

/**
 * Group roster. Read-only: there are no role controls, because there is
 * nothing a role grants any more — every member can edit every chore.
 */
@Composable
private fun MemberList(
    members: List<MemberInfo>,
    myUserId: String?,
) {
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
            val isMe = member.id == myUserId

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                text = if (isMe) "${member.username} (you)" else member.username,
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
}

/**
 * Human label for a role.
 *
 * "Manager" is kept only for groups that still hold an `admin` row from before
 * the role was removed — nothing assigns it any more.
 */
private fun roleLabel(role: String): String = when (role) {
    ROLE_OWNER -> "Owner"
    ROLE_ADMIN -> "Manager"
    ROLE_MEMBER -> "Member"
    else -> role.replaceFirstChar { it.uppercase() }
}

/**
 * Role badge. Separate from [StatusChip] because roles and task statuses are
 * different vocabularies — sharing the chip meant "owner" rendered raw and
 * lowercase, styled as if it were a task state.
 */
@Composable
private fun RoleChip(role: String) {
    val color = when (role) {
        ROLE_OWNER -> MaterialTheme.colorScheme.primary
        ROLE_ADMIN -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = .15f), shape = MaterialTheme.shapes.small) {
        Text(
            text = roleLabel(role),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Dialogs
// ═══════════════════════════════════════════════════════════════

/**
 * New chore — a definition, not a to-do.
 *
 * The two things that make it a chore rather than a task are here: a schedule,
 * and an ordered rotation. Rotation is a multi-select whose *order* is the turn
 * order, built by tapping names in the sequence you want them to come round —
 * so the control shows a position number rather than a tick.
 *
 * "What done means" (F4) is offered at creation because that is the moment the
 * household actually agrees it; the 140-char cap is the spec's, and it is a
 * treaty rather than a manual.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateChoreDialog(
    members: List<MemberInfo>,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        doneLine: String,
        scheduleType: String,
        intervalDays: Int?,
        fixedWeekdays: List<Int>?,
        rotation: List<String>,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var doneLine by remember { mutableStateOf("") }
    var scheduleType by remember { mutableStateOf(ScheduleType.INTERVAL) }
    // Held as text, not an Int: the field has to be allowed to be empty while
    // someone clears it to type a new number, and an Int has no way to say so.
    var intervalText by remember { mutableStateOf("7") }
    var weekday by remember { mutableIntStateOf(2) } // Tuesday
    // Order matters: this is the turn order, not a set.
    val rotation = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Chore", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // At the top, not the bottom: this form is taller than the
                // dialog, and an error under the fold is not feedback — the
                // button just appears to do nothing.
                AnimatedVisibility(visible = error != null) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = doneLine,
                    onValueChange = { if (it.length <= 140) doneLine = it },
                    label = { Text("What done means (optional)") },
                    supportingText = { Text("${doneLine.length}/140") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("How often", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // One-off is deliberately absent: a one-off is a task, and
                    // the board already creates those through New Task.
                    listOf(
                        ScheduleType.INTERVAL to "Every N days",
                        ScheduleType.FIXED_DATE to "A fixed day",
                        ScheduleType.AS_NEEDED to "As needed",
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = scheduleType == type,
                            onClick = { scheduleType = type },
                            label = { Text(label) },
                        )
                    }
                }

                when (scheduleType) {
                    ScheduleType.INTERVAL -> {
                        // Any whole number of days, typed or stepped. The
                        // presets below are shortcuts, not the whole choice —
                        // a fortnightly bin collection has no chip.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Every", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { typed ->
                                    // Digits only, and short enough that the
                                    // field can't hold a number the backend
                                    // will reject on length alone.
                                    val digits = typed.filter { it.isDigit() }.take(3)
                                    intervalText = digits
                                    error = null
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(96.dp),
                            )
                            Text(
                                if (intervalText.toIntOrNull() == 1) "day" else "days",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                1 to "Daily", 7 to "Weekly",
                                14 to "Fortnightly", 30 to "Monthly",
                            ).forEach { (days, label) ->
                                FilterChip(
                                    selected = intervalText.toIntOrNull() == days,
                                    onClick = { intervalText = days.toString(); error = null },
                                    label = { Text(label) },
                                )
                            }
                        }

                        Text(
                            "Counted from the last time it was done, so doing it late moves the whole schedule.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ScheduleType.FIXED_DATE -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 0 = Sunday, matching the backend.
                            listOf(
                                1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                                5 to "Fri", 6 to "Sat", 0 to "Sun",
                            ).forEach { (day, label) ->
                                FilterChip(
                                    selected = weekday == day,
                                    onClick = { weekday = day },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    else -> {
                        Text(
                            "No date — it just sits with whoever's turn it is until it's done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text("Whose turn, in order", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    members.forEach { member ->
                        val position = rotation.indexOf(member.id)
                        FilterChip(
                            selected = position >= 0,
                            onClick = {
                                if (position >= 0) rotation.remove(member.id)
                                else rotation.add(member.id)
                                error = null
                            },
                            label = {
                                Text(
                                    if (position >= 0) "${position + 1}. ${member.username}"
                                    else member.username,
                                )
                            },
                        )
                    }
                }
                Text(
                    "The first person gets the first turn. It moves on only when the chore is actually done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) {
                    error = "Name is required"
                    return@Button
                }
                // Checked here rather than left to the backend so the message
                // names the field the user is looking at.
                val interval = intervalText.toIntOrNull()
                if (scheduleType == ScheduleType.INTERVAL &&
                    (interval == null || interval !in 1..365)
                ) {
                    error = "How often must be a number of days between 1 and 365"
                    return@Button
                }
                if (rotation.isEmpty()) {
                    error = "Pick at least one person for the rotation"
                    return@Button
                }
                onCreate(
                    name,
                    doneLine,
                    scheduleType,
                    if (scheduleType == ScheduleType.INTERVAL) interval else null,
                    if (scheduleType == ScheduleType.FIXED_DATE) listOf(weekday) else null,
                    rotation.toList(),
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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

/**
 * Date picker for a task deadline.
 *
 * A deadline is a *day*, not an instant — "due Friday" — so this picks a date
 * and pins it to the end of that day in the device's own zone before
 * converting to the RFC 3339 instant the API stores. Using start-of-day would
 * make a task due on Friday show as overdue for the whole of Friday.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePickerDialog(
    initial: OffsetDateTime?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    // DatePicker works in UTC-midnight millis, so seed it from the deadline's
    // date as the user sees it locally, not from the raw instant.
    val initialMillis = initial
        ?.atZoneSameInstant(ZoneId.systemDefault())
        ?.toLocalDate()
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?.toEpochMilli()

    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        val endOfDay = date.atTime(23, 59)
                            .atZone(ZoneId.systemDefault())
                            .toOffsetDateTime()
                        onPicked(endOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Set deadline") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** Deadline as a person would read it. Includes the year only when it isn't this one. */
private fun formatDueDate(due: OffsetDateTime): String {
    val local = due.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    val pattern = if (local.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
    return local.format(DateTimeFormatter.ofPattern(pattern))
}

/** A deadline in the past on a task that isn't finished. */
private fun isOverdue(task: Task): Boolean {
    val due = task.dueDate ?: return false
    return task.status != "done" && due.isBefore(OffsetDateTime.now())
}

/**
 * The same question for a board row of either shape.
 *
 * Note what this deliberately is not: a state. An open occurrence past its date
 * is still just open — there is no "missed" anywhere in the system, and this
 * only decides whether the date is drawn in amber.
 */
private fun isOverdue(row: BoardRow): Boolean {
    val due = row.dueDate ?: return false
    return row.isOpen && due.isBefore(OffsetDateTime.now())
}

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
