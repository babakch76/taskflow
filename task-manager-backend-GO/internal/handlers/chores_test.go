package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/models"
)

// --- helpers ----------------------------------------------------------------

// createChore posts a chore and returns the decoded response, failing the test
// on any non-201.
func createChore(t *testing.T, db *database.DB, groupID, userID, body string) models.Chore {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.CreateChore(rec, request("POST", "/groups/"+groupID+"/chores", body, userID,
		map[string]string{"group_id": groupID}))

	if rec.Code != 201 {
		t.Fatalf("CreateChore: got %d, want 201 (%s)", rec.Code, rec.Body.String())
	}
	var c models.Chore
	if err := json.Unmarshal(rec.Body.Bytes(), &c); err != nil {
		t.Fatalf("decode chore: %v (%s)", err, rec.Body.String())
	}
	return c
}

// listOccurrences reads the board as userID sees it.
func listOccurrences(t *testing.T, db *database.DB, groupID, userID string) []models.Occurrence {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.ListOccurrences(rec, request("GET", "/groups/"+groupID+"/occurrences", "", userID,
		map[string]string{"group_id": groupID}))

	if rec.Code != 200 {
		t.Fatalf("ListOccurrences: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	var out []models.Occurrence
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode occurrences: %v", err)
	}
	return out
}

// patchOccurrence sets an occurrence's status and returns the recorder so the
// caller can assert on the status code as well as the body.
func patchOccurrence(t *testing.T, db *database.DB, groupID, occurrenceID, userID, status string) *httptest.ResponseRecorder {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateOccurrence(rec, request("PATCH",
		"/groups/"+groupID+"/occurrences/"+occurrenceID,
		fmt.Sprintf(`{"status":%q}`, status), userID,
		map[string]string{"group_id": groupID, "occurrence_id": occurrenceID}))
	return rec
}

// --- creation ---------------------------------------------------------------

// A chore is a definition; the board shows occurrences. Creating a chore must
// therefore also create its first occurrence, assigned to whoever is first in
// the rotation — otherwise the chore exists but is invisible and inert.
func TestCreateChoreSpawnsFirstOccurrenceForFirstInRotation(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	// Rotation deliberately does not start with the creator: the first turn
	// belongs to position 0, not to whoever happened to set the chore up.
	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Clean the bathroom",
		"done_line": "Sink, toilet, floor",
		"schedule_type": "interval",
		"interval_days": 3,
		"rotation": [%q, %q]
	}`, mate, owner))

	if chore.Name != "Clean the bathroom" {
		t.Errorf("name: got %q", chore.Name)
	}
	if len(chore.Rotation) != 2 || chore.Rotation[0] != mate || chore.Rotation[1] != owner {
		t.Fatalf("rotation not preserved in order: got %v, want [%s %s]", chore.Rotation, mate, owner)
	}

	occurrences := listOccurrences(t, db, groupID, owner)
	if len(occurrences) != 1 {
		t.Fatalf("expected exactly one occurrence, got %d", len(occurrences))
	}
	occ := occurrences[0]
	if occ.AssignedTo != mate {
		t.Errorf("first occurrence assigned to %s, want first in rotation (%s)", occ.AssignedTo, mate)
	}
	if occ.Status != models.OccurrenceOpen {
		t.Errorf("status: got %q, want open", occ.Status)
	}
	if occ.DoneBy != nil || occ.DoneAt != nil {
		t.Errorf("a fresh occurrence must have no completion recorded: done_by=%v done_at=%v", occ.DoneBy, occ.DoneAt)
	}
	// The board renders a row without a second round trip, so the chore's name
	// and done-line must come back joined in.
	if occ.ChoreName != "Clean the bathroom" || occ.DoneLine != "Sink, toilet, floor" {
		t.Errorf("chore fields not joined into the occurrence: name=%q done_line=%q", occ.ChoreName, occ.DoneLine)
	}
	if occ.DueDate == nil {
		t.Fatal("an interval chore's first occurrence must have a due date")
	}
	// Anchored to creation, since there is no completion to count from yet.
	wantDay := time.Now().AddDate(0, 0, 3).YearDay()
	if occ.DueDate.YearDay() != wantDay {
		t.Errorf("due date: got %s, want %d days out", occ.DueDate.Format(time.RFC3339), 3)
	}
}

// As-needed chores rotate with no date at all. Inventing one would turn a
// standing turn into a deadline, which is exactly what the spec refuses.
func TestAsNeededChoreHasNoDueDate(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Take out the trash",
		"schedule_type": "as_needed",
		"rotation": [%q]
	}`, owner))

	occurrences := listOccurrences(t, db, groupID, owner)
	if len(occurrences) != 1 {
		t.Fatalf("expected one occurrence, got %d", len(occurrences))
	}
	if occurrences[0].DueDate != nil {
		t.Errorf("as-needed chore must have no due date, got %v", occurrences[0].DueDate)
	}
}

