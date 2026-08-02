# TaskFlow — backlog

Running list of known bugs and missing features across both projects. Append as
things turn up; keep the newest reports at the top of each section.

Status key: **OPEN** · **IN PROGRESS** · **DONE**

---

## Bugs

### B-1 · Invite list can't scroll past ~6 entries — OPEN

**Reported:** 2026-08-01

With more than about six pending invites, the older ones are unreachable — the
dialog clips them and there's no way to scroll.

**Cause:** `PendingInvitesDialog` in
`TaskFlowApp-kotlin/…/ui/screens/DashboardScreen.kt` (~line 466) lays the
invites out in a plain `Column` with `invites.forEach`. `AlertDialog` constrains
its content height, so anything past the visible area is simply cut off. Nothing
in that Column scrolls.

**Fix:** give the content `Modifier.verticalScroll(rememberScrollState())`, or
swap the `Column` for a `LazyColumn` with a bounded `heightIn(max = …)`. Lazy is
the better choice if the list can get long, since it also avoids composing every
row. Worth capping the dialog height explicitly either way so it doesn't grow to
fill the screen on a phone.

**Note:** the same shape appears elsewhere — check the group detail dialogs for
the same clipping before calling this closed.

---

### B-2 · New invites only appear after a manual refresh — OPEN

**Reported:** 2026-08-01

If someone invites you while you're sat on the Dashboard, nothing happens. You
have to hit refresh to discover it.

**Cause:** `DashboardViewModel` only calls `fetchInvites()` from `init`,
`refresh()`, and after responding to an invite. There's no polling. Compare
`GroupDetailViewModel`, which polls the activity feed every 5s — the Dashboard
never got the same treatment.

**Fix:** poll `fetchInvites()` on an interval from a `LaunchedEffect` in
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

- **B-3 · Task can only be assigned to the first 3 members.** `CreateTaskDialog`
  in `GroupDetailScreen.kt` (~line 745) does `members.take(3)` for the assignee
  chips. Group of 5 → two people can never be assigned. Needs a scrollable/flow
  layout or a dropdown instead of a capped chip row.
- **B-4 · System back doesn't exit selection mode.** No `BackHandler` in
  `GroupDetailScreen`; in multi-select, back exits the whole screen instead of
  clearing the selection. Add `BackHandler(enabled = inSelectionMode)`.
- **B-5 · Keyboard covers the primary button on auth screens.** Observed while
  driving Register: the IME hid Create Account with no way to scroll to it.
  Auth columns need `verticalScroll` + `imePadding()`.

### UX debt (no crash, but hurts flow)

- **Tap-to-cycle status is undiscoverable and error-prone.** Tapping a task
  card silently advances todo → in_progress → done; nothing signals it, and a
  mis-tap "completes" a task with no undo. Replace with an explicit control
  (status chip opens a menu, or checkbox = done) or add undo via snackbar.
- **Destructive actions are one tap, no confirm, no undo.** Task delete
  especially — the trash icon sits beside every card. Snackbar-with-Undo is the
  Material pattern; confirmation dialog is the cheap stopgap.
- **No task detail/edit screen.** Title/description can never be changed after
  creation (the API supports it); descriptions truncate at 2 lines with no way
  to read the rest. Tapping a card should open a detail sheet — which also
  frees the tap gesture from status-cycling.
- **Activity feed leaks developer strings.** `detail` renders raw
  `assigned_to=cleared`, `status=in_progress`, `2 task(s) → done`. Humanise
  client-side ("cleared the assignee", "moved 2 tasks to Done").
- **Silent success.** Most mutations show nothing on success (only failures
  surface). Bulk moves, status changes, task creation should confirm via
  snackbar — which is also where Undo lives.
- **Session expiry is unexplained.** On a 401 the app bounces to Login with no
  message; user can't tell logout from crash. Show "session expired, sign in
  again."
- **Invite code can't be copied or shared.** `InviteCodeDialog` shows the code
  as plain text; user must transcribe it. Add copy-to-clipboard + system share
  sheet.
- **No pull-to-refresh** anywhere; refresh is a toolbar icon only. Material
  `PullToRefreshBox` on Dashboard + group tabs.
- **FAB overlaps the last list item** — task/group lists need bottom content
  padding (~88dp).
- **Members tab reuses StatusChip for roles**, so "owner" renders raw and
  lowercase.
- **No sorting/grouping/filtering of tasks** — done tasks interleave with
  todos in creation order. At minimum: group by status or sort done-last.
- **Login lacks show-password toggle; Login "success" card is dead weight**
  (navigation happens immediately, the message never gets read).
- **Logout is instant** from an icon that sits next to Refresh — one mis-tap
  signs you out. Confirm, or move it behind the overflow menu (Dashboard now
  has one).

## Also known (raised in passing, not yet triaged)

- **No due-date UI.** The backend stores `due_date`, accepts it on create and
  patch, and `Patchable.SetNull` can clear it — but no screen sets it. This is
  the prerequisite for the calendar/deadline work.
- **Reinstalling the APK signs you out.** `EncryptedSharedPreferences` loses its
  Keystore-backed key across reinstalls, so the JWT is unreadable and the app
  starts at Login. Expected behaviour rather than a defect, but it surprises
  testers — worth a line in whatever instructions go out with the APK.
- **Activity polling keeps running when backgrounded.** `GroupDetailScreen`'s
  poll is tied to composition, not lifecycle, so it continues if the app is
  backgrounded with that screen on top. Harmless for a demo; wasteful on
  battery and worth moving to a lifecycle-aware scope if this ships.
- **Debug builds log request bodies.** `HttpLoggingInterceptor` is at `BODY` in
  debug, which includes the plaintext password on login/register. `Authorization`
  is redacted and release builds log nothing, but don't share a debug logcat.
