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

	"github.com/google/uuid"
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

// Doing a fixed-date chore early must move it to the *next* slot, not leave it
// on the one just satisfied.
//
// The regression: nextDueDate scanned forward from the moment of completion, so
// putting the bins out on Wednesday for a "Fridays" chore found the soonest
// Friday after Wednesday — the very Friday just dealt with. The chore came
// straight back, due the same day, and had to be done twice.
func TestFixedDateDoneEarlyMovesToTheNextSlot(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	// A weekday two days out, so completing "now" is unambiguously early.
	target := time.Now().AddDate(0, 0, 2)
	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "fixed_date", "fixed_weekdays": [%d],
		"rotation": [%q]
	}`, int(target.Weekday()), owner))

	open := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if open.DueDate == nil {
		t.Fatal("the first occurrence has no due date")
	}
	firstDue := open.DueDate.In(time.Local)

	patchOccurrence(t, db, groupID, open.ID, owner, "done")

	next := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if next.DueDate == nil {
		t.Fatal("no due date on the spawned occurrence")
	}
	nextDue := next.DueDate.In(time.Local)

	if nextDue.YearDay() == firstDue.YearDay() && nextDue.Year() == firstDue.Year() {
		t.Fatalf("done early and the chore came back due the same day (%s) — it must move to the next slot",
			nextDue.Format(time.RFC3339))
	}
	if !nextDue.After(firstDue) {
		t.Errorf("next due %s is not after the slot just completed (%s)",
			nextDue.Format(time.RFC3339), firstDue.Format(time.RFC3339))
	}
	if nextDue.Weekday() != target.Weekday() {
		t.Errorf("next due is a %s; the schedule says %s", nextDue.Weekday(), target.Weekday())
	}
	// A week on, since a weekday recurs weekly.
	if days := nextDue.YearDay() - firstDue.YearDay(); days != 7 {
		t.Errorf("next due is %d days after the completed slot, want 7", days)
	}
}

// The late case still has to work: a fixed-date chore finally done long after
// its slot lands on the next slot from *now*, never on one already gone.
func TestFixedDateDoneLateLandsOnAFutureSlot(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	target := time.Now().AddDate(0, 0, 2)
	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "fixed_date", "fixed_weekdays": [%d],
		"rotation": [%q]
	}`, int(target.Weekday()), owner))

	open := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]

	// Its slot was three weeks ago and nobody did it.
	stale := time.Now().AddDate(0, 0, -19)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, stale.UTC(), open.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	patchOccurrence(t, db, groupID, open.ID, owner, "done")

	next := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if next.DueDate == nil {
		t.Fatal("no due date on the spawned occurrence")
	}
	if !next.DueDate.After(time.Now()) {
		t.Errorf("next due %s is in the past — a late completion must land on a future slot",
			next.DueDate.Format(time.RFC3339))
	}
	if next.DueDate.In(time.Local).Weekday() != target.Weekday() {
		t.Errorf("next due is a %s; the schedule says %s",
			next.DueDate.In(time.Local).Weekday(), target.Weekday())
	}
}

// covered_by is written when the debt rule hands a turn back, and only then.
//
// It is what lets the returned row say "back to you after Maya covered" rather
// than showing a turn that appears not to have moved at all.
func TestCoveredByIsSetOnADebtReturnAndNotOnANormalAdvance(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	// ann's turn, done by bo: a cover, so the next one comes back to ann and
	// should say who covered.
	first := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, bo, "done")

	returned := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if returned.AssignedTo != ann {
		t.Fatalf("the debt did not come back to ann; it went to %s", returned.AssignedTo)
	}
	if returned.CoveredBy == nil || *returned.CoveredBy != bo {
		t.Fatalf("covered_by = %v, want bo (%s)", returned.CoveredBy, bo)
	}
	if returned.CoveredByName == nil || *returned.CoveredByName != "bo" {
		t.Errorf("covered_by_name = %v, want \"bo\"", returned.CoveredByName)
	}

	// ann now does her own turn. That is a normal advance, so nobody covered
	// and the next occurrence must carry nothing.
	patchOccurrence(t, db, groupID, returned.ID, ann, "done")

	normal := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if normal.CoveredBy != nil {
		t.Errorf("covered_by = %v on a normal advance; want nil", *normal.CoveredBy)
	}
	if normal.CoveredByName != nil {
		t.Errorf("covered_by_name = %v on a normal advance; want nil", *normal.CoveredByName)
	}
}