// An interval is any whole number of days, not one of a fixed few. A
// fortnightly chore is the obvious case the enumerated set had no room for.
func TestIntervalAcceptsAnyNumberOfDays(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	for _, days := range []int{1, 9, 14, 45, 365} {
		t.Run(fmt.Sprintf("every %d days", days), func(t *testing.T) {
			chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
				"name": "Chore %d", "schedule_type": "interval",
				"interval_days": %d, "rotation": [%q]
			}`, days, days, owner))

			if chore.IntervalDays == nil || *chore.IntervalDays != days {
				t.Fatalf("interval_days: got %v, want %d", chore.IntervalDays, days)
			}

			// The due date is arithmetic, so an off-preset interval must land
			// exactly as a preset one does.
			var due *time.Time
			for _, o := range listOccurrences(t, db, groupID, owner) {
				if o.ChoreID == chore.ID {
					due = o.DueDate
				}
			}
			if due == nil {
				t.Fatal("no due date on the first occurrence")
			}
			want := time.Now().AddDate(0, 0, days).YearDay()
			if due.YearDay() != want {
				t.Errorf("due %s is not %d days out", due.Format(time.RFC3339), days)
			}
		})
	}
}

func TestCreateChoreRejectsInvalidInput(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	stranger := newUser(t, db, "stranger")
	groupID := newGroup(t, db, owner, "Flat")

	longLine := ""
	for i := 0; i <= models.DoneLineMaxLen; i++ {
		longLine += "x"
	}

	cases := []struct {
		name string
		body string
		want string
	}{
		{
			name: "empty rotation",
			body: `{"name":"X","schedule_type":"as_needed","rotation":[]}`,
			want: "rotation must contain at least one member",
		},
		{
			name: "rotation contains a non-member",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"as_needed","rotation":[%q]}`, stranger),
			want: "rotation contains a user who is not a group member",
		},
		{
			name: "duplicate in rotation",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"as_needed","rotation":[%q,%q]}`, owner, owner),
			want: "rotation contains a duplicate member",
		},
		{
			name: "unknown schedule type",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"whenever","rotation":[%q]}`, owner),
			want: "schedule_type must be interval, fixed_date, as_needed or one_off",
		},
		{
			name: "interval without interval_days",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"interval","rotation":[%q]}`, owner),
			want: "interval_days is required for an interval chore",
		},
		{
			name: "interval of zero days",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"interval","interval_days":0,"rotation":[%q]}`, owner),
			want: "interval_days must be between 1 and 365",
		},
		{
			name: "interval beyond a year",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"interval","interval_days":400,"rotation":[%q]}`, owner),
			want: "interval_days must be between 1 and 365",
		},
		{
			name: "fixed_date with both weekdays and month days",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"fixed_date","fixed_weekdays":[2],"fixed_month_days":[1],"rotation":[%q]}`, owner),
			want: "a fixed_date chore needs either fixed_weekdays or fixed_month_days, not both",
		},
		{
			name: "fixed_date with neither",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"fixed_date","rotation":[%q]}`, owner),
			want: "a fixed_date chore needs either fixed_weekdays or fixed_month_days, not both",
		},
		{
			name: "as_needed carrying schedule parameters",
			body: fmt.Sprintf(`{"name":"X","schedule_type":"as_needed","interval_days":3,"rotation":[%q]}`, owner),
			want: "a as_needed chore takes no schedule parameters",
		},
		{
			name: "missing name",
			body: fmt.Sprintf(`{"name":"   ","schedule_type":"as_needed","rotation":[%q]}`, owner),
			want: "name is required",
		},
		{
			name: "done_line over the cap",
			body: fmt.Sprintf(`{"name":"X","done_line":%q,"schedule_type":"as_needed","rotation":[%q]}`, longLine, owner),
			want: "done_line must be 140 characters or fewer",
		},
	}

	h := &ChoreHandler{DB: db}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			h.CreateChore(rec, request("POST", "/groups/"+groupID+"/chores", tc.body, owner,
				map[string]string{"group_id": groupID}))

			if rec.Code != 400 {
				t.Fatalf("got %d, want 400 (%s)", rec.Code, rec.Body.String())
			}
			if got := decodeError(t, rec); got != tc.want {
				t.Errorf("error: got %q, want %q", got, tc.want)
			}
		})
	}

	// None of the rejected requests may have left a chore or an occurrence
	// behind — validation happens before the transaction opens.
	var chores, occurrences int
	db.QueryRow(`SELECT COUNT(*) FROM chores`).Scan(&chores)
	db.QueryRow(`SELECT COUNT(*) FROM occurrences`).Scan(&occurrences)
	if chores != 0 || occurrences != 0 {
		t.Errorf("rejected requests left rows behind: %d chores, %d occurrences", chores, occurrences)
	}
}

// --- completion -------------------------------------------------------------

// F1's rule, now on the chore model: anyone may tick anything, and done_by
// records who actually did it. This is what makes a cover visible in the record
// without anyone having to announce it.
func TestAnyMemberMayCompleteAndDoerIsRecorded(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q]
	}`, mate))

	occ := listOccurrences(t, db, groupID, owner)[0]
	if occ.AssignedTo != mate {
		t.Fatalf("precondition: occurrence should be assigned to mate")
	}

	// owner completes mate's occurrence.
	rec := patchOccurrence(t, db, groupID, occ.ID, owner, "done")
	if rec.Code != 200 {
		t.Fatalf("complete: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}

	var updated models.Occurrence
	if err := json.Unmarshal(rec.Body.Bytes(), &updated); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if updated.Status != models.OccurrenceDone {
		t.Errorf("status: got %q, want done", updated.Status)
	}
	if updated.DoneBy == nil || *updated.DoneBy != owner {
		t.Errorf("done_by: got %v, want the doer (%s)", updated.DoneBy, owner)
	}
	if updated.AssignedTo != mate {
		t.Errorf("assigned_to changed to %s — the assignee must survive a cover", updated.AssignedTo)
	}
	if updated.DoneAt == nil {
		t.Error("done_at must be recorded alongside done_by")
	}
}

// Undo is narrower than completion: only the person who marked it, and only
// inside the window. Both halves are enforced server-side — a rule that lives
// only in the client is not a rule.
func TestUndoIsRestrictedToTheDoerAndTheWindow(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	newOpenOccurrence := func(name string) string {
		createChore(t, db, groupID, owner, fmt.Sprintf(`{
			"name": %q, "schedule_type": "as_needed", "rotation": [%q]
		}`, name, owner))
		for _, o := range listOccurrences(t, db, groupID, owner) {
			if o.ChoreName == name {
				return o.ID
			}
		}
		t.Fatalf("no occurrence created for %s", name)
		return ""
	}

	t.Run("someone else cannot undo it", func(t *testing.T) {
		id := newOpenOccurrence("A")
		patchOccurrence(t, db, groupID, id, owner, "done")

		rec := patchOccurrence(t, db, groupID, id, mate, "open")
		if rec.Code != 403 {
			t.Fatalf("got %d, want 403 (%s)", rec.Code, rec.Body.String())
		}
		if got := decodeError(t, rec); got != "only the person who marked this done can undo it" {
			t.Errorf("error: got %q", got)
		}
	})

	t.Run("the doer can undo it within the window", func(t *testing.T) {
		id := newOpenOccurrence("B")
		patchOccurrence(t, db, groupID, id, owner, "done")

		rec := patchOccurrence(t, db, groupID, id, owner, "open")
		if rec.Code != 200 {
			t.Fatalf("got %d, want 200 (%s)", rec.Code, rec.Body.String())
		}
		var reopened models.Occurrence
		json.Unmarshal(rec.Body.Bytes(), &reopened)
		if reopened.Status != models.OccurrenceOpen {
			t.Errorf("status: got %q, want open", reopened.Status)
		}
		// An undone completion must leave nothing behind: the columns should
		// never describe a completion that was taken back.
		if reopened.DoneBy != nil || reopened.DoneAt != nil {
			t.Errorf("undo left a completion behind: done_by=%v done_at=%v", reopened.DoneBy, reopened.DoneAt)
		}
	})

	t.Run("the doer cannot undo it once the window has passed", func(t *testing.T) {
		id := newOpenOccurrence("C")
		patchOccurrence(t, db, groupID, id, owner, "done")

		// Backdate the completion past the window.
		stale := time.Now().Add(-undoWindow - time.Minute).UTC().Format("2006-01-02 15:04:05")
		if _, err := db.Exec(`UPDATE occurrences SET done_at = ? WHERE id = ?`, stale, id); err != nil {
			t.Fatalf("backdate: %v", err)
		}

		rec := patchOccurrence(t, db, groupID, id, owner, "open")
		if rec.Code != 403 {
			t.Fatalf("got %d, want 403 (%s)", rec.Code, rec.Body.String())
		}
		if got := decodeError(t, rec); got != "the undo window has passed" {
			t.Errorf("error: got %q", got)
		}
	})
}

// There is no "missed" state anywhere in the system. The database must refuse
// one outright, not merely the handler — the invariant is worth more than the
// validation in front of it.
func TestOccurrenceStatusHasNoMissedState(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")
	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "as_needed", "rotation": [%q]
	}`, owner))

	_, err := db.Exec(
		`INSERT INTO occurrences (id, chore_id, group_id, assigned_to, status) VALUES ('x', ?, ?, ?, 'missed')`,
		chore.ID, groupID, owner,
	)
	if err == nil {
		t.Fatal("the schema accepted a 'missed' occurrence; it must not exist as a state")
	}

	// And the handler refuses it too, with something a client can show.
	rec := patchOccurrence(t, db, groupID, listOccurrences(t, db, groupID, owner)[0].ID, owner, "missed")
	if rec.Code != 400 {
		t.Fatalf("got %d, want 400", rec.Code)
	}
	if got := decodeError(t, rec); got != "status must be open or done" {
		t.Errorf("error: got %q", got)
	}
}

// --- spawning and rotation --------------------------------------------------

// openOccurrencesFor returns the open occurrences of one chore, so a test can
// assert on "what is on the board now" without the completed history.
func openOccurrencesFor(t *testing.T, db *database.DB, groupID, userID, choreID string) []models.Occurrence {
	t.Helper()
	out := []models.Occurrence{}
	for _, o := range listOccurrences(t, db, groupID, userID) {
		if o.ChoreID == choreID && o.Status == models.OccurrenceOpen {
			out = append(out, o)
		}
	}
	return out
}

// Completing an occurrence creates the next one and moves the turn on. This is
// the only way a turn moves: an occurrence left undone stays exactly where it
// is, which is what makes a turn impossible to wait out.
func TestCompletionSpawnsTheNextOccurrenceAndAdvancesTheRotation(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	c := newUser(t, db, "cass")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")
	addMember(t, db, groupID, c, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q, %q]
	}`, a, b, c))

	// Walk the rotation right round and back to the start.
	for _, want := range []string{b, c, a} {
		open := openOccurrencesFor(t, db, groupID, a, chore.ID)
		if len(open) != 1 {
			t.Fatalf("expected exactly one open occurrence, got %d", len(open))
		}
		// Each person completes their own turn.
		rec := patchOccurrence(t, db, groupID, open[0].ID, open[0].AssignedTo, "done")
		if rec.Code != 200 {
			t.Fatalf("complete: got %d (%s)", rec.Code, rec.Body.String())
		}

		next := openOccurrencesFor(t, db, groupID, a, chore.ID)
		if len(next) != 1 {
			t.Fatalf("completion should leave exactly one open occurrence, got %d", len(next))
		}
		if next[0].AssignedTo != want {
			t.Fatalf("turn went to %s, want %s", next[0].AssignedTo, want)
		}
		if next[0].SpawnedFrom == nil || *next[0].SpawnedFrom != open[0].ID {
			t.Errorf("spawned_from not recorded: %v", next[0].SpawnedFrom)
		}
	}
}

