# TaskFlow v2 — handoff

Written 2026-08-21, at the end of the chat that removed the manager role and
built F1. Read this first, then `BACKLOG.md`, then the spec.

**The spec is the build contract:** [`taskflow_feature_spec_2.md`](taskflow_feature_spec_2.md),
at the repo root.

---

## 1. What this project is now

TaskFlow was a generic task manager. The spec turns it into a **household chore
rotation app**. This is a pivot, not an increment:

- `Task` becomes **Chore** (a definition: schedule + rotation list) plus
  **Occurrence** (one cycle, assigned to one person).
- Occurrence states are `open → done` only. *"There is no 'missed' state
  anywhere in the system."* The current `todo / in_progress / done` is v1
  vocabulary and `in_progress` is due to disappear at F2.
- The governing metaphor is a hallway whiteboard whose entries don't wash off.
  Accountability comes from a chore staying visibly next to your name, never
  from badges, counters, or notifications about other people.

Read the eight **hard constraints** in the spec before designing anything. Two
that bite in code:

- **Constraint 3** — no red overdue states, no late counters, no shame badges.
  Amber or dim at most. `colorScheme.error` must not appear anywhere overdue is
  rendered. (Fixed in F1; keep it that way.)
- **Constraint 5** — no calendar *integration* or free/busy scheduling. A
  read-only month view of due dates was kept for a while on that reading, with
  a note here to defend it in the report. **It has since been deleted** (UI
  cleanup phase 3a) — not to settle the argument but because it only ever read
  `tasks`, so it showed the one-off minority of the work and hid every rotation
  chore. There is now no calendar in the app and nothing to defend.

---

## 2. Where things stand

Repo: `D:\0A-study-A0\0-ACSAI-0\sem 6\HCI\Antigravity workspace`
GitHub: `babakch76/taskflow` (**private**), branch `main`.

Where the history stands (this table was written at F1 and is kept as a map,
not as a tip — read `git log` for the tip):

| Commit | What |
|---|---|
| `7d8798e` | **Merge `v2-chores`** — F1 to F6 on `main`. The pre-v2 demo is the tag `v1-final`. |
| `4e323dc` | **F1 — the Board** |
| `b22bbc0` | Removed the manager role and the deadline permission gate |
| `77c541e` | Release signing + "Producing an APK" docs |

Since the merge: B-6, B-7 and B-8 closed on `main`, and the UI cleanup pass
(`taskflow_ui_cleanup_prompt.md`) ran on **`ui-cleanup`**, which is where the
tip is now. Phases 1 to 4a are done and device-verified — constraint 3 cleaned
up on the legacy task path, dynamic colour off, the two create dialogs merged
into one, the Calendar tab deleted, quiet hours moved to the Dashboard, an away
banner on the board, the tab count fixed, and the simplifications written down
in 5a. **Phase 4b — retaking every screenshot in `TaskFlow_Screens.pptx` from
the real build — is the only piece left, and it needs Babak at the emulator.**
The branch has not been merged into `main`; that is a decision, not an
oversight waiting to be corrected.

### Done

**Manager role removed.** The spec makes chore editing open to every member
(F2) and lets anyone mark anything done (F1), so a role gating deadlines
contradicted it. Gone: the `admin` role in practice, promote/demote UI, the
`PATCH /groups/{id}/members/{user_id}/role` endpoint, and the 403 on due dates.
Kept: the `owner` role (`LeaveGroup` still returns 409 for owners), `my_role`
on `GET /groups/{id}`, and the `RoleAdmin` constant so pre-existing `admin`
rows still render as "Manager".

**F1 — the Board.** Task tab regrouped into **Yours / Others / Done**.
`done_by` / `done_at` added to `tasks` (the spec asks for these "from step 1" —
a completion history can't be reconstructed later, and the doer is routinely
not the assignee). One-tap completion by anyone; undo only by the person who
ticked it and only within 10 minutes. Overdue is amber. A user in exactly one
group lands on the board rather than a one-item list.

**Removed as a consequence of F1:** multi-select and the bulk action bar. The
checkbox now means "done", and one control can't have two meanings. The bulk
endpoint still exists server-side and is still tested; the client no longer
calls it. Decide at F2 whether to delete it.

