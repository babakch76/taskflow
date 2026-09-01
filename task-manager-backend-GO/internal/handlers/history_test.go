package handlers

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/models"
)

func choreHistory(t *testing.T, db *database.DB, groupID, choreID, userID string) models.ChoreHistory {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.ChoreHistory(rec, request("GET",
		"/groups/"+groupID+"/chores/"+choreID+"/history", "", userID,
		map[string]string{"group_id": groupID, "chore_id": choreID}))
	if rec.Code != 200 {
		t.Fatalf("ChoreHistory: got %d (%s)", rec.Code, rec.Body.String())
	}
	var out models.ChoreHistory
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return out
}

func groupHistory(t *testing.T, db *database.DB, groupID, userID, window string) models.GroupHistory {
	t.Helper()
	h := &ChoreHandler{DB: db}
	target := "/groups/" + groupID + "/history"
	if window != "" {
		target += "?window=" + window
	}
	rec := httptest.NewRecorder()
	h.GroupHistory(rec, request("GET", target, "", userID,
		map[string]string{"group_id": groupID}))
	if rec.Code != 200 {
		t.Fatalf("GroupHistory: got %d (%s)", rec.Code, rec.Body.String())
	}
	var out models.GroupHistory
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return out
}

// A chore's timeline records who did each cycle, when it was due and when it
// was done — including when the doer was not the assignee.
func TestChoreHistoryRecordsWhoActuallyDidIt(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	// ann's turn, done by bo — a cover.
	first := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, first.ID, bo, "done")
	// The debt comes back to ann, who does it herself.
	second := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	patchOccurrence(t, db, groupID, second.ID, ann, "done")

	history := choreHistory(t, db, groupID, chore.ID, ann)
	if len(history.Entries) != 2 {
		t.Fatalf("expected two completions, got %d", len(history.Entries))
	}

	// Newest first.
	newest, oldest := history.Entries[0], history.Entries[1]
	if newest.DoneBy != ann || newest.AssignedTo != ann {
		t.Errorf("newest: assigned=%s done_by=%s, want both ann", newest.AssigneeName, newest.DoneByName)
	}
	if oldest.AssignedTo != ann || oldest.DoneBy != bo {
		t.Errorf("oldest should be ann's turn done by bo, got assigned=%s done_by=%s",
			oldest.AssigneeName, oldest.DoneByName)
	}
	if oldest.AssigneeName != "ann" || oldest.DoneByName != "bo" {
		t.Errorf("names not joined in: %q / %q", oldest.AssigneeName, oldest.DoneByName)
	}
	if oldest.DoneAt.IsZero() {
		t.Error("done_at missing")
	}
}

// Constraint 3 and F6: lateness is date arithmetic, never a flag. The payload
// must not contain a verdict the app reached.
func TestHistoryNeverLabelsAnythingLate(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	groupID := newGroup(t, db, ann, "Flat")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Bathroom", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q]
	}`, ann))

	// Complete it well after it was due.
	occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
	stale := time.Now().AddDate(0, 0, -10)
	if _, err := db.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, stale.UTC(), occ.ID); err != nil {
		t.Fatalf("backdate: %v", err)
	}
	patchOccurrence(t, db, groupID, occ.ID, ann, "done")

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.ChoreHistory(rec, request("GET",
		"/groups/"+groupID+"/chores/"+chore.ID+"/history", "", ann,
		map[string]string{"group_id": groupID, "chore_id": chore.ID}))

	body := strings.ToLower(rec.Body.String())
	for _, word := range []string{"late", "overdue", "missed", "days_late", "streak"} {
		if strings.Contains(body, word) {
			t.Errorf("history payload contains %q — lateness must be arithmetic the reader does, not a verdict the app reaches:\n%s",
				word, rec.Body.String())
		}
	}

	// But both dates are there, so the reader *can* do the arithmetic.
	history := choreHistory(t, db, groupID, chore.ID, ann)
	if history.Entries[0].DueDate == nil {
		t.Error("due date missing; without it lateness is not derivable at all")
	}
}

// Covering counts for the coverer — the reason both names have been kept since
// F1.
func TestPersonHistoryCreditsTheDoerNotTheAssignee(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q, %q]
	}`, ann, bo))

	// Three of ann's turns, all quietly done by bo.
	for i := 0; i < 3; i++ {
		occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
		if occ.AssignedTo != ann {
			t.Fatalf("cycle %d: expected ann's turn, got someone else", i)
		}
		patchOccurrence(t, db, groupID, occ.ID, bo, "done")
	}

	history := groupHistory(t, db, groupID, ann, "month")
	counts := map[string]int{}
	for _, p := range history.People {
		counts[p.Username] = p.Completed
	}

	if counts["bo"] != 3 {
		t.Errorf("bo did three of them; got %d", counts["bo"])
	}
	if counts["ann"] != 0 {
		t.Errorf("ann did none of them; got %d", counts["ann"])
	}
}

