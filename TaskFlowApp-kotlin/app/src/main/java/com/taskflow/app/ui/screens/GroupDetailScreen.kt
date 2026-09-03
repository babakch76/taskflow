package com.taskflow.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import com.taskflow.app.TaskFlowApp
import com.taskflow.app.reminders.QuietHours
import com.taskflow.app.reminders.ReminderScheduler
import com.taskflow.app.data.model.ActivityEvent
import com.taskflow.app.data.model.Absence
import com.taskflow.app.data.model.Chore
import com.taskflow.app.data.model.ChoreHistory
import com.taskflow.app.data.model.ChoreHistoryEntry
import com.taskflow.app.data.model.GroupHistory
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.Occurrence
import com.taskflow.app.data.model.neededByWording
import com.taskflow.app.data.model.scheduleWording
import com.taskflow.app.data.model.ScheduleType
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.isOpen
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How often the activity feed is polled while this screen is on screen. */
private const val ACTIVITY_POLL_MILLIS = 5_000L

// open / done, and nothing else — the v2 status language.
//
// "todo" stays as the wire value because that is what the tasks table holds and
// what every existing row already says; only the word shown changed. Legacy
// rows still carrying "in_progress" read as open (isOpen is status != "done"),
// so nothing needs migrating and no history is rewritten.
private val TASK_STATUSES = listOf("todo", "done")