// An interval counts from the completion, not from the old due date — done
// late, the whole schedule shifts with reality.
func TestIntervalCountsFromTheCompletionNotTheOldDueDate(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bathroom", "schedule_type": "interval", "interval_days": 4,
		"rotation": [%q]
	}`, owner))

	open := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]

	// Backdate the due date well into the past: this turn was missed for days.
	stale := time.Now().AddDate(0, 0, -10)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, stale.UTC(), open.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	patchOccurrence(t, db, groupID, open.ID, owner, "done")

	next := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if next.DueDate == nil {
		t.Fatal("no due date on the spawned occurrence")
	}
	// Four days after *now*, not four days after the date it should have been
	// done — which would still be in the past.
	want := time.Now().AddDate(0, 0, 4).YearDay()
	if next.DueDate.YearDay() != want {
		t.Errorf("next due %s; expected 4 days from the completion, not from the missed date",
			next.DueDate.Format(time.RFC3339))
	}
	if !next.DueDate.After(time.Now()) {
		t.Error("the next due date is in the past — the schedule did not shift with reality")
	}
}

// An as-needed chore keeps exactly one standing occurrence: completing it
// advances the turn and spawns the next, with no date attached.
func TestAsNeededKeepsOneStandingOccurrence(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Trash", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, a, b))

	open := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	patchOccurrence(t, db, groupID, open.ID, a, "done")

	next := openOccurrencesFor(t, db, groupID, a, chore.ID)
	if len(next) != 1 {
		t.Fatalf("expected one standing occurrence, got %d", len(next))
	}
	if next[0].AssignedTo != b {
		t.Errorf("turn went to %s, want %s", next[0].AssignedTo, b)
	}
	if next[0].DueDate != nil {
		t.Errorf("an as-needed occurrence must have no due date, got %v", next[0].DueDate)
	}
}

// A one-off is the whole of its own schedule. Completing it must not conjure
// another one.
func TestOneOffDoesNotRecur(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Pay the bill", "schedule_type": "one_off",
		"due_date": "2026-09-10T18:00:00Z", "rotation": [%q]
	}`, owner))

	open := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	patchOccurrence(t, db, groupID, open.ID, owner, "done")

	if got := openOccurrencesFor(t, db, groupID, owner, chore.ID); len(got) != 0 {
		t.Errorf("a one-off spawned %d further occurrence(s)", len(got))
	}
}

