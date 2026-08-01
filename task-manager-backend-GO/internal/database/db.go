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
		updated_at  DATETIME
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