// The same marker after a busy pass, which is the case it exists for: the
// receiver did the work, and the turn goes back to whoever passed it.
func TestCoveredByIsSetAfterABusyPass(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	mine := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.PassOccurrence(rec, request("POST",
		"/groups/"+groupID+"/occurrences/"+mine.ID+"/pass", "", ann,
		map[string]string{"group_id": groupID, "occurrence_id": mine.ID}))
	if rec.Code != 200 {
		t.Fatalf("pass: got %d (%s)", rec.Code, rec.Body.String())
	}

	passed := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if passed.AssignedTo != bo {
		t.Fatalf("the pass went to %s, want bo", passed.AssignedTo)
	}

	// bo does the chore he was passed. The turn owes back to ann, and the row
	// that comes back should say bo covered it.
	patchOccurrence(t, db, groupID, passed.ID, bo, "done")

	returned := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if returned.AssignedTo != ann {
		t.Fatalf("the debt went to %s, want ann", returned.AssignedTo)
	}
	if returned.CoveredBy == nil || *returned.CoveredBy != bo {
		t.Fatalf("covered_by = %v after a pass, want bo", returned.CoveredBy)
	}
}

// ── What an edit does to the turn already on the board ──────────────────────
//
// The rules under test: the assignee never moves because of an edit, a changed
// schedule re-dates the open occurrence from the last completion, and moving to
// as-needed clears the date entirely.

// Changing the interval re-dates the open occurrence without touching whose it
// is. Counted from the chore's creation here, since it has never been done.
func TestEditingTheScheduleRedatesTheOpenOccurrence(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	mate := newUser(t, db, "mate")
	groupID := newGroup(t, db, owner, "Flat")
	addMember(t, db, groupID, mate, "member")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bathroom", "schedule_type": "interval", "interval_days": 30,
		"rotation": [%q, %q]
	}`, owner, mate))

	before := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if before.DueDate == nil {
		t.Fatal("the first occurrence has no due date")
	}

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"interval_days":2}`, owner,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))
	if rec.Code != 200 {
		t.Fatalf("edit: got %d (%s)", rec.Code, rec.Body.String())
	}

	after := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if after.ID != before.ID {
		t.Fatalf("the edit replaced the occurrence (%s -> %s); it should re-date the one that is there",
			before.ID, after.ID)
	}
	if after.AssignedTo != before.AssignedTo {
		t.Errorf("the edit moved the turn from %s to %s; an edit is not a completion",
			before.AssignedTo, after.AssignedTo)
	}
	if after.DueDate == nil {
		t.Fatal("no due date after the edit")
	}
	// Two days from creation, not thirty.
	want := time.Now().AddDate(0, 0, 2).YearDay()
	if after.DueDate.In(time.Local).YearDay() != want {
		t.Errorf("re-dated to %s; expected two days out, under the new interval",
			after.DueDate.Format(time.RFC3339))
	}
}

