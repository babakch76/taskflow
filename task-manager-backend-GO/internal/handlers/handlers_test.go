package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/middleware"
	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

// --- test helpers -----------------------------------------------------------

func newTestDB(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.New(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open test db: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func newUser(t *testing.T, db *database.DB, username string) string {
	t.Helper()
	id := uuid.New().String()
	_, err := db.Exec(
		`INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')`,
		id, username, username+"@example.test",
	)
	if err != nil {
		t.Fatalf("insert user %s: %v", username, err)
	}
	return id
}

// newGroup creates a group with ownerID as its owner.
func newGroup(t *testing.T, db *database.DB, ownerID, name string) string {
	t.Helper()
	id := uuid.New().String()
	if _, err := db.Exec(
		`INSERT INTO groups (id, name, description, created_by) VALUES (?, ?, '', ?)`,
		id, name, ownerID,
	); err != nil {
		t.Fatalf("insert group: %v", err)
	}
	addMember(t, db, id, ownerID, "owner")
	return id
}

func addMember(t *testing.T, db *database.DB, groupID, userID, role string) {
	t.Helper()
	if _, err := db.Exec(
		`INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)`,
		uuid.New().String(), groupID, userID, role,
	); err != nil {
		t.Fatalf("insert membership: %v", err)
	}
}

func newTask(t *testing.T, db *database.DB, groupID, title string) string {
	t.Helper()
	id := uuid.New().String()
	if _, err := db.Exec(
		`INSERT INTO tasks (id, group_id, title, description, status) VALUES (?, ?, ?, '', 'todo')`,
		id, groupID, title,
	); err != nil {
		t.Fatalf("insert task: %v", err)
	}
	return id
}

// request builds an authenticated request with the given path values already
// resolved, standing in for what the router and Auth middleware would set.
func request(method, target, body, userID string, pathValues map[string]string) *http.Request {
	r := httptest.NewRequest(method, target, strings.NewReader(body))
	for k, v := range pathValues {
		r.SetPathValue(k, v)
	}
	return r.WithContext(context.WithValue(r.Context(), middleware.UserIDKey, userID))
}

func decodeError(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var body struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("response is not a JSON error object: %q", rec.Body.String())
	}
	return body.Error
}

// --- C6: invite expiry comparison -------------------------------------------

// An invite whose expires_at is an hour in the past must be refused with
// 410 Gone rather than silently letting the caller join.
func TestRedeemExpiredInviteCodeReturnsGone(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	joiner := newUser(t, db, "joiner")
	groupID := newGroup(t, db, owner, "Study Group")

	// Written directly via SQL, one hour in the past.
	if _, err := db.Exec(
		`INSERT INTO invites (id, group_id, invited_by, status, invite_code, max_uses, use_count, expires_at)
		 VALUES (?, ?, ?, 'active', 'deadbeef1234', 0, 0, ?)`,
		uuid.New().String(), groupID, owner, time.Now().Add(-1*time.Hour),
	); err != nil {
		t.Fatalf("insert expired invite: %v", err)
	}

	rec := httptest.NewRecorder()
	h.RedeemInviteCode(rec, request(
		http.MethodPost, "/invites/redeem", `{"code":"deadbeef1234"}`, joiner, nil,
	))

	if rec.Code != http.StatusGone {
		t.Fatalf("expected 410 Gone, got %d: %s", rec.Code, rec.Body.String())
	}

	// And the expired code must not have produced a membership.
	isMember, err := db.IsMember(groupID, joiner)
	if err != nil {
		t.Fatalf("IsMember: %v", err)
	}
	if isMember {
		t.Fatal("expired invite code created a membership")
	}
}

// The handler's pre-flight check would catch the case above on its own, so this
// exercises the atomic condition inside the redeem transaction directly — the
// part that actually guards against a code expiring mid-request.
//
// expires_at is bound as a time.Time, so the Go driver writes it in its own
// layout including a numeric timezone offset, while SQLite's CURRENT_TIMESTAMP
// is "YYYY-MM-DD HH:MM:SS" in UTC. Comparing those two as strings compares
// their spelling, not their instants. Wrapping both sides in datetime() is what
// makes the comparison mean what it says.
func TestInviteExpiryConditionIsFormatSafe(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Study Group")

	// A zone with a positive offset: the formatted string then sorts *after* an
	// equivalent UTC string, which is how a naive comparison goes wrong.
	rome := time.FixedZone("CEST", 2*60*60)

	cases := []struct {
		name      string
		expiresAt time.Time
		wantRows  int64
	}{
		{"expired an hour ago", time.Now().In(rome).Add(-1 * time.Hour), 0},
		{"expires in an hour", time.Now().In(rome).Add(1 * time.Hour), 1},
		{"expired a year ago", time.Now().UTC().Add(-365 * 24 * time.Hour), 0},
		{"expires next year", time.Now().UTC().Add(365 * 24 * time.Hour), 1},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := uuid.New().String()
			if _, err := db.Exec(
				`INSERT INTO invites (id, group_id, invited_by, status, invite_code, max_uses, use_count, expires_at)
				 VALUES (?, ?, ?, 'active', ?, 0, 0, ?)`,
				id, groupID, owner, uuid.New().String()[:12], tc.expiresAt,
			); err != nil {
				t.Fatalf("insert invite: %v", err)
			}

			// Same predicate as RedeemInviteCode's atomic UPDATE.
			res, err := db.Exec(`
				UPDATE invites
				SET use_count = use_count + 1
				WHERE id = ?
				  AND status = 'active'
				  AND (expires_at IS NULL OR datetime(expires_at) > datetime('now'))
				  AND (max_uses = 0 OR use_count < max_uses)
			`, id)
			if err != nil {
				t.Fatalf("update: %v", err)
			}
			got, _ := res.RowsAffected()
			if got != tc.wantRows {
				t.Fatalf("expires_at=%s: rows affected = %d, want %d",
					tc.expiresAt.Format(time.RFC3339), got, tc.wantRows)
			}
		})
	}
}

