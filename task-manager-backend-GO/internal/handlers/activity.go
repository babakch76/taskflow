package handlers

import (
	"database/sql"
	"log"
	"net/http"
	"time"

	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

// Event types written to activity_events. Clients switch on these strings, so
// treat them as part of the API contract.
const (
	EventTaskCreated      = "task_created"
	EventTaskUpdated      = "task_updated"
	EventTaskDeleted      = "task_deleted"
	EventTasksBulkUpdated = "tasks_bulk_updated"
	EventMemberJoined     = "member_joined"
	EventMemberLeft       = "member_left"
	EventInviteAccepted   = "invite_accepted"
	// No longer emitted — the manager role was removed. Kept because rows
	// written before that still carry this value.
	EventMemberRoleChanged = "member_role_changed"
)

// execer is satisfied by both *sql.DB (through database.DB) and *sql.Tx, so
// recordActivity can be called inside an existing transaction or on its own.
type execer interface {
	Exec(query string, args ...any) (sql.Result, error)
}

// activityTimeFormat is SQLite's "YYYY-MM-DD HH:MM:SS.SSS" — the column default
// is CURRENT_TIMESTAMP, but events are inserted with this instead.
//
// CURRENT_TIMESTAMP has one-second resolution, and that is not good enough for
// a feed that clients page through with ?since=. Two problems:
//
//   - Ordering: several events in the same second sort arbitrarily, so a
//     "created then updated" pair can display in the wrong order.
//   - Loss: a client polls at T, stores the newest created_at it saw, and sends
//     it back as ?since=T. The filter is strictly greater-than, so any event
//     written later in that same second is never returned — silently dropped,
//     which for an awareness feed means a member's change is invisible.
//
// Milliseconds make both practically impossible. ListActivity compares with the
// same format so the two agree.
const activityTimeFormat = `%Y-%m-%d %H:%M:%f`

// recordActivity appends one entry to a group's audit trail.
//
// Call it inside the caller's transaction wherever one exists, so the event and
// the change it describes commit or roll back together — an event for a write
// that was rolled back is worse than no event at all.
//
// taskID may be nil for events not tied to a single task (joins, leaves).
func recordActivity(ex execer, groupID, actorID, eventType string, taskID *string, detail string) error {
	_, err := ex.Exec(
		`INSERT INTO activity_events (id, group_id, actor_id, event_type, task_id, detail, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, strftime('`+activityTimeFormat+`', 'now'))`,
		uuid.New().String(), groupID, actorID, eventType, taskID, detail,
	)
	return err
}

// logActivityFailure records a best-effort activity write that failed outside a
// transaction. The user's actual operation already succeeded, so the request
// still returns success — a missing audit line must not fail a task creation.
func logActivityFailure(eventType string, err error) {
	log.Printf("activity: failed to record %s: %v", eventType, err)
}

// activityPageLimit caps a single poll. The client passes ?since= to get only
// what is new, so this only bites on a first load of a very busy group.
const activityPageLimit = 200

// ListActivity handles GET /groups/{group_id}/activity?since=<RFC3339>.
//
// Newest first, joined with the actor's username. Membership is already
// enforced by RequireMembership, same as every other group-scoped route.
func (h *GroupHandler) ListActivity(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	query := `
		SELECT a.id, a.group_id, a.actor_id, u.username, a.event_type, a.task_id, a.detail, a.created_at
		FROM activity_events a
		JOIN users u ON u.id = a.actor_id
		WHERE a.group_id = ?`
	args := []any{groupID}

	if raw := r.URL.Query().Get("since"); raw != "" {
		since, err := time.Parse(time.RFC3339, raw)
		if err != nil {
			jsonError(w, "since must be an RFC3339 timestamp", http.StatusBadRequest)
			return
		}
		// strftime() on both sides normalises the stored value and the
		// parameter to one comparable form; a raw string comparison between
		// two differently formatted timestamps is not reliable. Note this is
		// strftime and not datetime(), which would truncate to whole seconds
		// and reintroduce the event loss described on activityTimeFormat.
		query += ` AND strftime('` + activityTimeFormat + `', a.created_at) > strftime('` + activityTimeFormat + `', ?)`
		args = append(args, since.UTC().Format(time.RFC3339Nano))
	}

	// rowid breaks ties. Even at millisecond resolution several events can
	// share a timestamp — a task create and its immediate update, or the writes
	// inside one transaction. activity_events is append-only with a TEXT
	// primary key, so its implicit rowid increases monotonically with insertion
	// and is the only tiebreaker that reflects the real order of events.
	// Ordering by id instead would sort by random UUID.
	query += ` ORDER BY a.created_at DESC, a.rowid DESC LIMIT ?`
	args = append(args, activityPageLimit)

	rows, err := h.DB.Query(query, args...)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	events := []models.ActivityEvent{}
	for rows.Next() {
		var e models.ActivityEvent
		if err := rows.Scan(
			&e.ID, &e.GroupID, &e.ActorID, &e.ActorUsername,
			&e.EventType, &e.TaskID, &e.Detail, &e.CreatedAt,
		); err != nil {
			log.Printf("ListActivity: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		events = append(events, e)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ListActivity: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, events)
}