// A chore can move between the three repeating kinds. Switching to as-needed
// takes the date off the board row; switching back puts one on.
func TestSwitchingScheduleTypeClearsAndRestoresTheDate(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bins", "schedule_type": "interval", "interval_days": 3, "rotation": [%q]
	}`, owner))
	before := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"schedule_type":"as_needed"}`, owner,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))
	if rec.Code != 200 {
		t.Fatalf("switch to as_needed: got %d (%s)", rec.Code, rec.Body.String())
	}

	asNeeded := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if asNeeded.DueDate != nil {
		t.Errorf("an as-needed occurrence still has a due date: %s", asNeeded.DueDate.Format(time.RFC3339))
	}
	if asNeeded.AssignedTo != before.AssignedTo {
		t.Error("the type switch moved the turn")
	}

	rec = httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"schedule_type":"interval","interval_days":5}`, owner,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))
	if rec.Code != 200 {
		t.Fatalf("switch back to interval: got %d (%s)", rec.Code, rec.Body.String())
	}

	back := openOccurrencesFor(t, db, groupID, owner, chore.ID)[0]
	if back.DueDate == nil {
		t.Fatal("switching away from as-needed left the occurrence undated")
	}
	if back.AssignedTo != before.AssignedTo {
		t.Error("switching back moved the turn")
	}
}

// A one-off is a different table, so it is not reachable by changing a column.
func TestScheduleTypeCannotCrossTheOneOffBoundary(t *testing.T) {
	db := newTestDB(t)
	owner := newUser(t, db, "owner")
	groupID := newGroup(t, db, owner, "Flat")

	chore := createChore(t, db, groupID, owner, fmt.Sprintf(`{
		"name": "Bathroom", "schedule_type": "interval", "interval_days": 3, "rotation": [%q]
	}`, owner))

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		`{"schedule_type":"one_off"}`, owner,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))

	if rec.Code != 400 {
		t.Fatalf("turning a chore into a one-off: got %d, want 400 (%s)", rec.Code, rec.Body.String())
	}
}

// Reordering the rotation says who comes next. It must not move the turn that
// is already on somebody's row.
func TestReorderingTheRotationLeavesTheCurrentTurnAlone(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	groupID := newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")

	chore := createChore(t, db, groupID, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, a, b))
	before := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+chore.ID,
		fmt.Sprintf(`{"rotation":[%q,%q]}`, b, a), a,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))
	if rec.Code != 200 {
		t.Fatalf("reorder: got %d (%s)", rec.Code, rec.Body.String())
	}

	after := openOccurrencesFor(t, db, groupID, a, chore.ID)[0]
	if after.AssignedTo != before.AssignedTo {
		t.Errorf("reordering moved the open turn from %s to %s; it applies from the next spawn",
			before.AssignedTo, after.AssignedTo)
	}
	if after.DueDate == nil || before.DueDate == nil ||
		!after.DueDate.Equal(*before.DueDate) {
		t.Error("reordering changed the due date; only a schedule change should")
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

// --- the unified turn rule --------------------------------------------------

// The spec's rule, played out over four turns.
//
// rotation [ann, bo, cass], ann's turn, bo does it:
//
//	turn 1  ann   → bo completes it; that counted as bo's turn
//	turn 2  ann   ← handed back, because ann still owes one
//	turn 3  cass  ← after bo, not after ann
//	turn 4  ann   ← normal rotation resumes
//
// Net effect: ann and bo swapped places for one cycle. The failure this guards
// against is turn 3 going to bo, who would then have done two in a row while
// ann's cover cost her nothing.
func TestCoverCountsAsTheDoersTurnAndHandsTheChoreBack(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	names := map[string]string{ann: "ann", bo: "bo", cass: "cass"}

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed",
		"rotation": [%q, %q, %q]
	}`, ann, bo, cass))

	only := func() models.Occurrence {
		t.Helper()
		open := openOccurrencesFor(t, db, groupID, ann, chore.ID)
		if len(open) != 1 {
			t.Fatalf("expected exactly one open occurrence, got %d", len(open))
		}
		return open[0]
	}

	turn1 := only()
	if turn1.AssignedTo != ann {
		t.Fatalf("turn 1 should be ann's, got %s", names[turn1.AssignedTo])
	}

	// bo covers for ann.
	if rec := patchOccurrence(t, db, groupID, turn1.ID, bo, "done"); rec.Code != 200 {
		t.Fatalf("bo's cover: got %d (%s)", rec.Code, rec.Body.String())
	}

	turn2 := only()
	if turn2.AssignedTo != ann {
		t.Fatalf("turn 2 went to %s; a cover must hand the chore back to ann, who still owes one",
			names[turn2.AssignedTo])
	}
	if turn2.ResumeAfter == nil || *turn2.ResumeAfter != bo {
		t.Fatalf("turn 2 should resume after bo, got %v", turn2.ResumeAfter)
	}

	// ann repays it herself.
	if rec := patchOccurrence(t, db, groupID, turn2.ID, ann, "done"); rec.Code != 200 {
		t.Fatalf("ann repaying: got %d (%s)", rec.Code, rec.Body.String())
	}

	turn3 := only()
	if turn3.AssignedTo != cass {
		t.Fatalf("turn 3 went to %s, want cass — the rotation resumes after the coverer, not after ann",
			names[turn3.AssignedTo])
	}
	if turn3.ResumeAfter != nil {
		t.Errorf("turn 3 is an ordinary turn and should carry no resume point, got %v", turn3.ResumeAfter)
	}

	// And from here the rotation is back to normal.
	patchOccurrence(t, db, groupID, turn3.ID, cass, "done")
	turn4 := only()
	if turn4.AssignedTo != ann {
		t.Errorf("turn 4 went to %s, want ann", names[turn4.AssignedTo])
	}
}