// --- C3: leave group --------------------------------------------------------

func TestLeaveGroupRemovesMembership(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	member := newUser(t, db, "member")
	groupID := newGroup(t, db, owner, "Study Group")
	addMember(t, db, groupID, member, "member")

	rec := httptest.NewRecorder()
	h.LeaveGroup(rec, request(
		http.MethodDelete, "/groups/x/members/me", "", member,
		map[string]string{"group_id": groupID},
	))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if isMember, _ := db.IsMember(groupID, member); isMember {
		t.Fatal("membership still present after leaving")
	}
	// The group and the owner's membership survive.
	if isMember, _ := db.IsMember(groupID, owner); !isMember {
		t.Fatal("owner lost membership when another member left")
	}
}

func TestLeaveGroupRejectsOwnerWhileOthersRemain(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	member := newUser(t, db, "member")
	groupID := newGroup(t, db, owner, "Study Group")
	addMember(t, db, groupID, member, "member")

	rec := httptest.NewRecorder()
	h.LeaveGroup(rec, request(
		http.MethodDelete, "/groups/x/members/me", "", owner,
		map[string]string{"group_id": groupID},
	))

	if rec.Code != http.StatusConflict {
		t.Fatalf("expected 409 Conflict, got %d: %s", rec.Code, rec.Body.String())
	}
	if msg := decodeError(t, rec); msg == "" {
		t.Fatal("409 response carried no error message")
	}
	if isMember, _ := db.IsMember(groupID, owner); !isMember {
		t.Fatal("owner was removed despite the 409")
	}
}

func TestLeaveGroupDeletesGroupWhenLastMemberLeaves(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Solo Group")
	newTask(t, db, groupID, "orphan task")

	rec := httptest.NewRecorder()
	h.LeaveGroup(rec, request(
		http.MethodDelete, "/groups/x/members/me", "", owner,
		map[string]string{"group_id": groupID},
	))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var groups int
	if err := db.QueryRow(`SELECT COUNT(*) FROM groups WHERE id = ?`, groupID).Scan(&groups); err != nil {
		t.Fatalf("count groups: %v", err)
	}
	if groups != 0 {
		t.Fatal("group survived its last member leaving")
	}

	// ON DELETE CASCADE should have taken the tasks with it.
	var tasks int
	if err := db.QueryRow(`SELECT COUNT(*) FROM tasks WHERE group_id = ?`, groupID).Scan(&tasks); err != nil {
		t.Fatalf("count tasks: %v", err)
	}
	if tasks != 0 {
		t.Fatalf("expected cascaded task delete, %d task(s) remain", tasks)
	}
}

