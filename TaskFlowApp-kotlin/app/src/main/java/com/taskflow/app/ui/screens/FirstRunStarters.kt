package com.taskflow.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One offered chore, and the schedule it arrives with.
 *
 * The wording is stated rather than derived so the row can show it before the
 * chore exists. It matches what `scheduleWording()` will say once it does, and
 * the two are checked against each other by eye rather than by code: five
 * strings that change once a year do not need a mechanism.
 */
data class StarterChore(
    val name: String,
    val kind: ChoreKind,
    val intervalDays: Int = 7,
    val weekday: Int = 2,
    val wording: String,
    val chosen: Boolean = true,
)

/**
 * The offered set, from the deck.
 *
 * It carries a second job besides saving typing: it teaches the four schedule
 * kinds by example, before anyone meets the create flow. Each row shows its
 * wording underneath, so "as needed" and "Tuesdays" are met as facts about real
 * chores rather than as options in a list.
 */
private val STARTERS = listOf(
    StarterChore("Trash", ChoreKind.AS_NEEDED, wording = "as needed"),
    StarterChore("Bathroom", ChoreKind.INTERVAL, intervalDays = 4, wording = "every 4 days"),
    StarterChore("Kitchen floor", ChoreKind.INTERVAL, intervalDays = 7, wording = "weekly"),
    StarterChore("Recycling", ChoreKind.FIXED_DATE, weekday = 2, wording = "Tuesdays"),
    StarterChore("Hallway vacuum", ChoreKind.INTERVAL, intervalDays = 14, wording = "fortnightly"),
)

/**
 * The first thing a new household sees: an offer, not an empty board.
 *
 * The spec's onboarding loop is create or join, add three to five chores, land
 * on the board. Until now a new group landed on an empty state with nothing to
 * act on, which is the moment a household is most willing to agree things and
 * least able to.
 *
 * Everything here is editable afterwards, and the copy says so, because a
 * preset accepted wholesale is a schedule nobody actually agreed. That is worth
 * watching for in the sessions: convenience here costs the conversation the app
 * exists to start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunStarters(
    groupName: String,
    working: Boolean,
    error: String?,
    onSkip: () -> Unit,
    onAdd: (List<StarterChore>) -> Unit,
) {
    val rows = remember { mutableStateListOf<StarterChore>().apply { addAll(STARTERS) } }
    val chosen = rows.filter { it.chosen && it.name.isNotBlank() }

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(groupName, fontWeight = FontWeight.Bold) },
                        actions = {
                            TextButton(onClick = onSkip, enabled = !working) { Text("Skip") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "${chosen.size} chosen · everyone takes turns in join order",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onAdd(chosen) },
                                enabled = !working && chosen.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when {
                                        working -> "Adding…"
                                        chosen.size == 1 -> "Add 1 chore"
                                        else -> "Add ${chosen.size} chores"
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "What needs doing round here?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Pick a few to start with. You can change the schedule, the order " +
                            "and what counts as done at any point, and anyone in the house can.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    rows.forEachIndexed { index, starter ->
                        StarterRow(
                            starter = starter,
                            enabled = !working,
                            onToggle = { rows[index] = starter.copy(chosen = !starter.chosen) },
                            onRename = { rows[index] = starter.copy(name = it) },
                        )
                    }

                    // "Something else" adds a blank row rather than opening the
                    // create flow: this screen is one decision, and sending
                    // somebody into a two-screen form and back would lose the
                    // rest of their choices on the way.
                    OutlinedButton(
                        onClick = {
                            rows.add(
                                StarterChore(
                                    name = "",
                                    kind = ChoreKind.AS_NEEDED,
                                    wording = "as needed",
                                ),
                            )
                        },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Something else")
                    }
                }
            }
        }
    }
}

@Composable
private fun StarterRow(
    starter: StarterChore,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (starter.chosen) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (starter.chosen) MaterialTheme.colorScheme.primary.copy(alpha = .5f)
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = starter.chosen, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                // The name is a field, not a label: "Trash" is what one
                // household calls it and "Bins" is what the next one does, and
                // being able to say so here is the difference between a list
                // you accept and a list you agree.
                BasicNameField(
                    value = starter.name,
                    enabled = enabled && starter.chosen,
                    onValueChange = onRename,
                )
                Text(
                    text = starter.wording,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BasicNameField(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text("Name it") },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