/**
 * Group detail: tasks, activity feed, members and invites for one group.
 *
 * Three tabs rather than one long scroll: the activity feed is append-only and
 * would otherwise push the board off screen.
 *
 * There was a fourth, a month view of due dates. It read `state.tasks` only, so
 * it showed the one-off minority of the household's work and hid every rotation
 * chore — and teaching it about occurrences would have meant building the
 * scheduling view constraint 5 rules out. The board is the screen.
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
    // One create flow, not two. Whether the thing repeats is a question inside
    // the form, not a choice of button — see CreateChoreFlow.
    var showCreate by remember { mutableStateOf(false) }
    // The name of the thing just added, so its row can be picked out of the
    // board for a moment. Cleared on a timer: a highlight that stays is just a
    // second kind of status.
    var justAdded by remember { mutableStateOf<String?>(null) }
    // The occurrence a busy pass is being confirmed for. Both the swipe and the
    // detail sheet route through here, so the rule is stated once and the two
    // paths cannot drift apart.
    var passCandidate by remember { mutableStateOf<Occurrence?>(null) }
    var showAway by remember { mutableStateOf(false) }
    // Which history is open. The chore name is held alongside so the sheet has
    // a title before its data arrives.
    var historyChoreName by remember { mutableStateOf<String?>(null) }
    var showGroupHistory by remember { mutableStateOf(false) }

    // Per-form submit state, so a create dialog can stay open until the write
    // lands and show a rejection where the user is already looking (B-7).
    var createSubmitting by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    // Separate from the create pair: the edit dialog is a different form and can
    // be open over a different failure.
    var choreSubmitting by remember { mutableStateOf(false) }
    var choreError by remember { mutableStateOf<String?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // Task pending deletion — deleting is irreversible, so it goes via a confirm.
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    // Same for a chore, which takes more with it: every occurrence, and so the
    // whole history of who did it.
    var choreToDelete by remember { mutableStateOf<Chore?>(null) }
    // Detail sheet holds an *id*, not a Task. The Task is looked up from state
    // on each recomposition, so an edit (ours or a teammate's) is reflected
    // instead of the sheet showing the snapshot it opened with.
    var detailTaskId by remember { mutableStateOf<String?>(null) }
    val detailTask = detailTaskId?.let { id -> state.tasks.firstOrNull { it.id == id } }

    // Same trick for occurrences: hold the id, re-derive the row, so the sheet
    // follows a teammate completing it rather than showing a stale snapshot.
    var detailOccurrenceId by remember { mutableStateOf<String?>(null) }
    val detailOccurrence = detailOccurrenceId?.let { id ->
        state.occurrences.firstOrNull { it.id == id }
    }

    // Same pattern for the chore being edited: hold the id, re-derive the chore,
    // so a concurrent edit by someone else is reflected rather than overwritten
    // from a stale snapshot.
    var editChoreId by remember { mutableStateOf<String?>(null) }
    val editChore = editChoreId?.let { id -> state.chores.firstOrNull { it.id == id } }

    // Which member row is "me" — needed so the owner isn't offered a demote
    // button on their own row.
    val context = LocalContext.current
    val myUserId = remember {
        (context.applicationContext as? TaskFlowApp)?.tokenManager?.getUserId()
    }

    val iAmAway = state.members.firstOrNull { it.id == myUserId }?.away == true

    // Shown once, under the first row you can actually swipe, and never again
    // on this device. Whether somebody has found a gesture is not household
    // data, so it stays local.
    var showSwipeHint by remember { mutableStateOf(!LocalHints.swipeHintDismissed(context)) }

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

    // F3 — ask once for permission to notify. Refusing costs only reminders:
    // the board has never depended on them, so there is nothing to explain and
    // nothing to re-ask for.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not, the app works the same */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-arm reminders whenever the board or the quiet window changes.
    //
    // Rescheduling is a full replacement and skips anything already delivered,
    // so running it on every board change is safe and keeps the alarms in step
    // with a rotation that may have moved while we were away.
    LaunchedEffect(state.occurrences, state.chores, state.quietFrom, state.quietTo, myUserId) {
        ReminderScheduler.reschedule(
            context = context.applicationContext,
            groupId = groupId,
            occurrences = state.occurrences,
            chores = state.chores,
            myUserId = myUserId,
            quiet = QuietHours.parse(state.quietFrom, state.quietTo),
        )
    }

    // The new row is marked for a moment so it can be found without hunting,
    // then stops being marked. A highlight that stays is a second status.
    LaunchedEffect(justAdded) {
        if (justAdded != null) {
            delay(2_500)
            justAdded = null
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { text ->
            // Read once, before showing. The state is cleared as soon as the
            // snackbar closes, so reading it afterwards would always find null.
            val undoable = state.undoablePass
            val result = snackbarHostState.showSnackbar(
                message = text,
                actionLabel = undoable?.let { "Undo" },
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && undoable != null) {
                viewModel.undoPass(undoable)
            } else {
                viewModel.dismissMessage()
            }
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
                            DropdownMenuItem(
                                text = { Text("What's been done") },
                                leadingIcon = { Icon(Icons.Default.List, null) },
                                onClick = {
                                    showMenu = false
                                    showGroupHistory = true
                                    viewModel.loadGroupHistory(state.historyWindow)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (iAmAway) "I'm back" else "I'm away") },
                                leadingIcon = { Icon(Icons.Default.DateRange, null) },
                                onClick = {
                                    showMenu = false
                                    // Coming back needs no explaining; going
                                    // away does, so only that direction gets a
                                    // dialog.
                                    if (iAmAway) viewModel.setAway(false, null) else showAway = true
                                },
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
                // One button. There used to be two — a primary one for chores
                // and a small one for one-off tasks — which made the household
                // pick a storage model before it could write anything down.
                // Everything here is a chore to the person adding it; whether it
                // repeats is a question the form asks.
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add chore") },
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
                    run {
                        // Fixed, not scrollable: three labels fit a phone's
                        // width. It was scrollable while there were four.
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                // Open rows, both shapes. It used to count the
                                // done ones too, so finishing a chore never
                                // moved the number — and a row under Done is
                                // not a thing on the board in the sense a
                                // count next to a tab label implies.
                                text = {
                                    val open = state.tasks.count { it.isOpen } +
                                        state.occurrences.count { it.isOpen }
                                    Text("Board ($open)")
                                },
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

                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    when (selectedTab) {
                        0 -> Column(modifier = Modifier.fillMaxSize()) {
                        // Away is meant to be visible — it is the thing that
                        // explains why the rotation is stepping over you. It
                        // was only ever legible from the overflow menu you set
                        // it from, so your own board said nothing about it.
                        if (iAmAway) {
                            AwayBanner(onBack = { viewModel.setAway(false, null) })
                        }
                        TaskList(
                            // Chore occurrences first: they are the model the
                            // app is moving to, and tasks are the legacy
                            // one-off shape still carried alongside them.
                            rows = state.occurrences.map { BoardRow.OccurrenceRow(it) } +
                                state.tasks.map { BoardRow.TaskRow(it) },
                            chores = state.chores,
                            justAdded = justAdded,
                            members = state.members,
                            myUserId = myUserId,
                            showSwipeHint = showSwipeHint,
                            onOpenDetail = { row ->
                                when (row) {
                                    is BoardRow.TaskRow -> detailTaskId = row.task.id
                                    is BoardRow.OccurrenceRow ->
                                        detailOccurrenceId = row.occurrence.id
                                }
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
                            onPass = { row ->
                                (row as? BoardRow.OccurrenceRow)?.let {
                                    passCandidate = it.occurrence
                                }
                            },
                            onSwiped = {
                                if (showSwipeHint) {
                                    showSwipeHint = false
                                    LocalHints.dismissSwipeHint(context)
                                }
                            },
                            onCreate = { showCreate = true },
                        )
                        }

                        1 -> ActivityList(state.activity)

                        2 -> MemberList(
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

    detailOccurrence?.let { occurrence ->
        OccurrenceDetailSheet(
            occurrence = occurrence,
            chore = state.chores.firstOrNull { it.id == occurrence.choreId },
            members = state.members,
            myUserId = myUserId,
            onDismiss = { detailOccurrenceId = null },
            onPass = {
                detailOccurrenceId = null
                passCandidate = occurrence
            },
            onPickDay = { picked ->
                detailOccurrenceId = null
                viewModel.setOccurrenceDueDate(occurrence.id, picked)
            },
            onEditChore = {
                // Close the sheet first: an AlertDialog over a bottom sheet
                // stacks two surfaces and the sheet's scrim fights the dialog's.
                detailOccurrenceId = null
                editChoreId = occurrence.choreId
            },
            onShowHistory = {
                detailOccurrenceId = null
                historyChoreName = occurrence.choreName
                viewModel.loadChoreHistory(occurrence.choreId)
            },
        )
    }

    editChore?.let { chore ->
        CreateChoreFlow(
            members = state.members,
            myUserId = myUserId,
            submitting = choreSubmitting,
            serverError = choreError,
            onDismiss = { editChoreId = null; choreError = null },
            initial = chore.toDraft(),
            onDelete = { choreToDelete = chore },
            // Only what actually changed goes up. Restating an untouched field
            // would overwrite a concurrent edit to it and would put a change
            // nobody made into the diff the whole group reads.
            onSubmit = { draft ->
                choreSubmitting = true
                choreError = null
                val before = chore.toDraft()
                val typeChanged = draft.kind != before.kind
                viewModel.updateChore(
                    choreId = chore.id,
                    name = draft.name.trim().takeIf { it != chore.name },
                    doneLine = draft.doneLine.trim().takeIf { it != chore.doneLine },
                    // On a type change the new type's parameters always go with
                    // it: the server clears the old type's columns, so leaving
                    // these out would produce a chore of the new kind with
                    // nothing to schedule from.
                    intervalDays = draft.intervalDays.takeIf {
                        draft.kind == ChoreKind.INTERVAL &&
                            (typeChanged || it != before.intervalDays)
                    },
                    fixedWeekdays = listOf(draft.weekday).takeIf {
                        draft.kind == ChoreKind.FIXED_DATE && draft.byWeekday &&
                            (typeChanged || !before.byWeekday || draft.weekday != before.weekday)
                    },
                    rotation = draft.rotation.takeIf { it != before.rotation },
                    scheduleType = draft.kind?.scheduleType.takeIf { typeChanged },
                    fixedMonthDays = listOf(draft.monthDay).takeIf {
                        draft.kind == ChoreKind.FIXED_DATE && !draft.byWeekday &&
                            (typeChanged || before.byWeekday || draft.monthDay != before.monthDay)
                    },
                    neededByTime = draft.neededBy?.takeIf { it != before.neededBy },
                ) { failure ->
                    choreSubmitting = false
                    if (failure == null) editChoreId = null else choreError = failure
                }
            },
        )
    }

    // Deleting a chore takes every occurrence with it, and with them the record
    // of who did it — so it confirms, and the confirm says that rather than
    // asking "are you sure?". There is no undelete endpoint and a snackbar that
    // pretended otherwise would be a lie.
    choreToDelete?.let { chore ->
        AlertDialog(
            onDismissRequest = { choreToDelete = null },
            title = { Text("Delete ${chore.name}?") },
            text = {
                Text(
                    "This removes the chore, whoever's turn it currently is, and " +
                        "the history of every time it has been done. It cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        choreToDelete = null
                        editChoreId = null
                        viewModel.deleteChore(chore.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { choreToDelete = null }) { Text("Cancel") }
            },
        )
    }
    // An occurrence can vanish under the sheet: undoing a completion deletes
    // the one it spawned. Close rather than leave a sheet describing a row that
    // no longer exists.
    LaunchedEffect(detailOccurrenceId, state.occurrences) {
        if (detailOccurrenceId != null && state.occurrences.none { it.id == detailOccurrenceId }) {
            detailOccurrenceId = null
        }
    }

    // The busy pass, confirmed.
    //
    // The debt rule is stated twice by design: here, in plain language, at the
    // moment of passing; and afterwards as a standing marker on the row that
    // comes back. Once is not enough for a rule this counter-intuitive — the
    // chore leaves your row and returns a cycle later, which looks like a bug
    // unless somebody said so.
    passCandidate?.let { occurrence ->
        val chore = state.chores.firstOrNull { it.id == occurrence.choreId }
        val receiver = nextInRotationAfter(chore, occurrence, state.members)
        if (receiver == null) {
            // Nobody to pass to. Nothing to confirm, so say why rather than
            // showing a dialog with an empty name in it.
            passCandidate = null
        } else {
            AlertDialog(
                onDismissRequest = { passCandidate = null },
                title = { Text("Pass ${occurrence.choreName} to ${receiver.username}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Next time it's yours again. ${receiver.username}'s cover " +
                                "counts as ${receiver.username}'s turn.",
                        )
                        Text(passNoticeLine(occurrence, chore, receiver.username))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        passCandidate = null
                        viewModel.passOccurrence(occurrence, receiver.username)
                    }) { Text("Pass it to ${receiver.username}") }
                },
                dismissButton = {
                    TextButton(onClick = { passCandidate = null }) { Text("Keep it") }
                },
            )
        }
    }

    if (showCreate) {
        CreateChoreFlow(
            members = state.members,
            myUserId = myUserId,
            submitting = createSubmitting,
            serverError = createError,
            onDismiss = { showCreate = false; createError = null },
            // Two write paths behind one flow, and the flow does not know which
            // it is using: a one-off is still a task, the three rotating kinds
            // are still chores, and the split is decided here from the answer
            // given on screen 1.
            //
            // It stays open until the write lands. Closing first and reporting
            // afterwards threw away everything typed into it (B-7).
            onSubmit = { draft ->
                createSubmitting = true
                createError = null
                val settle = { failure: String? ->
                    createSubmitting = false
                    if (failure == null) {
                        showCreate = false
                        justAdded = draft.name.trim()
                    } else {
                        createError = failure
                    }
                }
                if (draft.kind == ChoreKind.ONE_OFF) {
                    viewModel.createTask(
                        draft.name,
                        draft.doneLine,
                        draft.assignee ?: myUserId,
                        draft.dueDate?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        settle,
                    )
                } else {
                    viewModel.createChore(
                        name = draft.name,
                        doneLine = draft.doneLine,
                        scheduleType = draft.kind?.scheduleType ?: ScheduleType.INTERVAL,
                        intervalDays = draft.intervalDays.takeIf { draft.kind == ChoreKind.INTERVAL },
                        fixedWeekdays = listOf(draft.weekday)
                            .takeIf { draft.kind == ChoreKind.FIXED_DATE && draft.byWeekday },
                        rotation = draft.rotation,
                        fixedMonthDays = listOf(draft.monthDay)
                            .takeIf { draft.kind == ChoreKind.FIXED_DATE && !draft.byWeekday },
                        neededByTime = draft.neededBy?.takeIf { draft.kind != ChoreKind.AS_NEEDED },
                        onResult = settle,
                    )
                }
            },
        )
    }

    historyChoreName?.let { name ->
        ChoreHistorySheet(
            choreName = name,
            history = state.choreHistory,
            loading = state.historyLoading,
            onDismiss = { historyChoreName = null; viewModel.clearHistory() },
        )
    }

    if (showGroupHistory) {
        GroupHistorySheet(
            history = state.groupHistory,
            window = state.historyWindow,
            loading = state.historyLoading,
            onWindowChange = { viewModel.loadGroupHistory(it) },
            onDismiss = { showGroupHistory = false; viewModel.clearHistory() },
        )
    }

    if (showAway) {
        AwayDialog(
            onDismiss = { showAway = false },
            onConfirm = { until ->
                showAway = false
                viewModel.setAway(true, until)
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

// There was a ProgressHeader here: "5 of 8 done", a percentage, and a filling
// bar above the tabs. It was v1 furniture that outlived the pivot.
//
// Constraint 4 forbids "points, streaks, leaderboards, or gamification", and
// the spec's note under history is flatter still: "No points, ranks, streaks,
// percentages, or comparisons drawn by the app. The data is presented;
// conclusions are the household's business." A completion percentage is a
// conclusion, and because it counted the whole group's rows it was a verdict on
// the household's week — the shame signal constraints 3 and 4 exist to keep
// out. It was also wrong: it counted `tasks` only and never saw an occurrence,
// so it reported a number for a fraction of the board.
//
// GroupWithProgress still carries total_tasks/done_tasks/progress and the API
// still returns them. Nothing reads them now, and that is fine — the endpoint
// is shared and the fields cost nothing.

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
 * "You're away" — shown on your own board, and only while you are.
 *
 * The spec makes away deliberately impossible to hide, but until now the only
 * places it showed were the member list and the rotation pickers — that is,
 * everywhere *except* the screen the away person is looking at. So your own
 * absence was the one thing you could not see.
 *
 * Deliberately quiet: a surface tint, no icon, no colour that reads as a
 * warning. Being away is not a failure state, and this is a reminder of a
 * setting rather than a nag about one.
 *
 * "I'm back" is here because this is where you notice you are still marked
 * away. Going away keeps its dialog in the overflow menu — that direction has
 * a rule worth reading first; coming back has nothing to explain.
 */
@Composable
private fun AwayBanner(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "You're away. Chores are passing over you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("I'm back") }
        }
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
    chores: List<Chore>,
    justAdded: String?,
    members: List<MemberInfo>,
    myUserId: String?,
    showSwipeHint: Boolean,
    onOpenDetail: (BoardRow) -> Unit,
    onToggleDone: (BoardRow) -> Unit,
    onPass: (BoardRow) -> Unit,
    onSwiped: () -> Unit,
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
            "YOURS" to open.filter { it.assignedTo != null && it.assignedTo == myUserId },
            "OTHERS" to open.filter { it.assignedTo == null || it.assignedTo != myUserId },
            "DONE THIS CYCLE" to rows.filterNot { it.isOpen },
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
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                TaskCard(
                    row = row,
                    scheduleText = scheduleLineFor(row, chores),
                    highlighted = justAdded != null && row.title.equals(justAdded, ignoreCase = true),
                    assigneeName = members.firstOrNull { it.id == row.assignedTo }?.username,
                    doneByName = members.firstOrNull { it.id == row.doneBy }?.username,
                    coveredByName = (row as? BoardRow.OccurrenceRow)?.occurrence?.coveredByName,
                    needsDate = (row as? BoardRow.OccurrenceRow)?.occurrence?.needsDate == true,
                    // Whether there is anybody to pass to at all. A rotation of
                    // one, or a household where everyone else is away, has
                    // nowhere to send it, and offering the gesture there would
                    // promise a hand-off that came straight back.
                    canPass = (row as? BoardRow.OccurrenceRow)?.let { occRow ->
                        nextInRotationAfter(
                            chores.firstOrNull { it.id == occRow.occurrence.choreId },
                            occRow.occurrence,
                            members,
                        ) != null
                    } == true,
                    passedByMe = (row as? BoardRow.OccurrenceRow)
                        ?.occurrence?.passedFrom == myUserId && myUserId != null,
                    myUserId = myUserId,
                    onOpenDetail = { onOpenDetail(row) },
                    onToggleDone = { onToggleDone(row) },
                    onPass = { onPass(row) },
                    onSwiped = onSwiped,
                )
                // The hint sits under the first row of the first section, which
                // is a row you can actually swipe. Under a heading it would be
                // advice about nothing in particular.
                if (showSwipeHint && label == "YOURS" && index == 0) {
                    Text(
                        text = "Swipe a row for actions.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
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
 * Line 2 of a row: how often this comes round, in the household's words.
 *
 * "every 4 days", "Tuesdays", "as needed" - the same forms the chore's own
 * schedule wording produces, so the row and the sheet cannot drift apart. A
 * needed-by time rides along, because it is what the second reminder is
 * measured against.
 *
 * A one-off has no frequency to state, so it says what it is and when:
 * "one-off, Fri 11 Sep", or just "one-off" when it has no date. Returns null
 * only while an occurrence's chore has yet to arrive, which is a moment rather
 * than a state.
 */
private fun scheduleLineFor(row: BoardRow, chores: List<Chore>): String? = when (row) {
    is BoardRow.OccurrenceRow ->
        chores.firstOrNull { it.id == row.occurrence.choreId }?.let { chore ->
            buildString {
                append(chore.scheduleWording())
                chore.neededByWording()?.let { append(" · $it") }
            }
        }

    is BoardRow.TaskRow -> buildString {
        append("one-off")
        row.dueDate?.let { append(" · ${formatDueDate(it)}") }
    }
}

/**
 * Line 3 of a row: whose turn this is, and when it is wanted.
 *
 * Your own rows do not repeat your name back at you - "Due 30 Aug" rather than
 * "you, due 30 Aug". A row with no date says whose turn it is instead of
 * leaving the line empty, which is exactly what an as-needed chore needs: it is
 * somebody's turn indefinitely, and that is the whole of its status.
 *
 * [dateAlreadyShown] suppresses the date when the schedule line above has
 * already given it, which is the one-off case.
 */
private fun cycleLineFor(
    row: BoardRow,
    assigneeName: String?,
    doneByName: String?,
    myUserId: String?,
    dateAlreadyShown: Boolean,
): String {
    if (!row.isOpen) {
        val doer = doneByName ?: "someone"
        val doneOn = row.doneAt?.let { formatDueDate(it) }
        return if (doneOn != null) "Done by $doer · $doneOn" else "Done by $doer"
    }

    val due = row.dueDate?.takeUnless { dateAlreadyShown }?.let { formatDueDate(it) }
    val mine = row.assignedTo != null && row.assignedTo == myUserId
    val who = assigneeName ?: "Unassigned"

    // A one-off has no rotation, so it has no *turns*: it was given to one
    // person once and stays with them. Saying "your turn" about it borrows a
    // word that means something precise everywhere else on this board, and
    // means nothing here. The name alone does the job, and for your own rows
    // the section heading has already said it.
    val oneOff = row is BoardRow.TaskRow

    return when {
        mine && due != null -> "Due $due"
        mine -> if (oneOff) "" else "Your turn"
        due != null -> "$who · due $due"
        // Somebody else's standing turn. Possessive rather than "Maya - turn",
        // which reads as a label instead of a sentence.
        assigneeName != null -> if (oneOff) who else "$who's turn"
        else -> who
    }
}

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
    scheduleText: String?,
    highlighted: Boolean,
    assigneeName: String?,
    doneByName: String?,
    coveredByName: String?,
    needsDate: Boolean,
    passedByMe: Boolean,
    canPass: Boolean,
    myUserId: String?,
    onOpenDetail: () -> Unit,
    onToggleDone: () -> Unit,
    onPass: () -> Unit,
    onSwiped: () -> Unit,
) {
    val overdue = isOverdue(row)

    // Undo is offered only to the person who ticked it, and only briefly.
    // Anything older is history, and history is not editable from the board.
    //
    // The permission half is a plain comparison. The *expiry* half cannot be:
    // reading the clock during composition freezes the answer at whatever it
    // was when the row was last drawn, so the checkbox stayed enabled well past
    // ten minutes until something unrelated happened to recompose it (B-6).
    //
    // Instead the row waits out its own window. One coroutine per undoable row,
    // sleeping exactly until the moment it lapses — no polling, and no clock
    // read that can go stale.
    val mineToUndo = !row.isOpen && row.doneBy != null && row.doneBy == myUserId
    var windowOpen by remember(row.key, row.doneAt) { mutableStateOf(false) }

    LaunchedEffect(row.key, row.doneAt, mineToUndo) {
        val doneAt = row.doneAt
        if (!mineToUndo || doneAt == null) {
            windowOpen = false
            return@LaunchedEffect
        }
        val remaining = Duration.between(OffsetDateTime.now(), doneAt.plus(UNDO_WINDOW))
        if (remaining.isNegative || remaining.isZero) {
            windowOpen = false
            return@LaunchedEffect
        }
        windowOpen = true
        delay(remaining.toMillis())
        windowOpen = false
    }

    val canUndo = mineToUndo && windowOpen

    // A one-off's date *is* its schedule, so it is stated once, on the schedule
    // line, and the cycle line below carries only the name. Everything else puts
    // its date on the cycle line, where it belongs to this turn rather than to
    // the arrangement.
    val dateIsOnScheduleLine = row is BoardRow.TaskRow && row.dueDate != null
    val cycleText = cycleLineFor(row, assigneeName, doneByName, myUserId, dateIsOnScheduleLine)

    // Swipe is offered on your own open chore rows and nowhere else.
    //
    // Not on somebody else's row: the two actions behind it are "I did this"
    // and "I can't do this", and neither is yours to say about their turn. Not
    // on a one-off either, which has no rotation to pass along. The detail
    // sheet keeps its own button, so the gesture is a shortcut rather than the
    // only way through — some people will never find it, and that is a finding
    // rather than a dead end.
    val swipeable = row is BoardRow.OccurrenceRow && row.isOpen &&
        myUserId != null && row.assignedTo == myUserId && canPass

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onPass()
                SwipeToDismissBoxValue.StartToEnd -> onToggleDone()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            if (value != SwipeToDismissBoxValue.Settled) onSwiped()
            // Never actually dismiss: the row is revealing an action, not
            // leaving the board, so it snaps back once the action has fired.
            false
        },
    )

    val card: @Composable () -> Unit = {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
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
                // Line 2 - the agreement: how often this comes round. It is on
                // the row rather than only in the sheet because the frequency is
                // half of what the household agreed, and an agreement nobody can
                // see is not doing its job (F4).
                scheduleText?.let {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The amber follows the date. On a one-off the date is
                        // up here, and hard-wiring the dot to the line below
                        // left an overdue one-off with no signal at all.
                        if (overdue && dateIsOnScheduleLine) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(overdueColor()),
                            )
                        }
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overdue && dateIsOnScheduleLine) overdueColor()
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Line 3 - this cycle: whose turn it is, and when it is wanted.
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The amber dot sits with whichever line carries the date,
                    // and for everything except a one-off that is this one.
                    if (overdue && !dateIsOnScheduleLine) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(overdueColor()),
                        )
                    }
                    if (cycleText.isNotEmpty()) {
                        Text(
                            text = cycleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overdue && !dateIsOnScheduleLine) overdueColor()
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // The debt rule, said out loud on the two rows it applies to.
                //
                // Without this a pass looks like the chore simply left, and the
                // return looks like a turn that never moved. Both are the rule
                // working; neither is legible without a sentence.
                val returned = coveredByName != null && row.isOpen
                val mine = row.assignedTo != null && row.assignedTo == myUserId
                val marker = when {
                    // Said before the debt marker, because it is the more
                    // surprising of the two: this chore has come back to you
                    // without anybody doing it.
                    //
                    // It states the fact and stops there. "Pick a day" belongs
                    // on the row only until a day is picked, and the row cannot
                    // tell: the flag is derived from who has passed, which does
                    // not change when a date is set. A line that keeps asking
                    // for something already done is worse than one that simply
                    // says where things stand, and the detail sheet carries the
                    // button and the explanation.
                    needsDate && mine -> "Everyone's busy, so it's back with you."

                    passedByMe -> "Next turn comes back to you. " +
                        "${assigneeName ?: "Someone"} is covering this one"

                    // Said in the past tense, because the cover has already
                    // happened. This line used to read "Back to you after X
                    // covered", which frames a finished event as a pending one
                    // and reads as though the turn were still on its way back
                    // when it is already sitting here.
                    returned && mine ->
                        "$coveredByName covered your last turn, so it's yours again"

                    // Somebody else's row, so it cannot say "you". It used to:
                    // the branch above had no ownership test, so every member
                    // saw "Back to you after maya covered" on the row it
                    // belonged to, addressed to the wrong person, and maya saw
                    // herself named in the third person in a sentence aimed at
                    // her. Whose row it is is already on the cycle line above,
                    // so this only has to give the reason the rotation did not
                    // move on.
                    returned -> assigneeName
                        ?.let { "$coveredByName covered $it's last turn" }
                        ?: "$coveredByName covered the last turn"

                    else -> null
                }
                marker?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                    )
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

    if (!swipeable) {
        card()
        return
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val toEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (toEnd) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                ) {
                    Text(
                        text = if (toEnd) "Done" else "Pass it",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (toEnd) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        content = { card() },
    )
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
/**
 * Detail for one chore occurrence — where F4's "what done means" is finally
 * read.
 *
 * The line is agreed once, at the chore, and shown on every occurrence of it.
 * That is the whole mechanism: it moves the standards argument (what counts as
 * "clean"? how often is often enough?) out of each individual turn and into a
 * single setup conversation the household has once. So the done-line and the
 * frequency sit together at the top, before anything about this particular
 * cycle — they describe the agreement; the rest describes today.
 *
 * Read-only. Editing a chore is open to every member and broadcasts a diff to
 * the group, which is a different affordance in a different place — this sheet
 * is for the person about to do the chore, wondering what "done" means.
 *
 * Deliberately absent: anything about lateness. A past-due occurrence shows its
 * date exactly as any other does. There is no "overdue" line, no day count and
 * no colour beyond the same amber the board uses — constraint 3, and the detail
 * view is precisely where a shame badge would feel most justified and do most
 * damage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccurrenceDetailSheet(
    occurrence: Occurrence,
    chore: Chore?,
    members: List<MemberInfo>,
    myUserId: String?,
    onDismiss: () -> Unit,
    onEditChore: () -> Unit,
    onShowHistory: () -> Unit,
    onPass: () -> Unit,
    onPickDay: (String) -> Unit,
) {
    var pickingDay by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val assignee = members.firstOrNull { it.id == occurrence.assignedTo }?.username
    val doer = members.firstOrNull { it.id == occurrence.doneBy }?.username
    val passer = occurrence.passedFrom?.let { id -> members.firstOrNull { it.id == id }?.username }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = occurrence.choreName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )

            // The agreement: what done means, and how often. Together, because
            // apart they are only half of what was agreed.
            val doneLine = occurrence.doneLine.trim()
            if (doneLine.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What done means", style = MaterialTheme.typography.labelLarge)
                    Text(doneLine, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(
                    "No agreed definition of done for this chore yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            chore?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How often", style = MaterialTheme.typography.labelLarge)
                    Text(
                        buildString {
                            append(it.scheduleWording().replaceFirstChar { c -> c.uppercase() })
                            it.neededByWording()?.let { needed -> append(", $needed") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Any member may change the agreement — that is the point of
                // it being an agreement. A quiet text button, not a primary
                // action: reading this sheet is the common case, editing the
                // household's standards is not.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(
                        onClick = onEditChore,
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Edit chore") }
                    TextButton(
                        onClick = onShowHistory,
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("History") }
                }
            }

            HorizontalDivider()

            // This cycle.
            DetailRow("Whose turn", assignee ?: "Nobody")
            DetailRow(
                "Due",
                occurrence.dueDate?.let { formatDueDate(it) }
                    // As-needed chores genuinely have no date. Saying so beats
                    // an empty field, which reads like missing data.
                    ?: "No date. It waits until it's needed",
            )

            // A passed chore says where it came from, so the receiver knows why
            // it is on their row and the passer can see it landed. Neutral
            // wording: passing is a normal move, not something to answer for.
            // Not while it is back in the passer's own hands. Once a whole
            // household has passed a chore it returns to whoever asked first,
            // and telling them it "comes back to you next time" describes a
            // future that has already happened; the Pick a day block below
            // says what is actually true.
            val stillAway = occurrence.assignedTo != occurrence.passedFrom
            if (occurrence.isOpen && passer != null && stillAway) {
                Text(
                    if (occurrence.passedFrom == myUserId) {
                        "You passed this on. It comes back to you next time."
                    } else {
                        "$passer passed this on, so it's yours this cycle."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!occurrence.isOpen) {
                DetailRow("Done by", doer ?: "Not recorded")
                DetailRow("Done", formatStamp(occurrence.doneAt))
                // Who the turn actually belonged to: the passer if it was
                // passed, otherwise whoever it was assigned to.
                val owed = passer ?: assignee
                if (doer != null && owed != null && doer != owed) {
                    // Stated plainly, without praise or blame either way. It is
                    // simply how the next turn was decided.
                    Text(
                        "$doer covered this one, so the next turn goes back to $owed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // "Busy — pass it": only on an open occurrence that is actually
            // yours. Passing somebody else's turn would be handing out work,
            // and the server refuses it anyway.
            if (occurrence.needsDate && occurrence.assignedTo == myUserId) {
                HorizontalDivider()
                Button(onClick = { pickingDay = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pick a day")
                }
                Text(
                    "Everyone was busy, so it came back to you. It's already set for " +
                        "the latest the schedule allows, and you can bring it forward.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Passing is offered on your own open turn, but not once the round
            // has closed: there is nobody left to hand it to, and the button
            // would only earn a refusal.
            if (occurrence.isOpen && occurrence.assignedTo == myUserId && !occurrence.needsDate) {
                HorizontalDivider()
                OutlinedButton(onClick = onPass, modifier = Modifier.fillMaxWidth()) {
                    Text("Busy? Pass it on")
                }
                Text(
                    "Goes to the next person in the rotation. It comes back to you next " +
                        "time round, so this delays your turn rather than skipping it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pickingDay) {
        DueDatePickerDialog(
            initial = occurrence.dueDate,
            onDismiss = { pickingDay = false },
            onPicked = { picked ->
                pickingDay = false
                onPickDay(picked)
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

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
                            isOverdue(task) -> overdueColor()
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

// The deck's dark amber (--pamber / --pamberdot), which is a shade off the one
// F1 picked by eye. Overdue has to read the same in both appearances, and the
// deck is where that was decided.
private val AmberOnDark = androidx.compose.ui.graphics.Color(0xFFE0A534)

@Composable
private fun overdueColor(): androidx.compose.ui.graphics.Color =
    // Asks the *theme*, not the system. Once appearance can be overridden in
    // the app, isSystemInDarkTheme() is the wrong question: a phone in light
    // mode showing the app in dark would have drawn the light amber onto a dark
    // card, which is the one colour in the app that must stay legible.
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) AmberOnDark else AmberOnLight

// StatusChip lived here. It rendered a task's status as a coloured pill on the
// board row, which is the "status pill" v2 does away with: a row is open or it
// is done, and the checkbox already says which.

private fun statusLabel(status: String) = when (status) {
    "todo" -> "Open"
    // Kept for the activity feed only. A row written before v2 can carry this,
    // and the feed is a record of what happened rather than of what the app
    // would say today.
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

/**
 * Event types come from handlers/activity.go — **keep these in sync**.
 *
 * A missing case used to fall through to "maya: occurrence_done", which is how
 * every event F2 to F6 added read for the whole of v2: the v1 half of the feed
 * spoke English and the chore half printed its own constants. The fallback no
 * longer leaks the identifier, so if you add an event to the backend and forget
 * it here, the feed stays readable — but it also stays vague, and the fix is to
 * add the case rather than to leave it generic.
 */
private fun describeEvent(e: ActivityEvent): String = when (e.eventType) {
    "task_created" -> "${e.actorUsername} added a task"
    "task_updated" -> "${e.actorUsername} updated a task"
    "task_deleted" -> "${e.actorUsername} deleted a task"
    "tasks_bulk_updated" -> "${e.actorUsername} moved several tasks"

    // The chore model (F2–F6). "did" rather than "completed" — the household
    // says the former, and the detail line underneath names the chore.
    "chore_created" -> "${e.actorUsername} added a chore"
    "chore_updated" -> "${e.actorUsername} changed a chore"
    "chore_deleted" -> "${e.actorUsername} deleted a chore"
    "occurrence_done" -> "${e.actorUsername} did a chore"
    "occurrence_reopened" -> "${e.actorUsername} undid a completion"

    "member_joined" -> "${e.actorUsername} joined the group"
    "member_left" -> "${e.actorUsername} left the group"
    "invite_accepted" -> "${e.actorUsername} accepted an invite"
    "member_role_changed" -> "${e.actorUsername} changed a member's role"
    else -> "${e.actorUsername} made a change"
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
    "task_created", "chore_created" -> MaterialTheme.colorScheme.primary
    // Completions get the same green a done row does. Not a reward — the feed
    // is a record, and this is the one entry that says something finished.
    "occurrence_done" -> MaterialTheme.colorScheme.tertiary
    "task_deleted", "chore_deleted", "member_left" -> MaterialTheme.colorScheme.error
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
                            // Away is stated on the member list because the
                            // spec makes it deliberately impossible to hide —
                            // the app cannot check whether someone is really
                            // gone, so it shows the claim to everyone instead.
                            if (member.away) {
                                Text(
                                    text = member.awayUntil?.let { "Away until ${formatDueDate(it)}" }
                                        ?: "Away",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
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
 * One chore's history (F6) — who did each cycle, when it was due, when it was
 * done.
 *
 * Both dates are shown side by side and nothing is computed from them. A
 * completion three days after its date simply reads "done Tue 8 Sep · was due
 * Sat 5 Sep", and whether that matters is the reader's judgement. The moment
 * the app renders "3 days late" it has taken a position, which is what the spec
 * means by lateness being visible only as arithmetic.
 *
 * Absences are interleaved by date rather than listed separately, so a gap in
 * someone's completions is visibly a gap in their being there — otherwise a
 * quiet stretch reads as flaking, which is the misreading this view exists to
 * prevent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoreHistorySheet(
    choreName: String,
    history: ChoreHistory?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // One list, newest first, of two kinds of thing.
    val rows: List<Pair<OffsetDateTime, Any>> = remember(history) {
        if (history == null) emptyList()
        else (
            history.entries.map { it.doneAt to (it as Any) } +
                history.absences.map { it.startedAt to (it as Any) }
            ).sortedByDescending { it.first }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = choreName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                "Every time this has been done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            when {
                loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)

                rows.isEmpty() -> Text(
                    "Nothing done yet. It'll show up here once someone ticks it off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> rows.forEach { (_, row) ->
                    when (row) {
                        is ChoreHistoryEntry -> Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = buildString {
                                    append("Done ")
                                    append(formatDueDate(row.doneAt))
                                    row.dueDate?.let { append(" · was due ${formatDueDate(it)}") }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (row.doneBy == row.assignedTo) {
                                    "${row.doneByName}'s turn"
                                } else {
                                    // Named without comment. Who did it is the
                                    // fact; what it says about anyone is not.
                                    "${row.doneByName} did ${row.assigneeName}'s turn"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is Absence -> Text(
                            text = buildString {
                                append("${row.username} away from ${formatDueDate(row.startedAt)}")
                                row.finishedAt?.let { append(" to ${formatDueDate(it)}") }
                                    ?: append(", still away")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The per-person view (F6): completions over a window.
 *
 * A count each, in the order people joined, with everyone listed — including
 * anyone who has done nothing. Re-sorting by count, or hiding the zeroes, would
 * build the leaderboard the whole feature is designed around not being. There
 * are no totals, shares or averages for the same reason: the data is presented
 * and the conclusions belong to the household.
 *
 * Days away sit beside the count so a quiet spell has its explanation attached
 * rather than being left to speak for itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupHistorySheet(
    history: GroupHistory?,
    window: String,
    loading: Boolean,
    onWindowChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "What's been done",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    "week" to "This week",
                    "month" to "This month",
                    "quarter" to "3 months",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = window == value,
                        onClick = { if (window != value) onWindowChange(value) },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()

            if (loading || history == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            } else {
                history.people.forEach { person ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = person.username,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            text = when (person.completed) {
                                0 -> "Nothing yet"
                                1 -> "1 chore done"
                                else -> "${person.completed} chores done"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (person.awayDays > 0) {
                            Text(
                                text = if (person.awayDays == 1) "Away 1 day of this"
                                else "Away ${person.awayDays} days of this",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    "Counted by who actually did it, so covering for someone counts for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Away (F5) — "I'm not at the house".
 *
 * The dialog states the rule it cannot enforce: away is for being physically
 * gone, not for a busy week. The app has no way to check, so the spec's answer
 * is to make the claim impossible to hide rather than to police it — hence the
 * line about everyone seeing it. Social enforcement, zero mechanics.
 *
 * It also names the alternative, because someone reaching for "away" when they
 * mean "not this week" needs somewhere else to go, and busy is right there on
 * the chore itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwayDialog(
    onDismiss: () -> Unit,
    onConfirm: (until: String?) -> Unit,
) {
    // Open-ended by default: "I don't know when I'm back" is the honest common
    // case, and guessing a return date you then have to correct is worse.
    var days by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Away from the house", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You'll be taken out of every rotation here until you're back, and you'll " +
                        "come back in the same place. No turns are owed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "This is for actually being away. If you're sleeping at home but this week " +
                        "is bad, use \"Busy? Pass it on\" on the chore instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Until", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = days == null,
                        onClick = { days = null },
                        label = { Text("Until I say") },
                    )
                    listOf(3, 7, 14, 30).forEach { option ->
                        FilterChip(
                            selected = days == option,
                            onClick = { days = option },
                            label = { Text("$option days") },
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    "Everyone in the household can see that you're away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val until = days?.let {
                    OffsetDateTime.now().plusDays(it.toLong())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                }
                onConfirm(until)
            }) { Text("I'm away") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// EditChoreDialog lived here too: a second, differently shaped form for the
// same facts the create dialog collected. Editing now reuses CreateChoreFlow
// prefilled, so a household changing "weekly" to "every 3 days" answers the
// question in the screen it first answered it in, and Delete sits at the foot
// of that flow rather than in a dialog of its own.

// CreateEntryDialog lived here: one AlertDialog with a "Does this repeat?"
// segmented control branching into two field sets. It is replaced by
// CreateChoreFlow, which asks how you know it is time rather than whether it
// repeats, and gives each answer a screen of its own instead of one dialog
// that scrolled past the fold on a 360dp phone.

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
internal fun DueDatePickerDialog(
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
internal fun formatDueDate(due: OffsetDateTime): String {
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