// Undo has to take the spawned occurrence with it. Otherwise ten seconds of
// misclick leaves a phantom turn sitting on someone's row forever.
func TestUndoRemovesTheSpawnedOccurrence(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, a, b))

	first := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, a, "done")

	if got := openOccurrencesFor(t, db, groupID, a, chore.ID); len(got) != 1 {
		t.Fatalf("precondition: expected one spawned occurrence, got %d", len(got))
	}

	rec := patchOccurrence(t, db, groupID, first.ID, a, "open")
	if rec.Code != 200 {
		t.Fatalf("undo: got %d (%s)", rec.Code, rec.Body.String())
	}

	open := openOccurrencesFor(t, db, groupID, a, chore.ID)
	if len(open) != 1 {
		t.Fatalf("after undo there should be exactly one open occurrence, got %d", len(open))
	}
	if open[0].ID != first.ID {
		t.Errorf("the wrong occurrence survived the undo: got %s, want the original %s", open[0].ID, first.ID)
	}
	if open[0].AssignedTo != a {
		t.Errorf("undo left the turn with %s, want the original holder %s", open[0].AssignedTo, a)
	}
}

// But an undo must not erase work someone else has already done. If the next
// turn was completed before the undo landed, it stays.
func TestUndoLeavesAnAlreadyCompletedNextTurnAlone(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, a, b))

	first := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, a, "done")

	// bo gets the next turn and does it straight away.
	second := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	patchOccurrence(t, db, groupID, second.ID, b, "done")

	// ann now undoes her completion. bo's work must survive.
	patchOccurrence(t, db, groupID, first.ID, a, "open")

	var status string
	var doneBy sql.NullString
	err := db.QueryRow(`SELECT status, done_by FROM occurrences WHERE id = ?`, second.ID).
		Scan(&status, &doneBy)
	if err != nil {
		t.Fatalf("bo's occurrence was deleted by ann's undo: %v", err)
	}
	if status != models.OccurrenceDone || !doneBy.Valid || doneBy.String != b {
		t.Errorf("bo's completion was disturbed: status=%s done_by=%v", status, doneBy)
	}
}

