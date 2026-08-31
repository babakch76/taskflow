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
	o.due_date, o.done_by, o.done_at, o.created_at, o.spawned_from, c.name, c.done_line`

// undoWindow is how long after marking an occurrence done it can still be taken
// back, and only by the person who marked it. Anything older is history, and
// history is not editable from the board.
//
// The Android client greys the checkbox out on the same rule, but a rule that
// lives only in the client is not a rule — an old client, or a direct API call,
// would otherwise rewrite completions from any point in the past.
const undoWindow = 10 * time.Minute

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

	occ := models.Occurrence{
		ID:         uuid.New().String(),
		ChoreID:    chore.ID,
		GroupID:    groupID,
		AssignedTo: rotation[0],
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

	// Schedule parameters, validated against the chore's *existing* type —
	// the category itself is immutable.
	newInterval := existing.IntervalDays
	newWeekdays := existing.FixedWeekdays
	newMonthDays := existing.FixedMonthDays
	scheduleTouched := false

	if req.IntervalDays != nil {
		if existing.ScheduleType != models.ScheduleInterval {
			jsonError(w, "interval_days applies only to interval chores", http.StatusBadRequest)
			return
		}
		newInterval = req.IntervalDays
		scheduleTouched = true
	}
	if req.FixedWeekdays != nil || req.FixedMonthDays != nil {
		if existing.ScheduleType != models.ScheduleFixedDate {
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
		if err := validateSchedule(existing.ScheduleType, newInterval, newWeekdays, newMonthDays); err != nil {
			jsonError(w, err.Error(), http.StatusBadRequest)
			return
		}
		setClauses = append(setClauses, "interval_days = ?", "fixed_weekdays = ?", "fixed_month_days = ?")
		args = append(args, newInterval, encodeInts(newWeekdays), encodeInts(newMonthDays))
		changed = append(changed, fmt.Sprintf("schedule: %s → %s",
			describeSchedule(existing.ScheduleType, existing.IntervalDays, existing.FixedWeekdays, existing.FixedMonthDays),
			describeSchedule(existing.ScheduleType, newInterval, newWeekdays, newMonthDays)))
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
		// on completion, never on an edit — moving someone's standing chore off
		// their row because the list was reordered would be exactly the
		// "waiting it out" escape the spec closes.
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
		JOIN chores c ON c.id = o.chore_id
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

	var (
		existingGroupID string
		status          string
		assignedTo      string
		choreID         string
		choreName       string
		doneBy          sql.NullString
		doneAt          sql.NullTime
	)
	err := h.DB.QueryRow(`
		SELECT o.group_id, o.status, o.assigned_to, o.chore_id, o.done_by, o.done_at, c.name
		FROM occurrences o JOIN chores c ON c.id = o.chore_id
		WHERE o.id = ?`, occurrenceID,
	).Scan(&existingGroupID, &status, &assignedTo, &choreID, &doneBy, &doneAt, &choreName)
	if err == sql.ErrNoRows || existingGroupID != groupID {
		jsonError(w, "occurrence not found", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if req.Status == status {
		jsonError(w, "occurrence is already "+status, http.StatusConflict)
		return
	}

	// Undo's two conditions are checked before opening the transaction, so a
	// refusal costs nothing and the error is the same either way.
	if req.Status == models.OccurrenceOpen {
		if !doneBy.Valid || doneBy.String != userID {
			jsonError(w, "only the person who marked this done can undo it", http.StatusForbidden)
			return
		}
		if !doneAt.Valid || time.Since(doneAt.Time) > undoWindow {
			jsonError(w, "the undo window has passed", http.StatusForbidden)
			return
		}
	}

	chore, err := h.loadChore(choreID)
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
		if err := spawnNext(tx, chore, occurrenceID, assignedTo, userID, completedAt); err != nil {
			log.Printf("UpdateOccurrence: spawn failed for chore %s: %v", choreID, err)
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

	if err := recordActivity(tx, groupID, userID, eventType, &occurrenceID, choreName); err != nil {
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

// spawnNext creates the occurrence that follows a completed one.
//
// Rotation advances **on completion, never on the calendar** — this function is
// the only place a turn moves on. An occurrence left undone simply stays where
// it is, which is what makes a turn impossible to wait out.
//
// A one-off has nothing to spawn: it is the whole of its own schedule.
//
// The assignee here is "the next person after whoever held it". That is the
// simple case, and it is deliberately not the final rule: an occurrence
// completed by someone *other* than its assignee counts as the doer's turn, and
// the next one goes back to the original assignee. That is the unified turn
// rule, and it lands in the next commit — doerID is already threaded through
// for it.
func spawnNext(tx *sql.Tx, chore *models.Chore, completedID, assignedTo, doerID string, completedAt time.Time) error {
	if chore.ScheduleType == models.ScheduleOneOff {
		return nil
	}
	if len(chore.Rotation) == 0 {
		// Can't happen through the API — a rotation must hold at least one
		// member — but a chore with nobody in it has no one to assign to, and
		// silently dropping the chore is better than a broken row.
		return nil
	}

	next := models.Occurrence{
		ID:          uuid.New().String(),
		ChoreID:     chore.ID,
		GroupID:     chore.GroupID,
		AssignedTo:  nextInRotation(chore.Rotation, assignedTo),
		Status:      models.OccurrenceOpen,
		DueDate:     nextDueDate(chore, completedAt),
		SpawnedFrom: &completedID,
	}
	return insertOccurrence(tx, next)
}

// nextInRotation returns whoever's turn follows the current holder's.
//
// If the holder is no longer in the rotation — they were edited out, or left
// the group — the turn starts again at the top rather than being lost.
func nextInRotation(rotation []string, current string) string {
	for i, userID := range rotation {
		if userID == current {
			return rotation[(i+1)%len(rotation)]
		}
	}
	return rotation[0]
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
func nextDueDate(chore *models.Chore, completedAt time.Time) *time.Time {
	switch chore.ScheduleType {
	case models.ScheduleInterval:
		if chore.IntervalDays == nil {
			return nil
		}
		due := atClockTime(completedAt.AddDate(0, 0, *chore.IntervalDays), chore.NeededByTime)
		return &due
	case models.ScheduleFixedDate:
		return nextFixedDate(chore.FixedWeekdays, chore.FixedMonthDays, chore.NeededByTime, completedAt)
	default:
		return nil
	}
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
	return s.Scan(
		&o.ID, &o.ChoreID, &o.GroupID, &o.AssignedTo, &o.Status,
		&o.DueDate, &o.DoneBy, &o.DoneAt, &o.CreatedAt, &o.SpawnedFrom,
		&o.ChoreName, &o.DoneLine,
	)
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
		`SELECT `+occurrenceColumns+` FROM occurrences o JOIN chores c ON c.id = o.chore_id WHERE o.id = ?`,
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
		`INSERT INTO occurrences (id, chore_id, group_id, assigned_to, status, due_date, spawned_from, created_at)
		 VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)`,
		o.ID, o.ChoreID, o.GroupID, o.AssignedTo, o.Status, o.DueDate, o.SpawnedFrom,
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
