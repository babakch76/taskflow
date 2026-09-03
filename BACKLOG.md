# TaskFlow — backlog

Running list of known bugs and missing features across both projects. Append as
things turn up; keep the newest reports at the top of each section.

Status key: **OPEN** · **IN PROGRESS** · **DONE**

> **The project pivoted on 2026-08-21** to the household chore-rotation app in
> `taskflow_feature_spec_2.md`. Read **[V2-HANDOFF.md](V2-HANDOFF.md)** first —
> several items below were resolved by that spec rather than by being fixed,
> and some (bulk status, three-state status) are now non-goals.

## v2 progress

- **Manager role + deadline permissions — REMOVED.** The spec makes chore
  editing open to every member, so the gate contradicted it.
- **F1 (Board) — DONE and device-verified** (2026-08-31). Driven on the emulator
  against a local backend: the three sections, single-group auto-open and its
  Back behaviour, one-tap completion by a non-assignee with `done_by` recorded,
  undo restricted to the doer (another member's completed row is greyed and
  sends no request), and amber — not red — overdue. See B-6 for the one defect
  this turned up.
- **F2 (chores, rotation, spawning) — DONE** on `v2-chores`, in three commits.
  Both open questions were settled first: chores/occurrences are **new tables
  alongside `tasks`** (existing tasks stay as the spec's one-off type). What
  landed: the chore/occurrence model with CRUD and the board reading it;
  completion spawning the next occurrence, interval dates counted from the
  completion, and a missed fixed date rolling forward with the same assignee;
  and the unified turn rule — a cover counts as the doer's turn, the chore goes
  back to whoever owed it, and the rotation resumes after the coverer.
  Device-verified at each step.
- **F3 (reminders + quiet hours) — DONE**, in three commits. Reminders are
  scheduled **on the device** with AlarmManager — no Firebase, no push service,
  no server-side scheduler, and nothing about anyone's chores leaves the phone.
  Quiet hours live on the user record so they survive a reinstall. Two reminders
  per occurrence to the assignee only, plus a flat 48-hourly nudge for anything
  still open. See B-8 for the one gap this delivery choice leaves.
- **F4 (done-line + edit diffs) — DONE**, in two commits. The occurrence detail
  sheet shows the done-line beside the chore's frequency (it had been stored
  since F2 and displayed nowhere), and "Edit chore" on that sheet lets any
  member change the name, done-line, schedule parameters and rotation. Only
  changed fields are sent, and the group sees the diff the backend already
  produced — the spec's own example, "weekly → every 3 days", now reachable
  from the UI. The schedule *type* stays immutable.
- **F5 (busy/away) — IN PROGRESS.** The busy pass is done server-side:
  `POST /occurrences/{id}/pass` hands the chore to the next person in the
  rotation, refreshes an already-overdue date to tomorrow, and keeps the debt
  with the passer via `passed_from`. It reuses `nextTurn` rather than growing a
  second rule — whoever ends up doing a passed chore is doing it for the passer,
  which is exactly a voluntary cover. No activity event is written: the spec
  gives a pass one private notification to the receiver and no group broadcast.
  **Away** is done server-side too: `PUT /groups/{id}/members/me/away`, per
  household, open-ended or dated. Rotation assignment steps over away members
  and they re-enter at the same position on return — no bookkeeping needed,
  since the order never changes. An expired period needs no cleanup. Away is
  *not* a debt: it never cancels a turn already owed. `ListMembers` reports it,
  because the spec makes away deliberately impossible to hide.
  The **client** has both now: "Busy — pass it on" on an open occurrence of
  yours, and an away toggle in the group's overflow menu whose dialog states the
  rule the app cannot enforce and points at busy as the alternative. Away shows
  on the member list and beside names in every rotation picker.
- **F6 (history) — IN PROGRESS**, the last feature in the build order. It began
  with a correction rather than a view: away was stored as a *current flag*, so
  a past absence was unrecoverable — and the per-person view counts completions
  over a window, where an unexplained dip reads as exactly the flaking the spec
  forbids. Away is now an `away_periods` record, closed rather than erased on
  return, with a one-time backfill so upgrading doesn't silently un-away anyone.
  Both **endpoints** are done: per chore, a timeline of completions with the
  due date and the done-at and deliberately no "late" flag; per person,
  completions in a window counted by `done_by`, in join order, with everyone
  listed and days-away alongside. The **client** has both: "History" on the
  occurrence detail, and "What's been done" in the group's overflow menu.
- **The spec's build order is complete** — F1 through F6. What remains is
  deployment (see the handoff) and whatever the report needs.
- **One create button, one form — DONE** (2026-09-01, UI cleanup phase 2). The
  board had two FABs, a primary one for chores and a small one for one-off
  tasks, which made the household choose a storage model before it could write
  anything down. There is now one "Add chore" button and one dialog, whose
  shared fields (name, done-line) are followed by a single question — **"Does
  this repeat?"**, *One time* / *Repeats* — and the answer decides the rest of
  the form. *One time* offers an assignee (defaulting to the creator) and an
  optional date and submits through the **task** path; *Repeats* offers the
  schedule and rotation and submits through the **chore** path. No endpoint
  changed, and `createTask` grew only a `dueDate` argument it already had a
  request field for.
  The one-off branch deliberately has no "Nobody" chip: the spec assigns
  everything, always (constraint 8). See B-12 for the leftover on the detail
  sheet.
  Device-verified: a one-off created through the merged form landed on the
  board with the chosen person and date (`POST /tasks`, 201); a fortnightly
  chore landed with its first occurrence (`POST /chores`, 201); with the
  backend killed the dialog stayed open with the name, interval and rotation
  intact and "Network error: Failed to connect to /10.0.2.2:8080" in its own
  error slot (B-7 not regressed), and pressing Create again once the server was
  back submitted the same form successfully.
- **v2 UI phase 1 (board grammar) — DONE** (2026-09-02). Implements section 1
  of the `Chore UI v2` deck.
  - **Status language is open / done, nothing else.** The `in_progress` pill is
    gone from the row, `StatusChip` deleted with it (it had no other caller),
    and the task detail sheet offers **Open / Done** rather than three states.
    `"todo"` stays as the wire value — only the word changed — so nothing needs
    migrating and legacy `in_progress` rows still read as open. `statusLabel`
    keeps its `in_progress` case for the activity feed, which is a record of
    what happened rather than of what the app would say today.
  - **The row is three lines now**, per the deck: name, then how often it comes
    round ("every 4 days", "Thursdays", "as needed"), then whose turn it is and
    when it is wanted ("Due Sun 6 Sep", "Your turn", "maya · due Sun 6 Sep",
    "Done by maya · Wed 26 Aug"). Putting the frequency on the row is F4's
    point: half an agreement on screen is not the treaty.
  - A one-off states its date on the schedule line ("one-off · Sat 29 Aug"),
    because a one-off's date *is* its schedule, and the cycle line below then
    carries only the name. **The amber overdue treatment follows the date**
    onto whichever line holds it — the first cut hard-wired it to the cycle
    line and an overdue one-off lost its amber entirely.
  - Section headers take the deck's wording: **YOURS / OTHERS / DONE THIS
    CYCLE**, and the per-section count comes off the header.
  - The done-line stays in the sheet only. The deck does not put it on the row
    and the prompt says sheet if in doubt.
  Device-verified at a true 360dp (density 480 on a 1080px screen) with a
  46-character chore name, an as-needed row, an overdue one-off and a done row:
  nothing truncates, the long name wraps to two lines, and nothing is red.

