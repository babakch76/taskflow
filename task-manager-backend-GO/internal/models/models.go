package models

import (
	"encoding/json"
	"fmt"
	"time"
)

type User struct {
	ID           string    `json:"id"`
	Username     string    `json:"username"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	CreatedAt    time.Time `json:"created_at"`
}

type Group struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description string    `json:"description"`
	CreatedBy   string    `json:"created_by"`
	CreatedAt   time.Time `json:"created_at"`
}

type GroupMember struct {
	ID       string    `json:"id"`
	GroupID  string    `json:"group_id"`
	UserID   string    `json:"user_id"`
	Role     string    `json:"role"` // "owner", "admin", "member"
	JoinedAt time.Time `json:"joined_at"`
}

type Invite struct {
	ID          string     `json:"id"`
	GroupID     string     `json:"group_id"`
	InvitedBy   string     `json:"invited_by"`
	InvitedUser *string    `json:"invited_user,omitempty"` // nil for code-based invites
	Status      string     `json:"status"`                 // "pending", "accepted", "declined", "expired", "active"
	InviteCode  string     `json:"invite_code,omitempty"`
	MaxUses     int        `json:"max_uses"`
	UseCount    int        `json:"use_count"`
	CreatedAt   time.Time  `json:"created_at"`
	ExpiresAt   *time.Time `json:"expires_at,omitempty"`
}

type Task struct {
	ID          string     `json:"id"`
	GroupID     string     `json:"group_id"`
	AssignedTo  *string    `json:"assigned_to,omitempty"`
	Title       string     `json:"title"`
	Description string     `json:"description"`
	Status      string     `json:"status"` // "todo", "in_progress", "done"
	DueDate     *time.Time `json:"due_date,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	// UpdatedAt is a pointer because the column was added after the fact:
	// rows written before that migration have NULL until their next update.
	UpdatedAt *time.Time `json:"updated_at,omitempty"`
}

// ActivityEvent is one entry in a group's audit trail. It is what the Android
// client polls for to keep members aware of each other's changes.
type ActivityEvent struct {
	ID string `json:"id"`
	// ActorUsername is joined in from users so clients don't need a second
	// round trip to render "Sam moved 3 tasks to done".
	ActorUsername string    `json:"actor_username"`
	GroupID       string    `json:"group_id"`
	ActorID       string    `json:"actor_id"`
	EventType     string    `json:"event_type"`
	TaskID        *string   `json:"task_id,omitempty"`
	Detail        string    `json:"detail"`
	CreatedAt     time.Time `json:"created_at"`
}

// --- Request / Response DTOs ---

type RegisterRequest struct {
	Username string `json:"username"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type AuthResponse struct {
	Token string `json:"token"`
	User  User   `json:"user"`
}

type CreateGroupRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

type InviteByUsernameRequest struct {
	Username string `json:"username"`
}

type RedeemInviteRequest struct {
	Code string `json:"code"`
}

type CreateTaskRequest struct {
	Title       string  `json:"title"`
	Description string  `json:"description"`
	AssignedTo  *string `json:"assigned_to,omitempty"`
	DueDate     *string `json:"due_date,omitempty"` // RFC3339
}

// NullableField distinguishes three JSON states:
//   - field omitted  → Present=false
//   - field: null    → Present=true, Value=""  (clear the value)
//   - field: "abc"   → Present=true, Value="abc"
type NullableField struct {
	Value   string
	Present bool // true if the key appeared in the JSON at all
	IsNull  bool // true if the value was explicitly null
}

func (n *NullableField) UnmarshalJSON(data []byte) error {
	n.Present = true
	if string(data) == "null" {
		n.IsNull = true
		n.Value = ""
		return nil
	}
	// Delegate to encoding/json for proper unescaping of \", \n, \uXXXX, etc.
	// This also rejects non-string JSON types (numbers, booleans, objects)
	// which would panic with the old manual quote-stripping approach.
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return fmt.Errorf("NullableField must be a string or null, got: %s", string(data))
	}
	n.Value = s
	return nil
}

type UpdateTaskRequest struct {
	Title       *string       `json:"title,omitempty"`
	Description *string       `json:"description,omitempty"`
	Status      *string       `json:"status,omitempty"`
	AssignedTo  NullableField `json:"assigned_to"`
	DueDate     NullableField `json:"due_date"`
}

// BulkUpdateTaskStatusRequest backs the multi-select UI: one status applied to
// a set of tasks in a single round trip and a single transaction.
type BulkUpdateTaskStatusRequest struct {
	TaskIDs []string `json:"task_ids"`
	Status  string   `json:"status"`
}

type GroupWithProgress struct {
	Group
	TotalTasks int     `json:"total_tasks"`
	DoneTasks  int     `json:"done_tasks"`
	Progress   float64 `json:"progress"` // 0.0 to 1.0
	// MyRole is the requesting user's role in this group: "owner", "admin" or
	// "member". Returned so a client can gate its UI without having to fetch
	// the member list and work out which row is itself.
	MyRole string `json:"my_role"`
}
