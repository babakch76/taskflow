package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strings"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/middleware"
	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

type TaskHandler struct {
	DB *database.DB
}

// taskColumns is the single source of truth for the task projection, so every
// SELECT stays in sync with scanTask and with models.Task.
const taskColumns = `id, group_id, assigned_to, title, description, status, due_date, created_at, updated_at, done_by, done_at`

// validTaskStatuses mirrors the CHECK constraint on tasks.status.
var validTaskStatuses = map[string]bool{"todo": true, "in_progress": true, "done": true}

// scanner is satisfied by both *sql.Row and *sql.Rows.
type scanner interface {
	Scan(dest ...any) error
}

// scanTask reads one row in taskColumns order.
func scanTask(s scanner, t *models.Task) error {
	return s.Scan(
		&t.ID, &t.GroupID, &t.AssignedTo, &t.Title, &t.Description,
		&t.Status, &t.DueDate, &t.CreatedAt, &t.UpdatedAt, &t.DoneBy, &t.DoneAt,
	)
}

func (h *TaskHandler) CreateTask(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	var req models.CreateTaskRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Title == "" {
		jsonError(w, "title is required", http.StatusBadRequest)
		return
	}

	// If assigning to someone, verify they're a group member
	if req.AssignedTo != nil {
		ok, _ := h.DB.IsMember(groupID, *req.AssignedTo)
		if !ok {
			jsonError(w, "assigned user is not a group member", http.StatusBadRequest)
			return
		}
	}

	now := time.Now()
	task := models.Task{
		ID:          uuid.New().String(),
		GroupID:     groupID,
		AssignedTo:  req.AssignedTo,
		Title:       req.Title,
		Description: req.Description,
		Status:      "todo",
		CreatedAt:   now,
		UpdatedAt:   &now,
	}

	if req.DueDate != nil {
		t, err := time.Parse(time.RFC3339, *req.DueDate)
		if err != nil {
			jsonError(w, "due_date must be RFC3339 format", http.StatusBadRequest)
			return
		}
		task.DueDate = &t
	}

	_, err := h.DB.Exec(
		`INSERT INTO tasks (id, group_id, assigned_to, title, description, status, due_date, updated_at)
		 VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)`,
		task.ID, task.GroupID, task.AssignedTo, task.Title, task.Description, task.Status, task.DueDate,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Feedback loop: group members see this in GET /groups/{id}/activity.
	// There is no transaction around the insert above, so a failure here is
	// logged rather than surfaced — the task itself was created.
	if err := recordActivity(h.DB, groupID, userID, EventTaskCreated, &task.ID, task.Title); err != nil {
		logActivityFailure(EventTaskCreated, err)
	}

	jsonResponse(w, http.StatusCreated, task)
}

func (h *TaskHandler) ListTasks(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	rows, err := h.DB.Query(`
		SELECT `+taskColumns+`
		FROM tasks WHERE group_id = ?
		ORDER BY created_at DESC
	`, groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	tasks := []models.Task{}
	for rows.Next() {
		var t models.Task
		if err := scanTask(rows, &t); err != nil {
			// Silently skipping here would return a short list that looks
			// complete — the client would think tasks had been deleted.
			log.Printf("ListTasks: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		tasks = append(tasks, t)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ListTasks: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, tasks)
}

func (h *TaskHandler) UpdateTask(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	taskID := r.PathValue("task_id")
	userID := middleware.GetUserID(r)

	// Verify task belongs to this group
	var existingGroupID string
	err := h.DB.QueryRow(`SELECT group_id FROM tasks WHERE id = ?`, taskID).Scan(&existingGroupID)
	if err == sql.ErrNoRows || existingGroupID != groupID {
		jsonError(w, "task not found", http.StatusNotFound)
		return
	}

	var req models.UpdateTaskRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	// Validate status if provided
	if req.Status != nil {
		if !validTaskStatuses[*req.Status] {
			jsonError(w, "status must be todo, in_progress, or done", http.StatusBadRequest)
			return
		}
	}

	// Handle assigned_to: present+null = unassign, present+value = reassign, absent = no change
	if req.AssignedTo.Present && !req.AssignedTo.IsNull {
		if req.AssignedTo.Value == "" {
			jsonError(w, "assigned_to cannot be empty string, use null to unassign", http.StatusBadRequest)
			return
		}
		ok, _ := h.DB.IsMember(groupID, req.AssignedTo.Value)
		if !ok {
			jsonError(w, "assigned user is not a group member", http.StatusBadRequest)
			return
		}
	}

	// Handle due_date: present+null = clear, present+value = set, absent = no change
	var parsedDueDate *time.Time
	clearDueDate := false
	if req.DueDate.Present {
		if req.DueDate.IsNull {
			clearDueDate = true
		} else {
			t, err := time.Parse(time.RFC3339, req.DueDate.Value)
			if err != nil {
				jsonError(w, "due_date must be RFC3339 format", http.StatusBadRequest)
				return
			}
			parsedDueDate = &t
		}
	}

	// Build a single atomic UPDATE with only the fields that were provided.
	// changed doubles as the activity event's detail, so members can see
	// *what* was touched and not just that something was.
	setClauses := []string{}
	args := []interface{}{}
	changed := []string{}

	if req.Title != nil {
		setClauses = append(setClauses, "title = ?")
		args = append(args, *req.Title)
		changed = append(changed, "title")
	}
	if req.Description != nil {
		setClauses = append(setClauses, "description = ?")
		args = append(args, *req.Description)
		changed = append(changed, "description")
	}
	if req.Status != nil {
		setClauses = append(setClauses, "status = ?")
		args = append(args, *req.Status)
		changed = append(changed, "status="+*req.Status)

		// Completion is a fact worth keeping: who did it and when. Moving a
		// task back out of done clears both, so the columns never describe a
		// completion that has been undone.
		if *req.Status == "done" {
			setClauses = append(setClauses, "done_by = ?", "done_at = CURRENT_TIMESTAMP")
			args = append(args, userID)
		} else {
			setClauses = append(setClauses, "done_by = NULL", "done_at = NULL")
		}
	}
	if req.AssignedTo.Present {
		setClauses = append(setClauses, "assigned_to = ?")
		if req.AssignedTo.IsNull {
			args = append(args, nil) // unassign
			changed = append(changed, "assigned_to=cleared")
		} else {
			args = append(args, req.AssignedTo.Value)
			changed = append(changed, "assigned_to")
		}
	}
	if req.DueDate.Present {
		setClauses = append(setClauses, "due_date = ?")
		if clearDueDate {
			args = append(args, nil) // clear due date
			changed = append(changed, "due_date=cleared")
		} else {
			args = append(args, parsedDueDate)
			changed = append(changed, "due_date")
		}
	}

	if len(setClauses) == 0 {
		jsonError(w, "no fields to update", http.StatusBadRequest)
		return
	}

	// Always bump updated_at alongside the caller's fields. Appended after the
	// emptiness check so it can never make an otherwise-empty patch look valid.
	setClauses = append(setClauses, "updated_at = CURRENT_TIMESTAMP")

	query := "UPDATE tasks SET " + strings.Join(setClauses, ", ") + " WHERE id = ?"
	args = append(args, taskID)

	// Single atomic write — if the CHECK constraint rejects anything, we catch it
	if _, err := h.DB.Exec(query, args...); err != nil {
		jsonError(w, "failed to update task: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if err := recordActivity(h.DB, groupID, userID, EventTaskUpdated, &taskID, strings.Join(changed, ", ")); err != nil {
		logActivityFailure(EventTaskUpdated, err)
	}

	// Return updated task
	var task models.Task
	if err := scanTask(
		h.DB.QueryRow(`SELECT `+taskColumns+` FROM tasks WHERE id = ?`, taskID),
		&task,
	); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, task)
}

// BulkUpdateTaskStatus handles PATCH /groups/{group_id}/tasks — one status
// applied to many tasks, backing the multi-select UI.
//
// Every id is verified to belong to this group before anything is written, so a
// member of group A cannot move group B's tasks by guessing ids. The whole set
// moves or none of it does.
func (h *TaskHandler) BulkUpdateTaskStatus(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	var req models.BulkUpdateTaskStatusRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(req.TaskIDs) == 0 {
		jsonError(w, "task_ids must contain at least one task", http.StatusBadRequest)
		return
	}
	if !validTaskStatuses[req.Status] {
		jsonError(w, "status must be todo, in_progress, or done", http.StatusBadRequest)
		return
	}

	// De-duplicate so a repeated id can't inflate the count check below.
	ids := dedupe(req.TaskIDs)

	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	idArgs := make([]any, 0, len(ids))
	for _, id := range ids {
		idArgs = append(idArgs, id)
	}

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Count how many of the requested ids actually live in this group. Anything
	// less than the full set means at least one id is missing or belongs to
	// another group — 404, consistent with the single-task handlers and with
	// the siloing rule that outsiders learn nothing about other groups.
	var found int
	if err := tx.QueryRow(
		`SELECT COUNT(*) FROM tasks WHERE group_id = ? AND id IN (`+placeholders+`)`,
		append([]any{groupID}, idArgs...)...,
	).Scan(&found); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if found != len(ids) {
		jsonError(w, "one or more tasks not found in this group", http.StatusNotFound)
		return
	}

	// Same done_by/done_at invariant as the single-task path.
	var doneSet string
	updateArgs := []any{req.Status}
	if req.Status == "done" {
		doneSet = ", done_by = ?, done_at = CURRENT_TIMESTAMP"
		updateArgs = append(updateArgs, userID)
	} else {
		doneSet = ", done_by = NULL, done_at = NULL"
	}
	updateArgs = append(updateArgs, idArgs...)
	if _, err := tx.Exec(
		`UPDATE tasks SET status = ?`+doneSet+`, updated_at = CURRENT_TIMESTAMP WHERE id IN (`+placeholders+`)`,
		updateArgs...,
	); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// One summary event, not one per task — the activity feed should read
	// "moved 5 tasks to done", not scroll past five near-identical lines.
	detail := fmt.Sprintf("%d task(s) → %s", len(ids), req.Status)
	if err := recordActivity(tx, groupID, userID, EventTasksBulkUpdated, nil, detail); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Read the updated rows inside the transaction so the response can never
	// reflect a concurrent write that lands between the UPDATE and the commit.
	rows, err := tx.Query(
		`SELECT `+taskColumns+` FROM tasks WHERE id IN (`+placeholders+`) ORDER BY created_at DESC`,
		idArgs...,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	tasks := []models.Task{}
	for rows.Next() {
		var t models.Task
		if err := scanTask(rows, &t); err != nil {
			rows.Close()
			log.Printf("BulkUpdateTaskStatus: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		tasks = append(tasks, t)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		log.Printf("BulkUpdateTaskStatus: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows.Close()

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, tasks)
}

func (h *TaskHandler) DeleteTask(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	taskID := r.PathValue("task_id")
	userID := middleware.GetUserID(r)

	var existingGroupID, title string
	err := h.DB.QueryRow(`SELECT group_id, title FROM tasks WHERE id = ?`, taskID).
		Scan(&existingGroupID, &title)
	if err != nil || existingGroupID != groupID {
		jsonError(w, "task not found", http.StatusNotFound)
		return
	}

	if _, err := h.DB.Exec(`DELETE FROM tasks WHERE id = ?`, taskID); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// task_id is kept on the event even though the row is gone: the feed says
	// which task disappeared, and activity_events has no FK to tasks so the
	// reference survives the delete.
	if err := recordActivity(h.DB, groupID, userID, EventTaskDeleted, &taskID, title); err != nil {
		logActivityFailure(EventTaskDeleted, err)
	}

	w.WriteHeader(http.StatusNoContent)
}

// dedupe returns the input with duplicates removed, in sorted order for a
// stable query shape.
func dedupe(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, v := range in {
		if seen[v] {
			continue
		}
		seen[v] = true
		out = append(out, v)
	}
	sort.Strings(out)
	return out
}