// A missed fixed date rolls to the next one and keeps the same assignee — you
// keep the chore until you have actually done it once.
func TestMissedFixedDateRollsForwardWithTheSameAssignee(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "fixed_date", "fixed_weekdays": [2],
		"rotation": [%q, %q]
	}`, a, b))

	open := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	if open.AssignedTo != a {
		t.Fatalf("precondition: first turn should be ann's")
	}

	// The Tuesday came and went without anyone doing it.
	missed := time.Now().AddDate(0, 0, -9)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, missed.UTC(), open.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	// Reading the board is what rolls it forward.
	rolled := openOccurrencesFor(t, db, groupID, a, chore.ID)
	if len(rolled) != 1 {
		t.Fatalf("expected one occurrence, got %d", len(rolled))
	}
	if rolled[0].ID != open.ID {
		t.Error("the roll-forward replaced the occurrence instead of re-dating it")
	}
	if rolled[0].AssignedTo != a {
		t.Errorf("the roll-forward moved the chore to %s; a missed date must not move the turn", rolled[0].AssignedTo)
	}
	if rolled[0].Status != models.OccurrenceOpen {
		t.Errorf("status changed to %q — a missed date is not a state", rolled[0].Status)
	}
	if rolled[0].DueDate == nil || !rolled[0].DueDate.After(time.Now()) {
		t.Fatalf("due date did not roll forward: %v", rolled[0].DueDate)
	}
	if rolled[0].DueDate.Weekday() != time.Tuesday {
		t.Errorf("rolled to %s, want a Tuesday", rolled[0].DueDate.Weekday())
	}

	// Idempotent: looking again changes nothing.
	again := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	if !again.DueDate.Equal(*rolled[0].DueDate) {
		t.Errorf("a second read moved the date again: %s then %s",
			rolled[0].DueDate.Format(time.RFC3339), again.DueDate.Format(time.RFC3339))
	}
}

// An interval chore, by contrast, just sits there with a date in the past. It
// must not be rolled forward — the past date is the honest information, and
// re-dating it would quietly erase that it is still waiting.
func TestMissedIntervalDoesNotRollForward(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bathroom", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q]
	}`, owner))

	open := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	past := time.Now().AddDate(0, 0, -5).Truncate(time.Second)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, past.UTC(), open.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	after := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if after.DueDate == nil || after.DueDate.After(time.Now()) {
		t.Errorf("an interval chore's missed date was rolled forward to %v; it should stay in the past", after.DueDate)
	}
}

