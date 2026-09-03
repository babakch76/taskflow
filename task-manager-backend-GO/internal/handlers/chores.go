package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/middleware"
	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

// ChoreHandler serves the chore model that F2 introduces: definitions, their
// rotation lists, and the occurrences that appear on the board.
//
// It sits alongside TaskHandler rather than replacing it. Tasks predate the
// chore spec and are the spec's "one-off" shape already; leaving them where
// they are keeps the existing demo working and keeps this change reversible.
type ChoreHandler struct {
	DB *database.DB
}

const choreColumns = `id, group_id, name, done_line, schedule_type, interval_days,
	fixed_weekdays, fixed_month_days, needed_by_time, created_by, created_at, updated_at`

// occurrenceColumns joins the chore's name and done-line in, so one board query
// returns rows that are renderable as they stand.
const occurrenceColumns = `o.id, o.chore_id, o.group_id, o.assigned_to, o.status,
	o.due_date, o.done_by, o.done_at, o.created_at, o.spawned_from, o.resume_after,
	o.passed_from, o.passed_at, o.covered_by, cov.username, c.name, c.done_line,
	o.passed_chain, o.pending_debts`

// occurrenceJoins go with occurrenceColumns. The coverer join is LEFT: almost
// every occurrence has no coverer, and an INNER join would quietly drop every
// row that was not handed back.
const occurrenceJoins = `JOIN chores c ON c.id = o.chore_id
	LEFT JOIN users cov ON cov.id = o.covered_by`

// undoWindow is how long after marking an occurrence done it can still be taken
// back, and only by the person who marked it. Anything older is history, and
// history is not editable from the board.
//
// The Android client greys the checkbox out on the same rule, but a rule that
// lives only in the client is not a rule — an old client, or a direct API call,
// would otherwise rewrite completions from any point in the past.
const undoWindow = 10 * time.Minute

// passUndoWindow is how long after passing a chore on it can be taken back.
//
// Much shorter than undoWindow, and for a reason: undoing a completion only
// rewrites your own history, while undoing a pass takes work off somebody
// else's board after they have been told it is theirs. Two minutes covers a
// mis-swipe noticed immediately, which is all this is for; a genuine change of
// mind belongs in a conversation, not in a silent reassignment.
const passUndoWindow = 2 * time.Minute

// Bounds on an interval chore's period, in days. Wide on purpose — these are
// here to catch a typo, not to curate the household's choices.
const (
	intervalDaysMin = 1
	intervalDaysMax = 365
)

// validScheduleTypes mirrors the CHECK constraint on chores.schedule_type.
var validScheduleTypes = map[string]bool{
	models.ScheduleInterval:  true,
	models.ScheduleFixedDate: true,
	models.ScheduleAsNeeded:  true,
	models.ScheduleOneOff:    true,
}

// ── Chore CRUD ──────────────────────────────────────────────────────────────