**F1 is now device-verified** (2026-08-31), against a locally-run backend. All
of it: the three sections, single-group auto-open and its Back behaviour,
one-tap completion by a non-assignee with `done_by` recorded, undo restricted to
the doer, and amber-not-red overdue. One defect came out of it — see B-6.

**F2 is done**, in three commits on `v2-chores`, each device-verified:

| Commit | What |
|---|---|
| `995df3e` + `89ce790` | The chore/occurrence model, CRUD, board reads it |
| `ceb0da7` | Interval chores take any number of days |
| `91d93a0` | Completion spawns the next occurrence |
| `40fcbae` | The unified turn rule |

Chores and occurrences are **new tables alongside `tasks`**, which is untouched;
existing tasks are the spec's one-off type already. The board shows both shapes
through one `BoardRow`. The migration was run against a populated database to
confirm it adds tables without disturbing what is there.

**F3 to F6 are done too**, on the same branch and to the same pattern: backend
first with tests, then the client, each step driven on the emulator before
being committed. Section 5 lists what each one landed. Two of them started by
correcting something behind them rather than adding a view —
F6 turned away from a flag into a record, because history has to be able to say
someone was absent *then*, not just now.

**One deliberate departure from the spec.** It enumerates interval periods as
1-6 days, weekly and monthly. That set has no entry for a fortnightly bin
collection, so an interval is now any number of days from 1 to 365. Nothing
downstream depended on the fixed set — the due date is arithmetic either way.
Say so in the report rather than letting a reader find it.

**Deliveries that differ from a literal reading, both worth stating in the
report:** reminders are scheduled on the device rather than pushed from a
server, because the spec's rules are all "to the assignee, about their own
chore", which a phone can decide alone — the cost is that a turn starting while
the app is closed is noticed at the next sync. And no scheduler exists at all,
so the one other time-based behaviour (rolling a missed fixed date forward)
happens when the board is read.

---

## 3. Decisions already made — don't re-litigate

| Decision | Choice |
|---|---|
| Manager role | Removed. Nothing is role-gated now. |
| Calendar | **Deleted** (UI cleanup phase 3a). It was kept as a secondary tab through F1–F6; it never learned about occurrences, and a calendar that does would be the scheduling view constraint 5 rules out. The Board is the screen. |
| Branching | F1 went to `main`. **F2 and everything after goes on a `v2-chores` integration branch**, so `main` keeps a working demo and installable APK. |
| Board placement | Replaced the Tasks tab in place, not a new tab. |
| Navigation | One group → straight to its board; several → the list. |
| `in_progress` | Still on `tasks`. It never reached the chore model — occurrences are `open`/`done` only — and it now only survives on legacy tasks. |
| Fate of `tasks` | **Answered:** chores/occurrences sit alongside it. Existing tasks stay as one-offs. Nothing migrated, nothing lost. |
| F2 slicing | **Answered:** three commits — model+CRUD+board, spawning, turn rule. Done. |
| Interval periods | Any number of days, 1-365, not the spec's enumerated set. See the departure note above. |
| The bulk endpoint | **Deleted** once the build order finished. It had been unreachable from the UI since F1 and nothing had claimed it in the meantime. The activity constant and its rendering stay, for rows already written. |

---

## 4. Blocked, needs Babak

1. ~~**The AWS server is well behind**~~ — **resolved 2026-09-01.** It is
   current: the full v2 backend is deployed and every v2 endpoint answers 200.

   Two things in the old note turned out to be wrong by the time anyone acted
   on them, which is worth knowing about this document generally: the v2 binary
   had *already* been deployed on 1 September (16:17 UTC), and the leftover
   `admin` rows had already been demoted — `group_members` holds only `owner`
   and `member`. Whoever writes here next: check the box before believing this
   section.

2. ~~**Demote the leftover admin row**~~ — already done. Verified:
   `SELECT role, COUNT(*) FROM group_members GROUP BY role;` returns
   `member|5`, `owner|13`, and no `admin`.

3. **Still needs Babak:** close the SSH rule again. AWS console → EC2 →
   Security Groups → `taskflow-sg` → Inbound rules → remove or narrow the SSH
   entry. It was opened for the 1–2 September deploy and should not stay open.