func TestNextInRotation(t *testing.T) {
	rotation := []string{"a", "b", "c"}
	cases := []struct{ current, want string }{
		{"a", "b"},
		{"b", "c"},
		{"c", "a"}, // wraps
		// Someone edited out of the rotation, or who left the group: the turn
		// restarts at the top rather than being lost.
		{"stranger", "a"},
	}
	for _, tc := range cases {
		if got := nextInRotation(rotation, tc.current); got != tc.want {
			t.Errorf("nextInRotation(%s): got %s, want %s", tc.current, got, tc.want)
		}
	}

	if got := nextInRotation([]string{"solo"}, "solo"); got != "solo" {
		t.Errorf("a one-person rotation should stay with them, got %s", got)
	}
}

// --- editing ----------------------------------------------------------------

// Editing is open to every member, and the group sees a diff phrased the way a
// housemate would say it — the spec's transparency-instead-of-approval rule.
func TestAnyMemberMayEditAChoreAndTheGroupSeesADiff(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 7, "rotation": [%q]
	}`, owner))

	// mate — not the creator, not the owner — changes the schedule.
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"interval_days":3}`, mate,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))

	if rec.Code != 200 {
		t.Fatalf("edit by a plain member: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}

	var detail string
	err := db.QueryRow(
		`SELECT detail FROM activity_events WHERE event_type = ? ORDER BY rowid DESC LIMIT 1`,
		EventChoreUpdated,
	).Scan(&detail)
	if err != nil {
		t.Fatalf("no chore_updated event recorded: %v", err)
	}
	// The spec's own example: "Sara changed Kitchen: weekly → every 3 days".
	want := "Kitchen: schedule: weekly → every 3 days"
	if detail != want {
		t.Errorf("diff detail:\n got %q\nwant %q", detail, want)
	}
}

// Rotation advances on completion, never on an edit. Reordering the list must
// not move a chore off the row of whoever is currently holding it — that would
// be precisely the "wait it out and it becomes someone else's" escape the
// unified turn rule exists to close.
func TestEditingRotationDoesNotReassignTheOpenOccurrence(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Hallway", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, owner, mate))

	before := listOccurrences(t, db, groupID, owner)[0]
	if before.AssignedTo != owner {
		t.Fatalf("precondition: occurrence should start with owner")
	}

	// Reverse the rotation.
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		fmt.Sprintf(`{"rotation":[%q,%q]}`, mate, owner), mate,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))
	if rec.Code != 200 {
		t.Fatalf("edit: got %d (%s)", rec.Code, rec.Body.String())
	}

	after := listOccurrences(t, db, groupID, owner)[0]
	if after.AssignedTo != owner {
		t.Errorf("the open occurrence moved to %s; an edit must not take a standing chore off its holder", after.AssignedTo)
	}

	// The definition itself did change, so the next spawn will use the new order.
	updated, err := (&ChoreHandler{DB: db}).loadChore(chore.ID)
	if err != nil {
		t.Fatalf("load chore: %v", err)
	}
	if len(updated.Rotation) != 2 || updated.Rotation[0] != mate {
		t.Errorf("rotation was not actually updated: %v", updated.Rotation)
	}
}