// "Nobody's patience can be waited out." However many times other people give
// in and do it, the chore keeps coming back to the person whose turn it is —
// their name only clears by doing one themselves.
func TestWaitingItOutDoesNotMoveTheTurnOn(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Bin", "schedule_type": "as_needed", "rotation": [%q, %q, %q]
	}`, ann, bo, cass))

	// Three cycles in which ann never lifts a finger.
	for i, coverer := range []string{bo, cass, bo} {
		open := openOccurrencesFor(t, db, groupID, ann, chore.ID)
		if len(open) != 1 {
			t.Fatalf("cycle %d: expected one open occurrence, got %d", i, len(open))
		}
		if open[0].AssignedTo != ann {
			t.Fatalf("cycle %d: the chore drifted off ann onto %s", i, open[0].AssignedTo)
		}
		patchOccurrence(t, db, groupID, open[0].ID, coverer, "done")
	}

	final := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if final.AssignedTo != ann {
		t.Errorf("after three covers the chore sits with %s; it must still be ann's", final.AssignedTo)
	}
}

// Completing your own turn is the ordinary case and must not set a debt.
func TestCompletingYourOwnTurnLeavesNoDebt(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Hallway", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	first := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, ann, "done")

	next := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if next.AssignedTo != bo {
		t.Errorf("turn went to %s, want bo", next.AssignedTo)
	}
	if next.ResumeAfter != nil {
		t.Errorf("an ordinary completion must not create a debt, got resume_after=%v", next.ResumeAfter)
	}
}

// Undoing a cover has to put things back exactly: the debt occurrence goes
// away, and the original returns to its holder still open.
func TestUndoingACoverRemovesTheDebt(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	first := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, bo, "done")

	// bo changes his mind inside the window.
	if rec := patchOccurrence(t, db, groupID, first.ID, bo, "open"); rec.Code != 200 {
		t.Fatalf("undo: got %d (%s)", rec.Code, rec.Body.String())
	}

	open := openOccurrencesFor(t, db, groupID, ann, chore.ID)
	if len(open) != 1 {
		t.Fatalf("expected one open occurrence after the undo, got %d", len(open))
	}
	if open[0].ID != first.ID || open[0].AssignedTo != ann {
		t.Errorf("undo did not restore the original turn: id=%s assignee=%s", open[0].ID, open[0].AssignedTo)
	}
	if open[0].ResumeAfter != nil {
		t.Errorf("the restored occurrence should carry no debt, got %v", open[0].ResumeAfter)
	}
}

// nextTurn in isolation, including the case the worked example exists to pin.
func TestNextTurn(t *testing.T) {
	rotation := []string{"ann", "bo", "cass"}
	resume := func(s string) *string { return &s }

	cases := []struct {
		name           string
		assignedTo     string
		resumeAfter    *string
		doer           string
		wantAssignee   string
		wantResumeAfte *string
	}{
		{
			name:         "own turn advances normally",
			assignedTo:   "ann",
			doer:         "ann",
			wantAssignee: "bo",
		},
		{
			name:           "a cover hands it back and remembers the coverer",
			assignedTo:     "ann",
			doer:           "bo",
			wantAssignee:   "ann",
			wantResumeAfte: resume("bo"),
		},
		{
			name:         "repaying a debt resumes after the coverer",
			assignedTo:   "ann",
			resumeAfter:  resume("bo"),
			doer:         "ann",
			wantAssignee: "cass",
		},
		{
			name:           "a debt covered again stays with the same person",
			assignedTo:     "ann",
			resumeAfter:    resume("bo"),
			doer:           "cass",
			wantAssignee:   "ann",
			wantResumeAfte: resume("cass"),
		},
		{
			name:         "the last in the rotation wraps to the first",
			assignedTo:   "cass",
			doer:         "cass",
			wantAssignee: "ann",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			completed := &models.Occurrence{AssignedTo: tc.assignedTo, ResumeAfter: tc.resumeAfter}
			gotAssignee, gotResume := nextTurn(rotation, completed, tc.doer, nil)

			if gotAssignee != tc.wantAssignee {
				t.Errorf("assignee: got %s, want %s", gotAssignee, tc.wantAssignee)
			}
			switch {
			case tc.wantResumeAfte == nil && gotResume != nil:
				t.Errorf("resume_after: got %s, want none", *gotResume)
			case tc.wantResumeAfte != nil && gotResume == nil:
				t.Errorf("resume_after: got none, want %s", *tc.wantResumeAfte)
			case tc.wantResumeAfte != nil && *gotResume != *tc.wantResumeAfte:
				t.Errorf("resume_after: got %s, want %s", *gotResume, *tc.wantResumeAfte)
			}
		})
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
		if got := nextInRotation(rotation, tc.current, nil); got != tc.want {
			t.Errorf("nextInRotation(%s): got %s, want %s", tc.current, got, tc.want)
		}
	}

	if got := nextInRotation([]string{"solo"}, "solo", nil); got != "solo" {
		t.Errorf("a one-person rotation should stay with them, got %s", got)
	}
}

// --- F5: the busy pass ------------------------------------------------------

func passOccurrence(t *testing.T, db *database.DB, groupID, occurrenceID, userID string) *httptest.ResponseRecorder {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.PassOccurrence(rec, request("POST",
		"/groups/"+groupID+"/occurrences/"+occurrenceID+"/pass", "", userID,
		map[string]string{"group_id": groupID, "occurrence_id": occurrenceID}))
	return rec
}

// A pass hands the chore to the next person in the rotation and nothing more —
// no approval, no reason, no penalty.
func TestBusyPassMovesTheChoreToTheNextPerson(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	open := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	rec := passOccurrence(t, db, groupID, open.ID, ann)
	if rec.Code != 200 {
		t.Fatalf("pass: got %d (%s)", rec.Code, rec.Body.String())
	}

	var passed models.Occurrence
	json.Unmarshal(rec.Body.Bytes(), &passed)
	if passed.AssignedTo != bo {
		t.Errorf("passed to %s, want bo", passed.AssignedTo)
	}
	if passed.PassedFrom == nil || *passed.PassedFrom != ann {
		t.Errorf("passed_from: got %v, want ann — the debt has to stay with her", passed.PassedFrom)
	}
	if passed.PassedAt == nil {
		t.Error("passed_at not recorded; the receiver's reminder is measured from it")
	}
	if passed.Status != models.OccurrenceOpen {
		t.Errorf("status changed to %q — a pass is not a completion", passed.Status)
	}
	// Still exactly one occurrence, with exactly one name on it.
	if got := openOccurrencesFor(t, db, groupID, ann, chore.ID); len(got) != 1 {
		t.Errorf("expected one open occurrence after a pass, got %d", len(got))
	}
}

// The debt rule, end to end: passing is a one-cycle swap with whoever is next.
//
// rotation [ann, bo, cass], ann's turn, ann passes to bo, bo does it:
//
//	turn 1  ann → bo   passed; bo completes it
//	turn 2  ann        ← back to ann, who deferred but still owes it
//	turn 3  cass       ← after bo, because doing it counted as bo's turn
//
// "Declaring busy defers your turn; it never deletes it."
func TestPassingDefersTheTurnItDoesNotDeleteIt(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	names := map[string]string{ann: "ann", bo: "bo", cass: "cass"}
	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q, %q]
	}`, ann, bo, cass))

	only := func() models.Occurrence {
		t.Helper()
		open := openOccurrencesFor(t, db, groupID, ann, chore.ID)
		if len(open) != 1 {
			t.Fatalf("expected one open occurrence, got %d", len(open))
		}
		return open[0]
	}

	turn1 := only()
	passOccurrence(t, db, groupID, turn1.ID, ann)
	// bo, who received it, does it.
	patchOccurrence(t, db, groupID, turn1.ID, bo, "done")

	turn2 := only()
	if turn2.AssignedTo != ann {
		t.Fatalf("turn 2 went to %s; a pass defers ann's turn, it does not delete it",
			names[turn2.AssignedTo])
	}
	if turn2.ResumeAfter == nil || *turn2.ResumeAfter != bo {
		t.Fatalf("turn 2 should resume after bo, got %v", turn2.ResumeAfter)
	}

	patchOccurrence(t, db, groupID, turn2.ID, ann, "done")
	turn3 := only()
	if turn3.AssignedTo != cass {
		t.Errorf("turn 3 went to %s, want cass — bo doing it counted as bo's turn",
			names[turn3.AssignedTo])
	}
}