// --- C1: activity feed ------------------------------------------------------

func TestListActivityReturnsNewestFirstAndFiltersBySince(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Study Group")
	otherGroup := newGroup(t, db, owner, "Other Group")

	// created_at has one-second resolution, so write explicit timestamps rather
	// than relying on wall-clock separation between inserts.
	insert := func(groupID, eventType string, at time.Time) {
		t.Helper()
		if _, err := db.Exec(
			`INSERT INTO activity_events (id, group_id, actor_id, event_type, detail, created_at)
			 VALUES (?, ?, ?, ?, '', ?)`,
			uuid.New().String(), groupID, owner, eventType, at.UTC().Format("2006-01-02 15:04:05"),
		); err != nil {
			t.Fatalf("insert event: %v", err)
		}
	}

	base := time.Now().UTC().Add(-1 * time.Hour)
	insert(groupID, "old", base)
	insert(groupID, "new", base.Add(30*time.Minute))
	insert(otherGroup, "other_group", base.Add(45*time.Minute))

	decode := func(target string) []models.ActivityEvent {
		t.Helper()
		rec := httptest.NewRecorder()
		h.ListActivity(rec, request(
			http.MethodGet, target, "", owner, map[string]string{"group_id": groupID},
		))
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
		}
		var events []models.ActivityEvent
		if err := json.Unmarshal(rec.Body.Bytes(), &events); err != nil {
			t.Fatalf("decode: %v (%s)", err, rec.Body.String())
		}
		return events
	}

	all := decode("/groups/x/activity")
	if len(all) != 2 {
		t.Fatalf("expected 2 events for this group, got %d", len(all))
	}
	if all[0].EventType != "new" || all[1].EventType != "old" {
		t.Fatalf("expected newest-first ordering, got %s then %s", all[0].EventType, all[1].EventType)
	}
	if all[0].ActorUsername != "owner" {
		t.Fatalf("actor username not joined in: %q", all[0].ActorUsername)
	}

	since := base.Add(10 * time.Minute).Format(time.RFC3339)
	recent := decode("/groups/x/activity?since=" + since)
	if len(recent) != 1 || recent[0].EventType != "new" {
		t.Fatalf("since filter returned %d event(s), want just the newer one", len(recent))
	}

	// A malformed timestamp is a client error, not an empty feed.
	rec := httptest.NewRecorder()
	h.ListActivity(rec, request(
		http.MethodGet, "/groups/x/activity?since=yesterday", "", owner,
		map[string]string{"group_id": groupID},
	))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for malformed since, got %d", rec.Code)
	}
}

