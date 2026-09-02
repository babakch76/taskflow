package com.taskflow.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskflow.app.data.model.MemberInfo
import com.taskflow.app.data.model.ScheduleType
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The four ways a chore can come round, as the flow asks about them.
 *
 * Deliberately not the same vocabulary as [ScheduleType]. The wire needs
 * "interval" / "fixed_date" / "as_needed" / one-off-as-a-task; the person adding
 * the chore is answering "how do you know it's time?", and never sees a type
 * named at all. [scheduleType] is where the two meet.
 */
enum class ChoreKind(val scheduleType: String) {
    INTERVAL(ScheduleType.INTERVAL),
    FIXED_DATE(ScheduleType.FIXED_DATE),
    AS_NEEDED(ScheduleType.AS_NEEDED),

    /**
     * Stored as a *task*, not a chore — the one-off shape predates the chore
     * model and is still where a bill or a repair belongs. The flow hides that
     * split completely; only [CreateChoreFlow]'s caller knows which endpoint a
     * draft ends up at.
     */
    ONE_OFF(ScheduleType.ONE_OFF),
}

/**
 * Everything the two screens collect, in one value.
 *
 * Held by the caller rather than inside the flow so that Back between screens,
 * and a rejected submission, both keep what was typed — the same reason the
 * merged dialog it replaces stays open until the write lands (B-7).
 */
data class ChoreDraft(
    val name: String = "",
    val kind: ChoreKind? = null,
    val intervalDays: Int = 4,
    val byWeekday: Boolean = true,
    val weekday: Int = 2,
    val monthDay: Int = 1,
    val neededBy: String? = null,
    val dueDate: OffsetDateTime? = null,
    /** Turn order for the rotating types. Order *is* the rotation. */
    val rotation: List<String> = emptyList(),
    /** The single owner of a one-off. */
    val assignee: String? = null,
    val doneLine: String = "",
) {
    val rotating: Boolean get() = kind != null && kind != ChoreKind.ONE_OFF
}

/** The interval presets the deck offers, plus a way out of them. */
private val INTERVAL_PRESETS = listOf(1, 2, 3, 4, 5, 6, 7, 14, 30)

/**
 * Adding a chore, asked in plain language — section 3 of the v2 deck.
 *
 * Two screens rather than one long form, and the schedule types are never
 * named. Screen 1 asks **how do you know it's time to do it?** and infers the
 * type from the answer; screen 2 asks only what that answer actually needs.
 * Reaching "as needed" is then a matter of recognising your own situation
 * rather than of learning a taxonomy — which is the whole point, and the thing
 * the housemate sessions are meant to test.
 *
 * A full-screen surface, not a dialog box: the form is taller than any dialog
 * and the old one already scrolled awkwardly. It is presented *as* a Dialog
 * with the platform width disabled, which is the cheapest way to get a
 * full-screen surface without teaching the nav graph about a flow that belongs
 * to one screen.
 *
 * The X always asks before discarding, even when nothing has been typed. That
 * looks like a bug until you watch someone lose a half-filled form: an
 * unconditional question is one habit, and a conditional one is a trap that
 * springs on exactly the occasion it matters.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateChoreFlow(
    members: List<MemberInfo>,
    myUserId: String?,
    submitting: Boolean,
    serverError: String?,
    onDismiss: () -> Unit,
    onSubmit: (ChoreDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(ChoreDraft()) }
    var step by remember { mutableIntStateOf(1) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { confirmDiscard = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // System back gets the same question the X does, rather than throwing
        // the form away silently.
        BackHandler(enabled = true) {
            if (step == 2) step = 1 else confirmDiscard = true
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { confirmDiscard = true }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        title = {
                            Column {
                                Text("New chore", fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Step $step of 2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (step == 2) {
                                OutlinedButton(
                                    onClick = { step = 1 },
                                    enabled = !submitting,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Back") }
                            }
                            Button(
                                onClick = {
                                    if (step == 1) {
                                        // The defaults have to go *into* the
                                        // draft here. Filling them in at render
                                        // time instead showed a rotation of
                                        // everyone while the draft still held
                                        // none, so Add chore then failed with
                                        // "pick at least one person" against a
                                        // list of three visible names.
                                        draft = draft.withDefaults(members, myUserId)
                                        step = 2
                                        localError = null
                                    } else {
                                        val problem = draft.problem(members)
                                        if (problem != null) localError = problem
                                        else { localError = null; onSubmit(draft) }
                                    }
                                },
                                // Screen 1 will not let you past a chore with no
                                // name or no answer: screen 2 is built entirely
                                // out of that answer, so there is nothing to show.
                                enabled = !submitting &&
                                    (step == 2 || (draft.name.isNotBlank() && draft.kind != null)),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    when {
                                        step == 1 -> "Next"
                                        submitting -> "Adding…"
                                        else -> "Add chore"
                                    },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val shown = localError ?: serverError
                    AnimatedVisibility(visible = shown != null) {
                        Text(
                            text = shown ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (step == 1) {
                        StepOne(draft = draft, onDraft = { draft = it })
                    } else {
                        StepTwo(
                            draft = draft,
                            members = members,
                            myUserId = myUserId,
                            onDraft = { draft = it },
                        )
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this chore?") },
            text = { Text("Nothing has been added to the board yet.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

/**
 * Everyone, you first — the deck's default, and the only order that needs no
 * explaining on a first run. A one-off defaults to you for the same reason.
 *
 * Applied when the flow moves to screen 2, so the draft and the screen agree.
 */