// CreateChore handles POST /groups/{group_id}/chores.
//
// Creating a chore also creates its first occurrence, assigned to whoever is
// first in the rotation. A chore with no occurrence would be invisible: the
// board shows occurrences, so a definition on its own would silently do nothing.
func (h *ChoreHandler) CreateChore(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	var req models.CreateChoreRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		jsonError(w, "name is required", http.StatusBadRequest)
		return
	}
	if len([]rune(req.DoneLine)) > models.DoneLineMaxLen {
		jsonError(w, fmt.Sprintf("done_line must be %d characters or fewer", models.DoneLineMaxLen), http.StatusBadRequest)
		return
	}
	if !validScheduleTypes[req.ScheduleType] {
		jsonError(w, "schedule_type must be interval, fixed_date, as_needed or one_off", http.StatusBadRequest)
		return
	}
	if err := validateSchedule(req.ScheduleType, req.IntervalDays, req.FixedWeekdays, req.FixedMonthDays); err != nil {
		jsonError(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.NeededByTime != nil {
		if err := validateClockTime(*req.NeededByTime); err != nil {
			jsonError(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	rotation, err := h.validateRotation(groupID, req.Rotation)
	if err != nil {
		jsonError(w, err.Error(), http.StatusBadRequest)
		return
	}

	// A one-off carries its own date; every other type derives one from the
	// schedule, and as-needed deliberately has none at all.
	var firstDue *time.Time
	if req.ScheduleType == models.ScheduleOneOff {
		if req.DueDate != nil {
			t, err := time.Parse(time.RFC3339, *req.DueDate)
			if err != nil {
				jsonError(w, "due_date must be RFC3339 format", http.StatusBadRequest)
				return
			}
			firstDue = &t
		}
	} else {
		firstDue = firstDueDate(req.ScheduleType, req.IntervalDays, req.FixedWeekdays, req.FixedMonthDays, req.NeededByTime, time.Now())
	}

	chore := models.Chore{
		ID:             uuid.New().String(),
		GroupID:        groupID,
		Name:           req.Name,
		DoneLine:       req.DoneLine,
		ScheduleType:   req.ScheduleType,
		IntervalDays:   req.IntervalDays,
		FixedWeekdays:  req.FixedWeekdays,
		FixedMonthDays: req.FixedMonthDays,
		NeededByTime:   req.NeededByTime,
		Rotation:       rotation,
		CreatedBy:      userID,
	}

	// The chore, its rotation and its first occurrence are one fact. A chore
	// that committed without its rotation would be a definition nobody can be
	// assigned from, and it could never spawn anything.
	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	if _, err := tx.Exec(
		`INSERT INTO chores (id, group_id, name, done_line, schedule_type, interval_days,
			fixed_weekdays, fixed_month_days, needed_by_time, created_by, created_at, updated_at)
		 VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`,
		chore.ID, chore.GroupID, chore.Name, chore.DoneLine, chore.ScheduleType,
		chore.IntervalDays, encodeInts(chore.FixedWeekdays), encodeInts(chore.FixedMonthDays),
		chore.NeededByTime, chore.CreatedBy,
	); err != nil {
		log.Printf("CreateChore: insert failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := replaceRotation(tx, chore.ID, rotation); err != nil {
		log.Printf("CreateChore: rotation insert failed for chore %s: %v", chore.ID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// The first turn goes to the top of the rotation, or to the first person
	// after them who is actually here. Handing a brand-new chore to somebody
	// who is away would leave it sitting untouched from the moment it existed.
	away, err := h.awayMembers(groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	firstAssignee := rotation[0]
	if away[firstAssignee] {
		firstAssignee = nextInRotation(rotation, rotation[0], away)
	}

	occ := models.Occurrence{
		ID:         uuid.New().String(),
		ChoreID:    chore.ID,
		GroupID:    groupID,
		AssignedTo: firstAssignee,
		Status:     models.OccurrenceOpen,
		DueDate:    firstDue,
		ChoreName:  chore.Name,
		DoneLine:   chore.DoneLine,
	}
	if err := insertOccurrence(tx, occ); err != nil {
		log.Printf("CreateChore: occurrence insert failed for chore %s: %v", chore.ID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Constraint 7: chore creation is a group-visible change, so it is
	// broadcast. Inside the transaction, so an event never describes a chore
	// that was rolled back.
	if err := recordActivity(tx, groupID, userID, EventChoreCreated, &chore.ID, chore.Name); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Read back so the response carries the database's timestamps rather than
	// this process's clock.
	created, err := h.loadChore(chore.ID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusCreated, created)
}

// ListChores handles GET /groups/{group_id}/chores — the definitions, with
// their rotation lists. The board itself reads occurrences, not this.
func (h *ChoreHandler) ListChores(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	rows, err := h.DB.Query(
		`SELECT `+choreColumns+` FROM chores WHERE group_id = ? ORDER BY created_at DESC`,
		groupID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	chores := []models.Chore{}
	ids := []string{}
	for rows.Next() {
		var c models.Chore
		if err := scanChore(rows, &c); err != nil {
			log.Printf("ListChores: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		chores = append(chores, c)
		ids = append(ids, c.ID)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ListChores: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// One query for every rotation rather than one per chore.
	rotations, err := h.loadRotations(ids)
	if err != nil {
		log.Printf("ListChores: rotation load failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	for i := range chores {
		chores[i].Rotation = rotations[chores[i].ID]
	}

	jsonResponse(w, http.StatusOK, chores)
}

// UpdateChore handles PATCH /groups/{group_id}/chores/{chore_id}.
//
// Open to every member: the spec replaces an approval flow with transparency,
// so any member may edit and the whole group sees a diff of what changed.
func (h *ChoreHandler) UpdateChore(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	choreID := r.PathValue("chore_id")
	userID := middleware.GetUserID(r)

	existing, err := h.loadChore(choreID)
	if err == sql.ErrNoRows || (existing != nil && existing.GroupID != groupID) {
		jsonError(w, "chore not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	var req models.UpdateChoreRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	setClauses := []string{}
	args := []any{}
	// changed is the diff the group sees. It is phrased in terms a housemate
	// would recognise ("weekly → every 3 days"), not column names.
	changed := []string{}

	if req.Name != nil {
		name := strings.TrimSpace(*req.Name)
		if name == "" {
			jsonError(w, "name cannot be empty", http.StatusBadRequest)
			return
		}
		if name != existing.Name {
			setClauses = append(setClauses, "name = ?")
			args = append(args, name)
			changed = append(changed, fmt.Sprintf("name: %s → %s", existing.Name, name))
		}
	}
	if req.DoneLine != nil {
		if len([]rune(*req.DoneLine)) > models.DoneLineMaxLen {
			jsonError(w, fmt.Sprintf("done_line must be %d characters or fewer", models.DoneLineMaxLen), http.StatusBadRequest)
			return
		}
		if *req.DoneLine != existing.DoneLine {
			setClauses = append(setClauses, "done_line = ?")
			args = append(args, *req.DoneLine)
			changed = append(changed, "what done means")
		}
	}

	// Schedule: the type as well as its parameters.
	newType := existing.ScheduleType
	newInterval := existing.IntervalDays
	newWeekdays := existing.FixedWeekdays
	newMonthDays := existing.FixedMonthDays
	scheduleTouched := false

	if req.ScheduleType != nil && *req.ScheduleType != existing.ScheduleType {
		// A one-off is a row in another table. Moving between it and a
		// rotating chore is a delete and an add, not an update, and pretending
		// otherwise here would leave the occurrence and the task shapes to be
		// reconciled by whoever read the code next.
		if *req.ScheduleType == models.ScheduleOneOff || existing.ScheduleType == models.ScheduleOneOff {
			jsonError(w, "a one-off cannot become a repeating chore, or the reverse", http.StatusBadRequest)
			return
		}
		switch *req.ScheduleType {
		case models.ScheduleInterval, models.ScheduleFixedDate, models.ScheduleAsNeeded:
		default:
			jsonError(w, "unknown schedule_type", http.StatusBadRequest)
			return
		}
		newType = *req.ScheduleType
		// The old type's parameters do not survive the move: an as-needed chore
		// has no interval, and a fixed-date one has no interval either. They
		// are cleared here and refilled below from whatever the request sent.
		newInterval, newWeekdays, newMonthDays = nil, nil, nil
		scheduleTouched = true
	}

	if req.IntervalDays != nil {
		if newType != models.ScheduleInterval {
			jsonError(w, "interval_days applies only to interval chores", http.StatusBadRequest)
			return
		}
		newInterval = req.IntervalDays
		scheduleTouched = true
	}
	if req.FixedWeekdays != nil || req.FixedMonthDays != nil {
		if newType != models.ScheduleFixedDate {
			jsonError(w, "fixed_weekdays and fixed_month_days apply only to fixed_date chores", http.StatusBadRequest)
			return
		}
		// Setting one clears the other: a fixed-date chore runs on weekdays or
		// on month days, never both.
		if req.FixedWeekdays != nil {
			newWeekdays, newMonthDays = req.FixedWeekdays, nil
		} else {
			newWeekdays, newMonthDays = nil, req.FixedMonthDays
		}
		scheduleTouched = true
	}
	if scheduleTouched {
		if err := validateSchedule(newType, newInterval, newWeekdays, newMonthDays); err != nil {
			jsonError(w, err.Error(), http.StatusBadRequest)
			return
		}
		setClauses = append(setClauses,
			"schedule_type = ?", "interval_days = ?", "fixed_weekdays = ?", "fixed_month_days = ?")
		args = append(args, newType, newInterval, encodeInts(newWeekdays), encodeInts(newMonthDays))
		changed = append(changed, fmt.Sprintf("schedule: %s → %s",
			describeSchedule(existing.ScheduleType, existing.IntervalDays, existing.FixedWeekdays, existing.FixedMonthDays),
			describeSchedule(newType, newInterval, newWeekdays, newMonthDays)))
	}

	if req.NeededByTime.Present {
		if req.NeededByTime.IsNull {
			setClauses = append(setClauses, "needed_by_time = NULL")
			changed = append(changed, "needed-by time cleared")
		} else {
			if err := validateClockTime(req.NeededByTime.Value); err != nil {
				jsonError(w, err.Error(), http.StatusBadRequest)
				return
			}
			setClauses = append(setClauses, "needed_by_time = ?")
			args = append(args, req.NeededByTime.Value)
			changed = append(changed, "needed by "+req.NeededByTime.Value)
		}
	}

	var newRotation []string
	if req.Rotation != nil {
		newRotation, err = h.validateRotation(groupID, req.Rotation)
		if err != nil {
			jsonError(w, err.Error(), http.StatusBadRequest)
			return
		}
		if !sameOrder(existing.Rotation, newRotation) {
			changed = append(changed, "rotation")
		} else {
			newRotation = nil // no actual change
		}
	}

	if len(setClauses) == 0 && newRotation == nil {
		jsonError(w, "no fields to update", http.StatusBadRequest)
		return
	}

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	if len(setClauses) > 0 {
		setClauses = append(setClauses, "updated_at = CURRENT_TIMESTAMP")
		args = append(args, choreID)
		if _, err := tx.Exec(
			"UPDATE chores SET "+strings.Join(setClauses, ", ")+" WHERE id = ?", args...,
		); err != nil {
			jsonError(w, "failed to update chore: "+err.Error(), http.StatusBadRequest)
			return
		}
	}
	if newRotation != nil {
		if err := replaceRotation(tx, choreID, newRotation); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		// Deliberately *not* reassigning the open occurrence. Rotation advances
		// on completion, never on an edit. Moving someone's standing chore off
		// their row because the list was reordered would be exactly the
		// "waiting it out" escape the spec closes, and a reordering is about
		// who comes *next*.
	}

	// What an edit does to the turn that is already on the board.
	//
	// The rules, in full, because this is the only place they exist:
	//
	//   - The assignee never changes because of an edit. Whose turn it is was
	//     decided by the rotation when the occurrence was spawned, and an edit
	//     is not a completion.
	//   - If the schedule changed, the open occurrence is re-dated under the
	//     new rule, counted from the last completion, or from the chore's
	//     creation if it has never been done. That is the same anchor the
	//     schedule itself uses, so "every 3 days" means the same thing whether
	//     it was set at creation or ten minutes ago.
	//   - Moving to as-needed clears the date. Moving away from as-needed sets
	//     one, from the same anchor.
	//   - A rotation reorder changes nothing about the current turn; it applies
	//     from the next spawn.
	//
	// A fixed-date chore whose anchor is old can land on a date that has
	// already passed. That is left alone rather than clamped: rollForwardFixedDates
	// already exists to walk a lapsed fixed date to the next one, keeping the
	// same assignee, and it runs on the next read of the board. Clamping here
	// would be a second rule doing the first one's job.
	if scheduleTouched {
		if err := redateOpenOccurrence(tx, choreID, newType, newInterval, newWeekdays, newMonthDays, neededByFor(&req, existing)); err != nil {
			log.Printf("UpdateChore: redate failed for chore %s: %v", choreID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
	}

	// The diff broadcast, per constraint 7 and F2's "Sara changed Kitchen:
	// weekly → every 3 days".
	detail := existing.Name + ": " + strings.Join(changed, ", ")
	if err := recordActivity(tx, groupID, userID, EventChoreUpdated, &choreID, detail); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	updated, err := h.loadChore(choreID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, updated)
}

// DeleteChore handles DELETE /groups/{group_id}/chores/{chore_id}. Occurrences
// and rotation rows go with it, by ON DELETE CASCADE.
func (h *ChoreHandler) DeleteChore(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	choreID := r.PathValue("chore_id")
	userID := middleware.GetUserID(r)

	var existingGroupID, name string
	err := h.DB.QueryRow(`SELECT group_id, name FROM chores WHERE id = ?`, choreID).
		Scan(&existingGroupID, &name)
	if err != nil || existingGroupID != groupID {
		jsonError(w, "chore not found", http.StatusNotFound)
		return
	}

	if _, err := h.DB.Exec(`DELETE FROM chores WHERE id = ?`, choreID); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := recordActivity(h.DB, groupID, userID, EventChoreDeleted, &choreID, name); err != nil {
		logActivityFailure(EventChoreDeleted, err)
	}

	w.WriteHeader(http.StatusNoContent)
}

// ── Occurrences: the board ──────────────────────────────────────────────────

// ListOccurrences handles GET /groups/{group_id}/occurrences — everything the
// board needs, in one query.
//
// Open occurrences come first and, within them, the oldest due date first: the
// board's question is "what still needs doing", and a chore that has been
// sitting for days is the most honest answer to it. Undated (as-needed) rows
// sort after dated ones rather than being treated as due at the epoch.
func (h *ChoreHandler) ListOccurrences(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	// A read that writes, deliberately. See rollForwardFixedDates: a failure
	// is logged rather than surfaced, because a board that renders with a
	// stale date is far better than a board that refuses to render.
	if err := h.rollForwardFixedDates(groupID); err != nil {
		log.Printf("ListOccurrences: fixed-date roll-forward failed for group %s: %v", groupID, err)
	}

	rows, err := h.DB.Query(`
		SELECT `+occurrenceColumns+`
		FROM occurrences o
		`+occurrenceJoins+`
		WHERE o.group_id = ?
		ORDER BY
			CASE o.status WHEN 'open' THEN 0 ELSE 1 END,
			o.due_date IS NULL,
			o.due_date ASC,
			o.created_at DESC
	`, groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	occurrences := []models.Occurrence{}
	for rows.Next() {
		var o models.Occurrence
		if err := scanOccurrence(rows, &o); err != nil {
			log.Printf("ListOccurrences: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		occurrences = append(occurrences, o)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ListOccurrences: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Tell the holder, and only the holder, when a chore has come back to them
	// because everybody available had passed it. Two extra reads on a board
	// load, which is the price of not storing a fact that the chain can change
	// out from under.
	markChoresNeedingADate(h, groupID, middleware.GetUserID(r), occurrences)

	jsonResponse(w, http.StatusOK, occurrences)
}

// UpdateOccurrence handles PATCH /groups/{group_id}/occurrences/{occurrence_id}
// — marking done, and undoing that.
//
// Any member may mark any occurrence done: "I just did it myself" is the common
// reality, and the board records it rather than fighting it. done_by keeps who
// actually did it, which is what makes a cover visible without anyone saying so.
//
// Undo is narrower: only the person who marked it, and only inside undoWindow.
//
// Completing also spawns the chore's next occurrence and advances the rotation
// — see spawnNext. Undoing removes that spawned occurrence again, so ten
// seconds of misclick cannot leave a phantom turn behind.
//
// The whole thing runs in one transaction: a completion that recorded itself
// but failed to spawn would silently end a chore's rotation, and the chore
// would just never come round again.
func (h *ChoreHandler) UpdateOccurrence(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	occurrenceID := r.PathValue("occurrence_id")
	userID := middleware.GetUserID(r)

	var req models.UpdateOccurrenceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Status != models.OccurrenceOpen && req.Status != models.OccurrenceDone {
		jsonError(w, "status must be open or done", http.StatusBadRequest)
		return
	}

	current, err := h.loadOccurrence(occurrenceID)
	if err == sql.ErrNoRows || (current != nil && current.GroupID != groupID) {
		jsonError(w, "occurrence not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if req.Status == current.Status {
		jsonError(w, "occurrence is already "+current.Status, http.StatusConflict)
		return
	}

	// Undo's two conditions are checked before opening the transaction, so a
	// refusal costs nothing and the error is the same either way.
	if req.Status == models.OccurrenceOpen {
		if current.DoneBy == nil || *current.DoneBy != userID {
			jsonError(w, "only the person who marked this done can undo it", http.StatusForbidden)
			return
		}
		if current.DoneAt == nil || time.Since(*current.DoneAt) > undoWindow {
			jsonError(w, "the undo window has passed", http.StatusForbidden)
			return
		}
	}

	chore, err := h.loadChore(current.ChoreID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var eventType string
	if req.Status == models.OccurrenceDone {
		completedAt := time.Now()
		if _, err := tx.Exec(
			`UPDATE occurrences SET status = 'done', done_by = ?, done_at = ? WHERE id = ?`,
			userID, completedAt.UTC(), occurrenceID,
		); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		away, err := h.awayMembers(groupID)
		if err != nil {
			log.Printf("UpdateOccurrence: away lookup failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		if err := spawnNext(tx, chore, current, userID, completedAt, away); err != nil {
			log.Printf("UpdateOccurrence: spawn failed for chore %s: %v", current.ChoreID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		eventType = EventOccurrenceDone
	} else {
		// Take the spawned occurrence away with the completion that created it.
		// Only if it is still open: if someone has already completed the next
		// turn, deleting it would erase their work to undo yours.
		if _, err := tx.Exec(
			`DELETE FROM occurrences WHERE spawned_from = ? AND status = 'open'`,
			occurrenceID,
		); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		if _, err := tx.Exec(
			`UPDATE occurrences SET status = 'open', done_by = NULL, done_at = NULL WHERE id = ?`,
			occurrenceID,
		); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		eventType = EventOccurrenceReopened
	}

	if err := recordActivity(tx, groupID, userID, eventType, &occurrenceID, current.ChoreName); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	updated, err := h.loadOccurrence(occurrenceID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, updated)
}

// rollForwardFixedDates moves an open fixed-date occurrence whose date has
// passed on to the next date in its schedule, **keeping the same assignee**.
//
// This is the spec's "a missed date rolls into the next one, same assignee —
// you keep the chore until you've actually done it once". The bin collection
// you missed on Tuesday becomes next Tuesday's, still yours: the date the world
// set has moved on, but the turn has not, because a turn only moves on when the
// chore is actually done.
//
// Two things it must never do, both of which would break the turn rule:
// reassign, or mark anything done. It only ever writes due_date.
//
// It runs on read rather than from a scheduler because there is no scheduler in
// this project, and a chore whose date rolls only when someone looks at the
// board is indistinguishable — from the board — from one that rolled at
// midnight. It is idempotent: a second call finds nothing past due.
//
// Chosen consequence: a fixed-date chore therefore never triggers the client's
// 48-hour "still waiting" nudge, because this re-dates it the moment it lapses
// and the nudge only fires on an occurrence that is open past its date. Their
// pressure is the weekly DUE_SOON instead. See ReminderSchedule.stillWaitingFor
// on the client, which carries the same note.
func (h *ChoreHandler) rollForwardFixedDates(groupID string) error {
	now := time.Now()

	rows, err := h.DB.Query(`
		SELECT o.id, c.fixed_weekdays, c.fixed_month_days, c.needed_by_time
		FROM occurrences o
		JOIN chores c ON c.id = o.chore_id
		WHERE o.group_id = ?
		  AND o.status = 'open'
		  AND c.schedule_type = 'fixed_date'
		  AND o.due_date IS NOT NULL
		  AND o.due_date < ?`,
		groupID, now.UTC(),
	)
	if err != nil {
		return err
	}

	type update struct {
		id  string
		due time.Time
	}
	pending := []update{}

	for rows.Next() {
		var (
			id                  string
			weekdays, monthDays sql.NullString
			neededBy            *string
		)
		if err := rows.Scan(&id, &weekdays, &monthDays, &neededBy); err != nil {
			rows.Close()
			return err
		}
		next := nextFixedDate(decodeInts(weekdays), decodeInts(monthDays), neededBy, now)
		if next == nil {
			continue
		}
		pending = append(pending, update{id: id, due: *next})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()

	for _, u := range pending {
		if _, err := h.DB.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, u.due, u.id); err != nil {
			return err
		}
	}
	return nil
}

// PassOccurrence handles POST /groups/{group_id}/occurrences/{occurrence_id}/pass
// — F5's busy pass, one of the only two ways an assigned chore leaves you
// without being done.
//
// It moves the chore to the next person in the rotation and nothing else: no
// approval, no reason, no cap, no penalty. That is deliberate. A pass that cost
// something would be a pass people avoid using honestly, and the whole point is
// to replace "I can't this week, sorry, I know it's my turn…" with a state
// change the household can simply see.
//
// What it does *not* do is delete the turn. `passed_from` keeps the debt with
// whoever passed, so when the chore is finally done the next one comes back to
// them — see nextTurn. Declaring busy defers your turn; it never cancels it.
//
// No activity event is written. The spec allows group-wide broadcasts only for
// membership and chore edits, and gives passes exactly one notification: a
// private one to the receiver. A feed line saying "demo passed Kitchen to mate"
// would be a group announcement that someone declined — which is the social
// pressure this feature exists to remove. The board showing the new name is the
// intended mechanism, and that is enough.
func (h *ChoreHandler) PassOccurrence(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	occurrenceID := r.PathValue("occurrence_id")
	userID := middleware.GetUserID(r)

	current, err := h.loadOccurrence(occurrenceID)
	if err == sql.ErrNoRows || (current != nil && current.GroupID != groupID) {
		jsonError(w, "occurrence not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// "On any open occurrence of yours." Passing something already done means
	// nothing, and passing somebody else's turn would be handing out work.
	if current.Status != models.OccurrenceOpen {
		jsonError(w, "only an open occurrence can be passed", http.StatusConflict)
		return
	}
	if current.AssignedTo != userID {
		jsonError(w, "only the person it is assigned to can pass it", http.StatusForbidden)
		return
	}

	chore, err := h.loadChore(current.ChoreID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	away, err := h.awayMembers(groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	// The round closed already: it is back with whoever asked first and there is
	// nobody who has not declined it. Asking again would lap the household, so
	// say what is left to do instead of quietly doing nothing.
	if everyoneHasPassed(chore.Rotation, current.PassedChain, away) {
		jsonError(w,
			"everyone has passed this one, so there is nobody to hand it to. Pick a day for it instead.",
			http.StatusConflict)
		return
	}

	chain := appendOnce(current.PassedChain, userID)

	// Everyone available has now passed it, so there is nobody to hand it to
	// and circling it again would only lap the household. It goes back to
	// whoever asked first, with the date it would next have come round on
	// anyway, and they are the one to bring that forward.
	//
	// Their pass still counts: they were holding the chore when they passed
	// it, so they still owe a turn, exactly like everybody else in the chain.
	if everyoneHasPassed(chore.Rotation, chain, away) {
		h.closeTheRound(w, chore, current, chain)
		return
	}

	receiver := nextCoverer(chore.Rotation, current, userID, away)
	if receiver == current.AssignedTo || receiver == userID {
		// Either a rotation of one, or everyone else is away. Both mean there
		// is nobody to hand it to, and saying so is better than silently doing
		// nothing and looking broken.
		jsonError(w, "there is nobody else available in this chore's rotation", http.StatusConflict)
		return
	}

	// "If it was already due, the new assignee's due date becomes tomorrow
	// (earliest convenience); otherwise it keeps its date." Receiving something
	// that is already late with today's deadline attached would make a favour
	// feel like a penalty.
	now := time.Now()
	dueDate := current.DueDate
	if dueDate != nil && dueDate.Before(now) {
		tomorrow := atClockTime(now.AddDate(0, 0, 1), chore.NeededByTime)
		dueDate = &tomorrow
	}

	// Every passer owes a turn, and they are repaid in the order they passed.
	//
	// The chain used to keep only the first passer, on the reasoning that
	// passing something on declines a favour rather than a duty. The turn-rule
	// document overrides that: B was holding the chore when B passed it, so B
	// skipped too, and owes one back.
	//
	// appendOnce, not append: somebody may pass the same occurrence again once
	// it has come round to them, and that is allowed, but it does not buy them
	// a second debt. Recording every pass event would also be a skip counter,
	// which Part 0 forbids outright.
	passedFrom := &chain[0]

	// due_before_pass remembers the date this pass is about to overwrite, so
	// UndoPass can put it back exactly.
	//
	// Without it an undo would be a way to launder lateness: pass an overdue
	// chore, take it straight back, and the rule above has quietly moved its
	// deadline to tomorrow. COALESCE so a chain of passes keeps the *first*
	// original rather than each pass overwriting the last, since the debt and
	// therefore the right to undo belong to whoever passed it first.
	if _, err := h.DB.Exec(
		`UPDATE occurrences
		 SET assigned_to = ?, due_date = ?, passed_from = ?, passed_at = ?,
		     passed_chain = ?, due_before_pass = COALESCE(due_before_pass, ?)
		 WHERE id = ?`,
		receiver, dueDate, passedFrom, now.UTC(), encodeIDs(chain),
		current.DueDate, occurrenceID,
	); err != nil {
		log.Printf("PassOccurrence: update failed for %s: %v", occurrenceID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	updated, err := h.loadOccurrence(occurrenceID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, updated)
}

// closeTheRound hands a chore nobody can take back to whoever asked first.
//
// The date it carries is the one the chore would next have come round on, which
// is both a sensible default and the latest the household should wait: a busy
// round must not push a chore past its own rhythm. SetOccurrenceDueDate lets
// the holder bring it forward, and if nobody does, this date stands and the
// chore stands with it.
//
// An as-needed chore has no next date, so there is nothing to default to and
// nothing to bound. It simply comes back and waits, which is what an as-needed
// chore does anyway.
func (h *ChoreHandler) closeTheRound(
	w http.ResponseWriter,
	chore *models.Chore,
	current *models.Occurrence,
	chain []string,
) {
	first := chain[0]
	dueDate := current.DueDate
	if bound := nextDueDate(chore, current, time.Now()); bound != nil {
		dueDate = bound
	}

	if _, err := h.DB.Exec(
		`UPDATE occurrences
		 SET assigned_to = ?, due_date = ?, passed_from = ?, passed_at = ?,
		     passed_chain = ?, due_before_pass = COALESCE(due_before_pass, ?)
		 WHERE id = ?`,
		first, dueDate, first, time.Now().UTC(), encodeIDs(chain),
		current.DueDate, current.ID,
	); err != nil {
		log.Printf("closeTheRound: update failed for %s: %v", current.ID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	updated, err := h.loadOccurrence(current.ID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	// Not flagged here. The person who has to act is the one it landed on, and
	// that is never the person who just passed it: whoever closes a round is
	// by definition not the one who opened it. They see it on their board,
	// where ListOccurrences sets the flag for its own reader.
	jsonResponse(w, http.StatusOK, updated)
}

// SetOccurrenceDueDate handles PUT /groups/{group_id}/occurrences/{occurrence_id}/due-date
//
// The one place a chore's own date can be moved by hand, and only in one
// direction: earlier. It exists for the case where a whole household was busy
// at once, the chore came back to whoever asked first, and they are picking the
// day it will actually happen.
//
// Bounded by the date the occurrence already carries, which closeTheRound set
// to the chore's next scheduled date. Letting it move later would turn "we were
// all busy" into a way to postpone a chore indefinitely, one round at a time.
func (h *ChoreHandler) SetOccurrenceDueDate(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	occurrenceID := r.PathValue("occurrence_id")
	userID := middleware.GetUserID(r)

	var req struct {
		DueDate *time.Time `json:"due_date"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.DueDate == nil {
		jsonError(w, "due_date is required", http.StatusBadRequest)
		return
	}

	current, err := h.loadOccurrence(occurrenceID)
	if err == sql.ErrNoRows || (current != nil && current.GroupID != groupID) {
		jsonError(w, "occurrence not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if current.Status != models.OccurrenceOpen {
		jsonError(w, "only an open occurrence has a date to set", http.StatusConflict)
		return
	}
	if current.AssignedTo != userID {
		jsonError(w, "only the person holding it can set the day", http.StatusForbidden)
		return
	}
	if current.DueDate == nil {
		jsonError(w, "this chore has no schedule to bring forward", http.StatusConflict)
		return
	}
	if req.DueDate.After(*current.DueDate) {
		jsonError(w, "the day can be brought forward but not put back", http.StatusBadRequest)
		return
	}

	if _, err := h.DB.Exec(
		`UPDATE occurrences SET due_date = ? WHERE id = ?`, req.DueDate.UTC(), occurrenceID,
	); err != nil {
		log.Printf("SetOccurrenceDueDate: update failed for %s: %v", occurrenceID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	updated, err := h.loadOccurrence(occurrenceID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, updated)
}

// UndoPass handles DELETE /groups/{group_id}/occurrences/{occurrence_id}/pass
//
// Takes back a pass, for the case the pass was a mistake: the swipe caught the
// wrong row, or the wrong finger. It is not a way to change your mind later,
// which is why the window is [passUndoWindow] and not [undoWindow].
//
// Narrow on purpose:
//
//   - Only the person whose pass it was, which is the **last** link in the
//     chain. Not the receiver: being handed a chore is not consent to hand it
//     back, and if it were, "pass" and "refuse" would be the same button. And
//     not an earlier passer either — if A passed to B and B passed on to C,
//     A undoing would strand B with a chore B has already declined.
//   - Only while it is still open. Undoing a pass whose receiver has already
//     done the chore would take away work that has actually happened.
//   - Only inside the window, measured from `passed_at` on the server. The
//     snackbar's own timer is not evidence: a client can be slow, paused, or
//     lying.
//
// The due date is restored from `due_before_pass` rather than left as the pass
// set it. See the comment where that column is written.
func (h *ChoreHandler) UndoPass(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	occurrenceID := r.PathValue("occurrence_id")
	userID := middleware.GetUserID(r)

	current, err := h.loadOccurrence(occurrenceID)
	if err == sql.ErrNoRows || (current != nil && current.GroupID != groupID) {
		jsonError(w, "occurrence not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	chain := current.PassedChain
	if len(chain) == 0 {
		jsonError(w, "this occurrence has not been passed", http.StatusConflict)
		return
	}
	if chain[len(chain)-1] != userID {
		jsonError(w, "only the person who passed it can take it back", http.StatusForbidden)
		return
	}
	if current.Status != models.OccurrenceOpen {
		jsonError(w, "it has already been done, so there is nothing to take back", http.StatusConflict)
		return
	}
	if current.PassedAt == nil || time.Since(*current.PassedAt) > passUndoWindow {
		jsonError(w, "too late to take this pass back", http.StatusConflict)
		return
	}

	// Read plainly, not through COALESCE or MAX: go-sqlite3 decides whether to
	// parse a DATETIME from the column's *declared* type, and a wrapping
	// function throws that away and hands back a raw string. This has bitten
	// twice in this file's history.
	var dueBefore sql.NullTime
	if err := h.DB.QueryRow(
		`SELECT due_before_pass FROM occurrences WHERE id = ?`, occurrenceID,
	).Scan(&dueBefore); err != nil {
		log.Printf("UndoPass: reading due_before_pass for %s: %v", occurrenceID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	remaining := chain[:len(chain)-1]

	// The date is only put back when the chain empties, because due_before_pass
	// holds the date before the *first* pass. Undoing a later link in a chain
	// leaves an earlier pass standing, and that pass's date should stand with
	// it. In practice there is nothing to put back in that case anyway: the
	// first pass already moved an overdue chore to tomorrow, so by the second
	// pass it was no longer overdue and no shift happened.
	//
	// A chore with no date at all (as-needed) has nothing to restore, and the
	// pass will not have invented one either.
	// passed_from and passed_at likewise stay while an earlier pass stands: the
	// occurrence is still a passed one, and passed_at is what the undo window
	// and the receiver's reminder are measured from.
	restored := current.DueDate
	var passedFrom, passedAt, dueBeforeAfter any
	if len(remaining) > 0 {
		passedFrom = remaining[0]
		passedAt = current.PassedAt
		if dueBefore.Valid {
			dueBeforeAfter = dueBefore.Time
		}
	} else if dueBefore.Valid {
		restored = &dueBefore.Time
	}

	if _, err := h.DB.Exec(
		`UPDATE occurrences
		 SET assigned_to = ?, due_date = ?, passed_from = ?, passed_at = ?,
		     passed_chain = ?, due_before_pass = ?
		 WHERE id = ?`,
		userID, restored, passedFrom, passedAt, encodeIDs(remaining),
		dueBeforeAfter, occurrenceID,
	); err != nil {
		log.Printf("UndoPass: update failed for %s: %v", occurrenceID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// No activity event, for the same reason the pass itself writes none:
	// constraint 7 keeps a pass between the two people involved, and an undo
	// is part of the same private exchange.
	updated, err := h.loadOccurrence(occurrenceID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, updated)
}

// spawnNext creates the occurrence that follows a completed one.
//
// Rotation advances **on completion, never on the calendar** — this function is
// the only place a turn moves on. An occurrence left undone simply stays where
// it is, which is what makes a turn impossible to wait out.
//
// A one-off has nothing to spawn: it is the whole of its own schedule.
//
// Who gets the next turn is decided by nextTurn — the unified turn rule.
func spawnNext(
	tx *sql.Tx,
	chore *models.Chore,
	completed *models.Occurrence,
	doerID string,
	completedAt time.Time,
	away map[string]bool,
) error {
	if chore.ScheduleType == models.ScheduleOneOff {
		return nil
	}
	if len(chore.Rotation) == 0 {
		// Can't happen through the API — a rotation must hold at least one
		// member — but a chore with nobody in it has no one to assign to, and
		// silently dropping the chore is better than a broken row.
		return nil
	}

	assignee, resumeAfter, remaining, coveredBy := nextTurn(chore.Rotation, completed, doerID, away)

	next := models.Occurrence{
		ID:           uuid.New().String(),
		ChoreID:      chore.ID,
		GroupID:      chore.GroupID,
		AssignedTo:   assignee,
		Status:       models.OccurrenceOpen,
		DueDate:      nextDueDate(chore, completed, completedAt),
		SpawnedFrom:  &completed.ID,
		ResumeAfter:  resumeAfter,
		CoveredBy:    coveredBy,
		PendingDebts: remaining,
	}
	return insertOccurrence(tx, next)
}

// owedBy reports whose turn an occurrence actually is.
//
// The first person to pass it, if anyone did, and otherwise whoever holds it. A
// pass moves the name on the board without moving the obligation.
func owedBy(o *models.Occurrence) string {
	if len(o.PassedChain) > 0 {
		return o.PassedChain[0]
	}
	return o.AssignedTo
}

// debtsAfter is the queue of turns owed on this chore once `completed` has been
// done by doerID, oldest debt first.
//
// Three sources, in the order they were incurred: debts carried in from earlier
// occurrences, then everyone who passed this one, then the person left holding
// it if somebody else did it for them.
func debtsAfter(completed *models.Occurrence, doerID string, rotation []string) []string {
	queue := []string{}
	for _, id := range completed.PendingDebts {
		queue = appendOnce(queue, id)
	}
	for _, id := range completed.PassedChain {
		queue = appendOnce(queue, id)
	}
	if doerID != completed.AssignedTo {
		queue = appendOnce(queue, completed.AssignedTo)
	}

	out := []string{}
	for _, id := range queue {
		// Whoever actually did it owes nothing for it. This matters when a
		// chore has come back round to somebody who passed it earlier and they
		// then do it: the pass is settled by the doing.
		if id == doerID {
			continue
		}
		// 3.3 and 3.4: a debt attaches to a *person*, not a position, and it
		// dies with their place in this chore's rotation. Somebody edited out
		// of the rotation, or gone from the household, owes nothing — and
		// nothing is said about the turn they did not take.
		if !contains(rotation, id) {
			continue
		}
		out = append(out, id)
	}
	return out
}

// resumeFrom picks the point the ordinary rotation continues from.
//
// 3.1 and 3.2: the resume point is whoever covered, and they may not be in this
// chore's rotation at all — any member can mark any chore done, including
// someone outside its rotation, and a coverer can leave the household
// afterwards. "After them" then means nothing, so it falls back to the position
// of whoever owed the turn.
func resumeFrom(rotation []string, resume *string, owed string) string {
	if resume != nil && contains(rotation, *resume) {
		return *resume
	}
	return owed
}

// nextTurn is **the unified turn rule**, and it is the heart of F2.
//
// One rule covers three situations the spec treats as the same thing — a
// housemate quietly doing someone else's chore, a busy pass (F5), and the
// overflowing bin somebody else finally emptied. All three are a *cover*: the
// turn counted as the doer's, and whoever owed it still owes it.
//
// What comes back is the next assignee, the point the rotation resumes from
// once every debt is settled, the debts still waiting, and who covered.
//
// Worked example, rotation [ann, bo, cass], turn 1 on ann, bo does it:
//
//	turn 1  ann   ← bo covered
//	turn 2  ann   ← handed back; resume_after = bo
//	turn 3  cass  ← after bo, not after ann
//	turn 4  ann   ← normal rotation from here on
//
// ann and bo have simply swapped places for one cycle. Without resume_after,
// turn 3 would go to bo, who has just done two in a row.
//
// And with a chain, rotation [A, B, C], A passes to B, B passes on to C, C does
// it: two debts exist and both are honoured before the order resumes.
//
//	occ 1  C      ← A passed, then B passed; C did it
//	occ 2  A      ← first skipper first; B still queued
//	occ 3  B      ← the second skipper
//	occ 4  A      ← queue empty, so resume after the doer C
func nextTurn(
	rotation []string,
	completed *models.Occurrence,
	doerID string,
	away map[string]bool,
) (assignee string, resumeAfter *string, remaining []string, coveredBy *string) {
	owed := owedBy(completed)

	// Where the rotation picks up, and equally who covered: the same person,
	// because a cover both earns the doer their turn and is the point the order
	// resumes from. Carried forward untouched while debts are still being paid,
	// so occ 3 above still resumes after C rather than after B.
	resume := completed.ResumeAfter
	if doerID != owed {
		doer := doerID
		resume = &doer
	}

	queue := debtsAfter(completed, doerID, rotation)
	if len(queue) > 0 {
		for i, id := range queue {
			if away[id] {
				continue
			}
			rest := append(append([]string(nil), queue[:i]...), queue[i+1:]...)
			// The coverer travels with the debt, so the board can still say why
			// a chore has come back to this person several occurrences later.
			return id, resume, rest, resume
		}
		// 1.5: everyone who owes a turn is away. The debts wait — away is not a
		// way to discharge one — and the ordinary rotation carries on without
		// them, rather than parking a live chore on the row of somebody who is
		// not in the house. Constraint 8 lifts an away member out of every
		// rotation, and a debt is no exception to that.
		return nextInRotation(rotation, resumeFrom(rotation, resume, owed), away), resume, queue, nil
	}

	return nextInRotation(rotation, resumeFrom(rotation, resume, owed), away), nil, nil, nil
}

// everyoneHasPassed reports whether there is nobody left to hand a chore to.
//
// True when every member of the rotation who is actually available has already
// passed this occurrence. Away members are not counted: they are out of the
// rotation entirely, so a household of three with one away is a household of
// two for this purpose.
//
// A rotation of one is never "everyone busy": there was never anybody to pass
// to, which is a different refusal and already has its own message.
func everyoneHasPassed(rotation []string, chain []string, away map[string]bool) bool {
	available := 0
	for _, id := range rotation {
		if away[id] {
			continue
		}
		available++
		if !contains(chain, id) {
			return false
		}
	}
	return available > 1
}

// markChoresNeedingADate sets NeedsDate on the caller's own open rows where
// everybody available has already passed the chore.
//
// Failures are silent: this decorates a board that has already loaded, and an
// error here should cost a hint rather than the whole screen.
func markChoresNeedingADate(h *ChoreHandler, groupID, userID string, occurrences []models.Occurrence) {
	if userID == "" {
		return
	}
	choreIDs := []string{}
	for i := range occurrences {
		o := &occurrences[i]
		if o.Status == models.OccurrenceOpen && o.AssignedTo == userID && len(o.PassedChain) > 0 {
			choreIDs = append(choreIDs, o.ChoreID)
		}
	}
	if len(choreIDs) == 0 {
		return
	}

	away, err := h.awayMembers(groupID)
	if err != nil {
		return
	}
	rotations, err := h.loadRotations(choreIDs)
	if err != nil {
		return
	}
	for i := range occurrences {
		o := &occurrences[i]
		if o.Status != models.OccurrenceOpen || o.AssignedTo != userID {
			continue
		}
		if everyoneHasPassed(rotations[o.ChoreID], o.PassedChain, away) {
			o.NeedsDate = true
		}
	}
}

// nextCoverer picks who a busy pass hands the chore to.
//
// Counted on from the last coverer when there has been one, not from the person
// passing. Counting from the passer lands on the same neighbour every single
// time, so one unlucky person absorbs every skip by a serial passer — case 1.3,
// where covering went B, B, B instead of moving round the household. The last
// coverer is where the rotation has actually got to.
//
// The passer is stepped over: handing a chore back to yourself is not a pass.
func nextCoverer(rotation []string, occ *models.Occurrence, passer string, away map[string]bool) string {
	from := occ.AssignedTo
	if occ.ResumeAfter != nil && contains(rotation, *occ.ResumeAfter) {
		from = *occ.ResumeAfter
	}
	candidate := nextInRotation(rotation, from, away)
	if candidate == passer {
		candidate = nextInRotation(rotation, passer, away)
	}
	return candidate
}

// nextInRotation returns whoever's turn follows the current holder's, skipping
// anyone who is away.
//
// Away members are lifted out of every rotation for the duration and re-enter
// at the same position on return — which needs no bookkeeping at all, because
// the order never changes; assignment simply steps over them. And no turns are
// owed back: unlike a busy pass, being away is not a debt.
//
// If the holder is no longer in the rotation — edited out, or left the group —
// the turn starts again at the top rather than being lost.
//
// If *everyone* is away, the turn goes to whoever it would have gone to anyway.
// A chore has to have exactly one name on it, and the least surprising name is
// the one whose turn it actually is; it waits on their row until they are back.
func nextInRotation(rotation []string, current string, away map[string]bool) string {
	start := 0
	for i, userID := range rotation {
		if userID == current {
			start = i + 1
			break
		}
	}

	for step := 0; step < len(rotation); step++ {
		candidate := rotation[(start+step)%len(rotation)]
		if !away[candidate] {
			return candidate
		}
	}
	// Nobody is available. Fall back to the naive next.
	return rotation[start%len(rotation)]
}

// awayMembers is the set of members currently lifted out of this group's
// rotations.
//
// A finished away period needs no cleanup: away_until simply stops being in the
// future, and the row stops matching. People come back on their own.
func (h *ChoreHandler) awayMembers(groupID string) (map[string]bool, error) {
	now := time.Now().UTC()
	rows, err := h.DB.Query(`
		SELECT DISTINCT user_id FROM away_periods
		WHERE group_id = ?
		  AND ended_at IS NULL
		  AND (ends_at IS NULL OR ends_at > ?)`,
		groupID, now,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	away := map[string]bool{}
	for rows.Next() {
		var userID string
		if err := rows.Scan(&userID); err != nil {
			return nil, err
		}
		away[userID] = true
	}
	return away, rows.Err()
}

// nextDueDate is when the chore is next due, given when it was just completed.
//
// Interval chores count from the **completion**, not from the old due date:
// the bathroom needs cleaning four days after it was last cleaned, not four
// days after it was supposed to be. Doing it late moves the whole schedule with
// reality, which is the point.
//
// Fixed-date chores take the next date off the calendar, because the world sets
// those. As-needed chores have no date at all.
//
// The fixed-date scan starts from **the slot just satisfied, or now, whichever
// is later** — not simply from the completion. Starting from the completion is
// wrong whenever a chore is done early: put the bins out on Wednesday for a
// "Fridays" chore and the soonest Friday after Wednesday is the very Friday you
// just dealt with, so the chore reappears due the same day and has to be done
// twice. Anchoring to the completed occurrence's own due date moves it to the
// following Friday, which is what "Fridays" means.
//
// Taking the later of the two keeps the late case right as well: a Friday chore
// finally done a fortnight on must land on the *next* Friday, not on one that
// has already gone. Both ends matter, so neither anchor works alone.
func nextDueDate(chore *models.Chore, completed *models.Occurrence, completedAt time.Time) *time.Time {
	switch chore.ScheduleType {
	case models.ScheduleInterval:
		if chore.IntervalDays == nil {
			return nil
		}
		due := atClockTime(completedAt.AddDate(0, 0, *chore.IntervalDays), chore.NeededByTime)
		return &due
	case models.ScheduleFixedDate:
		from := completedAt
		if completed != nil && completed.DueDate != nil {
			// Into the completion's location first: times come back from
			// SQLite in UTC (the DSN sets no _loc), and a weekday read in the
			// wrong zone is a weekday off by one either side of midnight.
			slot := completed.DueDate.In(completedAt.Location())
			if slot.After(from) {
				from = slot
			}
		}
		return nextFixedDate(chore.FixedWeekdays, chore.FixedMonthDays, chore.NeededByTime, from)
	default:
		return nil
	}
}

// neededByFor resolves the needed-by time an edit leaves the chore with:
// whatever the patch set, or what it already had when the patch is silent.
func neededByFor(req *models.UpdateChoreRequest, existing *models.Chore) *string {
	if !req.NeededByTime.Present {
		return existing.NeededByTime
	}
	if req.NeededByTime.IsNull {
		return nil
	}
	v := req.NeededByTime.Value
	return &v
}

// redateOpenOccurrence re-dates a chore's open occurrence under the schedule as
// it now stands, without touching who it belongs to.
//
// The anchor is the last completion of this chore, or the chore's creation if
// it has never been completed — the same anchor the schedule uses everywhere
// else, so an interval means the same thing whether it was set at creation or
// changed a minute ago.
//
// Writes only due_date. It must never touch assigned_to or status: an edit is
// not a completion, and the turn rule is the only thing allowed to move a turn.
func redateOpenOccurrence(
	tx *sql.Tx,
	choreID, scheduleType string,
	intervalDays *int,
	weekdays, monthDays []int,
	neededBy *string,
) error {
	var occurrenceID string
	err := tx.QueryRow(
		`SELECT id FROM occurrences WHERE chore_id = ? AND status = 'open' ORDER BY rowid DESC LIMIT 1`,
		choreID,
	).Scan(&occurrenceID)
	if err == sql.ErrNoRows {
		return nil // nothing on the board to re-date
	}
	if err != nil {
		return err
	}

	// Two queries rather than one COALESCE, and deliberately so: go-sqlite3
	// parses a DATETIME column into time.Time from the column's *declared*
	// type, and wrapping it in COALESCE or MAX() throws that away. The value
	// then comes back as the raw string and the scan fails. Selecting each
	// column on its own keeps the type.
	var anchor time.Time
	err = tx.QueryRow(
		`SELECT done_at FROM occurrences
		 WHERE chore_id = ? AND status = 'done' AND done_at IS NOT NULL
		 ORDER BY done_at DESC LIMIT 1`,
		choreID,
	).Scan(&anchor)
	if err == sql.ErrNoRows {
		err = tx.QueryRow(`SELECT created_at FROM chores WHERE id = ?`, choreID).Scan(&anchor)
	}
	if err != nil {
		return err
	}

	due := firstDueDate(scheduleType, intervalDays, weekdays, monthDays, neededBy, anchor.Local())
	_, err = tx.Exec(`UPDATE occurrences SET due_date = ? WHERE id = ?`, due, occurrenceID)
	return err
}

// ── Loading helpers ─────────────────────────────────────────────────────────

func scanChore(s scanner, c *models.Chore) error {
	var weekdays, monthDays sql.NullString
	if err := s.Scan(
		&c.ID, &c.GroupID, &c.Name, &c.DoneLine, &c.ScheduleType, &c.IntervalDays,
		&weekdays, &monthDays, &c.NeededByTime, &c.CreatedBy, &c.CreatedAt, &c.UpdatedAt,
	); err != nil {
		return err
	}
	c.FixedWeekdays = decodeInts(weekdays)
	c.FixedMonthDays = decodeInts(monthDays)
	return nil
}

func scanOccurrence(s scanner, o *models.Occurrence) error {
	var chain, debts sql.NullString
	if err := s.Scan(
		&o.ID, &o.ChoreID, &o.GroupID, &o.AssignedTo, &o.Status,
		&o.DueDate, &o.DoneBy, &o.DoneAt, &o.CreatedAt, &o.SpawnedFrom, &o.ResumeAfter,
		&o.PassedFrom, &o.PassedAt, &o.CoveredBy, &o.CoveredByName, &o.ChoreName, &o.DoneLine,
		&chain, &debts,
	); err != nil {
		return err
	}
	o.PassedChain = decodeIDs(chain)
	o.PendingDebts = decodeIDs(debts)

	// The migration, done on read rather than as a backfill: a row written
	// before passed_chain existed carries only passed_from, which describes a
	// chain of exactly one.
	if len(o.PassedChain) == 0 && o.PassedFrom != nil {
		o.PassedChain = []string{*o.PassedFrom}
	}
	return nil
}

func (h *ChoreHandler) loadChore(choreID string) (*models.Chore, error) {
	var c models.Chore
	if err := scanChore(
		h.DB.QueryRow(`SELECT `+choreColumns+` FROM chores WHERE id = ?`, choreID), &c,
	); err != nil {
		return nil, err
	}
	rotations, err := h.loadRotations([]string{choreID})
	if err != nil {
		return nil, err
	}
	c.Rotation = rotations[choreID]
	return &c, nil
}

func (h *ChoreHandler) loadOccurrence(occurrenceID string) (*models.Occurrence, error) {
	var o models.Occurrence
	err := scanOccurrence(h.DB.QueryRow(
		`SELECT `+occurrenceColumns+` FROM occurrences o `+occurrenceJoins+` WHERE o.id = ?`,
		occurrenceID,
	), &o)
	if err != nil {
		return nil, err
	}
	return &o, nil
}

// loadRotations fetches the rotation lists for several chores at once, keyed by
// chore id and ordered by position.
func (h *ChoreHandler) loadRotations(choreIDs []string) (map[string][]string, error) {
	out := map[string][]string{}
	if len(choreIDs) == 0 {
		return out, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(choreIDs)), ",")
	args := make([]any, 0, len(choreIDs))
	for _, id := range choreIDs {
		args = append(args, id)
	}

	rows, err := h.DB.Query(
		`SELECT chore_id, user_id FROM chore_rotation
		 WHERE chore_id IN (`+placeholders+`) ORDER BY chore_id, position`, args...,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	for rows.Next() {
		var choreID, userID string
		if err := rows.Scan(&choreID, &userID); err != nil {
			return nil, err
		}
		out[choreID] = append(out[choreID], userID)
	}
	return out, rows.Err()
}

// ── Validation and schedule maths ───────────────────────────────────────────

// validateRotation checks the turn list: at least one person, no duplicates,
// and everyone actually in the group. Order is significant and preserved.
func (h *ChoreHandler) validateRotation(groupID string, rotation []string) ([]string, error) {
	if len(rotation) == 0 {
		return nil, fmt.Errorf("rotation must contain at least one member")
	}
	seen := map[string]bool{}
	for _, userID := range rotation {
		if seen[userID] {
			return nil, fmt.Errorf("rotation contains a duplicate member")
		}
		seen[userID] = true

		ok, err := h.DB.IsMember(groupID, userID)
		if err != nil {
			return nil, fmt.Errorf("internal error")
		}
		if !ok {
			return nil, fmt.Errorf("rotation contains a user who is not a group member")
		}
	}
	return rotation, nil
}

// replaceRotation rewrites a chore's turn list in place, preserving the given
// order as positions 0..n-1.
func replaceRotation(tx *sql.Tx, choreID string, rotation []string) error {
	if _, err := tx.Exec(`DELETE FROM chore_rotation WHERE chore_id = ?`, choreID); err != nil {
		return err
	}
	for position, userID := range rotation {
		if _, err := tx.Exec(
			`INSERT INTO chore_rotation (chore_id, user_id, position) VALUES (?,?,?)`,
			choreID, userID, position,
		); err != nil {
			return err
		}
	}
	return nil
}

func insertOccurrence(tx *sql.Tx, o models.Occurrence) error {
	_, err := tx.Exec(
		`INSERT INTO occurrences (id, chore_id, group_id, assigned_to, status, due_date,
			spawned_from, resume_after, covered_by, pending_debts, created_at)
		 VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)`,
		o.ID, o.ChoreID, o.GroupID, o.AssignedTo, o.Status, o.DueDate, o.SpawnedFrom,
		o.ResumeAfter, o.CoveredBy, encodeIDs(o.PendingDebts),
	)
	return err
}

// validateSchedule enforces the same pairing the CHECK constraint does, but
// with an error a client can show a user.
func validateSchedule(scheduleType string, intervalDays *int, weekdays, monthDays []int) error {
	switch scheduleType {
	case models.ScheduleInterval:
		if intervalDays == nil {
			return fmt.Errorf("interval_days is required for an interval chore")
		}
		// Any whole number of days up to a year.
		//
		// The spec enumerates 1-6, weekly and monthly, which is a narrower set
		// than households actually run on — a fortnightly bin collection has no
		// entry in it. Nothing downstream depends on the value being one of a
		// fixed few: the due date is arithmetic either way, and describeSchedule
		// still names the common ones. The bounds are what matter, and they only
		// exist to catch a typo: 0 would make a chore due the instant it was
		// completed, and past a year it is not a rotation any more.
		if *intervalDays < intervalDaysMin || *intervalDays > intervalDaysMax {
			return fmt.Errorf("interval_days must be between %d and %d", intervalDaysMin, intervalDaysMax)
		}
		if len(weekdays) > 0 || len(monthDays) > 0 {
			return fmt.Errorf("an interval chore cannot also have fixed dates")
		}
	case models.ScheduleFixedDate:
		if (len(weekdays) == 0) == (len(monthDays) == 0) {
			return fmt.Errorf("a fixed_date chore needs either fixed_weekdays or fixed_month_days, not both")
		}
		for _, d := range weekdays {
			if d < 0 || d > 6 {
				return fmt.Errorf("fixed_weekdays must be 0 (Sunday) to 6 (Saturday)")
			}
		}
		for _, d := range monthDays {
			if d < 1 || d > 31 {
				return fmt.Errorf("fixed_month_days must be 1 to 31")
			}
		}
		if intervalDays != nil {
			return fmt.Errorf("a fixed_date chore cannot also have an interval")
		}
	case models.ScheduleAsNeeded, models.ScheduleOneOff:
		if intervalDays != nil || len(weekdays) > 0 || len(monthDays) > 0 {
			return fmt.Errorf("a %s chore takes no schedule parameters", scheduleType)
		}
	}
	return nil
}

// validateClockTime accepts "HH:MM" in 24-hour form.
func validateClockTime(s string) error {
	if _, err := time.Parse("15:04", s); err != nil {
		return fmt.Errorf("needed_by_time must be HH:MM")
	}
	return nil
}

// firstDueDate derives the due date of a chore's *first* occurrence.
//
// There is no completion to count from yet, so an interval chore is anchored to
// creation — the first bathroom clean is due N days after you set the chore up,
// and every one after that is N days after the previous completion.
//
// As-needed chores return nil: they rotate with no date at all, and inventing
// one would turn a standing turn into a deadline.
func firstDueDate(scheduleType string, intervalDays *int, weekdays, monthDays []int, neededBy *string, from time.Time) *time.Time {
	switch scheduleType {
	case models.ScheduleInterval:
		if intervalDays == nil {
			return nil
		}
		due := atClockTime(from.AddDate(0, 0, *intervalDays), neededBy)
		return &due
	case models.ScheduleFixedDate:
		return nextFixedDate(weekdays, monthDays, neededBy, from)
	default:
		return nil
	}
}

// nextFixedDate finds the soonest matching weekday or month day strictly after
// `from`. It scans forward a day at a time — at most 366 iterations, and the
// clarity is worth more here than the arithmetic would be. A month day of 31
// simply doesn't match in a 30-day month, which is the honest reading of "the
// 31st" rather than silently sliding it to the 1st.
func nextFixedDate(weekdays, monthDays []int, neededBy *string, from time.Time) *time.Time {
	for i := 0; i <= 366; i++ {
		day := from.AddDate(0, 0, i)
		candidate := atClockTime(day, neededBy)
		if !candidate.After(from) {
			continue // today's slot has already passed
		}
		for _, wd := range weekdays {
			if int(day.Weekday()) == wd {
				return &candidate
			}
		}
		for _, md := range monthDays {
			if day.Day() == md {
				return &candidate
			}
		}
	}
	// Unreachable for any valid schedule: a weekday recurs within 7 days and a
	// month day within a year. Returning nil rather than a wrong date means an
	// impossible schedule shows as undated instead of quietly inventing a
	// deadline.
	return nil
}

// atClockTime puts a date at the chore's needed-by time, or at the end of the
// day when none is set — an undated-time chore is due *that day*, not at
// midnight when it becomes due and instantly overdue.
func atClockTime(day time.Time, neededBy *string) time.Time {
	hour, minute := 23, 59
	if neededBy != nil {
		if t, err := time.Parse("15:04", *neededBy); err == nil {
			hour, minute = t.Hour(), t.Minute()
		}
	}
	return time.Date(day.Year(), day.Month(), day.Day(), hour, minute, 0, 0, day.Location())
}

// describeSchedule renders a schedule the way a housemate would say it, for the
// edit diff: "every 3 days", "weekly", "Tuesdays".
func describeSchedule(scheduleType string, intervalDays *int, weekdays, monthDays []int) string {
	switch scheduleType {
	case models.ScheduleInterval:
		if intervalDays == nil {
			return "unscheduled"
		}
		switch *intervalDays {
		case 1:
			return "daily"
		case 7:
			return "weekly"
		case 30:
			return "monthly"
		default:
			return fmt.Sprintf("every %d days", *intervalDays)
		}
	case models.ScheduleFixedDate:
		if len(weekdays) > 0 {
			names := make([]string, 0, len(weekdays))
			for _, d := range weekdays {
				names = append(names, time.Weekday(d).String()+"s")
			}
			return strings.Join(names, ", ")
		}
		days := make([]string, 0, len(monthDays))
		for _, d := range monthDays {
			days = append(days, "the "+ordinal(d))
		}
		return strings.Join(days, ", ")
	case models.ScheduleAsNeeded:
		return "as needed"
	default:
		return "one-off"
	}
}

func ordinal(n int) string {
	suffix := "th"
	// 11th, 12th and 13th are the exceptions to the 1st/2nd/3rd rule.
	if n%100 < 11 || n%100 > 13 {
		switch n % 10 {
		case 1:
			suffix = "st"
		case 2:
			suffix = "nd"
		case 3:
			suffix = "rd"
		}
	}
	return strconv.Itoa(n) + suffix
}

// encodeInts stores an int list as a comma-separated string, or NULL when
// empty — the CHECK constraint distinguishes "no weekdays" from "some", so an
// empty string would read as a set fixed-date schedule with nothing in it.
func encodeInts(values []int) any {
	if len(values) == 0 {
		return nil
	}
	sorted := append([]int(nil), values...)
	sort.Ints(sorted)
	parts := make([]string, 0, len(sorted))
	for _, v := range sorted {
		parts = append(parts, strconv.Itoa(v))
	}
	return strings.Join(parts, ",")
}

// encodeIDs and decodeIDs store an *ordered* list of user ids in one column,
// comma-separated like fixed_weekdays. Order is the whole point here, so unlike
// encodeInts these must not sort. Ids are UUIDs and contain no commas.
func encodeIDs(ids []string) any {
	if len(ids) == 0 {
		return nil
	}
	return strings.Join(ids, ",")
}

func decodeIDs(s sql.NullString) []string {
	if !s.Valid || s.String == "" {
		return nil
	}
	out := []string{}
	for _, p := range strings.Split(s.String, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// appendOnce adds id to the end unless it is already there, so a list of people
// stays a set with an order rather than a tally.
func appendOnce(ids []string, id string) []string {
	for _, existing := range ids {
		if existing == id {
			return ids
		}
	}
	return append(append([]string(nil), ids...), id)
}

func contains(ids []string, id string) bool {
	for _, existing := range ids {
		if existing == id {
			return true
		}
	}
	return false
}

func decodeInts(s sql.NullString) []int {
	if !s.Valid || s.String == "" {
		return nil
	}
	parts := strings.Split(s.String, ",")
	out := make([]int, 0, len(parts))
	for _, p := range parts {
		if v, err := strconv.Atoi(strings.TrimSpace(p)); err == nil {
			out = append(out, v)
		}
	}
	return out
}

// sameOrder reports whether two rotations are identical, order included —
// reordering the same people is a real change to whose turn comes next.
func sameOrder(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