// Passing on declines a favour, not a duty: the second passer keeps their own
// turn, and the debt stays with whoever it belonged to in the first place.
func TestPassingOnwardLeavesTheDebtWithTheFirstPasser(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Bin", "schedule_type": "as_needed", "rotation": [%q, %q, %q]
	}`, ann, bo, cass))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann) // ann → bo
	passOccurrence(t, db, groupID, occ.ID, bo)  // bo  → cass

	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if after.AssignedTo != cass {
		t.Fatalf("the chain did not reach cass: %s", after.AssignedTo)
	}
	if after.PassedFrom == nil || *after.PassedFrom != ann {
		t.Errorf("passed_from moved to %v; it must stay with ann, whose turn it was", after.PassedFrom)
	}

	// cass does it: the debt returns to ann, not to bo.
	patchOccurrence(t, db, groupID, after.ID, cass, "done")
	next := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if next.AssignedTo != ann {
		t.Errorf("next turn went to %s, want ann", next.AssignedTo)
	}
	// The assignee alone does not prove the debt was honoured: with this
	// rotation, "the person after cass" is also ann, so a build that had lost
	// passed_from entirely would still land on her. resume_after is what
	// distinguishes the two — it is only set when the completion was a cover.
	if next.ResumeAfter == nil || *next.ResumeAfter != cass {
		t.Errorf("resume_after: got %v, want cass — this was a cover, not cass's own turn",
			next.ResumeAfter)
	}
}

// An overdue chore arrives with a fresh date. Receiving something already late,
// still stamped late, would make a favour feel like a penalty.
func TestPassingSomethingOverdueGivesTheReceiverUntilTomorrow(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	stale := time.Now().AddDate(0, 0, -4)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, stale.UTC(), occ.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	passOccurrence(t, db, groupID, occ.ID, ann)

	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if after.DueDate == nil {
		t.Fatal("due date was cleared")
	}
	if after.DueDate.YearDay() != time.Now().AddDate(0, 0, 1).YearDay() {
		t.Errorf("due %s, want tomorrow", after.DueDate.Format(time.RFC3339))
	}
}

// A chore that isn't late keeps its date — the deadline belongs to the chore,
// not to whoever happens to be holding it.
func TestPassingSomethingNotYetDueKeepsItsDate(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 5,
		"rotation": [%q, %q]
	}`, ann, bo))

	before := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, before.ID, ann)
	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]

	if before.DueDate == nil || after.DueDate == nil || !before.DueDate.Equal(*after.DueDate) {
		t.Errorf("due date moved from %v to %v", before.DueDate, after.DueDate)
	}
}

