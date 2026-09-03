package database

import (
	"database/sql"
	"fmt"

	_ "github.com/mattn/go-sqlite3"
)

type DB struct {
	*sql.DB
}

func New(path string) (*DB, error) {
	db, err := sql.Open("sqlite3", path+"?_journal_mode=WAL&_foreign_keys=on&_busy_timeout=5000")
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	// SQLite allows concurrent readers but only one writer. With Go's default
	// connection pool, multiple goroutines may open separate connections and
	// attempt simultaneous writes. _busy_timeout=5000 makes writers wait up
	// to 5 seconds for the lock instead of immediately returning SQLITE_BUSY.
	//
	// We also cap the pool to prevent connection sprawl — SQLite doesn't
	// benefit from many connections the way PostgreSQL does.
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &DB{db}, nil
}

// migrate brings a database — fresh or existing — up to the current schema.
//
// activity_events is an append-only audit trail powering the CSCW feedback
// loop: the Android client polls GET /groups/{id}/activity?since=... to show
// who did what, which is why it is indexed on (group_id, created_at).
func migrate(db *sql.DB) error {
	schema := `
	CREATE TABLE IF NOT EXISTS users (
		id            TEXT PRIMARY KEY,
		username      TEXT UNIQUE NOT NULL,
		email         TEXT UNIQUE NOT NULL,
		password_hash TEXT NOT NULL,
		created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
		-- Quiet hours, "HH:MM", per user (F3). A reminder that would land
		-- inside this window is held until the next allowed moment.
		--
		-- The default window wraps midnight, which is the normal case and the
		-- one the arithmetic has to get right: 21:00 to 09:00 is "late evening
		-- until morning", not an empty range.
		quiet_from    TEXT NOT NULL DEFAULT '21:00',
		quiet_to      TEXT NOT NULL DEFAULT '09:00'
	);

	CREATE TABLE IF NOT EXISTS groups (
		id          TEXT PRIMARY KEY,
		name        TEXT NOT NULL,
		description TEXT DEFAULT '',
		created_by  TEXT NOT NULL REFERENCES users(id),
		created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS group_members (
		id        TEXT PRIMARY KEY,
		group_id  TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		role      TEXT NOT NULL DEFAULT 'member' CHECK(role IN ('owner','admin','member')),
		joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
		-- Away (F5): physically not at the house, so lifted out of every
		-- rotation for the duration and re-entered at the same position on
		-- return. No turns are owed back — away is not a debt, unlike busy.
		--
		-- away_since NULL means present. away_until NULL alongside a set
		-- away_since means open-ended: away until they say otherwise. A period
		-- that has run out needs no cleanup — the comparison simply stops
		-- matching, so people return on their own.
		away_since DATETIME,
		away_until DATETIME,
		UNIQUE(group_id, user_id)
	);

	CREATE TABLE IF NOT EXISTS invites (
		id           TEXT PRIMARY KEY,
		group_id     TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		invited_by   TEXT NOT NULL REFERENCES users(id),
		invited_user TEXT REFERENCES users(id),
		status       TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','declined','expired','active')),
		invite_code  TEXT UNIQUE,
		max_uses     INTEGER DEFAULT 1,
		use_count    INTEGER DEFAULT 0,
		created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
		expires_at   DATETIME
	);

	CREATE TABLE IF NOT EXISTS tasks (
		id          TEXT PRIMARY KEY,
		group_id    TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		assigned_to TEXT REFERENCES users(id),
		title       TEXT NOT NULL,
		description TEXT DEFAULT '',
		status      TEXT NOT NULL DEFAULT 'todo' CHECK(status IN ('todo','in_progress','done')),
		due_date    DATETIME,
		created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
		updated_at  DATETIME,
		done_by     TEXT REFERENCES users(id),
		done_at     DATETIME
	);

	-- A chore is a *definition*, not a to-do: what it is, how often it comes
	-- round, and whose turn order it follows. It never appears on the board
	-- itself — its occurrences do.
	--
	-- The four schedule types come from the spec. Only the columns belonging to
	-- the chosen type are populated; the CHECK below enforces that pairing so a
	-- half-specified schedule cannot be stored.
	CREATE TABLE IF NOT EXISTS chores (
		id             TEXT PRIMARY KEY,
		group_id       TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		name           TEXT NOT NULL,
		-- F4's "what done means": one agreed line, capped at 140 chars because
		-- this is a treaty, not a manual. Enforced in the handler too.
		done_line      TEXT DEFAULT '',
		schedule_type  TEXT NOT NULL CHECK(schedule_type IN ('interval','fixed_date','as_needed','one_off')),
		-- interval only: every N days, any whole number from 1 to 365. The
		-- handler holds the bounds; they exist to catch a typo rather than to
		-- limit the choice.
		interval_days  INTEGER,
		-- fixed_date only: comma-separated weekdays (0=Sunday..6) or month days
		-- (1..31). Exactly one of the two is set.
		fixed_weekdays   TEXT,
		fixed_month_days TEXT,
		-- Optional "needed by" clock time, "HH:MM". Drives F3's second reminder;
		-- stored now so the schedule is complete from the start.
		needed_by_time TEXT,
		created_by     TEXT NOT NULL REFERENCES users(id),
		created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
		updated_at     DATETIME,

		CHECK (
			(schedule_type = 'interval'   AND interval_days IS NOT NULL
			                              AND fixed_weekdays IS NULL AND fixed_month_days IS NULL) OR
			(schedule_type = 'fixed_date' AND interval_days IS NULL
			                              AND ((fixed_weekdays IS NOT NULL) <> (fixed_month_days IS NOT NULL))) OR
			(schedule_type IN ('as_needed','one_off') AND interval_days IS NULL
			                              AND fixed_weekdays IS NULL AND fixed_month_days IS NULL)
		)
	);

	-- The ordered rotation list: any subset of members, minimum one. Position is
	-- the turn order; rotation advances through it on completion, never on the
	-- calendar. A member leaving is deleted here and the gap closes.
	CREATE TABLE IF NOT EXISTS chore_rotation (
		chore_id TEXT NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
		user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		position INTEGER NOT NULL,
		PRIMARY KEY (chore_id, user_id)
	);

	-- One cycle of a chore, assigned to exactly one member. This is what the
	-- board shows and what gets marked done.
	--
	-- Two invariants from the spec, both enforced here rather than by convention:
	--   * status is 'open' or 'done'. There is deliberately no 'missed' — an
	--     open occurrence never expires and never disappears, it just keeps
	--     sitting on its assignee's row with a due date in the past.
	--   * assigned_to is NOT NULL. "Everything on the board always has exactly
	--     one name on it" — there are no unassigned chores and no claim pool.
	CREATE TABLE IF NOT EXISTS occurrences (
		id          TEXT PRIMARY KEY,
		chore_id    TEXT NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
		group_id    TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		assigned_to TEXT NOT NULL REFERENCES users(id),
		status      TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','done')),
		due_date    DATETIME,
		done_by     TEXT REFERENCES users(id),
		done_at     DATETIME,
		created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
		-- The occurrence whose completion spawned this one, if any.
		--
		-- Completing an occurrence creates the next one, and undoing that
		-- completion has to take the new one away again — otherwise ten
		-- seconds of misclick leaves a phantom turn on someone's row forever.
		-- Matching on "the newest open occurrence of this chore" would guess;
		-- this records it.
		spawned_from TEXT REFERENCES occurrences(id) ON DELETE SET NULL,
		-- Where the rotation picks up again once this occurrence is completed
		-- by the person it is assigned to.
		--
		-- Normally NULL, and the turn simply moves to whoever follows the
		-- assignee. It is set only on a *debt* occurrence — one handed back to
		-- someone because a cover was done on their behalf — and it holds the
		-- coverer, because the cover counted as the coverer's turn and the
		-- rotation must resume after *them*, not after the person repaying.
		-- Without it the repayment would look like an ordinary turn and the
		-- coverer would be asked to go again immediately.
		resume_after TEXT REFERENCES users(id),
		-- Who passed this occurrence away, if anyone (F5's busy pass).
		--
		-- The debt belongs to whoever's turn it *was*, so this survives a chain
		-- of passes: if A passes to B and B passes on to C, A still owes it. B
		-- declined a favour, not a duty — B's own turn comes round untouched.
		--
		-- It is what lets a pass reuse the turn rule instead of growing a second
		-- copy of it: whoever finally does the chore is doing it for the passer,
		-- which is exactly a voluntary cover.
		passed_from TEXT REFERENCES users(id),
		-- When it last changed hands. The receiver's "your turn" reminder is
		-- measured from this rather than from creation, which may be weeks old.
		passed_at   DATETIME,

		-- done_by and done_at travel together: either both set (a completion) or
		-- both NULL (still open, or an undone completion). Neither half alone
		-- describes anything real.
		CHECK ((done_by IS NULL) = (done_at IS NULL)),
		CHECK (status = 'done' OR done_by IS NULL)
	);

	-- Away, as a record rather than a flag (F5, needed properly by F6).
	--
	-- The first cut kept away on group_members as two columns, which answers
	-- "is this person away now" and nothing else. History has to answer "were
	-- they away *then*", because the per-person view counts completions over a
	-- window and a low count with no explanation is exactly the reading the
	-- spec exists to prevent: absence must never look like flaking.
	--
	-- Three timestamps, because "until" and "came back" are different facts:
	--   started_at — when they left
	--   ends_at    — when they said they would be back; NULL for open-ended
	--   ended_at   — when they actually returned, if they said so explicitly
	--
	-- A period is over at the earliest of ended_at and ends_at. Someone who
	-- said "a week" and came back in three days has a three-day period.
	CREATE TABLE IF NOT EXISTS away_periods (
		id         TEXT PRIMARY KEY,
		group_id   TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		started_at DATETIME NOT NULL,
		ends_at    DATETIME,
		ended_at   DATETIME
	);

	CREATE TABLE IF NOT EXISTS activity_events (
		id         TEXT PRIMARY KEY,
		group_id   TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
		actor_id   TEXT NOT NULL REFERENCES users(id),
		event_type TEXT NOT NULL,
		task_id    TEXT,
		detail     TEXT DEFAULT '',
		created_at DATETIME DEFAULT CURRENT_TIMESTAMP
	);

	CREATE INDEX IF NOT EXISTS idx_group_members_group ON group_members(group_id);
	CREATE INDEX IF NOT EXISTS idx_group_members_user  ON group_members(user_id);
	CREATE INDEX IF NOT EXISTS idx_tasks_group         ON tasks(group_id);
	CREATE INDEX IF NOT EXISTS idx_tasks_assigned      ON tasks(assigned_to);
	CREATE INDEX IF NOT EXISTS idx_invites_code        ON invites(invite_code);
	CREATE INDEX IF NOT EXISTS idx_invites_user        ON invites(invited_user);
	CREATE INDEX IF NOT EXISTS idx_activity_group_time ON activity_events(group_id, created_at);
	CREATE INDEX IF NOT EXISTS idx_chores_group          ON chores(group_id);
	CREATE INDEX IF NOT EXISTS idx_rotation_chore        ON chore_rotation(chore_id, position);
	-- The board query: every occurrence in a group, open ones first.
	CREATE INDEX IF NOT EXISTS idx_occurrences_group     ON occurrences(group_id, status);
	CREATE INDEX IF NOT EXISTS idx_occurrences_chore     ON occurrences(chore_id);
	CREATE INDEX IF NOT EXISTS idx_away_group_user       ON away_periods(group_id, user_id);
	`
	if _, err := db.Exec(schema); err != nil {
		return err
	}

	// CREATE TABLE IF NOT EXISTS is a no-op on a database that already has the
	// table, so columns added after the fact need their own step. Run them
	// after the schema block, and make them idempotent.
	if err := addMissingColumns(db); err != nil {
		return err
	}
	return backfillAwayPeriods(db)
}