func TestUpdateChoreRejectsParametersFromAnotherScheduleType(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")
	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "fixed_date", "fixed_weekdays": [2], "rotation": [%q]
	}`, owner))

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"interval_days":3}`, owner,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))

	if rec.Code != 400 {
		t.Fatalf("got %d, want 400 (%s)", rec.Code, rec.Body.String())
	}
	if got := decodeError(t, rec); got != "interval_days applies only to interval chores" {
		t.Errorf("error: got %q", got)
	}
}

// --- schedule arithmetic ----------------------------------------------------

func TestNextFixedDate(t *testing.T) {
	// Wednesday 2 September 2026, 10:00.
	from := time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)
	nine := "09:00"

	cases := []struct {
		name      string
		weekdays  []int
		monthDays []int
		neededBy  *string
		want      time.Time
	}{
		{
			name:     "next Tuesday from a Wednesday is six days out",
			weekdays: []int{2}, // Tuesday
			want:     time.Date(2026, 9, 8, 23, 59, 0, 0, time.UTC),
		},
		{
			name:     "today still counts if its slot has not passed",
			weekdays: []int{3}, // Wednesday, and 23:59 today is still ahead
			want:     time.Date(2026, 9, 2, 23, 59, 0, 0, time.UTC),
		},
		{
			name:     "today does not count once its slot has passed",
			weekdays: []int{3},
			neededBy: &nine, // 09:00 today is behind us
			want:     time.Date(2026, 9, 9, 9, 0, 0, 0, time.UTC),
		},
		{
			name:      "the 1st rolls into next month",
			monthDays: []int{1},
			want:      time.Date(2026, 10, 1, 23, 59, 0, 0, time.UTC),
		},
		{
			name:      "the 31st skips 30-day months rather than sliding",
			monthDays: []int{31},
			want:      time.Date(2026, 10, 31, 23, 59, 0, 0, time.UTC),
		},
		{
			name:     "several weekdays pick the soonest",
			weekdays: []int{1, 5}, // Monday and Friday
			want:     time.Date(2026, 9, 4, 23, 59, 0, 0, time.UTC),
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := nextFixedDate(tc.weekdays, tc.monthDays, tc.neededBy, from)
			if got == nil {
				t.Fatal("got nil, want a date")
			}
			if !got.Equal(tc.want) {
				t.Errorf("got %s, want %s", got.Format(time.RFC3339), tc.want.Format(time.RFC3339))
			}
		})
	}
}