// Events written in quick succession — well inside one second — must come back
// in the order they happened, and a ?since= poll built from the newest
// timestamp the client has seen must not skip any of them.
//
// With a one-second CURRENT_TIMESTAMP this fails both ways: the order is
// arbitrary, and every event sharing the last poll's second is dropped by the
// strictly-greater-than filter.
func TestActivityFeedOrdersAndPagesWithinTheSameSecond(t *testing.T) {
	db := newTestDB(t)
	gh := &GroupHandler{DB: db}
	th := &TaskHandler{DB: db}

	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Study Group")

	// Six events as fast as the machine will write them.
	for i := 0; i < 3; i++ {
		rec := httptest.NewRecorder()
		th.CreateTask(rec, request(
			http.MethodPost, "/groups/x/tasks",
			`{"title":"task `+string(rune('a'+i))+`"}`, owner,
			map[string]string{"group_id": groupID},
		))
		if rec.Code != http.StatusCreated {
			t.Fatalf("create %d: %d %s", i, rec.Code, rec.Body.String())
		}
		var task models.Task
		if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
			t.Fatalf("decode: %v", err)
		}
		rec = httptest.NewRecorder()
		th.UpdateTask(rec, request(
			http.MethodPatch, "/groups/x/tasks/y", `{"status":"done"}`, owner,
			map[string]string{"group_id": groupID, "task_id": task.ID},
		))
		if rec.Code != http.StatusOK {
			t.Fatalf("update %d: %d %s", i, rec.Code, rec.Body.String())
		}
	}

	feed := func(target string) []models.ActivityEvent {
		t.Helper()
		rec := httptest.NewRecorder()
		gh.ListActivity(rec, request(
			http.MethodGet, target, "", owner, map[string]string{"group_id": groupID},
		))
		if rec.Code != http.StatusOK {
			t.Fatalf("feed: %d %s", rec.Code, rec.Body.String())
		}
		var events []models.ActivityEvent
		if err := json.Unmarshal(rec.Body.Bytes(), &events); err != nil {
			t.Fatalf("decode feed: %v", err)
		}
		return events
	}

	all := feed("/groups/x/activity")
	if len(all) != 6 {
		t.Fatalf("expected 6 events, got %d", len(all))
	}

	// Newest first, so reading backwards must alternate created, updated, ...
	want := []string{
		EventTaskUpdated, EventTaskCreated,
		EventTaskUpdated, EventTaskCreated,
		EventTaskUpdated, EventTaskCreated,
	}
	for i, w := range want {
		if all[i].EventType != w {
			got := make([]string, len(all))
			for j, e := range all {
				got[j] = e.EventType
			}
			t.Fatalf("event %d: got %s, want %s (full order: %v)", i, all[i].EventType, w, got)
		}
	}

	// Polling with the newest cursor must not re-deliver what the client has.
	if again := feed("/groups/x/activity?since=" + all[0].CreatedAt.UTC().Format(time.RFC3339Nano)); len(again) != 0 {
		t.Fatalf("polling with the newest cursor re-delivered %d event(s)", len(again))
	}

	// A client polls, then more work happens, then it polls again with the
	// cursor it stored. Nothing written after the first poll may be missed.
	// The pause is deliberate: `since` is a timestamp cursor, so it can only
	// separate events that differ in their millisecond stamp.
	cursor := all[0].CreatedAt.UTC().Format(time.RFC3339Nano)
	time.Sleep(5 * time.Millisecond)

	rec := httptest.NewRecorder()
	th.CreateTask(rec, request(
		http.MethodPost, "/groups/x/tasks", `{"title":"later"}`, owner,
		map[string]string{"group_id": groupID},
	))
	if rec.Code != http.StatusCreated {
		t.Fatalf("late create: %d %s", rec.Code, rec.Body.String())
	}

	newer := feed("/groups/x/activity?since=" + cursor)
	if len(newer) != 1 || newer[0].EventType != EventTaskCreated {
		types := make([]string, len(newer))
		for i, e := range newer {
			types[i] = e.EventType
		}
		t.Fatalf("since=%s returned %v, want exactly the one later task_created", cursor, types)
	}
}

// --- C1/C2: task lifecycle writes events and stamps updated_at --------------

