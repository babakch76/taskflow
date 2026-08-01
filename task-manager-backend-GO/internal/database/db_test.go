package database

import (
	"database/sql"
	"path/filepath"
	"testing"
)

// hasColumn is the test-side equivalent of columnExists, kept separate so a bug
// in columnExists can't make these tests pass by agreeing with itself.
func hasColumn(t *testing.T, db *sql.DB, table, column string) bool {
	t.Helper()
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		t.Fatalf("pragma table_info(%s): %v", table, err)
	}
	defer rows.Close()

	for rows.Next() {
		var (
			cid                 int
			name, colType       string
			notNull, primaryKey int
			defaultValue        sql.NullString
		)
		if err := rows.Scan(&cid, &name, &colType, &notNull, &defaultValue, &primaryKey); err != nil {
			t.Fatalf("scan table_info: %v", err)
		}
		if name == column {
			return true
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("iterate table_info: %v", err)
	}
	return false
}

// A brand new database gets the full current schema, and reopening it runs
// migrate() a second time without error.
func TestMigrateIsIdempotentOnFreshDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fresh.db")

	db, err := New(path)
	if err != nil {
		t.Fatalf("first open: %v", err)
	}
	if !hasColumn(t, db.DB, "tasks", "updated_at") {
		t.Fatal("fresh database is missing tasks.updated_at")
	}
	db.Close()

	// Second run: every statement must be a no-op, not an error.
	db2, err := New(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer db2.Close()

	if !hasColumn(t, db2.DB, "tasks", "updated_at") {
		t.Fatal("tasks.updated_at disappeared on reopen")
	}
}

// The case the ALTER step exists for: a database created before updated_at was
// added. CREATE TABLE IF NOT EXISTS would leave it without the column forever.
func TestMigrateAddsUpdatedAtToPreExistingDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")

	// Build a database with the pre-updated_at tasks table, exactly as an older
	// build of the server would have left it.
	legacy, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatalf("open legacy db: %v", err)
	}
	_, err = legacy.Exec(`
		CREATE TABLE users (
			id            TEXT PRIMARY KEY,
			username      TEXT UNIQUE NOT NULL,
			email         TEXT UNIQUE NOT NULL,
			password_hash TEXT NOT NULL,
			created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE groups (
			id          TEXT PRIMARY KEY,
			name        TEXT NOT NULL,
			description TEXT DEFAULT '',
			created_by  TEXT NOT NULL REFERENCES users(id),
			created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE tasks (
			id          TEXT PRIMARY KEY,
			group_id    TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
			assigned_to TEXT REFERENCES users(id),
			title       TEXT NOT NULL,
			description TEXT DEFAULT '',
			status      TEXT NOT NULL DEFAULT 'todo' CHECK(status IN ('todo','in_progress','done')),
			due_date    DATETIME,
			created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
		);
		INSERT INTO users (id, username, email, password_hash) VALUES ('u1','old','old@example.test','x');
		INSERT INTO groups (id, name, created_by) VALUES ('g1','Old Group','u1');
		INSERT INTO tasks (id, group_id, title) VALUES ('t1','g1','pre-existing task');
	`)
	if err != nil {
		t.Fatalf("build legacy schema: %v", err)
	}
	if hasColumn(t, legacy, "tasks", "updated_at") {
		t.Fatal("legacy fixture already has updated_at — the test proves nothing")
	}
	legacy.Close()

	db, err := New(path)
	if err != nil {
		t.Fatalf("migrate existing database: %v", err)
	}
	defer db.Close()

	if !hasColumn(t, db.DB, "tasks", "updated_at") {
		t.Fatal("migrate() did not add tasks.updated_at to an existing database")
	}

	// The new tables arrive too, and the old row survives with a NULL stamp.
	if !hasColumn(t, db.DB, "activity_events", "event_type") {
		t.Fatal("migrate() did not create activity_events on an existing database")
	}

	var (
		title     string
		updatedAt sql.NullString
	)
	if err := db.QueryRow(`SELECT title, updated_at FROM tasks WHERE id = 't1'`).
		Scan(&title, &updatedAt); err != nil {
		t.Fatalf("read migrated row: %v", err)
	}
	if title != "pre-existing task" {
		t.Fatalf("existing data was lost: title = %q", title)
	}
	if updatedAt.Valid {
		t.Fatalf("expected NULL updated_at on a backfilled row, got %q", updatedAt.String)
	}

	// And migrating again changes nothing.
	db.Close()
	db2, err := New(path)
	if err != nil {
		t.Fatalf("second migrate of existing database: %v", err)
	}
	db2.Close()
}