func TestFirstDueDateByScheduleType(t *testing.T) {
	from := time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)
	three := 3

	t.Run("interval anchors to creation", func(t *testing.T) {
		got := firstDueDate(models.ScheduleInterval, &three, nil, nil, nil, from)
		want := time.Date(2026, 9, 5, 23, 59, 0, 0, time.UTC)
		if got == nil || !got.Equal(want) {
			t.Errorf("got %v, want %s", got, want.Format(time.RFC3339))
		}
	})

	t.Run("as_needed has no date", func(t *testing.T) {
		if got := firstDueDate(models.ScheduleAsNeeded, nil, nil, nil, nil, from); got != nil {
			t.Errorf("got %v, want nil", got)
		}
	})

	t.Run("one_off has no derived date", func(t *testing.T) {
		// A one-off's date comes from the request, not from a schedule.
		if got := firstDueDate(models.ScheduleOneOff, nil, nil, nil, nil, from); got != nil {
			t.Errorf("got %v, want nil", got)
		}
	})
}

func TestDescribeSchedule(t *testing.T) {
	one, three, seven, thirty := 1, 3, 7, 30
	cases := []struct {
		scheduleType string
		interval     *int
		weekdays     []int
		monthDays    []int
		want         string
	}{
		{models.ScheduleInterval, &one, nil, nil, "daily"},
		{models.ScheduleInterval, &three, nil, nil, "every 3 days"},
		{models.ScheduleInterval, &seven, nil, nil, "weekly"},
		{models.ScheduleInterval, &thirty, nil, nil, "monthly"},
		{models.ScheduleFixedDate, nil, []int{2}, nil, "Tuesdays"},
		{models.ScheduleFixedDate, nil, []int{1, 4}, nil, "Mondays, Thursdays"},
		{models.ScheduleFixedDate, nil, nil, []int{1}, "the 1st"},
		{models.ScheduleFixedDate, nil, nil, []int{2, 22}, "the 2nd, the 22nd"},
		{models.ScheduleFixedDate, nil, nil, []int{3, 11, 13}, "the 3rd, the 11th, the 13th"},
		{models.ScheduleAsNeeded, nil, nil, nil, "as needed"},
		{models.ScheduleOneOff, nil, nil, nil, "one-off"},
	}

	for _, tc := range cases {
		got := describeSchedule(tc.scheduleType, tc.interval, tc.weekdays, tc.monthDays)
		if got != tc.want {
			t.Errorf("describeSchedule(%s): got %q, want %q", tc.scheduleType, got, tc.want)
		}
	}
}

// --- siloing ----------------------------------------------------------------

// The membership guard runs in the router, but the handlers must not leak
// across groups even when it is satisfied for a *different* group.
func TestChoreEndpointsDoNotCrossGroups(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	other := newUser(t, db, "other")
	groupA := newGroup(t, db, owner, "A")
	groupB := newGroup(t, db, other, "B")

	chore := createChore(t, db, groupA, owner, fmt.Sprintf(`{
		"name": "Private", "schedule_type": "as_needed", "rotation": [%q]
	}`, owner))
	occ := listOccurrences(t, db, groupA, owner)[0]

	h := &ChoreHandler{DB: db}

	t.Run("editing through the wrong group is a 404", func(t *testing.T) {
		rec := httptest.NewRecorder()
		h.UpdateChore(rec, request("PATCH", "/groups/"+groupB+"/chores/"+chore.ID,
			`{"name":"Hijacked"}`, other,
			map[string]string{"group_id": groupB, "chore_id": chore.ID}))
		if rec.Code != 404 {
			t.Errorf("got %d, want 404 (%s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("completing through the wrong group is a 404", func(t *testing.T) {
		rec := patchOccurrence(t, db, groupB, occ.ID, other, "done")
		if rec.Code != 404 {
			t.Errorf("got %d, want 404 (%s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("the other group's board is empty", func(t *testing.T) {
		if got := listOccurrences(t, db, groupB, other); len(got) != 0 {
			t.Errorf("group B sees %d of group A's occurrences", len(got))
		}
	})
}