- **v2 UI phase 3 (stepped creation) — DONE** (2026-09-02). Section 3 of the
  deck. The merged dialog is replaced by a full-screen two-screen flow in
  `CreateChoreFlow.kt`; `CreateEntryDialog` is deleted.
  - **The schedule types are never named.** Screen 1 asks *how do you know it's
    time to do it?* and infers the type from the answer; screen 2 asks only what
    that answer needs. Reaching "as needed" is a matter of recognising your own
    situation rather than learning a taxonomy — which is the thing the housemate
    sessions are meant to test.
  - Deck copy verbatim throughout, including the four answers and their
    examples, every parameter explainer, and the discard sheet.
  - **Two things became sayable that were not.** `createChore` never passed
    `fixed_month_days` or `needed_by_time`, both of which the request already
    had — so "rent on the 1st" could not be expressed at all. The flow offers
    Day of week / Day of month and an optional needed-by time.
  - The X always asks, even with nothing typed. A conditional prompt is a trap
    that springs on exactly the occasion it matters.
  - Rotation reorder is offered as up/down controls beside the drag affordance.
    The deck shows a drag handle; the buttons do the same job for screen-reader
    and switch users, and are the only version a test harness can drive.
  - **One bug found only by driving it:** the default rotation (everyone, you
    first) was computed at render time and never written into the draft, so
    *Add chore* failed with "pick at least one person to take a turn" against a
    visible list of three names. Defaults are now applied to the draft on the
    way into screen 2, so the screen and the model agree.
  Device-verified end to end, all four types: interval (`interval_days: 14`),
  fixed-date by month day (`fixed_month_days: [1]`, 201), as-needed, and a
  one-off routed to `POST /tasks` with its assignee and date. Next stays
  disabled until a name and an answer exist; Back preserves both screens (the
  14-day choice survived a round trip); X shows the discard sheet on both
  screens and Keep editing returns to the form. The new row lands highlighted
  with a "{Chore} added." snackbar.

- **v2 UI phase 3, copy revision — DONE** (2026-09-02). Babak's call, and a
  deliberate departure from the deck, whose copy the prompt had made canonical.
  - Screen 1's chooser was a wall of prose: four sentence-length labels each
    with an example underneath. Two of them did not say what they meant —
    "a while since it was last done" never mentions the thing it is actually
    asking for, which is a number of days.
  - The heading is now **"How often?"** and the four labels are **Every so many
    days / A fixed day of the week or month / As needed / A one-time thing**.
    The schedule types are still never named; the labels just say what the
    answer *is* rather than describing the situation it comes from.
  - The explanations moved behind a **circled "?"** on each row, using
    Material's own tooltip so it behaves the way a "?" does elsewhere on the
    phone. **Persistent, not timed:** it stays until dismissed by tapping
    outside or tapping the "?" again. A timeout would be a race against the
    reader, and two sentences outlast any sensible one. Tapping the "?" does
    not select the option.
  - **Long dashes are out of every user-facing string in the app**, not just
    the new ones: the away banner, quiet hours, the pass confirmation and
    message, the occurrence sheet, the rotation note. Empty-value placeholders
    that were a bare dash now say "Nobody" and "Not recorded".
  **Note for the report:** the app's copy and the research deck now differ here.
  The deck remains the stimulus that was designed; if the housemate sessions run
  against the app, they test these labels instead.

- **v2 UI phase 4 (edit and delete) — DONE** (2026-09-03). Editing reuses the
  two-screen flow, prefilled; `EditChoreDialog` is deleted.
  - **Backend, with tests.** A chore's schedule *type* can now move among
    interval / fixed_date / as_needed, which it could not: `UpdateChoreRequest`
    had no `schedule_type` at all. one_off stays unreachable in both directions
    and returns 400 — a one-off is a row in `tasks`, so that switch is a delete
    and an add, not a column.
  - **The one new rule, and it lives in a comment beside the code:** the
    assignee never changes because of an edit; a changed schedule re-dates the
    open occurrence from the last completion, or from the chore's creation if
    it has never been done; moving to as-needed clears the date and moving away
    from it sets one; a rotation reorder applies from the next spawn only.
  - A fixed-date chore whose anchor is old can land on a date already past.
    Left alone rather than clamped: `rollForwardFixedDates` already walks a
    lapsed fixed date forward keeping the same assignee, and runs on the next
    board read. A clamp here would be a second rule doing the first one's job.
  - **A scan bug worth remembering:** the anchor query used
    `COALESCE(MAX(done_at), created_at)`, and go-sqlite3 parses a DATETIME from
    the column's *declared* type — which `COALESCE` and `MAX()` throw away. The
    value came back as a raw string and every edit 500'd. Two plain selects
    instead.
  - Screen 1 disables the kinds a chore cannot become and says why. X asks
    "Discard changes? / Your edits won't be saved." Delete sits at the foot of
    screen 2 in the edit flow only, reusing F-2's confirm.
  Four backend tests (re-date keeps the assignee, as-needed clears and restores
  the date, the one-off boundary is refused, a reorder leaves the current turn
  alone); full suite green twice. Device-verified: weekly → Fridays sent only
  `{"fixed_weekdays":[5],"schedule_type":"fixed_date"}`, kept the assignee,
  re-dated 9 Sep → Fri 4 Sep, and broadcast "Clean the bathroom: schedule:
  weekly → Fridays"; switching to as-needed cleared the date; X mid-edit
  discarded and left the name untouched; delete returned 204 and took the row.