func TestPassIsRefusedWhenItWouldMakeNoSense(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	shared := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))
	solo := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Solo", "schedule_type": "as_needed", "rotation": [%q]
	}`, ann))

	t.Run("somebody else's turn is not yours to hand out", func(t *testing.T) {
		occ := openOccurrencesFor(t, db, groupID, ann, shared.ID)[0]
		rec := passOccurrence(t, db, groupID, occ.ID, bo)
		if rec.Code != 403 {
			t.Fatalf("got %d, want 403 (%s)", rec.Code, rec.Body.String())
		}
		if got := decodeError(t, rec); got != "only the person it is assigned to can pass it" {
			t.Errorf("error: got %q", got)
		}
	})

	t.Run("a rotation of one has nobody to pass to", func(t *testing.T) {
		occ := openOccurrencesFor(t, db, groupID, ann, solo.ID)[0]
		rec := passOccurrence(t, db, groupID, occ.ID, ann)
		if rec.Code != 409 {
			t.Fatalf("got %d, want 409 (%s)", rec.Code, rec.Body.String())
		}
		if got := decodeError(t, rec); got != "there is nobody else available in this chore's rotation" {
			t.Errorf("error: got %q", got)
		}
	})

	t.Run("a finished chore cannot be passed", func(t *testing.T) {
		occ := openOccurrencesFor(t, db, groupID, ann, shared.ID)[0]
		patchOccurrence(t, db, groupID, occ.ID, ann, "done")
		rec := passOccurrence(t, db, groupID, occ.ID, ann)
		if rec.Code != 409 {
			t.Fatalf("got %d, want 409 (%s)", rec.Code, rec.Body.String())
		}
	})
}

// Constraint 7 and F3: a pass gets one private notification to the receiver and
// no group broadcast. A feed line naming who declined would be exactly the
// social pressure this feature removes.
func TestPassingWritesNoActivityEvent(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	var before int
	db.QueryRow(`SELECT COUNT(*) FROM activity_events WHERE group_id = ?`, groupID).Scan(&before)

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann)

	var after int
	db.QueryRow(`SELECT COUNT(*) FROM activity_events WHERE group_id = ?`, groupID).Scan(&after)
	if after != before {
		t.Errorf("a pass wrote %d activity event(s); the board showing the new name is the whole mechanism",
			after-before)
	}
}

// --- F5: away ---------------------------------------------------------------

// setAway opens an away period for a member, optionally with a planned end.
func setAway(t *testing.T, db *database.DB, groupID, userID string, until *time.Time) {
	t.Helper()
	var untilArg any
	if until != nil {
		untilArg = until.UTC()
	}
	if _, err := db.Exec(
		`INSERT INTO away_periods (id, group_id, user_id, started_at, ends_at) VALUES (?,?,?,?,?)`,
		uuid.New().String(), groupID, userID, time.Now().UTC(), untilArg,
	); err != nil {
		t.Fatalf("set away: %v", err)
	}
}

// An away member is stepped over when the turn moves on, and steps back into
// the same position when they return. No turns are owed back.
func TestAwayMembersAreSkippedAndReturnInPlace(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	names := map[string]string{ann: "ann", bo: "bo", cass: "cass"}
	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q, %q]
	}`, ann, bo, cass))

	only := func() models.Occurrence {
		t.Helper()
		open := openOccurrencesFor(t, db, groupID, ann, chore.ID)
		if len(open) != 1 {
			t.Fatalf("expected one open occurrence, got %d", len(open))
		}
		return open[0]
	}

	// bo goes away, so ann's completion should skip straight to cass.
	setAway(t, db, groupID, bo, nil)

	turn1 := only()
	patchOccurrence(t, db, groupID, turn1.ID, ann, "done")

	turn2 := only()
	if turn2.AssignedTo != cass {
		t.Fatalf("turn went to %s, want cass — bo is away", names[turn2.AssignedTo])
	}

	// bo comes back. The rotation order never changed, so the next turn after
	// cass is ann, and bo simply takes part again from their old position.
	setAwayBack(t, db, groupID, bo)

	patchOccurrence(t, db, groupID, turn2.ID, cass, "done")
	turn3 := only()
	if turn3.AssignedTo != ann {
		t.Errorf("turn 3 went to %s, want ann", names[turn3.AssignedTo])
	}

	patchOccurrence(t, db, groupID, turn3.ID, ann, "done")
	turn4 := only()
	if turn4.AssignedTo != bo {
		t.Errorf("turn 4 went to %s, want bo — back in their old position", names[turn4.AssignedTo])
	}
}

// setAwayBack closes the open period rather than deleting it — the absence
// still happened, and F6 needs to be able to say so.
func setAwayBack(t *testing.T, db *database.DB, groupID, userID string) {
	t.Helper()
	if _, err := db.Exec(
		`UPDATE away_periods SET ended_at = ? WHERE group_id = ? AND user_id = ? AND ended_at IS NULL`,
		time.Now().UTC(), groupID, userID,
	); err != nil {
		t.Fatalf("clear away: %v", err)
	}
}

// An away period that has run out needs no cleanup — the member is simply back.
func TestAnExpiredAwayPeriodMeansPresent(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	past := time.Now().Add(-time.Hour)
	setAway(t, db, groupID, bo, &past)

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, occ.ID, ann, "done")

	next := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if next.AssignedTo != bo {
		t.Errorf("turn went to %s; bo's away period has ended, so bo is back in the rotation", next.AssignedTo)
	}
}