// Constraint 4: no leaderboards. Ordering by count would be one whatever it was
// called, so people come back in the order they joined.
func TestPersonHistoryDoesNotRankPeople(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")
	cass := newUser(t, db, "cass")
	groupID := newGroup(t, db, ann, "Flat")
	addMember(t, db, groupID, bo, "member")
	addMember(t, db, groupID, cass, "member")

	chore := createChore(t, db, groupID, ann, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "as_needed", "rotation": [%q]
	}`, ann))

	// cass does several; ann and bo do none. A ranked list would put cass first.
	for i := 0; i < 4; i++ {
		occ := openOccurrencesFor(t, db, groupID, ann, chore.ID)[0]
		patchOccurrence(t, db, groupID, occ.ID, cass, "done")
	}

	history := groupHistory(t, db, groupID, ann, "month")
	got := []string{}
	for _, p := range history.People {
		got = append(got, p.Username)
	}

	want := []string{"ann", "bo", "cass"} // join order
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("people came back as %v, want join order %v — sorting by count is a leaderboard", got, want)
	}

	// Everyone appears, including those who did nothing. Dropping them would
	// make the list a ranking of people who did something.
	if len(history.People) != 3 {
		t.Errorf("expected all three members, got %d", len(history.People))
	}
}

// A quiet stretch has its explanation next to it.
func TestPersonHistoryReportsDaysAway(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	groupID := newGroup(t, db, ann, "Flat")

	// Away for five days, a fortnight ago, and already back.
	start := time.Now().AddDate(0, 0, -14)
	end := start.AddDate(0, 0, 5)
	if _, err := db.Exec(
		`INSERT INTO away_periods (id, group_id, user_id, started_at, ends_at, ended_at)
		 VALUES ('p1', ?, ?, ?, NULL, ?)`,
		groupID, ann, start.UTC(), end.UTC(),
	); err != nil {
		t.Fatalf("insert period: %v", err)
	}

	history := groupHistory(t, db, groupID, ann, "month")
	if history.People[0].AwayDays != 5 {
		t.Errorf("away days: got %d, want 5", history.People[0].AwayDays)
	}

	// A window that predates the absence entirely shouldn't count it.
	week := groupHistory(t, db, groupID, ann, "week")
	if week.People[0].AwayDays != 0 {
		t.Errorf("the absence was a fortnight ago; this week should show 0, got %d",
			week.People[0].AwayDays)
	}
}

func TestAwayDaysWithin(t *testing.T) {
	base := time.Date(2026, 9, 20, 12, 0, 0, 0, time.UTC)
	from := base.AddDate(0, 0, -30)
	to := base

	at := func(daysAgo int) time.Time { return base.AddDate(0, 0, -daysAgo) }
	ptr := func(t time.Time) *time.Time { return &t }

	cases := []struct {
		name string
		a    models.Absence
		want int
	}{
		{
			name: "a closed period counts its actual length",
			a:    models.Absence{UserID: "u", StartedAt: at(10), EndedAt: ptr(at(6))},
			want: 4,
		},
		{
			name: "coming back early beats the planned end",
			// Said two weeks, came back after three days.
			a:    models.Absence{UserID: "u", StartedAt: at(10), EndsAt: ptr(at(-4)), EndedAt: ptr(at(7))},
			want: 3,
		},
		{
			name: "a planned end that has passed closes the period",
			a:    models.Absence{UserID: "u", StartedAt: at(10), EndsAt: ptr(at(8))},
			want: 2,
		},
		{
			name: "an open period still running counts up to now",
			a:    models.Absence{UserID: "u", StartedAt: at(3)},
			want: 3,
		},
		{
			name: "only the part inside the window counts",
			a:    models.Absence{UserID: "u", StartedAt: at(40), EndedAt: ptr(at(25))},
			want: 5, // from is 30 days ago
		},
		{
			name: "an absence entirely before the window counts nothing",
			a:    models.Absence{UserID: "u", StartedAt: at(60), EndedAt: ptr(at(50))},
			want: 0,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := awayDaysWithin([]models.Absence{tc.a}, from, to)["u"]
			if got != tc.want {
				t.Errorf("got %d days, want %d", got, tc.want)
			}
		})
	}
}

func TestGroupHistoryRejectsAnUnknownWindow(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	groupID := newGroup(t, db, ann, "Flat")

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.GroupHistory(rec, request("GET", "/groups/"+groupID+"/history?window=forever", "", ann,
		map[string]string{"group_id": groupID}))

	if rec.Code != 400 {
		t.Fatalf("got %d, want 400", rec.Code)
	}
	if got := decodeError(t, rec); got != "window must be week, month or quarter" {
		t.Errorf("error: got %q", got)
	}
}

func TestChoreHistoryDoesNotCrossGroups(t *testing.T) {
	db := newTestDB(t)
	ann := newUser(t, db, "ann")
	other := newUser(t, db, "other")
	groupA := newGroup(t, db, ann, "A")
	groupB := newGroup(t, db, other, "B")

	chore := createChore(t, db, groupA, ann, fmt.Sprintf(`{
		"name": "Private", "schedule_type": "as_needed", "rotation": [%q]
	}`, ann))

	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.ChoreHistory(rec, request("GET",
		"/groups/"+groupB+"/chores/"+chore.ID+"/history", "", other,
		map[string]string{"group_id": groupB, "chore_id": chore.ID}))

	if rec.Code != 404 {
		t.Errorf("got %d, want 404", rec.Code)
	}
}