- **v2 UI phase 2 (busy pass by swipe) — DONE** (2026-09-03), with one gap
  recorded below.
  - **Swipe your own open chore row**: left passes it, right marks it done. Not
    on somebody else's row (neither action is yours to take about their turn),
    not on a one-off (no rotation to pass along), and not when there is nobody
    to pass to — a rotation of one, or everyone else away. The detail sheet
    keeps its own button, so the gesture is a shortcut rather than the only way.
  - **A one-line hint** under the first row of YOURS, gone on the first swipe
    and never shown again. Stored in plain SharedPreferences: whether somebody
    has found a gesture is not household data and has no business on the server.
  - **The confirmation states the debt rule in plain language**, with the right
    date variant: "due tomorrow" only when the pass actually re-dates an
    already-due chore, the real date when it is still ahead, and "it's their
    turn" for an as-needed chore that has no date at all.
  - **Backend, `covered_by` (+ tests).** An occurrence handed back under the
    debt rule records who covered it, so the board can say
    *"Back to you after maya covered"*. The passer's board meanwhile shows
    *"Next turn comes back to you. maya is covering this one"*, from the
    `passed_from` it already had. The migration added the column to a populated
    database without disturbing it.
  - **The bug worth remembering:** the first condition compared the new
    assignee against `assigned_to` alone, which catches a voluntary cover and
    misses a *pass* — the very case the marker exists for. The unit test I
    wrote first covered only the cover case and passed. It now mirrors
    `nextTurn`'s own `owedBy` calculation, and there is a second test for the
    pass path.
  - **Gap: no Undo on the pass snackbar.** The deck shows one and the prompt
    asks for it, but undoing a pass means writing `assigned_to` back and
    clearing `passed_from`, and no endpoint does that. The phase authorises one
    backend change (`covered_by`), so the endpoint was not invented. The
    snackbar reads "Passed to maya. It's yours again next cycle." with no
    action. **Needs a decision**: add `DELETE /occurrences/{id}/pass`, or drop
    Undo from the design.
  Device-verified: hint shown then dismissed on first swipe; a one-person
  rotation correctly does not swipe; confirmation named maya and used the
  future-date variant; the pass created no group activity entry (constraint 7);
  both markers appear on the right rows.

- **v2 UI phase 5 (first run) — DONE** (2026-09-03). A new group lands on an
  offer instead of an empty board, closing the spec's onboarding loop: create or
  join, add three to five chores, land on the board.
  - The deck's five starters, each showing the wording it will have (Trash / as
    needed, Bathroom / every 4 days, Kitchen floor / weekly, Recycling /
    Tuesdays, Hallway vacuum / fortnightly). That teaches the schedule kinds by
    example before anyone meets the create flow, which is the second job the
    list does.
  - Rows are keep-or-remove **and renameable**: "Trash" is what one household
    calls it and "Bins" is what the next one does, and being able to say so is
    the difference between a list you accept and one you agree. "Something
    else" adds a blank row rather than opening the create flow, which would
    lose the rest of the choices on the way out and back.
  - **Skip** is always there. Each kept starter becomes an ordinary chore,
    editable through phase 4 like any other.
  - Creation is sequential so a partial failure is legible — five requests in
    flight can fail in five ways and leave the household guessing which chores
    exist. (An earlier comment claimed it preserved board order; it does not,
    since the board sorts by status and due date. Corrected rather than left.)
  Device-verified: new group "Lake House" → five offered → three removed, one
  renamed Trash to Bins → "Add 2 chores" → board shows exactly Bathroom (every
  4 days, dated) and Bins (as needed, "Your turn"), nothing else. Skip on a
  second new group added nothing and landed on the empty board.