// A brand-new chore should not land on somebody who is away — it would sit
// untouched from the moment it existed.
func TestANewChoreSkipsAnAwayFirstMember(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	setAway(t, db, groupID, ann, nil)

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, bo, chore.ID)[0]
	if occ.AssignedTo != bo {
		t.Errorf("first turn went to %s, want bo — ann is away", occ.AssignedTo)
	}
}

// Away is not a way to discharge a debt. A cover hands the turn back to whoever
// owed it, whether or not they are away; it waits on their row, which is what
// an open occurrence does anyway.
func TestAwayDoesNotCancelADebtAlreadyOwed(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	// ann goes away, and bo covers her turn while she is gone.
	setAway(t, db, groupID, ann, nil)
	patchOccurrence(t, db, groupID, occ.ID, bo, "done")

	next := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if next.AssignedTo != ann {
		t.Errorf("the debt went to %s; going away does not discharge a turn you already owed", next.AssignedTo)
	}
}

// With everybody away the chore still has to have exactly one name on it.
func TestEveryoneAwayStillLeavesTheChoreWithSomebody(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	setAway(t, db, groupID, ann, nil)
	setAway(t, db, groupID, bo, nil)

	patchOccurrence(t, db, groupID, occ.ID, ann, "done")

	next := openOccurrencesFor(t, db, groupID, ann, chore.ID)
	if len(next) != 1 {
		t.Fatalf("expected one occurrence, got %d — everything on the board has exactly one name", len(next))
	}
	if next[0].AssignedTo != bo {
		t.Errorf("assigned to %s, want the person whose turn it would have been (bo)", next[0].AssignedTo)
	}
}

// Coming back closes the period; it does not erase it.
//
// This is the whole reason away is a record rather than a flag. F6's
// per-person view counts completions over a window, and somebody who was away
// for three weeks shows a number near zero. Without the absence on file there
// is nothing to explain it with, and a low count with no explanation is exactly
// the reading the spec exists to prevent.
func TestReturningLeavesTheAbsenceOnRecord(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	groupID := newGroup(t, db, ann, "Flat")

	setAway(t, db, groupID, ann, nil)
	setAwayBack(t, db, groupID, ann)

	var periods, open int
	db.QueryRow(`SELECT COUNT(*) FROM away_periods WHERE group_id = ? AND user_id = ?`,
		groupID, ann).Scan(&periods)
	db.QueryRow(`SELECT COUNT(*) FROM away_periods WHERE group_id = ? AND user_id = ? AND ended_at IS NULL`,
		groupID, ann).Scan(&open)

	if periods != 1 {
		t.Errorf("expected the absence to survive the return, found %d period(s)", periods)
	}
	if open != 0 {
		t.Errorf("the period should be closed, found %d still open", open)
	}

	// And they are back in the rotation.
	away, err := (&ChoreHandler{DB: db}).awayMembers(groupID)
	if err != nil {
		t.Fatalf("awayMembers: %v", err)
	}
	if away[ann] {
		t.Error("ann still reads as away after returning")
	}
}

// Declaring away twice must not leave two overlapping records for one person,
// or any window arithmetic over them double-counts the same absence.
func TestDeclaringAwayTwiceLeavesOneOpenPeriod(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	groupID := newGroup(t, db, ann, "Flat")
	h := &GroupHandler{DB: db}

	away := func(body string) {
		t.Helper()
		rec := httptest.NewRecorder()
		h.SetAway(rec, request("PUT", "/groups/"+groupID+"/members/me/away", body, ann,
			map[string]string{"group_id": groupID}))
		if rec.Code != 200 {
			t.Fatalf("set away: got %d (%s)", rec.Code, rec.Body.String())
		}
	}

	away(`{"away":true}`)
	away(`{"away":true}`)

	var open int
	db.QueryRow(`SELECT COUNT(*) FROM away_periods WHERE group_id = ? AND user_id = ? AND ended_at IS NULL`,
		groupID, ann).Scan(&open)
	if open != 1 {
		t.Errorf("expected exactly one open period, got %d", open)
	}
}

