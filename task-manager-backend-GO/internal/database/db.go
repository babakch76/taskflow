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
		created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
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

		-- done_by and done_at travel together: either both set (a completion) or
		-- both NULL (still open, or an undone completion). Neither half alone
		-- describes anything real.
		CHECK ((done_by IS NULL) = (done_at IS NULL)),
		CHECK (status = 'done' OR done_by IS NULL)
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
	`
	if _, err := db.Exec(schema); err != nil {
		return err
	}

	// CREATE TABLE IF NOT EXISTS is a no-op on a database that already has the
	// table, so columns added after the fact need their own step. Run them
	// after the schema block, and make them idempotent.
	return addMissingColumns(db)
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