func TestTaskLifecycleRecordsActivityAndUpdatedAt(t *testing.T) {
	db := newTestDB(t)
	h := &TaskHandler{DB: db}

	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Study Group")

	// Create
	rec := httptest.NewRecorder()
	h.CreateTask(rec, request(
		http.MethodPost, "/groups/x/tasks", `{"title":"Write report"}`, owner,
		map[string]string{"group_id": groupID},
	))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: expected 201, got %d: %s", rec.Code, rec.Body.String())
	}
	var created models.Task
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode created task: %v", err)
	}

	// Update
	rec = httptest.NewRecorder()
	h.UpdateTask(rec, request(
		http.MethodPatch, "/groups/x/tasks/y", `{"status":"done"}`, owner,
		map[string]string{"group_id": groupID, "task_id": created.ID},
	))
	if rec.Code != http.StatusOK {
		t.Fatalf("update: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var updated models.Task
	if err := json.Unmarshal(rec.Body.Bytes(), &updated); err != nil {
		t.Fatalf("decode updated task: %v", err)
	}
	if updated.Status != "done" {
		t.Fatalf("status = %q, want done", updated.Status)
	}
	if updated.UpdatedAt == nil {
		t.Fatal("updated_at was not set by UpdateTask")
	}

	// An empty patch is still rejected, and the added updated_at clause must not
	// make one look non-empty.
	rec = httptest.NewRecorder()
	h.UpdateTask(rec, request(
		http.MethodPatch, "/groups/x/tasks/y", `{}`, owner,
		map[string]string{"group_id": groupID, "task_id": created.ID},
	))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("empty patch: expected 400, got %d: %s", rec.Code, rec.Body.String())
	}

	// Delete
	rec = httptest.NewRecorder()
	h.DeleteTask(rec, request(
		http.MethodDelete, "/groups/x/tasks/y", "", owner,
		map[string]string{"group_id": groupID, "task_id": created.ID},
	))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete: expected 204, got %d: %s", rec.Code, rec.Body.String())
	}

	for _, eventType := range []string{EventTaskCreated, EventTaskUpdated, EventTaskDeleted} {
		var count int
		if err := db.QueryRow(
			`SELECT COUNT(*) FROM activity_events WHERE group_id = ? AND event_type = ?`,
			groupID, eventType,
		).Scan(&count); err != nil {
			t.Fatalf("count %s: %v", eventType, err)
		}
		if count != 1 {
			t.Errorf("expected exactly 1 %s event, got %d", eventType, count)
		}
	}

	// The update event should say which field moved.
	var detail string
	if err := db.QueryRow(
		`SELECT detail FROM activity_events WHERE group_id = ? AND event_type = ?`,
		groupID, EventTaskUpdated,
	).Scan(&detail); err != nil {
		t.Fatalf("read update detail: %v", err)
	}
	if !strings.Contains(detail, "status=done") {
		t.Fatalf("update event detail = %q, want it to name the changed field", detail)
	}
}

// --- Anyone may set a deadline -----------------------------------------------

// Deadlines were briefly owner/manager-only. The chore spec makes chore
// editing explicitly open to every member, so the gate is gone; this pins that
// a plain member can both set and clear one.
func TestAnyMemberCanManageDueDates(t *testing.T) {
	db := newTestDB(t)
	th := &TaskHandler{DB: db}

	owner := newUser(t, db, "owner")
	plain := newUser(t, db, "plain")
	groupID := newGroup(t, db, owner, "Household")
	addMember(t, db, groupID, plain, RoleMember)
	taskID := newTask(t, db, groupID, "Take the bins out")

	patch := func(body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		th.UpdateTask(rec, request(
			http.MethodPatch, "/groups/x/tasks/y", body, plain,
			map[string]string{"group_id": groupID, "task_id": taskID},
		))
		return rec
	}

	if rec := patch(`{"due_date":"2026-12-01T09:00:00Z"}`); rec.Code != http.StatusOK {
		t.Fatalf("member setting a due date: got %d, want 200: %s", rec.Code, rec.Body.String())
	}
	if rec := patch(`{"due_date":null}`); rec.Code != http.StatusOK {
		t.Fatalf("member clearing a due date: got %d, want 200: %s", rec.Code, rec.Body.String())
	}

	var dueDate sql.NullString
	if err := db.QueryRow(`SELECT due_date FROM tasks WHERE id = ?`, taskID).Scan(&dueDate); err != nil {
		t.Fatalf("read due_date: %v", err)
	}
	if dueDate.Valid {
		t.Fatal("clear did not take effect")
	}

	rec := httptest.NewRecorder()
	th.CreateTask(rec, request(
		http.MethodPost, "/groups/x/tasks",
		`{"title":"Buy milk","due_date":"2026-12-01T09:00:00Z"}`, plain,
		map[string]string{"group_id": groupID},
	))
	if rec.Code != http.StatusCreated {
		t.Fatalf("member creating with a due date: got %d, want 201: %s", rec.Code, rec.Body.String())
	}
}

// GET /groups/{id} tells the caller their own role, so a client can gate its
// UI without fetching the member list.
func TestGetGroupReportsCallerRole(t *testing.T) {
	db := newTestDB(t)
	h := &GroupHandler{DB: db}

	owner := newUser(t, db, "owner")
	member := newUser(t, db, "member")
	groupID := newGroup(t, db, owner, "Study Group")
	addMember(t, db, groupID, member, RoleAdmin)

	for userID, want := range map[string]string{owner: RoleOwner, member: RoleAdmin} {
		rec := httptest.NewRecorder()
		h.GetGroup(rec, request(
			http.MethodGet, "/groups/x", "", userID,
			map[string]string{"group_id": groupID},
		))
		if rec.Code != http.StatusOK {
			t.Fatalf("got %d: %s", rec.Code, rec.Body.String())
		}
		var g models.GroupWithProgress
		if err := json.Unmarshal(rec.Body.Bytes(), &g); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if g.MyRole != want {
			t.Errorf("my_role = %q, want %q", g.MyRole, want)
		}
	}
}

