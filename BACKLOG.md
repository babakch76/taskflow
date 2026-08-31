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
- **F1 (Board) — DONE**, not yet device-verified. Yours/Others/Done sections,
  `done_by`/`done_at` persisted, one-tap completion with a 10-minute undo,
  amber overdue, single-group users land on the board.
- **F2 (chores, rotation, spawning) — NEXT.** Two open questions in the
  handoff; goes on a `v2-chores` branch.
- Multi-select and bulk status were removed with F1. The backend endpoint
  survives, unused by the client.

---

## Bugs

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
