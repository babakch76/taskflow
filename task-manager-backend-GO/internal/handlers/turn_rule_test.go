package handlers

import (
	"database/sql"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/models"
)

// The turn rule, case by case, from turn_rule_cases_prompt.md.
//
// Every case in the document is written here as an assertion of the sequence of
// *assignments*, because that sequence is the whole observable behaviour of the
// rotation engine: who the board puts a chore on next is the only thing a
// household can see, and every rule in the document is a claim about it.
//
// Assertions name people A, B, C rather than user ids, so a failure reads
// "occ3 went to A, want B" instead of comparing two UUIDs.

// nameFor reverses a name->id map so failures are legible.
func nameFor(people map[string]string) func(string) string {
	rev := make(map[string]string, len(people))
	for name, id := range people {
		rev[id] = name
	}
	return func(id string) string {
		if id == "" {
			return "(nobody)"
		}
		if n, ok := rev[id]; ok {
			return n
		}
		return "unknown(" + id + ")"
	}
}

// theOpenOcc returns the chore's single open occurrence.
//
// It fails when there is not exactly one. Under completion-anchored rotation a
// chore has exactly one live turn at a time, so "two open" is itself a bug and
// worth catching here rather than silently taking [0].
func theOpenOcc(t *testing.T, db *database.DB, groupID, caller, choreID string) models.Occurrence {
	t.Helper()
	open := openOccurrencesFor(t, db, groupID, caller, choreID)
	if len(open) != 1 {
		t.Fatalf("want exactly one open occurrence, got %d", len(open))
	}
	return open[0]
}

func markDone(t *testing.T, db *database.DB, groupID, occID, doer string) {
	t.Helper()
	if rec := patchOccurrence(t, db, groupID, occID, doer, "done"); rec.Code != 200 {
		t.Fatalf("marking done: %d %s", rec.Code, rec.Body.String())
	}
}

func passOK(t *testing.T, db *database.DB, groupID, occID, passer string) {
	t.Helper()
	if rec := passOccurrence(t, db, groupID, occID, passer); rec.Code != 200 {
		t.Fatalf("passing: %d %s", rec.Code, rec.Body.String())
	}
}