**There are backups now**, which there were not before. `~/backups` on the box
holds a WAL-safe `sqlite3 .backup` of the database and a copy of the binary it
was taken alongside, both stamped `20260901T230027Z`. The database copy was
verified row-for-row against the live one across all nine tables, not just
`PRAGMA integrity_check`. `sqlite3` is now installed on the box, which it was
not — a plain `cp` of that database would have been unsafe, since it runs in
WAL mode and had a 531 KB WAL at the time.

Restoring means putting **both** back: the binary alone re-runs the migrations
against whatever database it finds, so a database rolled back under a new
binary is not a rollback.

---

## 5. Next step: deployment, not features

**The spec's build order is complete.** F1 through F6 are built, tested and
device-verified, on `v2-chores`:

| | |
|---|---|
| F1 | The board — Yours / Others / Done, one-tap completion by anyone, 10-minute undo |
| F2 | Chores, occurrences, four schedule types, spawning, the unified turn rule |
| F3 | Reminders scheduled on the device, quiet hours on the user record |
| F4 | The done-line shown and editable, with the edit diff broadcast |
| F5 | Busy pass and away, both reusing the turn rule |
| F6 | History — per chore and per person, ranking nothing |

What is left is not features:

1. ~~**Deploy.**~~ — **done.** The v2 binary went out on 1 September; the
   backend was redeployed from `main` on 2 September and smoke-tested
   (`/chores`, `/occurrences`, `/history`, `/members`, `/tasks`, `/activity`,
   `/me` all 200, row counts unchanged across the restart). The redeploy
   carried a comment and nothing else — the box had already been current — but
   it exercised the path end to end and left `~/taskflow-src` matching `main`
   exactly, which it had not.

   The deploy recipe in section 6 works as written. Two things it does not say:
   `go` is genuinely absent from the non-interactive PATH, so every remote
   command needs `PATH=$PATH:/usr/local/go/bin`, and `sudo install -o taskflow
   -g taskflow -m 0755` is what keeps the binary's ownership right — a plain
   `cp` leaves it owned by root and the unit still starts, which hides the
   mistake until something else needs to write.
2. ~~**Merge `v2-chores` into `main`**~~ — **done** (`7d8798e`), ahead of the
   deploy rather than after it. The pre-v2 demo stays reachable as the tag
   `v1-final`, which is what keeping `main` unmerged was protecting. Work since
   then goes on a branch off `main` for the same reason: the UI cleanup pass is
   on `ui-cleanup`.
3. **The report.** The non-goals in the spec's "considered and rejected" section
   are worth keeping, and several decisions here were deliberate departures
   worth naming — the interval departure in section 2, and the known
   simplifications immediately below.

## 5a. Known simplifications — say these in the report

None of these is a bug. Each is a delivery choice with a visible consequence,
and a reader who finds one unmentioned will assume it was missed.

**Reminders are device-local.** They are scheduled on the phone with
AlarmManager — no Firebase, no push service, no server-side scheduler — because
every rule the spec gives is "to the assignee, about their own chore", which a
phone can decide alone, and because nothing about anyone's chores then leaves
the device. The cost is real and worth stating plainly:

- A busy pass notifies the receiver **when their device next syncs**, not
  instantly. A turn that starts while the app is closed is noticed at the next
  open.
- Someone who never opens the app learns of a pass only from the board.
- **Do not demo the pass flow on a device that has not reopened the app**, or
  the notification will not have been armed yet and the demo will look broken
  when it is behaving as designed.

**The activity feed polls every 5 seconds** while the screen is resumed
(`PollWhileResumed`, which stops on background). That is the deliberate
stand-in for push. It is fine for a household and would not be fine for a
product; say so rather than letting a reader assume a socket.

**Fixed-date chores never trigger the 48-hour still-waiting nudge.**
`rollForwardFixedDates` re-dates a lapsed fixed date the moment it passes, so
such an occurrence is never open past its date long enough to owe one. Their
pressure is the weekly DUE_SOON instead. This asymmetry is chosen: a fixed date
belongs to the world — the bin lorry comes Tuesday — and nagging about a date
that has already moved would be nagging about the wrong day. Both
`rollForwardFixedDates` and `ReminderSchedule.stillWaitingFor` now carry a
comment saying this, so the next reader of either finds the other.