private fun ChoreDraft.withDefaults(members: List<MemberInfo>, myUserId: String?): ChoreDraft {
    if (!rotating) {
        return copy(assignee = assignee ?: myUserId ?: members.firstOrNull()?.id)
    }
    if (rotation.isNotEmpty()) return this
    val everyoneYouFirst = (listOfNotNull(myUserId) + members.map { it.id })
        .distinct()
        .filter { id -> members.any { it.id == id } }
    return copy(rotation = everyoneYouFirst)
}

/** What is still missing, phrased for the person rather than for the endpoint. */
private fun ChoreDraft.problem(members: List<MemberInfo>): String? = when {
    name.isBlank() -> "Give the chore a name"
    kind == null -> "Say how you know it's time to do it"
    rotating && rotation.isEmpty() -> "Pick at least one person to take a turn"
    !rotating && assignee == null && members.isNotEmpty() -> "Pick who's doing it"
    else -> null
}

// ═══════════════════════════════════════════════════════════════
// Screen 1 — what, and how you know
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StepOne(draft: ChoreDraft, onDraft: (ChoreDraft) -> Unit) {
    Text("What's the chore?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = draft.name,
        onValueChange = { onDraft(draft.copy(name = it)) },
        label = { Text("Name") },
        placeholder = { Text("e.g. Trash") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "What a housemate would call it on a whiteboard.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(2.dp))
    Text(
        "How do you know it's time to do it?",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    // The four answers, in the deck's words. The type each one implies is never
    // shown; it is confirmed back on the board afterwards in the app's own
    // wording ("every 4 days", "Tuesdays", "as needed").
    KindOption(
        selected = draft.kind == ChoreKind.INTERVAL,
        title = "A while since it was last done",
        example = "The bathroom, the floors — it builds up again after a few days.",
        onClick = { onDraft(draft.copy(kind = ChoreKind.INTERVAL)) },
    )
    KindOption(
        selected = draft.kind == ChoreKind.FIXED_DATE,
        title = "A particular day decides it",
        example = "Bin collection on Tuesday, rent on the 1st.",
        onClick = { onDraft(draft.copy(kind = ChoreKind.FIXED_DATE)) },
    )
    KindOption(
        selected = draft.kind == ChoreKind.AS_NEEDED,
        title = "You can just see it needs doing",
        example = "The bin's full, the bulb's gone. No schedule — it takes a turn each time.",
        onClick = { onDraft(draft.copy(kind = ChoreKind.AS_NEEDED)) },
    )
    KindOption(
        selected = draft.kind == ChoreKind.ONE_OFF,
        title = "It's a one-time thing",
        example = "A repair, a delivery, a bill to pay once.",
        onClick = { onDraft(draft.copy(kind = ChoreKind.ONE_OFF)) },
    )
}

@Composable
private fun KindOption(selected: Boolean, title: String, example: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = example,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Screen 2 — only what the answer needs
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepTwo(
    draft: ChoreDraft,
    members: List<MemberInfo>,
    myUserId: String?,
    onDraft: (ChoreDraft) -> Unit,
) {
    when (draft.kind) {
        ChoreKind.INTERVAL -> IntervalBlock(draft, onDraft)
        ChoreKind.FIXED_DATE -> FixedDateBlock(draft, onDraft)
        ChoreKind.AS_NEEDED -> AsNeededBlock()
        ChoreKind.ONE_OFF -> OneOffBlock(draft, onDraft)
        null -> Unit
    }

    HorizontalDivider()

    if (draft.rotating) {
        Text("Whose turn does it take?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Everyone here takes it in turn. Drag to change the order.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RotationPicker(
            members = members,
            rotation = draft.rotation,
            onChange = { onDraft(draft.copy(rotation = it)) },
        )
    } else {
        Text("Who's doing it?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "You assign a one-off yourself. It stays with that person.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val chosen = draft.assignee ?: myUserId
            members.forEach { member ->
                FilterChip(
                    selected = chosen == member.id,
                    onClick = { onDraft(draft.copy(assignee = member.id)) },
                    label = {
                        Text(
                            buildString {
                                append(if (member.id == myUserId) "You" else member.username)
                                if (member.away) append(" · away")
                            },
                        )
                    },
                )
            }
        }
    }

    HorizontalDivider()

    Text("What counts as done?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "One line, agreed once, so it isn't argued every time. You can skip it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft.doneLine,
        onValueChange = { if (it.length <= 140) onDraft(draft.copy(doneLine = it)) },
        label = { Text("What done means") },
        placeholder = { Text("Bin emptied and a fresh bag in") },
        supportingText = { Text("${draft.doneLine.length}/140") },
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalBlock(draft: ChoreDraft, onDraft: (ChoreDraft) -> Unit) {
    var other by remember { mutableStateOf(draft.intervalDays !in INTERVAL_PRESETS) }
    Text("How many days between turns?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        INTERVAL_PRESETS.forEach { days ->
            FilterChip(
                selected = !other && draft.intervalDays == days,
                onClick = { other = false; onDraft(draft.copy(intervalDays = days)) },
                label = { Text("$days") },
            )
        }
        FilterChip(
            selected = other,
            onClick = { other = true },
            label = { Text("Other") },
        )
    }
    if (other) {
        OutlinedTextField(
            value = draft.intervalDays.toString(),
            onValueChange = { typed ->
                val n = typed.filter { it.isDigit() }.take(3).toIntOrNull()
                if (n != null && n in 1..365) onDraft(draft.copy(intervalDays = n))
            },
            label = { Text("Days") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
    }
    Text(
        "Counted from when it was last done, not from the calendar. Done late, the next turn moves with it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NeededByField(draft, onDraft)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FixedDateBlock(draft: ChoreDraft, onDraft: (ChoreDraft) -> Unit) {
    Text("Which day decides it?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = draft.byWeekday,
            onClick = { onDraft(draft.copy(byWeekday = true)) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Day of week") }
        SegmentedButton(
            selected = !draft.byWeekday,
            onClick = { onDraft(draft.copy(byWeekday = false)) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Day of month") }
    }

    if (draft.byWeekday) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 0 = Sunday on the wire; shown Monday-first, as a week is read.
            listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")
                .forEach { (day, label) ->
                    FilterChip(
                        selected = draft.weekday == day,
                        onClick = { onDraft(draft.copy(weekday = day)) },
                        label = { Text(label) },
                    )
                }
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..31).forEach { d ->
                FilterChip(
                    selected = draft.monthDay == d,
                    onClick = { onDraft(draft.copy(monthDay = d)) },
                    label = { Text("$d") },
                )
            }
        }
    }

    Text(
        text = if (draft.byWeekday) {
            "The date comes from the calendar. Miss it and it rolls to the next " +
                "${weekdayLabel(draft.weekday)} — still yours."
        } else {
            "The date comes from the calendar. Miss it and it rolls to the next month — still yours."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NeededByField(draft, onDraft)
}

@Composable
private fun AsNeededBlock() {
    Text("No date on this one", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "It sits on the board as someone's turn until it's done. Nothing is due, " +
            "and there are no date reminders — only a nudge when a turn starts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OneOffBlock(draft: ChoreDraft, onDraft: (ChoreDraft) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Text("When does it need doing?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { picking = true }) {
            Text(draft.dueDate?.let { formatDueDate(it) } ?: "Date (optional)")
        }
        if (draft.dueDate != null) {
            TextButton(onClick = { onDraft(draft.copy(dueDate = null)) }) { Text("Clear") }
        }
    }
    Text(
        "A one-off is assigned once and doesn't rotate. Leave the date empty if there isn't one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (picking) {
        DueDatePickerDialog(
            initial = draft.dueDate,
            onDismiss = { picking = false },
            onPicked = { iso ->
                picking = false
                onDraft(draft.copy(dueDate = OffsetDateTime.parse(iso)))
            },
        )
    }
}

@Composable
private fun NeededByField(draft: ChoreDraft, onDraft: (ChoreDraft) -> Unit) {
    OutlinedTextField(
        value = draft.neededBy.orEmpty(),
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() || it == ':' }.take(5)
            onDraft(draft.copy(neededBy = cleaned.ifBlank { null }))
        },
        label = { Text("Needed by (optional)") },
        placeholder = { Text("20:00") },
        singleLine = true,
        modifier = Modifier.width(180.dp),
    )
}

/**
 * The turn order, which *is* the rotation — the list is the schedule, not a set
 * of people who happen to be involved.
 *
 * Reorder is offered two ways on purpose. The deck shows a drag handle, and
 * dragging is what most people reach for; the up/down controls beside it do the
 * same job for anyone using a screen reader or switch access, for whom a drag
 * is close to impossible. They are also the only version that can be driven
 * deterministically from a test harness.
 *
 * Away members stay in the list, tagged. Assignment steps over them in the
 * engine, and removing them here would quietly rewrite the rotation for the
 * fortnight somebody is on holiday.
 */
@Composable
private fun RotationPicker(
    members: List<MemberInfo>,
    rotation: List<String>,
    onChange: (List<String>) -> Unit,
) {
    // Anyone not in the order yet sits below it, greyed, waiting to be added.
    val inOrder = rotation.filter { id -> members.any { it.id == id } }
    val rest = members.map { it.id }.filterNot { it in inOrder }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        inOrder.forEachIndexed { index, id ->
            val member = members.first { it.id == id }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = member.username.take(1).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(member.username, style = MaterialTheme.typography.bodyMedium)
                        if (index == 0 || member.away) {
                            Text(
                                text = listOfNotNull(
                                    "first turn".takeIf { index == 0 },
                                    "away".takeIf { member.away },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onChange(inOrder.moved(index, index - 1)) },
                        enabled = index > 0,
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(
                        onClick = { onChange(inOrder.moved(index, index + 1)) },
                        enabled = index < inOrder.lastIndex,
                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                    TextButton(onClick = { onChange(inOrder.filterNot { it == id }) }) { Text("Remove") }
                }
            }
        }

        rest.forEach { id ->
            val member = members.first { it.id == id }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange(inOrder + id) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append(member.username)
                        if (member.away) append(" · away")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(inOrder + id) }) { Text("Add") }
            }
        }
    }
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (to !in indices) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

private fun weekdayLabel(day: Int): String {
    val dayOfWeek = if (day == 0) DayOfWeek.SUNDAY else DayOfWeek.of(day)
    return dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}
