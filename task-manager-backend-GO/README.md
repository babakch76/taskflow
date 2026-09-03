# TaskFlow backend (Go)

The API behind TaskFlow, a chore board for a household that shares one:
private households, invite-based membership, recurring chores with a rotation,
and a record of who did what.

**"Progress tracking" is gone**, and its absence is a requirement rather than an
omission: no scores, percentages, streaks or rankings anywhere. See
[`../FEATURES.md`](../FEATURES.md) section 8 for the full list of what this
deliberately does not do.

## Architecture

Single-server deployment: Go HTTP server + SQLite database.

```
Mobile App  ──HTTPS/JSON──►  Go Server (port 8080)
                                 ├── Auth (JWT)
                                 ├── Groups & Invites
                                 ├── Tasks CRUD
                                 ├── Membership Guard (data siloing)
                                 └── SQLite database file
```

## Quick Start

```bash
# Prerequisites: Go 1.22+, GCC (for SQLite CGO)

# Clone and run
cd task-manager-backend
go mod tidy
go run ./cmd/server

# Server starts on :8080 with SQLite at ./taskmanager.db
```

### Environment Variables

See the table under [Deployment](#deployment). For local development the
defaults are fine and nothing needs setting.

## API Reference

### Auth (public)

```
POST /auth/register  { "username", "email", "password" }  → { token, user }
POST /auth/login     { "email", "password" }               → { token, user }
```

### Groups (requires Bearer token)

```
POST   /groups                    { "name", "description" }  → group
GET    /groups                                               → [groups]
GET    /groups/:group_id                                     → group
GET    /groups/:group_id/members                             → [members]

# Leave a group (removes only the caller's membership)
DELETE /groups/:group_id/members/me                          → { message }

# Away, or back (F5). `until` is RFC 3339, or null for open-ended.
PUT    /groups/:group_id/members/me/away  { "away", "until?" }  → { message }
```

`GET /groups/:group_id` still returns `my_role`, the caller's own role.

**There is no role endpoint.** `PATCH /members/:user_id/role` was removed with
the manager role: the spec makes chore editing open to every member, so a
permission gate contradicted it. `group_members.role` still accepts `admin` at
the schema level because dropping a CHECK constraint rewrites the table for no
gain, but nothing sets it and nothing reads it. Ownership is the only
distinction left, and it does exactly one thing: the owner cannot leave a
household while other members remain.

`DELETE /members/me` has three outcomes:

| Caller                              | Result                                        |
|-------------------------------------|-----------------------------------------------|
| Last remaining member               | `200` — group deleted, cascade clears its data |
| Owner, other members still present  | `409` — ownership transfer is out of scope     |
| Any other member                    | `200` — membership row removed                 |

### Chores and occurrences

The recurring half of the board, and the part with all the rules in it. A
**chore** is the arrangement; an **occurrence** is one turn of it. Completing an
occurrence creates the next one, which is the only way a turn moves.

```
POST   /groups/:group_id/chores    { "name", "schedule_type", "interval_days?",
                                     "fixed_weekdays?", "fixed_month_days?",
                                     "needed_by_time?", "done_line?", "rotation" }
GET    /groups/:group_id/chores                              → [chores]
PATCH  /groups/:group_id/chores/:chore_id                    → chore
DELETE /groups/:group_id/chores/:chore_id                    → { message }

GET    /groups/:group_id/occurrences                         → [occurrences]

# Mark done, or undo that. Anyone may mark anything done; undo is only for the
# person who marked it, and only inside 10 minutes.
PATCH  /groups/:group_id/occurrences/:occurrence_id  { "status": "open"|"done" }

# The busy pass (F5), and taking one back within 2 minutes.
POST   /groups/:group_id/occurrences/:occurrence_id/pass     → occurrence
DELETE /groups/:group_id/occurrences/:occurrence_id/pass     → occurrence

# Bring a day forward. Only when a whole household passed and the chore came
# back to whoever asked first; only earlier, never later.
PUT    /groups/:group_id/occurrences/:occurrence_id/due-date  { "due_date" }
```

`schedule_type` is `interval`, `fixed_date` or `as_needed`. A one-off is a
**task**, not a chore, and lives under the task endpoints below.

Two fields on an occurrence are deliberately **not** serialised: `passed_chain`
(everyone who has passed it) and `pending_debts` (turns still owed). Publishing
either would undo the privacy of the busy pass, and a client that could read
them could count them. The turn rule itself is documented in
[`../FEATURES.md`](../FEATURES.md) section 4, and case by case in
`internal/handlers/turn_rule_test.go`.

### History

```
GET /groups/:group_id/history                       → per-person record
GET /groups/:group_id/chores/:chore_id/history      → one chore, every completion
```

Counts, never ranks. `internal/handlers/history.go` carries the constraint in a
comment and `history_test.go` asserts that words like `late`, `overdue`,
`missed` and `streak` appear nowhere in the payload.

### The signed-in user

```
GET   /me                                        → user
PATCH /me   { "quiet_from?", "quiet_to?" }       → user
```

Quiet hours live on the **user**, not the group: one window applying to
reminders from every household they belong to.

### Activity feed

```
GET /groups/:group_id/activity                  → [events]  (newest first)
GET /groups/:group_id/activity?since=<RFC3339>  → [events]  (newer than `since`)
```

**A busy pass writes no event here**, nor does undoing one. That is the one
deliberate hole in the feed, and it is what makes the pass private.

An append-only trail of what members did, joined with the actor's username so
the client can render it without a second request. This is the polling target
for the shared-awareness feedback loop: pass the `created_at` of the newest
event you already hold as `since`. Capped at 200 events per call.

Two details matter if you touch this code:

- **Events carry millisecond timestamps**, written with
  `strftime('%Y-%m-%d %H:%M:%f','now')` rather than the column's
  `CURRENT_TIMESTAMP` default. `CURRENT_TIMESTAMP` has one-second resolution,
  and `since` is a strictly-greater-than filter — at that resolution every event
  written later in the same second as a client's last poll is silently dropped
  and that member's change never becomes visible.
- **Ties break on `rowid`, not `id`.** Even at millisecond resolution events can
  share a timestamp (a task create and its immediate update, or several writes
  in one transaction). `activity_events.id` is a random UUID, so ordering by it
  would scramble same-millisecond events; the implicit `rowid` rises with
  insertion and reflects the real order.

`event_type` is one of `task_created`, `task_updated` (with the changed fields
in `detail`), `task_deleted`, `tasks_bulk_updated`, `member_joined`,
`member_left`, `invite_accepted`.

Events are written inside the same transaction as the change they describe
wherever one exists, so a rolled-back write never leaves a phantom event behind.

### Invites

```
# Direct invite (must be group member)
POST   /groups/:group_id/invite        { "username" }        → invite

# Generate shareable code (must be group member)
POST   /groups/:group_id/invite-code                         → { code, expires_at }

# Redeem a code (any authenticated user)
POST   /invites/redeem                 { "code" }            → { message }

# List pending invites for current user
GET    /invites                                              → [invites]

# Accept or decline
PATCH  /invites/:invite_id             { "action": "accept"|"decline" }
```

### Tasks (must be group member)

A **task** is the app's one-off: given to one person once, with no rotation and
no turn to owe. Tasks predate the chore model and are the spec's one-off type,
so they stay on the board beside occurrences rather than being migrated.

```
POST   /groups/:group_id/tasks         { "title", "description", "assigned_to?", "due_date?" }
GET    /groups/:group_id/tasks                               → [tasks]
PATCH  /groups/:group_id/tasks/:id     { "title?", "status?", "assigned_to?", "due_date?" }
DELETE /groups/:group_id/tasks/:id
```

**There is no bulk endpoint.** `PATCH /groups/:group_id/tasks` was removed with
the multi-select UI it existed for; nothing had called it since. Its
`tasks_bulk_updated` activity constant and the client's rendering of that
constant both stay, because rows written before the removal still carry it.

`status` accepts `todo`, `in_progress` or `done` at the schema level, but the
app only ever writes `todo` and `done`: the middle state was dropped with the
status chips. The feed still renders `in_progress` for old rows.

### PATCH field semantics

`assigned_to` and `due_date` are tri-state (see `models.NullableField`):

| Request body           | Meaning              |
|------------------------|----------------------|
| key omitted            | leave the field alone |
| `"assigned_to": null`  | clear the field       |
| `"assigned_to": "id"`  | set the field         |

A patch with no recognised fields is rejected with
`400 {"error":"no fields to update"}`.

## Roles and permissions

**Nothing is gated by role.** Every member can create, edit and delete chores
and tasks, set and clear deadlines, invite, and leave.

This section used to describe a **Manager** role that could set deadlines when
members could not. It was removed: the spec makes chore editing open to every
member, so the gate contradicted the thing it was guarding. `BACKLOG.md` records
it under the manager-role removal.

What remains:

- `group_members.role` still stores `owner` or `member`, and its CHECK still
  accepts `admin` because dropping a CHECK constraint means rebuilding the table
  in SQLite, for no gain. Nothing writes `admin` and nothing reads it.
- `GET /groups/:group_id` returns `my_role`, which clients use for labelling
  rather than for gating.
- **Ownership does exactly one thing:** `LeaveGroup` returns `409` when an owner
  tries to leave a household that still has other members. There is one owner,
  set at creation, and ownership transfer is out of scope.

Non-members get `404` rather than `403`, so the API never confirms that a
household exists to somebody who is not in it.

The tri-state patch contract that the old deadline gate relied on is still
worth knowing about, because it is load-bearing elsewhere: `Patchable` tells
"absent" apart from "present and null", which is how a client clears a field
without every silent field being wiped. See `internal/models/Patchable`.

## Data Siloing

Non-members receive `404 Not Found` (not `403 Forbidden`) for any group-scoped
endpoint. This prevents external users from even confirming a group exists.

## Database migrations

`migrate()` runs on every start and is safe against both a fresh and an existing
database. It has two phases:

1. `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` for the whole
   schema.
2. `addMissingColumns()` for columns added after the initial schema. Each is
   guarded by a `PRAGMA table_info` check rather than by matching on SQLite's
   "duplicate column name" error text, so re-running is a no-op.

### ⚠️ `tasks.updated_at` — existing dev databases

`tasks.updated_at` was added after the first version of the schema. Because
phase 1 is `CREATE TABLE IF NOT EXISTS`, a `taskmanager.db` created before that
change would never have picked the column up on its own — phase 2 exists for
exactly this.

**You do not need to do anything**: start the server and the column is added
automatically. If you would rather start clean, delete the db file (this
destroys all local data):

```bash
rm taskmanager.db taskmanager.db-wal taskmanager.db-shm
```

SQLite cannot add a column with a non-constant default, so `updated_at` is added
bare and pre-existing task rows keep `NULL` until their next update. That is why
`Task.UpdatedAt` is a `*time.Time` and is `omitempty` in JSON, and why the
Kotlin `Task.updatedAt` is nullable. It is set on insert and bumped on every
`UPDATE`, including the bulk endpoint.

## Deployment

Build a binary, put it behind a TLS reverse proxy, run it under systemd.
`deploy/` holds a ready systemd unit and a Caddy config.

Two traps that will cost you an afternoon each:

- **`GOOS=linux GOARCH=amd64 go build` does not work here.** Cross-compiling
  implicitly sets `CGO_ENABLED=0`, and `mattn/go-sqlite3` is a CGO package — it
  still compiles, but into a stub, and the binary dies at startup with
  `go-sqlite3 requires cgo to work`. Build on the target machine (or with a
  Linux C cross-toolchain) and confirm with
  `go version -m ./server | grep CGO_ENABLED`.
- **The Android client requires HTTPS for any non-local host.** Its
  `network_security_config.xml` forbids cleartext except to the emulator/LAN
  addresses, so a plain `http://` public endpoint is refused by the app before
  a request is even sent. Terminate TLS in front of the server — Caddy obtains
  and renews a Let's Encrypt certificate on its own, which Android trusts with
  no client-side configuration. Do **not** work around this by widening
  `cleartextTrafficPermitted`; on a public host that puts every password and
  JWT on the wire in plaintext.

Note that the client's base URL is compiled in via `buildConfigField`, and the
constant gets inlined — after changing it you must **clean** the Android build,
not just rebuild, or the APK keeps calling the old server.

### Environment variables

| Variable     | Default                  | Description                                        |
|-------------|--------------------------|----------------------------------------------------|
| `PORT`      | `8080`                   | Server port                                        |
| `BIND_ADDR` | `` (all interfaces)      | Set to `127.0.0.1` behind a TLS reverse proxy      |
| `DB_PATH`   | `./taskmanager.db`       | SQLite database file                               |
| `JWT_SECRET`| `change-me-in-production`| **Must** be changed before exposing the server     |

Leaving `JWT_SECRET` at its default means anyone who has read this source can
forge a token for any account; the server logs a warning at startup if you do.

## Project Structure

```
├── cmd/server/main.go          # Entrypoint, routing, middleware wiring
├── internal/
│   ├── auth/auth.go            # JWT + bcrypt helpers
│   ├── database/db.go          # SQLite setup, migrations, membership checks
│   ├── handlers/
│   │   ├── auth.go             # Register, login
│   │   ├── groups.go           # Groups, leave, invites (both paths)
│   │   ├── tasks.go            # Task CRUD + bulk status update
│   │   ├── activity.go         # Activity events: recording + feed endpoint
│   │   ├── helpers.go          # JSON response utilities
│   │   └── handlers_test.go    # Handler-level tests (real SQLite, temp file)
│   ├── middleware/middleware.go # Auth + membership guard
│   └── models/models.go        # Structs and DTOs
└── go.mod
```

## Tests

```bash
go test ./...
```

Tests run against a real SQLite file in `t.TempDir()`, so they exercise the
actual schema and migrations rather than a mock. **CGO is required** —
`go-sqlite3` compiles to a non-functional stub with `CGO_ENABLED=0` and every
test fails with "requires cgo to work". On Windows install a MinGW-w64 GCC
(e.g. `winget install --id BrechtSanders.WinLibs.POSIX.UCRT`, or MSYS2) and make
sure `gcc` is on `PATH`; the same requirement applies to `go run ./cmd/server`.
