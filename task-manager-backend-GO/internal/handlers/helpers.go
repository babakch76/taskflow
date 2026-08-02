package handlers

import (
	"encoding/json"
	"net/http"
)

func jsonResponse(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func jsonError(w http.ResponseWriter, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}

// Membership roles. The set is fixed by a CHECK constraint on
// group_members.role, so these are the only legal values.
//
// RoleAdmin is what the UI calls "Manager". The stored value stays "admin"
// because changing it would mean rebuilding the table — SQLite cannot alter a
// CHECK constraint in place — for a purely cosmetic difference.
const (
	RoleOwner  = "owner"
	RoleAdmin  = "admin"
	RoleMember = "member"
)

// canManageDeadlines reports whether a role may set or clear a task's due date.
//
// Deliberately narrow: this is the *only* thing the role gates. Creating
// tasks, editing their text, moving them between statuses and inviting people
// all remain open to every member, because nothing in the need-finding work so
// far says otherwise.
func canManageDeadlines(role string) bool {
	return role == RoleOwner || role == RoleAdmin
}

// deadlinePermissionError is the message shown when a plain member tries to
// touch a due date. It names who can, so the user knows what to do next
// rather than just that they were refused.
const deadlinePermissionError = "only the group owner or a manager can set or clear due dates"