func TestNextInRotationSkipsAwayMembers(t *testing.T) {
	rotation := []string{"a", "b", "c"}

	if got := nextInRotation(rotation, "a", map[string]bool{"b": true}); got != "c" {
		t.Errorf("with b away, after a should be c, got %s", got)
	}
	if got := nextInRotation(rotation, "a", map[string]bool{"b": true, "c": true}); got != "a" {
		t.Errorf("with b and c away it wraps back to a, got %s", got)
	}
	if got := nextInRotation(rotation, "c", map[string]bool{"a": true}); got != "b" {
		t.Errorf("wrapping past an away member: got %s, want b", got)
	}
	// Everybody away: still returns a name rather than an empty string.
	if got := nextInRotation(rotation, "a", map[string]bool{"a": true, "b": true, "c": true}); got != "b" {
		t.Errorf("everyone away should still yield the naive next (b), got %s", got)
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

func undoPass(t *testing.T, db *database.DB, groupID, occurrenceID, userID string) *httptest.ResponseRecorder {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UndoPass(rec, request("DELETE",
		"/groups/"+groupID+"/occurrences/"+occurrenceID+"/pass", "", userID,
		map[string]string{"group_id": groupID, "occurrence_id": occurrenceID}))
	return rec
}

// The whole point: a mis-swipe puts the chore back where it was.
func TestUndoPassGivesTheChoreBack(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann)

	if rec := undoPass(t, db, groupID, occ.ID, ann); rec.Code != 200 {
		t.Fatalf("undo returned %d: %s", rec.Code, rec.Body.String())
	}

	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if after.AssignedTo != ann {
		t.Errorf("assigned to %s after undo, want ann back", after.AssignedTo)
	}
	if after.PassedFrom != nil {
		t.Errorf("passed_from still %v; the pass should leave no trace", *after.PassedFrom)
	}
	if after.PassedAt != nil {
		t.Error("passed_at survived the undo")
	}
}

// The reason due_before_pass exists. Passing an overdue chore moves its
// deadline to tomorrow for the receiver; if the undo left that in place, then
// pass-then-undo would be a way to launder lateness into a fresh deadline.
func TestUndoPassRestoresAnOverdueDeadlineRatherThanLaunderingIt(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	stale := time.Now().AddDate(0, 0, -4)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, stale.UTC(), occ.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	passOccurrence(t, db, groupID, occ.ID, ann)
	handed := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if handed.DueDate == nil || handed.DueDate.YearDay() != time.Now().AddDate(0, 0, 1).YearDay() {
		t.Fatalf("precondition: the pass should have moved the date to tomorrow, got %v", handed.DueDate)
	}

	if rec := undoPass(t, db, groupID, occ.ID, ann); rec.Code != 200 {
		t.Fatalf("undo returned %d: %s", rec.Code, rec.Body.String())
	}

	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if after.DueDate == nil {
		t.Fatal("due date was cleared by the undo")
	}
	if after.DueDate.YearDay() != stale.YearDay() {
		t.Errorf("due %s after undo, want the original %s: taking a pass back must not "+
			"move a late chore's deadline forward",
			after.DueDate.Format(time.RFC3339), stale.Format(time.RFC3339))
	}
}

// Being handed a chore is not consent to hand it straight back. If the receiver
// could undo, "pass" and "refuse" would be the same button.
func TestUndoPassIsRefusedToTheReceiver(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann)

	if rec := undoPass(t, db, groupID, occ.ID, bo); rec.Code != 403 {
		t.Errorf("receiver undoing returned %d, want 403: %s", rec.Code, rec.Body.String())
	}

	after := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	if after.AssignedTo != bo {
		t.Errorf("the refused undo still moved the chore: assigned to %s", after.AssignedTo)
	}
}

// The window is measured on the server from passed_at, because the snackbar's
// own timer is not evidence.
func TestUndoPassIsRefusedOnceTheWindowHasClosed(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann)

	stale := time.Now().Add(-passUndoWindow - time.Minute).UTC().Format("2006-01-02 15:04:05")
	if _, err := db.Exec(`UPDATE occurrences SET passed_at = ? WHERE id = ?`, stale, occ.ID); err != nil {
		t.Fatalf("backdate passed_at: %v", err)
	}

	if rec := undoPass(t, db, groupID, occ.ID, ann); rec.Code != 409 {
		t.Errorf("late undo returned %d, want 409: %s", rec.Code, rec.Body.String())
	}
}

// Nothing to take back, and nothing to take back *from*.
func TestUndoPassIsRefusedWhenItWouldMakeNoSense(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]

	// Never passed.
	if rec := undoPass(t, db, groupID, occ.ID, ann); rec.Code != 409 {
		t.Errorf("undo of an unpassed occurrence returned %d, want 409: %s", rec.Code, rec.Body.String())
	}

	// Passed, then actually done by the receiver: the work has happened, so
	// there is nothing to reclaim.
	passOccurrence(t, db, groupID, occ.ID, ann)
	patchOccurrence(t, db, groupID, occ.ID, bo, "done")
	if rec := undoPass(t, db, groupID, occ.ID, ann); rec.Code != 409 {
		t.Errorf("undo after completion returned %d, want 409: %s", rec.Code, rec.Body.String())
	}
}

// Constraint 7 keeps a pass between the two people it concerns, and an undo is
// part of the same private exchange.
func TestUndoPassWritesNoActivityEvent(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	passOccurrence(t, db, groupID, occ.ID, ann)

	var before int
	if err := db.QueryRow(`SELECT COUNT(*) FROM activity_events WHERE group_id = ?`, groupID).Scan(&before); err != nil {
		t.Fatalf("count before: %v", err)
	}
	undoPass(t, db, groupID, occ.ID, ann)
	var after int
	if err := db.QueryRow(`SELECT COUNT(*) FROM activity_events WHERE group_id = ?`, groupID).Scan(&after); err != nil {
		t.Fatalf("count after: %v", err)
	}
	if after != before {
		t.Errorf("the undo wrote %d activity event(s); a pass and its undo are private", after-before)
	}
}