// threePersonChore sets up [A, B, C] on one chore and returns the ids, the
// chore, and a name lookup.
func threePersonChore(t *testing.T, scheduleType string) (
	db *database.DB, groupID string, a, b, c string,
	chore models.Chore, name func(string) string,
) {
	t.Helper()
	db = newTestDB(t)
	a = newUser(t, db, "ann")
	b = newUser(t, db, "bo")
	c = newUser(t, db, "cass")
	groupID = newGroup(t, db, a, "Flat")
	addMember(t, db, groupID, b, "member")
	addMember(t, db, groupID, c, "member")
	name = nameFor(map[string]string{"A": a, "B": b, "C": c})

	// An as-needed chore takes no schedule parameters at all, and the API is
	// right to refuse them, so the interval is only sent when it means
	// something.
	schedule := `"interval_days": 3,`
	if scheduleType == "as_needed" {
		schedule = ""
	}
	spec := fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": %q, %s
		"rotation": [%q, %q, %q]
	}`, scheduleType, schedule, a, b, c)
	chore = createChore(t, db, groupID, a, spec)
	return
}

// ── Part 1: cases believed correct ────────────────────────────────────────

// 1.1 Simple pass. A passes, B covers, the debt returns to A, and rotation
// then resumes after the doer rather than after the repayer.
func TestTurnRule_1_1_SimplePass(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	if occ1.AssignedTo != a {
		t.Fatalf("occ1 went to %s, want A", name(occ1.AssignedTo))
	}

	passOK(t, db, g, occ1.ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)
	if handed.AssignedTo != b {
		t.Fatalf("A passed and it went to %s, want B", name(handed.AssignedTo))
	}

	markDone(t, db, g, handed.ID, b)
	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A: the debt returns to whoever passed", name(occ2.AssignedTo))
	}

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != c {
		t.Errorf("occ3 went to %s, want C: rotation resumes after the doer B, not after the repayer A",
			name(occ3.AssignedTo))
	}
}

// 1.2 A voluntary cover with no pass at all must be the same event to the
// engine as a pass. If these two diverge, "pass" has grown a rule of its own.
func TestTurnRule_1_2_VoluntaryCoverIsTheSameAsAPass(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	markDone(t, db, g, occ1.ID, b) // B just does it; nobody passed anything.

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(occ2.AssignedTo))
	}

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != c {
		t.Errorf("occ3 went to %s, want C", name(occ3.AssignedTo))
	}
}

// 1.3 Repeated skips by the same person. The chore keeps coming back to the
// skipper, and the covering must move around the household rather than landing
// on the same neighbour every time.
func TestTurnRule_1_3_RepeatedSkipsSpreadTheCovering(t *testing.T) {
	db, g, a, _, c, chore, name := threePersonChore(t, "interval")

	coverers := []string{}
	for round := 1; round <= 3; round++ {
		occ := theOpenOcc(t, db, g, a, chore.ID)
		if occ.AssignedTo != a {
			t.Fatalf("round %d: the turn is on %s, want A each time", round, name(occ.AssignedTo))
		}
		passOK(t, db, g, occ.ID, a)
		handed := theOpenOcc(t, db, g, a, chore.ID)
		coverers = append(coverers, name(handed.AssignedTo))
		markDone(t, db, g, handed.ID, handed.AssignedTo)
	}

	final := theOpenOcc(t, db, g, a, chore.ID)
	if final.AssignedTo != a {
		t.Errorf("occ4 went to %s, want A", name(final.AssignedTo))
	}

	// The document's requirement: covering alternates rather than always
	// falling on whoever happens to follow the skipper.
	want := []string{"B", "C", "B"}
	for i := range want {
		if coverers[i] != want[i] {
			t.Errorf("covering went %v, want %v: the same neighbour must not carry every skip",
				coverers, want)
			break
		}
	}
	_ = c
}

// 1.4 A two-person rotation. A doing two in a row is correct here: across the
// three occurrences A performs two and B one, the same split an unbroken
// alternation would have produced.
func TestTurnRule_1_4_TwoPersonRotation(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	g := newGroup(t, db, a, "Flat")
	addMember(t, db, g, b, "member")
	name := nameFor(map[string]string{"A": a, "B": b})

	chore := createChore(t, db, g, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, a, b))

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)
	if handed.AssignedTo != b {
		t.Fatalf("A passed and it went to %s, want B", name(handed.AssignedTo))
	}
	markDone(t, db, g, handed.ID, b)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(occ2.AssignedTo))
	}
	markDone(t, db, g, occ2.ID, a)

	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != a {
		t.Errorf("occ3 went to %s, want A: resume after doer B, and after B comes A",
			name(occ3.AssignedTo))
	}
}

// 1.5 A debt must survive the debtor going away: held, not cancelled, and not
// parked on the row of somebody who is not there.
func TestTurnRule_1_5_DebtSurvivesAway(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)

	// A leaves the house before the debt occurrence is created.
	setAway(t, db, g, a, nil)
	markDone(t, db, g, handed.ID, b)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo == a {
		t.Errorf("occ2 went to A, who is away: an away member is lifted out of every "+
			"rotation, so the turn should pass to the next active member and A's debt "+
			"should be held for their return (got %s)", name(occ2.AssignedTo))
	}
	// A comes back before that turn is finished. Debts are read when the *next*
	// occurrence is created, which is the moment a completion happens, so this
	// is the completion that should notice A is available again.
	setAwayBack(t, db, g, a)
	markDone(t, db, g, occ2.ID, occ2.AssignedTo)

	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != a {
		t.Errorf("after A returned, occ3 went to %s, want A: the debt was retained, not cancelled",
			name(occ3.AssignedTo))
	}
	_ = c
}

// 1.6 The same rules with no dates anywhere. An as-needed chore has no due
// date to reason about, and the engine must not invent one.
func TestTurnRule_1_6_AsNeededCarriesNoDates(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "as_needed")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	if occ1.DueDate != nil {
		t.Errorf("an as-needed occurrence has a due date of %v; it should have none", occ1.DueDate)
	}

	passOK(t, db, g, occ1.ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)
	if handed.DueDate != nil {
		t.Errorf("passing an as-needed chore gave it a due date of %v", handed.DueDate)
	}
	markDone(t, db, g, handed.ID, b)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(occ2.AssignedTo))
	}
	if occ2.DueDate != nil {
		t.Errorf("the spawned as-needed occurrence has a due date of %v", occ2.DueDate)
	}

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != c {
		t.Errorf("occ3 went to %s, want C", name(occ3.AssignedTo))
	}
}

// ── Part 2: the suspected bug ─────────────────────────────────────────────

// 2.1 Chained passes. A passes to B, B passes on to C, C does it.
//
// The document requires two debts, honoured in the order the passes happened:
// occ2 to A, occ3 to B, occ4 back round to A.
func TestTurnRule_2_1_ChainedPassesOweTwoDebts(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)

	toB := theOpenOcc(t, db, g, a, chore.ID)
	if toB.AssignedTo != b {
		t.Fatalf("A's pass went to %s, want B", name(toB.AssignedTo))
	}
	passOK(t, db, g, toB.ID, b)

	toC := theOpenOcc(t, db, g, a, chore.ID)
	if toC.AssignedTo != c {
		t.Fatalf("B's pass went to %s, want C", name(toC.AssignedTo))
	}

	// What the storage actually held at the end of the chain, reported either
	// way: this is the fact the document asks about before any fix.
	t.Logf("after A->B->C, passed_from = %s", func() string {
		if toC.PassedFrom == nil {
			return "(null)"
		}
		return name(*toC.PassedFrom)
	}())

	markDone(t, db, g, toC.ID, c)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Errorf("occ2 went to %s, want A: the first skipper is repaid first", name(occ2.AssignedTo))
	}
	markDone(t, db, g, occ2.ID, occ2.AssignedTo)

	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != b {
		t.Errorf("occ3 went to %s, want B: B passed too, so B owes a turn as well",
			name(occ3.AssignedTo))
	}
	markDone(t, db, g, occ3.ID, occ3.AssignedTo)

	occ4 := theOpenOcc(t, db, g, a, chore.ID)
	if occ4.AssignedTo != a {
		t.Errorf("occ4 went to %s, want A: both debts paid, rotation resumes after the doer C",
			name(occ4.AssignedTo))
	}
}

// ── Part 3: cases the spec never named ────────────────────────────────────

// setRotation edits a chore's rotation, for the cases where the household
// changes while a turn is owed.
func setRotation(t *testing.T, db *database.DB, groupID, choreID, actor string, ids []string) {
	t.Helper()
	quoted := make([]string, 0, len(ids))
	for _, id := range ids {
		quoted = append(quoted, fmt.Sprintf("%q", id))
	}
	body := fmt.Sprintf(`{"rotation":[%s]}`, strings.Join(quoted, ","))
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.UpdateChore(rec, request("PATCH", "/groups/"+groupID+"/chores/"+choreID, body, actor,
		map[string]string{"group_id": groupID, "chore_id": choreID}))
	if rec.Code != 200 {
		t.Fatalf("editing the rotation: %d %s", rec.Code, rec.Body.String())
	}
}

// chainOf reads the pass chain straight from the row.
//
// It is deliberately not on the wire — constraint 7 keeps a pass between the
// people it concerns — so a test that wants to see it has to look in the
// database.
func chainOf(t *testing.T, db *database.DB, occID string) string {
	t.Helper()
	var chain sql.NullString
	if err := db.QueryRow(`SELECT passed_chain FROM occurrences WHERE id = ?`, occID).Scan(&chain); err != nil {
		t.Fatalf("reading passed_chain: %v", err)
	}
	return chain.String
}

func debtsOf(t *testing.T, db *database.DB, occID string) string {
	t.Helper()
	var debts sql.NullString
	if err := db.QueryRow(`SELECT pending_debts FROM occurrences WHERE id = ?`, occID).Scan(&debts); err != nil {
		t.Fatalf("reading pending_debts: %v", err)
	}
	return debts.String
}

// 3.1 Anyone in the household may mark any chore done, including someone who is
// not in that chore's rotation at all. "Resume after the doer" then means
// nothing, so the order resumes after the person who owed the turn instead.
func TestTurnRule_3_1_DoerOutsideTheRotation(t *testing.T) {
	db := newTestDB(t)
	a := newUser(t, db, "ann")
	b := newUser(t, db, "bo")
	outsider := newUser(t, db, "cass")
	g := newGroup(t, db, a, "Flat")
	addMember(t, db, g, b, "member")
	addMember(t, db, g, outsider, "member")
	name := nameFor(map[string]string{"A": a, "B": b, "outsider": outsider})

	// The rotation is only A and B; cass is in the house but not on this chore.
	chore := createChore(t, db, g, a, fmt.Sprintf(`{
		"name": "Kitchen", "schedule_type": "interval", "interval_days": 3,
		"rotation": [%q, %q]
	}`, a, b))

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	markDone(t, db, g, occ1.ID, outsider)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A: an outsider doing it is still a cover", name(occ2.AssignedTo))
	}
	// The cover is recorded, so the board can explain the hand-back.
	if occ2.CoveredBy == nil || *occ2.CoveredBy != outsider {
		t.Errorf("covered_by is %v, want the outsider who actually did it", occ2.CoveredBy)
	}

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != b {
		t.Errorf("occ3 went to %s, want B: with the doer outside the rotation the order "+
			"resumes after A, whose turn it was", name(occ3.AssignedTo))
	}
}

// 3.2 The coverer leaves the household before the debt is repaid, so "resume
// after them" points at nobody. The turn still has to land somewhere sensible.
func TestTurnRule_3_2_CovererLeavesBeforeTheResume(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)
	markDone(t, db, g, handed.ID, b) // B covers.

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(occ2.AssignedTo))
	}

	// B is edited out of the chore, standing in for having left: either way the
	// resume point is no longer someone this rotation knows about.
	setRotation(t, db, g, chore.ID, a, []string{a, c})

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != c {
		t.Errorf("occ3 went to %s, want C: with B gone the order continues to whoever "+
			"follows, and falls back to after A if it cannot be resolved", name(occ3.AssignedTo))
	}
}

// 3.4 A debt attaches to a person, not to a position. Reordering the rotation
// must not move it to somebody else.
func TestTurnRule_3_4_DebtFollowsThePersonThroughAnEdit(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	// A chain, so that a debt is genuinely pending rather than being repaid by
	// the very next occurrence.
	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	markDone(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if occ2.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(occ2.AssignedTo))
	}
	if got := debtsOf(t, db, occ2.ID); got != b {
		t.Fatalf("pending debts are %q, want B still owing", got)
	}

	// The household reorders the rotation entirely. B's position changes; B's
	// debt should not.
	setRotation(t, db, g, chore.ID, a, []string{c, b, a})

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo != b {
		t.Errorf("occ3 went to %s, want B: the debt belongs to B, not to a slot in the order",
			name(occ3.AssignedTo))
	}
}

// 3.3 and 3.4's other half: someone removed from the rotation owes nothing, and
// nothing is said about the turn they did not take.
func TestTurnRule_3_3_LeavingTheRotationVoidsTheDebt(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	markDone(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)

	occ2 := theOpenOcc(t, db, g, a, chore.ID)
	if got := debtsOf(t, db, occ2.ID); got != b {
		t.Fatalf("pending debts are %q, want B", got)
	}

	// B is taken off the chore while still owing a turn on it.
	setRotation(t, db, g, chore.ID, a, []string{a, c})

	markDone(t, db, g, occ2.ID, a)
	occ3 := theOpenOcc(t, db, g, a, chore.ID)
	if occ3.AssignedTo == b {
		t.Errorf("occ3 went to B, who is no longer in this chore's rotation: the debt "+
			"should be void and the order should simply close the gap")
	}
	// And it closes the gap rather than losing the turn: the rotation is [A, C]
	// now and it resumes after C, who covered, so A is next. A repaying in occ2
	// and then taking their own turn in occ3 is the same shape as case 1.4.
	if occ3.AssignedTo != a {
		t.Errorf("occ3 went to %s, want A: with B gone the order resumes after the "+
			"coverer C, and A follows C in what is left of the rotation", name(occ3.AssignedTo))
	}
	_ = c
}

// 3.7 Undo rolls back every consequence of a completion, the debts included. A
// half-paid debt would be worse than either state.
func TestTurnRule_3_7_UndoRestoresTheChainAndTheDebts(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ1 := theOpenOcc(t, db, g, a, chore.ID)
	passOK(t, db, g, occ1.ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)

	held := theOpenOcc(t, db, g, a, chore.ID)
	wantChain := a + "," + b
	if got := chainOf(t, db, held.ID); got != wantChain {
		t.Fatalf("chain is %q, want A,B", got)
	}

	markDone(t, db, g, held.ID, c)
	spawned := theOpenOcc(t, db, g, a, chore.ID)
	if spawned.AssignedTo != a {
		t.Fatalf("occ2 went to %s, want A", name(spawned.AssignedTo))
	}

	// C undoes their own completion, inside the window.
	if rec := patchOccurrence(t, db, g, held.ID, c, "open"); rec.Code != 200 {
		t.Fatalf("undo: %d %s", rec.Code, rec.Body.String())
	}

	// The spawned turn is gone and the original is live again, chain intact.
	back := theOpenOcc(t, db, g, a, chore.ID)
	if back.ID != held.ID {
		t.Fatalf("after the undo the open occurrence is %s, want the original back", back.ID)
	}
	if got := chainOf(t, db, held.ID); got != wantChain {
		t.Errorf("chain is %q after the undo, want A,B: undoing must not forget who passed", got)
	}

	// And doing it again reaches the same place, so nothing was consumed.
	markDone(t, db, g, held.ID, c)
	again := theOpenOcc(t, db, g, a, chore.ID)
	if again.AssignedTo != a {
		t.Errorf("after undo and redo, occ2 went to %s, want A", name(again.AssignedTo))
	}
	if got := debtsOf(t, db, again.ID); got != b {
		t.Errorf("pending debts are %q after undo and redo, want B still owing", got)
	}
}

// A row written before passed_chain existed carries only passed_from, and must
// still behave like the one-element chain it describes.
//
// There is no backfill: the scanner does it on read. That is the cheaper and
// safer choice, but it only works if it is actually wired, hence this test —
// the live database had no passed rows when the column was added, so nothing
// else would have caught a mistake here.
func TestTurnRule_PreChainRowsStillOweTheirDebt(t *testing.T) {
	db, g, a, b, _, chore, name := threePersonChore(t, "interval")

	occ := theOpenOcc(t, db, g, a, chore.ID)

	// Exactly what the old code wrote for "A passed this to B": a passed_from
	// pointer, no chain.
	if _, err := db.Exec(
		`UPDATE occurrences SET assigned_to = ?, passed_from = ?, passed_at = CURRENT_TIMESTAMP,
		        passed_chain = NULL WHERE id = ?`, b, a, occ.ID); err != nil {
		t.Fatalf("staging a pre-chain row: %v", err)
	}

	markDone(t, db, g, occ.ID, b)

	next := theOpenOcc(t, db, g, a, chore.ID)
	if next.AssignedTo != a {
		t.Errorf("the turn went to %s, want A: a pre-chain row still records that A passed it",
			name(next.AssignedTo))
	}
	if next.CoveredBy == nil || *next.CoveredBy != b {
		t.Errorf("covered_by is %v, want B, who did it", next.CoveredBy)
	}
}

// ── When the whole household is busy ─────────────────────────────────────

func setDueDate(t *testing.T, db *database.DB, groupID, occID, actor, body string) *httptest.ResponseRecorder {
	t.Helper()
	h := &ChoreHandler{DB: db}
	rec := httptest.NewRecorder()
	h.SetOccurrenceDueDate(rec, request("PUT",
		"/groups/"+groupID+"/occurrences/"+occID+"/due-date", body, actor,
		map[string]string{"group_id": groupID, "occurrence_id": occID}))
	return rec
}

// Everybody passes, so there is nobody left to pass to. The chore returns to
// whoever asked first, carrying the date it would next have come round on.
func TestAllBusyReturnsTheChoreToTheFirstPasser(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	occ := theOpenOcc(t, db, g, a, chore.ID)
	before := occ.DueDate
	if before == nil {
		t.Fatal("the first occurrence has no due date to bound against")
	}

	passOK(t, db, g, occ.ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)

	back := theOpenOcc(t, db, g, a, chore.ID)
	if back.AssignedTo != a {
		t.Errorf("it went to %s, want A: with everybody busy it returns to whoever asked first",
			name(back.AssignedTo))
	}
	// Every passer still owes a turn, A included.
	if got := chainOf(t, db, back.ID); got != a+","+b+","+c {
		t.Errorf("chain is %q, want all three: passing last does not excuse you", got)
	}
	// And it is dated no later than the chore's own rhythm.
	if back.DueDate == nil {
		t.Fatal("it came back with no date at all")
	}
	if back.DueDate.Before(*before) {
		t.Errorf("the new date %s is before the old %s", back.DueDate, before)
	}
}

// There is nobody to hand it to, so asking again is refused rather than
// silently lapping the household.
func TestAllBusyRefusesAFurtherPass(t *testing.T) {
	db, g, a, b, c, chore, _ := threePersonChore(t, "interval")

	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)

	back := theOpenOcc(t, db, g, a, chore.ID)
	rec := passOccurrence(t, db, g, back.ID, a)
	if rec.Code != 409 {
		t.Errorf("passing again returned %d, want 409: there is nobody left", rec.Code)
	}
}

// The holder may bring the day forward, and only forward. Letting it move later
// would make "we were all busy" a way to postpone a chore a round at a time.
func TestAllBusyLetsTheHolderBringTheDayForwardOnly(t *testing.T) {
	db, g, a, b, c, chore, _ := threePersonChore(t, "interval")

	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)
	back := theOpenOcc(t, db, g, a, chore.ID)
	bound := *back.DueDate

	earlier := bound.AddDate(0, 0, -1).Format(time.RFC3339)
	if rec := setDueDate(t, db, g, back.ID, a, `{"due_date":"`+earlier+`"}`); rec.Code != 200 {
		t.Fatalf("bringing it forward: %d %s", rec.Code, rec.Body.String())
	}
	moved := theOpenOcc(t, db, g, a, chore.ID)
	if !moved.DueDate.Before(bound) {
		t.Errorf("the date is %s, want earlier than %s", moved.DueDate, bound)
	}

	later := bound.AddDate(0, 0, 3).Format(time.RFC3339)
	if rec := setDueDate(t, db, g, back.ID, a, `{"due_date":"`+later+`"}`); rec.Code != 400 {
		t.Errorf("pushing it back returned %d, want 400", rec.Code)
	}

	// And it is the holder's to set, nobody else's.
	if rec := setDueDate(t, db, g, back.ID, b, `{"due_date":"`+earlier+`"}`); rec.Code != 403 {
		t.Errorf("a housemate setting it returned %d, want 403", rec.Code)
	}
}

// Away members are not counted as busy: they are out of the rotation, so a
// household of three with one away is a household of two for this purpose.
func TestAllBusyIgnoresAwayMembers(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "interval")

	setAway(t, db, g, c, nil)

	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, a)
	handed := theOpenOcc(t, db, g, a, chore.ID)
	if handed.AssignedTo != b {
		t.Fatalf("A's pass went to %s, want B: C is away", name(handed.AssignedTo))
	}
	// B is the last available member, so B passing closes the round.
	passOK(t, db, g, handed.ID, b)

	back := theOpenOcc(t, db, g, a, chore.ID)
	if back.AssignedTo != a {
		t.Errorf("it went to %s, want A: with C away, A and B are the whole household",
			name(back.AssignedTo))
	}
}

// An as-needed chore has no next date, so there is nothing to default to and
// nothing to bound. It comes back and waits, which is what it does anyway.
func TestAllBusyOnAnAsNeededChoreNeedsNoDate(t *testing.T) {
	db, g, a, b, c, chore, name := threePersonChore(t, "as_needed")

	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, a)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, b)
	passOK(t, db, g, theOpenOcc(t, db, g, a, chore.ID).ID, c)

	back := theOpenOcc(t, db, g, a, chore.ID)
	if back.AssignedTo != a {
		t.Errorf("it went to %s, want A", name(back.AssignedTo))
	}
	if back.DueDate != nil {
		t.Errorf("an as-needed chore came back with a date of %v", back.DueDate)
	}
	if rec := setDueDate(t, db, g, back.ID, a, `{"due_date":"2026-12-01T12:00:00Z"}`); rec.Code != 409 {
		t.Errorf("setting a date on an as-needed chore returned %d, want 409", rec.Code)
	}
}