// --- F1: completion records who and when ------------------------------------

// The board lets anyone mark anything done, and the doer is often not the
// assignee ("I just did it myself"). Both names have to survive, because the
// history in F6 cannot be reconstructed after the fact.
func TestCompletionRecordsDoerAndTime(t *testing.T) {
	db := newTestDB(t)
	th := &TaskHandler{DB: db}

	owner := newUser(t, db, "olivia")
	other := newUser(t, db, "marco")
	groupID := newGroup(t, db, owner, "Flat 3B")
	addMember(t, db, groupID, other, RoleMember)

	// Assigned to olivia...
	taskID := newTask(t, db, groupID, "Take the bins out")
	if _, err := db.Exec(`UPDATE tasks SET assigned_to = ? WHERE id = ?`, owner, taskID); err != nil {
		t.Fatalf("assign: %v", err)
	}

	setStatus := func(actor, status string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		th.UpdateTask(rec, request(
			http.MethodPatch, "/groups/x/tasks/y", `{"status":"`+status+`"}`, actor,
			map[string]string{"group_id": groupID, "task_id": taskID},
		))
		return rec
	}

	// ...but marco does it.
	rec := setStatus(other, "done")
	if rec.Code != http.StatusOK {
		t.Fatalf("mark done: got %d: %s", rec.Code, rec.Body.String())
	}
	var task models.Task
	if err := json.Unmarshal(rec.Body.Bytes(), &task); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if task.DoneBy == nil || *task.DoneBy != other {
		t.Fatalf("done_by = %v, want marco (%s)", task.DoneBy, other)
	}
	if task.DoneAt == nil {
		t.Fatal("done_at was not recorded")
	}
	if task.AssignedTo == nil || *task.AssignedTo != owner {
		t.Fatalf("assigned_to = %v; the assignee must survive completion by someone else", task.AssignedTo)
	}

	// Undo: moving out of done must not leave a stale completion behind.
	rec = setStatus(other, "todo")
	if rec.Code != http.StatusOK {
		t.Fatalf("undo: got %d: %s", rec.Code, rec.Body.String())
	}
	// A *fresh* struct, deliberately: done_by/done_at are omitempty, so they
	// are simply absent from this response, and unmarshalling into the struct
	// above would leave the previous completion's values sitting in it.
	var afterUndo models.Task
	if err := json.Unmarshal(rec.Body.Bytes(), &afterUndo); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if afterUndo.DoneBy != nil || afterUndo.DoneAt != nil {
		t.Fatalf("undo left done_by=%v done_at=%v", afterUndo.DoneBy, afterUndo.DoneAt)
	}

	// And confirm against the database, not just the response shape.
	var doneBy, doneAt sql.NullString
	if err := db.QueryRow(`SELECT done_by, done_at FROM tasks WHERE id = ?`, taskID).
		Scan(&doneBy, &doneAt); err != nil {
		t.Fatalf("read completion columns: %v", err)
	}
	if doneBy.Valid || doneAt.Valid {
		t.Fatalf("undo left done_by=%v done_at=%v in the database", doneBy, doneAt)
	}
}

// --- C5: invite code --------------------------------------------------------

func TestGenerateCodeLengthAndUniqueness(t *testing.T) {
	seen := make(map[string]bool, 100)
	for i := 0; i < 100; i++ {
		code, err := generateCode(inviteCodeLength)
		if err != nil {
			t.Fatalf("generateCode: %v", err)
		}
		if len(code) != inviteCodeLength {
			t.Fatalf("code %q has length %d, want %d", code, len(code), inviteCodeLength)
		}
		if seen[code] {
			t.Fatalf("generateCode returned a duplicate: %q", code)
		}
		seen[code] = true
	}
}