- **v2 UI phase 6 (appearance) — DONE** (2026-09-03). **System / Light / Dark**
  in the Dashboard overflow menu, beside quiet hours, because they are the same
  kind of thing: settings about the person, not the household.
  - The app's existing dark scheme turned out to *be* the deck's dark palette
    already — background `#0F1117`, surface `#181A24`, violet `#A78BFA`, green
    `#34D399` all match. The one difference was the overdue amber, `#E3B341`
    picked by eye at F1 against the deck's `#E0A534`. Now the deck's.
  - **A real bug the toggle exposed:** `overdueColor()` asked
    `isSystemInDarkTheme()`. That was right while the app only followed the
    system and wrong the moment it could be overridden — a phone in light mode
    showing the app in dark would have drawn the *light* amber onto a dark
    card, which is the one colour in the app that has to stay legible. It now
    asks the theme, via the surface's luminance.
  - **SharedPreferences, not DataStore.** The prompt says DataStore; it is not a
    dependency, and one enum written on a radio tap and read once at startup
    does not need asynchronous storage or a new library. The swipe hint next
    door is kept the same way. Flagged rather than done silently.
  - Kept on the device and not on the server: it is a property of this phone in
    this person's hand, and syncing it would decide the tablet's appearance for
    them.
  - `dynamicColor` stays `false`.
  Device-verified: all three modes; Dark survived a force-stop and relaunch;
  the overdue row is amber in both appearances (dark amber on dark, even with
  the phone's own system theme set to light) and nothing is red on either.

- **The board is the screen — DONE** (2026-09-02, UI cleanup phase 3), in four
  commits:
  - **Calendar tab deleted.** It read `state.tasks` only, so it showed the
    one-off minority of the household's work and hid every rotation chore — on
    the test board it claimed "1 task due this month" while five chores sat on
    the board. Teaching it about occurrences would have meant building the
    scheduling view constraint 5 rules out, so it went instead. That also
    retires the generous reading of constraint 5 the handoff flagged: there is
    no calendar to defend in the report now. Tabs are **Board / Activity /
    Members**, and a plain `TabRow` again.
    Written up in full as
    [`calendar_decision_record.md`](calendar_decision_record.md), which adds the
    reason the code alone does not show: under completion-anchored rotation a
    chore has only **one live occurrence**, so a forward-looking month grid has
    almost nothing to draw. Re-adding one is a spec change, not a UI task.
  - **Quiet hours moved to the Dashboard menu.** It is stored on the user
    record and applies to every group, but it was reached from one group's
    overflow menu. Saving it there now also re-arms every group's alarms,
    because the Dashboard is what schedules them (see B-8).
  - **Away is visible on your own board.** A quiet banner above the sections,
    only while you are away in this group, with an inline "I'm back". Away
    already showed on the member list and in rotation pickers — everywhere
    except the screen the away person is actually looking at.
  - **The Board tab counts open rows** (B-11).
  Device-verified throughout; the details are in each commit message.
- **Undo on the pass snackbar — DONE** (2026-09-03, Babak's call). The v2
  design showed an Undo on "Passed to maya. It's yours again next cycle." and
  there was none. `DELETE /groups/{id}/occurrences/{id}/pass` now exists.

  **Scoped to a mis-swipe, not a change of mind**, which is what Babak asked
  for once the two were separated. `passUndoWindow` is 2 minutes, against the
  10 of `undoWindow`: undoing a completion only rewrites your own history,
  while undoing a pass takes work off somebody else's board after they have
  been told it is theirs.

  Four guards, each with a test:

  - Only the person the debt belongs to (`passed_from`). **Not the receiver**:
    being handed a chore is not consent to hand it back, and if it were, "pass"
    and "refuse" would be the same button.
  - Only while it is still open, so an undo cannot take away work that has
    actually happened.
  - Only inside the window, measured on the server from `passed_at`. The
    snackbar's own timer is not evidence; a client can be slow or paused.
  - No activity event, matching the pass itself. Constraint 7 keeps a pass
    between the two people involved and an undo is the same exchange.

  **The part that needed a migration.** The pass rule moves an already-overdue
  chore's deadline to tomorrow for whoever receives it. A naive undo would
  leave that in place, which makes pass-then-undo **a way to launder
  lateness**: a chore four days late comes back due tomorrow. So the pass now
  records the date it overwrote in a new `occurrences.due_before_pass` column
  and the undo restores it exactly. Written with `COALESCE` so a chain of
  passes keeps the *first* original, since the debt and the right to undo
  belong to whoever passed it first. Read with a plain `SELECT`, never through
  a wrapping function, because go-sqlite3 decides whether to parse a DATETIME
  from the column's declared type and `COALESCE`/`MAX` throw that away. That
  has bitten this file twice.

  Verified past the unit tests: the migration ran against the populated local
  database with every row count unchanged, the four HTTP paths were exercised
  with curl (200 restore, 409 never-passed, 403 receiver, 200 passer), and the
  button itself was driven on the device with Logcat showing `POST .../pass`
  then `DELETE .../pass` two seconds apart, both 200. The seed was left
  canonical afterwards, and the activity table still held 14 rows, which is the
  privacy guard proving itself rather than being asserted.

  **One client-side trap worth keeping.** The snackbar message and the Undo
  offer are written in a *single* state update. Two updates would let the
  snackbar be composed from the first and read an offer that had not landed
  yet, which shows up as an Undo button that is intermittently missing.

- **The return marker said the wrong thing to the wrong person — FIXED**
  (2026-09-03, reported by Babak). The line under a chore that has come back to
  you after a cover read **"Back to you after maya covered"**. Two defects, one
  of which Babak spotted and one which fell out of looking:

  - **Tense.** The cover has already happened, and the turn is already sitting
    on your board. "Back to you after X covered" frames a finished event as a
    pending condition, so it reads as though the turn were still on its way.
    It now reads "maya covered your last turn, so it's yours again".
  - **No ownership test.** The branch checked only that the occurrence had a
    `covered_by` and was open, never that it was *yours*. So every member saw
    the second-person line on somebody else's row: signed in as maya, the board
    said "Back to you after maya covered" on **demo's** row, addressed to the
    wrong person and naming maya in the third person in a sentence aimed at
    her. Other people's rows now read "maya covered demo's last turn", which is
    the useful half for them: it says why the rotation did not move on to
    *them*. Whose row it is is already on the line above.

  Verified on the device from both sides, signed in as demo and as maya. The
  `passedByMe` branch beside it was left alone; its tenses are already right.

- **The screens deck re-shot — DONE** (2026-09-03). Nine slides, 31 screens,
  all captured at 360dp against a running backend after the six v2 UI phases;
  the source screenshots are committed under `screens/` so the deck is
  rebuildable without the seeded database. Re-seeded first, because the scratch
  database had drifted into three groups with duplicate occurrences. The
  seeded completions and the activity feed's timestamps are both **backdated**,
  which matters: seeding completes a weekly chore twice 0.13s apart, and
  unadjusted the history screen said "Done Thu 27 Aug" while the feed said "1h"
  for the same event.

- **The rotation picker can be dragged — DONE** (2026-09-03, Babak's call).
  The deck's copy had always read "Drag to change the order" and the picker had
  only ever had up and down arrows. Rather than delete the promise, the gesture
  now exists: a 48dp handle at the head of each row, and the line reads
  "Everyone takes a turn. Drag to change the order."

  **The arrows stay, deliberately.** Dragging is not something TalkBack can
  perform, so they are the accessible route to the same change rather than a
  leftover; for the same reason the handle's icon is unlabelled, so screen
  readers are not offered a control they cannot work.

  Two things in the implementation are load-bearing and should not be
  "simplified":

  - Each row is wrapped in `key(id)`. Without it a reorder re-keys the
    `pointerInput` under the finger, because a plain `Column` identifies its
    children by position, and the drag dies on the first swap.
  - The gesture reads the order through `rememberUpdatedState` instead of
    closing over the list it started with. Every swap rewrites that list, so a
    captured copy would go stale and the second swap would act on stale
    positions.

  Row heights are measured rather than assumed, since the first row carries a
  "first turn" caption and an away member carries "away", so a fixed step would
  drift. Device-verified: one swap down, two swaps in a single continuous
  gesture in both directions, and the arrows still working alongside.

- Multi-select and bulk status were removed with F1, and the backend endpoint
  behind them is **now gone too** — nothing had called it since, and the
  ViewModel wrapper was unreachable from any screen. The
  `tasks_bulk_updated` activity constant and the client's rendering of it stay,
  because rows written before the removal still carry it.

---

## Bugs

### B-16 · The activity feed still prints raw event types for every v2 event — DONE (2026-09-02)

**Fixed:** `describeEvent` has the five missing cases, so the feed now reads
"maya did a chore / Clean the bathroom" and "demo added a chore" instead of
"maya: occurrence_done". `chore_updated` needed nothing extra — the backend
already writes the diff in words, and `humaniseDetail` passes it through, so
F4's broadcast now shows as "maya changed a chore / Water the plants: schedule:
every 3 days → every 4 days". That sentence is the spec's own example and this
is the first time it was legible in the app.

`eventColor` gained matching entries: chore creation shares the task-creation
primary, deletion the destructive red, and a completion takes the same tertiary
green a done row has. Not a reward — the feed is a record, and it is the one
entry that says something finished.

The fallback no longer prints the raw identifier: an unknown event now reads
"$user made a change". That trades a little debuggability for not leaking
constants into the household's feed; the comment above the function says to add
the case rather than lean on it.

Verified on the emulator: every row in the seeded feed reads as English, and
editing a chore through the API produced the diff line above.

Original report follows.

### B-16 · The activity feed still prints raw event types — was OPEN

**Found** 2026-09-02, while shooting the report screenshots — it was in the
frame, which is how it got noticed.

`describeEvent` (`GroupDetailScreen.kt` ~1457) humanises the v1 events and has
no case for any of the ones F2–F6 added, so they fall through to
`"${actorUsername}: ${eventType}"`. The feed reads:

```
maya: occurrence_done
demo: chore_created
demo: chore_updated
```

The v1 half of the same feed says "demo added a task". This is the defect the
backlog already closed once — "Activity feed leaks developer strings", round 1
— reopened by the pivot, because the chore events were never added to the map.

Missing cases, from `handlers/activity.go`: `chore_created`, `chore_updated`,
`chore_deleted`, `occurrence_done`, `occurrence_reopened`.

**Fix:** five lines in `describeEvent`, plus a look at `humaniseDetail` for the
second line — `chore_updated` carries the edit diff the backend already phrases
("weekly → every 3 days"), which is the good half of F4 and currently renders
unlabelled underneath a debug string.

Worth doing before the report: the activity feed is the app's most visible
shared surface and it is the one screen deliberately left out of the deck
because of this.

---

### B-13 · A fixed-date chore done early came back due the same day — DONE (2026-09-02)

**Reported by Babak**, 2026-09-02: a repeating chore completed before its due
date "stays on the same date" instead of following its pattern.

**Confirmed, and it was real on the fixed-date path.** A "Fridays" chore due
Fri 4 Sep, completed on Wed 2 Sep, spawned its next occurrence **also due Fri 4
Sep**. Put the bins out early and the chore reappears for the same collection,
so it has to be done twice — and a household that is habitually early never
advances the schedule at all.

**Cause:** `nextDueDate` passed the *completion time* to `nextFixedDate`, which
returns the soonest matching weekday strictly after it. Completing on Wednesday
made "the soonest Friday after Wednesday" the very Friday just dealt with.

**Fix:** the fixed-date scan now starts from the later of the completion and
the completed occurrence's own due date — the calendar slot just satisfied.
Both ends matter and neither anchor works alone: anchoring only to the due date
would put a chore finally done three weeks late onto a Friday that has already
gone. The due date is converted into the completion's location first, because
times come back from SQLite in UTC (the DSN sets no `_loc`) and a weekday read
in the wrong zone is a weekday off by one either side of midnight.

Two regression tests, `TestFixedDateDoneEarlyMovesToTheNextSlot` and
`TestFixedDateDoneLateLandsOnAFutureSlot`. The first was checked against the
unfixed code and fails there with exactly the reported symptom. Verified
end-to-end too: same chore, same early completion, now spawns Fri **11** Sep.

**The interval path was not changed** — see B-14, which is a question rather
than a defect.

---

### B-14 · Should an interval chore done early pull its schedule earlier? — DECIDED: no change

**Raised** alongside B-13, from the same report. Not a bug against the spec, so
it was left alone rather than changed quietly.

An interval chore's next date is counted from the **completion**:
`spec_2` line 43 — *"next occurrence is due N days after the last completion"* —
and the create dialog says so out loud ("Counted from the last time it was
done"). So an every-3-days chore done on Wednesday is next due Saturday, and
doing it early pulls the whole schedule earlier, exactly as doing it late
pushes it later.

What makes it *look* broken is the first cycle: a new chore's first occurrence
is due `created + N`, so creating an every-3-days chore and ticking it off the
same day produces a next date of `created + 3` — the same date the completed
one had. Nothing moved, apparently. It is a coincidence of completing exactly N
days early, not a stuck schedule.

**The question:** is completion-anchoring right for early completions, or
should the rhythm hold (done early → still due on the original date + N)? The
spec only ever justifies the rule with a *late* example. Completion-anchoring
is defensible — the bathroom is dirty three days after it was cleaned, not
three days after it was meant to be — but a household that tidies up two days
early every time will find "every 3 days" quietly drifting into every other
day. Changing it means changing the spec's stated rule and the copy in the
create dialog, so it needs Babak's call.

**Answered 2026-09-03 (Babak): keep the current behaviour.** The next date stays
`completion + N`, and nothing was changed.

Three reasons worth having in the report, because a reader may well ask:

- It is what `spec_2` line 43 says, and what the create screen tells the person
  out loud: "Counted from when it was last done, not from the calendar."
- The asymmetry with fixed-date chores is **meaningful rather than an
  inconsistency**. Since B-13 a fixed-date chore done early holds its pattern,
  because "Thursdays" is a promise about the calendar. "Every 3 days" is not:
  it is a measurement from the work.
- The known cost is a ratchet. Someone habitually a day early pulls the chore
  permanently earlier, so an every-3-days chore can drift towards every-2-days.
  Accepted, not overlooked: the household can edit the interval, and a chore
  being done sooner than required is not the failure mode this app is for.

---

### B-15 · Chore-history ordering has no tiebreaker, and the test is flaky — DONE (2026-09-02)

**Fixed:** `ORDER BY o.done_at DESC, o.rowid DESC`.

The first attempt used `created_at` as the second key and the test still failed
about one run in four — worth recording, because the obvious fix is the wrong
one. `created_at` defaults to `CURRENT_TIMESTAMP`, which is whole seconds, so
it ties in exactly the cases `done_at` does. And `done_at` ties more readily
than it looks: it comes from `time.Now()`, whose resolution on Windows is coarse
enough that two writes a few milliseconds apart get the identical value.

`rowid` is insertion order and always distinct, and for a chore's cycles that
is spawn order — the later cycle cannot have been inserted first. The table has
no `WITHOUT ROWID`, so it is there to use.

Verified by hammering: ten consecutive runs of the previously flaky test, zero
failures, then three full-suite runs, zero failures.

Original report follows.

### B-15 · Chore-history ordering has no tiebreaker — was OPEN

**Found** 2026-09-02 while running the full suite for B-13.
`TestChoreHistoryRecordsWhoActuallyDidIt` failed once in four runs and passes in
isolation, so it is timing, not the change under test.

`ChoreHistory` orders by `o.done_at DESC` alone (`history.go:70`). Two
completions of the same chore close enough together share a stored `done_at`,
and SQLite is then free to return them in either order — which is what the test
trips over, and what a real household would see as a timeline that shuffles
between refreshes.

**Fix:** add a deterministic tiebreaker, e.g. `ORDER BY o.done_at DESC,
o.created_at DESC`. One line, no change to what the history means — it ranks
nothing either way.

---

### B-12 · A task can still be un-assigned from the detail sheet — OPEN

**Reported:** 2026-09-01, noticed while merging the create dialogs (phase 2).

The merged create form has no "Nobody" option, because constraint 8 makes
assignment unconditional — busy and away are the only exits from an assigned
chore, and an unassigned one is the arrangement the spec exists to replace.
`TaskDetailSheet` still offers a **Nobody** chip under "Assigned to", so a task
that cannot be created unowned can still be made unowned a moment later, and it
then sits in the board's *Others* section belonging to no one.

Small, and left alone deliberately: phase 2 was a merge of two dialogs and
changing the detail sheet is a different concern. **Fix:** drop the chip, and
decide what the sheet should do with the legacy tasks that already have a null
`assigned_to` (show them as unassigned, but don't offer it as a choice).

---

### B-10 · The board still wears a completion percentage — DONE (2026-09-02)

**Fixed:** `ProgressHeader` and its one call site are gone. The board now goes
straight from the app bar to the tabs.

Removed ahead of retaking the report screenshots, so the deck does not preserve
a constraint 4 violation in the images the report is graded on — it sat at the
top of nearly every board, dialog and away-banner shot.

`GroupWithProgress` still carries `total_tasks`/`done_tasks`/`progress` and the
API still returns them; nothing reads them now. The endpoint is shared and the
fields cost nothing, so they stay rather than becoming a backend change.

Original report follows.

### B-10 · The board still wears a completion percentage — was OPEN

**Reported:** 2026-09-01, while device-verifying phase 1 of the UI cleanup. Not
in that prompt's scope; raised rather than improvised on.

`ProgressHeader` (`GroupDetailScreen.kt` ~647, called at ~334) sits above the
tabs and renders **"0 of 3 done"**, **"0%"** and a filling bar, from
`GroupWithProgress.progress`. It is v1 furniture that survived the pivot.

The spec's constraint 4 forbids "points, streaks, leaderboards, or
gamification", and the Dynamics note under history is explicit: *"No points,
ranks, streaks, percentages, or comparisons drawn by the app."* A household
completion percentage is a conclusion the app is drawing — and because it
counts the whole group's rows, a low number is a statement about the
household's week, which is the shame signal constraints 3 and 4 exist to keep
out. It also only counts `tasks`, not occurrences, so the number it shows is
wrong as well as unwanted.

**Fix:** delete `ProgressHeader` and its call. Nothing else reads
`progress`/`doneTasks`; the API can keep returning them. Sits naturally with
the phase-3 information-architecture commits, including B-11 — needs Babak's
go-ahead first.

---

### B-11 · Board tab count includes done rows — DONE (2026-09-02)

**Reported:** 2026-09-01. **Fixed** in UI cleanup phase 3d.

`Board (${tasks.size + occurrences.size})` counted every row including the done
ones, so finishing a chore never moved the number — the one moment the count
should visibly change was the one moment it didn't. It now counts open rows of
both shapes. Verified on the emulator: six open rows read "Board (6)"; ticking
one off left "Board (5)" with one row under Done.

---

### B-9 · Red overdue survived on the legacy task path — DONE (2026-09-01)

**Reported and fixed** during the UI cleanup pass, phase 1.

F1 made overdue amber on the board and left a comment beside `overdueColor()`
saying `colorScheme.error` must not appear anywhere overdue is rendered. Two
places on the legacy `Task` path still contradicted it — the board had been
fixed, the screens either side of it had not:

- `GroupDetailScreen.kt` ~1315, `TaskDetailSheet`: the deadline line rendered
  `MaterialTheme.colorScheme.error` when the task was past due, so opening an
  overdue task turned an amber row into a red one. Now `overdueColor()`, the
  same amber the board and the calendar use — there is one amber, not two.
- `GroupDetailScreen.kt` ~1416, `CalendarTab`: the *doc comment* still said the
  dots are "overdue in error". The dot code below it had already been moved to
  `overdueColor()`, so this was a stale description rather than a live
  violation — but it is the comment a later change would have followed.

Verified on the emulator against a local backend: a one-off task due three days
ago shows amber on the board, amber on its detail sheet, and an amber dot on
the calendar's 29 August. `Delete task` and "Title is required" are still red —
the constraint is about lateness, not failures.

The rest of `ui/` was grepped for `colorScheme.error`: every other use is an
error message, a destructive control, or the Dashboard's avatar palette. None
key off a due date.

---

### B-8 · Reminders only cover the group whose board you last opened — DONE (2026-09-01)

**Fixed:** the Dashboard now loads occurrences and chores for every group and
arms reminders for all of them. It is the one screen that sees them all.

The part that needed care: `reschedule` replaced the *whole* stored set, which
was fine while only the board called it. With two callers, whichever ran last
would have cancelled the other's alarms — a worse bug than the one being fixed,
and a silent one. Reminders are now stored and replaced **per group**, so the
board (one group) and the Dashboard (all of them) can both call in.

Failures while loading are deliberately silent: this drives reminders, not the
screen, and a group whose board could not be fetched keeps whatever alarms it
already had rather than raising a banner over a group list that loaded fine.

Verified with demo in two households: the Dashboard armed 2 reminders for one
and 6 for the other, "Your turn: Water the plants" fired for the group whose
board had never been opened, and opening one board left the other group's six
untouched.

Original report follows.

**Reported:** 2026-09-01, while building F3.

Reminders are scheduled on the device from the board's data, and the board is
per-group. `GroupDetailScreen` is the only thing that calls
`ReminderScheduler.reschedule`, so a user in two households gets reminders for
whichever board they opened most recently, and the other group's turns pass
unmentioned.

**Not a problem for the common case.** The spec's household is one group, and a
user in exactly one group lands straight on its board at login — so their
reminders are always current. This only bites the multi-group user.

**Fix:** schedule from somewhere that sees every group. The Dashboard already
lists them, so it could fetch occurrences per group and hand the union to the
scheduler — N requests where N is the number of households, which is 1 or 2 in
practice. The scheduler itself needs no change: it is keyed by occurrence and
replaces its whole set each time, so feeding it more occurrences is enough.

---

### B-7 · A rejected create closes the dialog and loses what you typed — DONE (2026-09-01)

**Fixed:** both create dialogs now stay open until the write actually lands.
`runAction` takes an optional result callback; the screen closes the dialog on
success and, on failure, shows the message in the dialog's own error slot with
every field still filled. Create reads "Creating…" and is disabled while the
request is in flight, and Cancel is disabled with it so the form cannot be
dismissed out from under a pending write. A failure routed to a form no longer
also raises a snackbar — one message, where the user is looking.

Verified by filling the chore form, killing the backend, and pressing Create:
the dialog stayed up with "Network error: Failed to connect to /10.0.2.2:8080"
and the name, schedule and rotation all intact. Restarting the server and
pressing Create again submitted the same form successfully.

Original report follows.

**Reported:** 2026-08-31, hit while testing chore creation against a stale
server binary.

Both `CreateTaskDialog` and `CreateChoreDialog` set `showCreate… = false` and
*then* fire the request, so a 400 from the backend surfaces as a snackbar over
an empty board — the dialog is already gone, and every field the user filled in
goes with it. On the chore form that is a name, a done-line, a schedule and a
rotation order.

Client-side validation now catches the common cases before the request (so this
is rarer than it was), but anything only the server can know — a name that
collides, a member who left the group mid-edit, a dropped connection — still
loses the whole form.

**Fix:** keep the dialog open until the call succeeds. That means the dialog
needs to see `isWorking`/`message` rather than being dismissed optimistically:
disable Create while in flight, close on success, show the server's message
in-dialog on failure. The in-dialog error slot already exists.

---

### B-6 · The undo window doesn't close on its own — DONE (2026-09-01)

**Fixed:** each undoable row now waits out its own window in a `LaunchedEffect`
that sleeps exactly until the moment it lapses, then closes it. No clock read
during composition, so nothing can go stale, and no polling either — one
coroutine per undoable row, and only while there is one.

Verified by temporarily shortening the window to 20 seconds, ticking a row, and
watching the checkbox go from enabled to disabled with no taps, no scrolling and
no refresh. The original 10 minutes was restored afterwards.

Original report follows.

**Reported:** 2026-08-31, while device-verifying F1.

`canUndo` in `GroupDetailScreen.kt` (~line 600) calls `OffsetDateTime.now()`
during composition, with nothing to trigger a recomposition when the ten
minutes elapse. The checkbox therefore stays enabled past the window until
something else recomposes the row — a poll, a refresh, or leaving and coming
back.

**Not a permissions hole.** The `doneBy == myUserId` half is correct, and since
F2 the *server* enforces both halves (403 "the undo window has passed"), so a
stale screen produces a clear error rather than a silent rewrite. What's wrong
is only that the control looks available when it isn't.

**Fix:** drive it from a ticker rather than from composition — a `produceState`
that re-evaluates once a minute while a done row is on screen, or recompute
against a clock the ViewModel already updates. Cheap either way; it was left
alone so F1's verification pass didn't turn into a refactor.

---

### B-1 · Invite list can't scroll past ~6 entries — DONE (round 1)

**Reported:** 2026-08-01

With more than about six pending invites, the older ones are unreachable — the
dialog clips them and there's no way to scroll.

**Cause:** `PendingInvitesDialog` in
`TaskFlowApp-kotlin/…/ui/screens/DashboardScreen.kt` (~line 466) lays the
invites out in a plain `Column` with `invites.forEach`. `AlertDialog` constrains
its content height, so anything past the visible area is simply cut off. Nothing
in that Column scrolls.

**Fixed:** content now has `heightIn(max = 400.dp)` + `verticalScroll`.
Original plan: give the content `Modifier.verticalScroll(rememberScrollState())`, or
swap the `Column` for a `LazyColumn` with a bounded `heightIn(max = …)`. Lazy is
the better choice if the list can get long, since it also avoids composing every
row. Worth capping the dialog height explicitly either way so it doesn't grow to
fill the screen on a phone.

**Note:** the same shape appears elsewhere — check the group detail dialogs for
the same clipping before calling this closed.

---

### B-2 · New invites only appear after a manual refresh — DONE (round 3)

**Reported:** 2026-08-01

If someone invites you while you're sat on the Dashboard, nothing happens. You
have to hit refresh to discover it.

**Cause:** `DashboardViewModel` only calls `fetchInvites()` from `init`,
`refresh()`, and after responding to an invite. There's no polling. Compare
`GroupDetailViewModel`, which polls the activity feed every 5s — the Dashboard
never got the same treatment.

**Fixed:** `PollWhileResumed` ticks `fetchInvites()` every 20s while the
Dashboard is resumed, and immediately on resume. Built on `repeatOnLifecycle`,
so it also stops polling while the app is backgrounded — which fixes the
separate complaint about the activity feed below. The FCM point still stands
if a third poller ever appears.

Original plan: poll `fetchInvites()` on an interval from a `LaunchedEffect` in
`DashboardScreen`, the same way `GroupDetailScreen` polls activity. Points to
settle:

- A slower interval than the activity feed is fine — invites are rare. 15–30s.
- Also refresh on resume, not just on a timer, so coming back to the app shows
  the truth immediately.
- Polling on every screen is the wrong end state. If this list grows, the real
  answer is one push channel (FCM) rather than N pollers — the backend already
  records the events that would drive it. Worth raising before adding a third
  poller.

---

## Missing features

### F-2 · No way to delete a chore from the app — DONE (2026-09-02)

**Fixed:** "Delete chore" on `EditChoreDialog`, behind a confirm.

It went there rather than on the occurrence sheet because that dialog is
already the "this chore is wrong" screen; on a single occurrence the same
button reads as deleting *that cycle* rather than the whole rotation.

The confirm says what is lost instead of asking "are you sure?": *"This removes
the chore, whoever's turn it currently is, and the history of every time it has
been done. It cannot be undone."* There is no undelete endpoint, and a snackbar
pretending otherwise would be a lie — the same reasoning that governs task
delete.

**Left open to every member**, consistent with everything else about chores
since the manager role went (F2/F4). It is the most destructive thing in the
app, so it is worth naming in the report as a deliberate choice rather than an
oversight.

Nothing behind it needed writing: the endpoint, `deleteChore` on the API
service and `deleteChore` on the ViewModel had all existed since F2 and had
simply never been reachable.

Verified on the emulator: `DELETE /groups/{id}/chores/{id}` → 204, and the
chore and its open occurrence both left the board.

Original report follows.

### F-2 · No way to delete a chore from the app — was OPEN

**Asked by Babak**, 2026-09-02: "why is there no option to delete a chore?"

Because the UI was never wired up. Everything else exists:

- `DELETE /groups/{group_id}/chores/{chore_id}` is routed
  (`cmd/server/main.go:93`) and works — verified against the local backend,
  returns 204 and takes the chore's occurrences with it.
- `GroupApiService.deleteChore` exists (`GroupApiService.kt:154`).
- `GroupDetailViewModel.deleteChore` exists (`GroupDetailViewModel.kt:553`),
  with a "Chore deleted" message ready.

Nothing calls the ViewModel method. It has been unreachable since F2. Tasks got
a delete button on `TaskDetailSheet` back in v1 and chores never got the
equivalent, so the one-off shape is deletable and the chore shape is not.

**Fix:** a "Delete chore" action on `EditChoreDialog` — that is already the
"this chore is wrong" screen and is open to every member, so delete belongs
beside edit rather than on the occurrence sheet, where it would read as
deleting *this cycle*. Behind a confirm, like task delete: deleting a chore
removes its whole history, which is not something to do on one tap.

Worth settling at the same time: whether a member may delete a chore anyone
created. Everything else about chores is open to every member by design
(F2/F4), so the consistent answer is yes — but it is the most destructive thing
in the app, and it is the first place that question really bites since the
manager role was removed.

---

### F-1 · No group settings; description can't be edited after creation — OPEN

**Reported:** 2026-08-01

If you skip the description when creating a group, there's no way to add one
later. No group settings screen exists at all.

**Needs backend work first** — there is currently *no* update route for a group.
`cmd/server/main.go` has `GET /groups/{group_id}` and the members/tasks/invite
routes, but nothing to modify the group itself.

**Backend:** add `PATCH /groups/{group_id}` accepting `{"name"?, "description"?}`,
membership-guarded like its neighbours. Decisions to make:

- Reuse the `NullableField` tri-state so a description can be *cleared* as well
  as set, consistent with how task fields behave.
- Should any member be able to rename the group, or only the owner? Role-based
  permissions were explicitly deferred pending the need-finding interviews, so
  the simplest consistent choice for now is any member — but flag it, because
  it's the first place that question really bites.
- Record a `group_updated` activity event, with the changed fields in `detail`,
  so it shows in the feed like every other change.

**Android:** a settings entry in the group detail overflow menu → dialog with
name and description, calling the new endpoint. `Group`/`GroupWithProgress`
models already carry `description`, so nothing to add there.

---

## UX review findings (2026-08-01, from driving every screen)

### Functional bugs found during review

- **B-3 · DONE (round 1) · Task could only be assigned to the first 3 members.** `CreateTaskDialog`
  in `GroupDetailScreen.kt` (~line 745) does `members.take(3)` for the assignee
  chips. Group of 5 → two people can never be assigned. Needs a scrollable/flow
  layout or a dropdown instead of a capped chip row.
- **B-4 · DONE (round 1) · System back didn't exit selection mode.** No `BackHandler` in
  `GroupDetailScreen`; in multi-select, back exits the whole screen instead of
  clearing the selection. Add `BackHandler(enabled = inSelectionMode)`.
- **B-5 · DONE (round 1) · Keyboard covered the primary button on auth screens.** Observed while
  driving Register: the IME hid Create Account with no way to scroll to it.
  Auth columns need `verticalScroll` + `imePadding()`.

### UX debt (no crash, but hurts flow)

- **DONE (round 2)** ~~Tap-to-cycle status is undiscoverable and error-prone.~~
  A tap now opens the task detail sheet; status is an explicit chip row there.
- **DONE (round 1)** ~~Destructive actions are one tap, no confirm, no undo.~~
  Task delete now confirms. (Undo would need an undelete endpoint — deliberately
  not faked with a snackbar.)  Original note: Task delete
  especially — the trash icon sits beside every card. Snackbar-with-Undo is the
  Material pattern; confirmation dialog is the cheap stopgap.
- **DONE (round 2)** ~~No task detail/edit screen.~~ `TaskDetailSheet` gives
  editable title/description (staged, saved with a button), explicit status and
  assignee, created/updated stamps, and delete. It is also where the due-date
  picker belongs when that work starts.
- **DONE (round 1)** ~~Activity feed leaks developer strings.~~ Original note: `detail` renders raw
  `assigned_to=cleared`, `status=in_progress`, `2 task(s) → done`. Humanise
  client-side ("cleared the assignee", "moved 2 tasks to Done").
- **DONE (round 1)** ~~Silent success.~~ Mutations now confirm via snackbar. Original note: Most mutations show nothing on success (only failures
  surface). Bulk moves, status changes, task creation should confirm via
  snackbar — which is also where Undo lives.
- **DONE (round 3)** ~~Session expiry is unexplained.~~ Login now shows
  "Your session expired. Please sign in again." Was: On a 401 the app bounces to Login with no
  message; user can't tell logout from crash. Show "session expired, sign in
  again."
- **DONE (round 1)** ~~Invite code can't be copied or shared.~~ Original note: `InviteCodeDialog` shows the code
  as plain text; user must transcribe it. Add copy-to-clipboard + system share
  sheet.
- **DONE (round 3)** ~~No pull-to-refresh anywhere.~~ `PullToRefreshBox` on the
  Dashboard and the group tabs; toolbar icons kept for discoverability.
  Required bumping the Compose BOM to 2024.09.00 (Material 3 1.3.0), since
  the pull-to-refresh API did not exist in 1.2.1.
- **DONE (round 1)** ~~FAB overlaps the last list item~~ — task/group lists need bottom content
  padding (~88dp).
- **DONE (round 1)** ~~Members tab reuses StatusChip for roles~~ — now a RoleChip.
  Was: so "owner" renders raw and
  lowercase.
- **DONE (round 3)** ~~No sorting/grouping of tasks.~~ The task list is grouped
  under To do / In progress / Done headers with counts, so finished work sinks
  to the bottom. Filtering is still not implemented.
- **Login lacks show-password toggle; Login "success" card is dead weight**
  (navigation happens immediately, the message never gets read).
- **Logout is instant** from an icon that sits next to Refresh — one mis-tap
  signs you out. Confirm, or move it behind the overflow menu (Dashboard now
  has one).

## Deadlines, roles and calendar (2026-08-02) — DONE

Built on request, ahead of the need-finding interviews that were originally
meant to inform role design. Flagged at the time; the decision was to proceed.

- `group_members.role` (owner/admin/member) is now actually enforced. It was in
  the schema from the start but `GetMemberRole` had never been called once.
- Only owner/manager may set or clear a task's `due_date`; everything else stays
  open to all members. The check keys off whether `due_date` is *present* in the
  patch, so a member editing a title is unaffected.
- `PATCH /groups/{id}/members/{user_id}/role`, owner only. The owner's own role
  cannot be changed, by anyone.
- Calendar is a per-group tab: month grid, a dot per task (error = overdue,
  tertiary = done), tap a day for its tasks. Tasks with no deadline are counted
  at the bottom rather than silently omitted.
- "admin" on the wire is shown as **Manager** throughout the UI.

Open questions deliberately left:

- Should the *assignee* be able to set their own deadline? Currently no.
- Nothing else is role-gated (delete, invite, group settings). Revisit after the
  interviews rather than guessing.
- The calendar is per-group. A cross-group "my deadlines" view was considered
  and deferred — it needs an aggregate endpoint.

## Also known (raised in passing, not yet triaged)

- **DONE** ~~No due-date UI.~~ Deadlines are set from the task detail sheet by
  the owner or a manager, shown on a month calendar tab, and cleared with
  `Patchable.SetNull`.
- **Reinstalling the APK signs you out.** `EncryptedSharedPreferences` loses its
  Keystore-backed key across reinstalls, so the JWT is unreadable and the app
  starts at Login. Expected behaviour rather than a defect, but it surprises
  testers — worth a line in whatever instructions go out with the APK.
- **DONE (round 3)** ~~Activity polling keeps running when backgrounded.~~ Both
  pollers now go through `PollWhileResumed`, which uses `repeatOnLifecycle` to
  pause on background and tick on resume.
- **Debug builds log request bodies.** `HttpLoggingInterceptor` is at `BODY` in
  debug, which includes the plaintext password on login/register. `Authorization`
  is redacted and release builds log nothing, but don't share a debug logcat.
