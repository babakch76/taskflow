# TaskFlow v2 — handoff

Written 2026-08-21, at the end of the chat that removed the manager role and
built F1. Read this first, then `BACKLOG.md`, then the spec.

**The spec is the build contract:** `C:\Users\babak\Downloads\taskflow_feature_spec_2.md`

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
- **Constraint 5** — no calendar *integration* or free/busy scheduling. The
  read-only month view of due dates was kept on that reading. Say so explicitly
  in the report, because a stricter reading would call it a violation.

---

## 2. Where things stand

Repo: `D:\0A-study-A0\0-ACSAI-0\sem 6\HCI\Antigravity workspace`
GitHub: `babakch76/taskflow` (**private**), branch `main`.

Last three commits:

| Commit | What |
|---|---|
| `4e323dc` | **F1 — the Board** |
| `b22bbc0` | Removed the manager role and the deadline permission gate |
| `77c541e` | Release signing + "Producing an APK" docs |

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

### Not verified

**F1 was never run on a device.** The emulator was closed. `done_by`/`done_at`
is covered by Go tests; the board layout, the undo window, and the auto-open
navigation are compile-and-test only. **Verify these first in the next
session.**

---

## 3. Decisions already made — don't re-litigate

| Decision | Choice |
|---|---|
| Manager role | Removed. Nothing is role-gated now. |
| Calendar | Kept, as a secondary group tab. The Board is the home screen. |
| Branching | F1 went to `main`. **F2 and everything after goes on a `v2-chores` integration branch**, so `main` keeps a working demo and installable APK. |
| Board placement | Replaced the Tasks tab in place, not a new tab. |
| Navigation | One group → straight to its board; several → the list. |
| `in_progress` | Kept for now; it disappears with the chore model at F2. |

---

## 4. Blocked, needs Babak

1. **The AWS server is two deploys behind.** It still runs the binary with the
   deadline 403 and without `done_by`/`done_at`. SSH is refused because
   `taskflow-sg`'s SSH rule is pinned to an IP that no longer matches.
   **Fix:** AWS console → EC2 → Security Groups → `taskflow-sg` → Inbound rules
   → Edit → SSH → source **My IP**.
2. **After deploying**, demote the leftover admin row:
   `UPDATE group_members SET role='member' WHERE role='admin';`
   Cosmetic only — the role grants nothing now — but the chip still reads
   "Manager".

---

## 5. Next step: F2

The spec's build order, item 2. This is the first thing that cannot be built on
existing endpoints. Two questions were asked and **not answered** — settle them
before writing code:

**(a) What happens to the `tasks` table and its data?**
- New `chores` + `occurrences` tables alongside `tasks`, existing tasks become
  the spec's "one-off" type (it already defines one). Nothing lost, `main`
  keeps working. Most code, least risk.
- Or migrate tasks into occurrences and drop `tasks` — cleaner end state, but
  irreversible and has to be right first time against the live database.
- Or abandon existing tasks and wipe the demo data.

**(b) How to slice F2?** It is large: four schedule types, rotation, the
unified turn rule, occurrence spawning. Suggested three commits on
`v2-chores`, each building and tested: model + CRUD + board reads them →
schedule types and spawning on completion → the unified turn rule with the
debt case.

### The two rules in F2 most easily got wrong

- **Rotation advances on completion, never on the calendar.** An undone chore
  stays with the same person; interval chores just sit there with a past due
  date, fixed-date chores roll to the next date with the *same* assignee.
- **The unified turn rule.** An occurrence completed by anyone other than its
  assignee — or passed via busy (F5) — *counts as the doer's turn*: the next
  occurrence goes back to the original assignee, and rotation continues after
  the doer. One rule covers busy passes, voluntary covers, and the overflowing
  bin. Nobody's patience can be waited out.

---

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