// backfillAwayPeriods moves anyone currently marked away under the old
// two-column scheme into away_periods, once.
//
// Without it, upgrading would silently un-away everybody: the new code reads
// periods, the old rows hold columns, and nobody would be told.
//
// It clears the old columns as it goes, and that is the whole of what makes it
// run once. An earlier version skipped members who already had an *open*
// period, which looks idempotent and is not: once that member came back the
// period closed, the stale column still said "away", and the next restart
// inserted a second record for the same absence. Two overlapping periods then
// double-count in F6's away-days arithmetic — which showed up as one member
// being "away 11 days" for a five-day trip.
func backfillAwayPeriods(db *sql.DB) error {
	hasOldColumn, err := columnExists(db, "group_members", "away_since")
	if err != nil {
		return err
	}
	if !hasOldColumn {
		return nil
	}

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`
		INSERT INTO away_periods (id, group_id, user_id, started_at, ends_at, ended_at)
		SELECT lower(hex(randomblob(16))), gm.group_id, gm.user_id, gm.away_since, gm.away_until, NULL
		FROM group_members gm
		WHERE gm.away_since IS NOT NULL`); err != nil {
		return err
	}
	// Consumed. The columns are dead from here on, and clearing them is what
	// stops this ever firing again for the same absence.
	if _, err := tx.Exec(
		`UPDATE group_members SET away_since = NULL, away_until = NULL WHERE away_since IS NOT NULL`,
	); err != nil {
		return err
	}
	return tx.Commit()
}