**There is no scheduler at all**, so the one other time-based behaviour —
rolling a lapsed fixed date forward — happens when the board is read. From the
board it is indistinguishable from one that rolled at midnight.

### The rules now in code — don't quietly undo them

- **Rotation advances on completion, never on the calendar.** Interval chores
  sit with a past due date; fixed-date chores roll to the next date with the
  *same* assignee (`rollForwardFixedDates`, which only ever writes due_date).
- **The unified turn rule** (`nextTurn`) covers three things with one rule:
  a voluntary cover, a busy pass, and the overflowing bin. `passed_from` is what
  lets a pass reuse it; `resume_after` is what makes the rotation resume after
  the coverer rather than the repayer.
- **Away is not a debt.** It never cancels a turn already owed, and it is a
  record (`away_periods`) rather than a flag, because F6 has to be able to say
  someone was absent *then*.
- **There is no `missed` state**, and the schema refuses one. Overdue is a
  rendering decision.
- **History ranks nothing.** People come back in join order with the zeroes
  included, and lateness is two dates rather than a flag. Both are tested, and
  both are the kind of thing a later "improvement" would quietly break.

## 6. Environment — things that cost hours if you don't know them

**Android**

- `JAVA_HOME` must be **JDK 17**: `C:\Program Files\Java\jdk-17`. Android
  Studio's bundled JBR is **25**, which AGP 8.4 rejects. In Studio: Settings →
  Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK.
- `ANDROID_HOME=C:\Users\babak\AppData\Local\Android\Sdk`, platform 34.
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- **After changing `taskflow.baseUrl`, you must `clean`.** `BASE_URL` is a
  `buildConfigField`; the compiler inlines the constant, and an incremental
  build regenerates `BuildConfig.java` while leaving the compiled Kotlin alone.
  **Reading `BuildConfig.java` will show the new URL while the APK still holds
  the old one** — verify via Logcat's `okhttp` lines instead. This has already
  cost one debugging round.
- `local.properties` is gitignored and holds `sdk.dir`, `taskflow.baseUrl`, and
  optional release-signing keys. A fresh clone needs it recreated.

**Backend**

- **CGO is required** — `go-sqlite3` compiles to a non-functional stub with
  `CGO_ENABLED=0`, and every test fails with "requires cgo to work".
  On this machine: `export PATH="/c/msys64/ucrt64/bin:$PATH"` and
  `export CGO_ENABLED=1`.
- `GOOS=linux go build` from Windows silently disables CGO. Build on the server.
- Tests: `go test ./...` — runs against real SQLite in a temp dir.

**AWS**

- Host `task-flow-babak.duckdns.org` → `13.61.127.214` (Elastic IP).
- SSH: `ssh -i ~/.ssh/taskflow-key.pem ubuntu@13.61.127.214`. `go` is not on the
  non-interactive PATH — prefix commands with `PATH=$PATH:/usr/local/go/bin`.
- Redeploy: scp `cmd internal deploy go.mod go.sum` to `~/taskflow-src`, build
  with `CGO_ENABLED=1`, `systemctl stop taskflow`, `install` the binary to
  `/opt/taskflow/`, `systemctl start taskflow`. Database at
  `/var/lib/taskflow/taskmanager.db` is untouched by redeploys and `migrate()`
  runs on every start.
- **Costs money.** ~$3.60/month for the IPv4 address alone, plus the instance.
  Terminate and release the Elastic IP when the project is done.

**Test accounts** on the live server: `demo@taskflow.test`, `mate@…`,
`mate2@…`, all `password123`; plus `babakch`.

---

## 7. How this was being worked

One feature per step, with a procedural question before each — branch strategy,
migration approach, what to remove. That worked well; keep it.

Verification pattern: build → unit tests → install on the emulator → drive it
with `adb` and read screenshots → check the actual request bodies in Logcat.
Several real bugs were only caught at the last step (a stale inlined URL, a
`take(3)` that hid group members, a red overdue state violating the spec).
**Compiling is not evidence.**

One method to avoid: index-based slicing of source files in Python
(`s[:start] + s[end:]`). It silently removed three unrelated functions in this
session. The compiler caught each, but use line-verified edits or exact-string
replacement instead.