// addMissingColumns applies ALTER TABLE ADD COLUMN for columns introduced after
// the initial schema, skipping any that a fresh database already got from the
// CREATE TABLE above. PRAGMA table_info is checked first rather than matching on
// the "duplicate column name" error string, which is not part of SQLite's
// stable API.
//
// Note: SQLite cannot add a column with a non-constant DEFAULT, so updated_at
// is added bare — pre-existing rows keep NULL until they are next updated,
// which is why models.Task.UpdatedAt is a *time.Time.
func addMissingColumns(db *sql.DB) error {
	type column struct {
		table string
		name  string
		ddl   string
	}
	columns := []column{
		{table: "tasks", name: "updated_at", ddl: `ALTER TABLE tasks ADD COLUMN updated_at DATETIME`},
		// Who actually completed it and when — recorded from the moment the
		// board exists, because the completion history cannot be reconstructed
		// afterwards. The doer is not always the assignee.
		{table: "tasks", name: "done_by", ddl: `ALTER TABLE tasks ADD COLUMN done_by TEXT REFERENCES users(id)`},
		{table: "tasks", name: "done_at", ddl: `ALTER TABLE tasks ADD COLUMN done_at DATETIME`},
		// Added while F2 was in progress, so a database created from the first
		// chore commit picks it up rather than needing to be thrown away.
		{table: "occurrences", name: "spawned_from", ddl: `ALTER TABLE occurrences ADD COLUMN spawned_from TEXT REFERENCES occurrences(id)`},
		{table: "occurrences", name: "resume_after", ddl: `ALTER TABLE occurrences ADD COLUMN resume_after TEXT REFERENCES users(id)`},
		// F3. A literal is a constant default, so unlike updated_at these can be
		// added NOT NULL and existing users get the standard window rather than
		// a NULL that every caller would have to interpret.
		// Superseded by away_periods, which records absences rather than only
		// the current one. Kept so backfillAwayPeriods still has something to
		// read on a database written by the version in between, and because
		// dropping a column rewrites the table for no gain. Nothing reads them.
		{table: "group_members", name: "away_since", ddl: `ALTER TABLE group_members ADD COLUMN away_since DATETIME`},
		{table: "group_members", name: "away_until", ddl: `ALTER TABLE group_members ADD COLUMN away_until DATETIME`},
		{table: "occurrences", name: "passed_from", ddl: `ALTER TABLE occurrences ADD COLUMN passed_from TEXT REFERENCES users(id)`},
		{table: "occurrences", name: "passed_at", ddl: `ALTER TABLE occurrences ADD COLUMN passed_at DATETIME`},
		// Who covered the turn this occurrence is handing back.
		//
		// Set only when the debt rule returns a chore to the person who owed it,
		// which is the one moment the board has something to explain: the row
		// came back to you because somebody else did the last one.
		{table: "occurrences", name: "covered_by", ddl: `ALTER TABLE occurrences ADD COLUMN covered_by TEXT REFERENCES users(id)`},
		{table: "users", name: "quiet_from", ddl: `ALTER TABLE users ADD COLUMN quiet_from TEXT NOT NULL DEFAULT '21:00'`},
		{table: "users", name: "quiet_to", ddl: `ALTER TABLE users ADD COLUMN quiet_to TEXT NOT NULL DEFAULT '09:00'`},
	}

	for _, c := range columns {
		exists, err := columnExists(db, c.table, c.name)
		if err != nil {
			return fmt.Errorf("check %s.%s: %w", c.table, c.name, err)
		}
		if exists {
			continue
		}
		if _, err := db.Exec(c.ddl); err != nil {
			return fmt.Errorf("add %s.%s: %w", c.table, c.name, err)
		}
	}
	return nil
}

// columnExists reports whether table has a column with the given name.
func columnExists(db *sql.DB, table, column string) (bool, error) {
	// PRAGMA does not accept a bound parameter for the table name. Every call
	// site passes a hardcoded literal, so there is no injection surface here.
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return false, err
	}
	defer rows.Close()

	// PRAGMA table_info yields: cid, name, type, notnull, dflt_value, pk
	for rows.Next() {
		var (
			cid                 int
			name, colType       string
			notNull, primaryKey int
			defaultValue        sql.NullString
		)
		if err := rows.Scan(&cid, &name, &colType, &notNull, &defaultValue, &primaryKey); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

// IsMember checks if a user belongs to a group — the core of data siloing.
func (db *DB) IsMember(groupID, userID string) (bool, error) {
	var exists bool
	err := db.QueryRow(
		`SELECT EXISTS(SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?)`,
		groupID, userID,
	).Scan(&exists)
	return exists, err
}

// GetMemberRole returns the role of a user in a group, or empty string if not a member.
func (db *DB) GetMemberRole(groupID, userID string) (string, error) {
	var role string
	err := db.QueryRow(
		`SELECT role FROM group_members WHERE group_id = ? AND user_id = ?`,
		groupID, userID,
	).Scan(&role)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return role, err
}
