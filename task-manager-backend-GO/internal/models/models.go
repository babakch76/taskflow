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
	// DoneBy is who marked it done, which is not necessarily who it was
	// assigned to — anyone may complete anything, and the board records the
	// reality rather than the intention. Both names are kept.
	DoneBy *string    `json:"done_by,omitempty"`
	DoneAt *time.Time `json:"done_at,omitempty"`
}

// Schedule types. A chore picks exactly one at creation and cannot change
// category afterwards — only its parameters.
const (
	// ScheduleInterval: every N days, counted from the last *completion*, so a
	// chore done late shifts the whole schedule with reality.
	ScheduleInterval = "interval"
	// ScheduleFixedDate: specific weekdays or month days, because the world sets
	// the deadline (bin collection). A missed date rolls to the next one with
	// the same assignee.
	ScheduleFixedDate = "fixed_date"
	// ScheduleAsNeeded: rotates with no date at all. One standing occurrence
	// always exists; completing it advances the turn. No due date, so no due
	// reminder — and deliberately no "it's needed now" button, which would be a
	// nag with a disguise on.
	ScheduleAsNeeded = "as_needed"
	// ScheduleOneOff: no recurrence. A bill, a repair, a delivery.
	ScheduleOneOff = "one_off"
)

// OccurrenceStatus values. The whole set — there is no "missed" state anywhere
// in the system, by design: a chore can only be not yet done.
const (
	OccurrenceOpen = "open"
	OccurrenceDone = "done"
)

// DoneLineMaxLen caps F4's "what done means" line. The limit is deliberate:
// this is a treaty between housemates, not a manual.
const DoneLineMaxLen = 140

// Chore is a definition — what the chore is, how often it comes round, and the
// turn order it follows. It is never itself on the board; its occurrences are.
type Chore struct {
	ID       string `json:"id"`
	GroupID  string `json:"group_id"`
	Name     string `json:"name"`
	DoneLine string `json:"done_line"`

	ScheduleType string `json:"schedule_type"`
	// IntervalDays is set only for ScheduleInterval.
	IntervalDays *int `json:"interval_days,omitempty"`
	// FixedWeekdays (0=Sunday) and FixedMonthDays (1..31) are set only for
	// ScheduleFixedDate, and exactly one of the two is non-empty.
	FixedWeekdays  []int `json:"fixed_weekdays,omitempty"`
	FixedMonthDays []int `json:"fixed_month_days,omitempty"`
	// NeededByTime is an optional "HH:MM" clock time.
	NeededByTime *string `json:"needed_by_time,omitempty"`

	// Rotation is the ordered turn list, by user id, position 0 first.
	Rotation []string `json:"rotation"`

	CreatedBy string     `json:"created_by"`
	CreatedAt time.Time  `json:"created_at"`
	UpdatedAt *time.Time `json:"updated_at,omitempty"`
}

// Occurrence is one cycle of a chore, assigned to exactly one member.
//
// AssignedTo is not a pointer: every occurrence always has exactly one name on
// it. DoneBy may differ from AssignedTo — anyone may complete anything, and
// recording who actually did it is what makes covering visible without anyone
// having to say so.
type Occurrence struct {
	ID         string     `json:"id"`
	ChoreID    string     `json:"chore_id"`
	GroupID    string     `json:"group_id"`
	AssignedTo string     `json:"assigned_to"`
	Status     string     `json:"status"`
	DueDate    *time.Time `json:"due_date,omitempty"`
	DoneBy     *string    `json:"done_by,omitempty"`
	DoneAt     *time.Time `json:"done_at,omitempty"`
	CreatedAt  time.Time  `json:"created_at"`
	// SpawnedFrom is the occurrence whose completion created this one. Undoing
	// that completion removes this occurrence again.
	SpawnedFrom *string `json:"spawned_from,omitempty"`

	// ChoreName and DoneLine are joined in so the board can render a row
	// without a second round trip per occurrence.
	ChoreName string `json:"chore_name"`
	DoneLine  string `json:"done_line"`
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

// CreateChoreRequest defines a chore and, implicitly, its first occurrence.
//
// Rotation must contain at least one member: an occurrence always has exactly
// one name on it, so a chore with nobody in its rotation could never spawn one.
type CreateChoreRequest struct {
	Name           string   `json:"name"`
	DoneLine       string   `json:"done_line"`
	ScheduleType   string   `json:"schedule_type"`
	IntervalDays   *int     `json:"interval_days,omitempty"`
	FixedWeekdays  []int    `json:"fixed_weekdays,omitempty"`
	FixedMonthDays []int    `json:"fixed_month_days,omitempty"`
	NeededByTime   *string  `json:"needed_by_time,omitempty"`
	Rotation       []string `json:"rotation"`
	// DueDate applies to one-off chores only, which are assigned at creation by
	// the creator and have no recurrence to derive a date from. RFC3339.
	DueDate *string `json:"due_date,omitempty"`
}

// UpdateChoreRequest edits a chore. Open to any member by design — the spec
// replaces an approval flow with a diff broadcast to the whole group.
//
// ScheduleType is absent on purpose: a chore cannot change category, only its
// parameters. Changing category would make the existing open occurrence's due
// date meaningless, and re-deriving it silently is worse than requiring a new
// chore.
type UpdateChoreRequest struct {
	Name           *string       `json:"name,omitempty"`
	DoneLine       *string       `json:"done_line,omitempty"`
	IntervalDays   *int          `json:"interval_days,omitempty"`
	FixedWeekdays  []int         `json:"fixed_weekdays,omitempty"`
	FixedMonthDays []int         `json:"fixed_month_days,omitempty"`
	NeededByTime   NullableField `json:"needed_by_time"`
	Rotation       []string      `json:"rotation,omitempty"`
}

// UpdateOccurrenceRequest is the board's one-tap completion, and its undo.
//
// Status is the only field: an occurrence's assignee is decided by rotation,
// never by hand, and its due date by the schedule. Reassignment happens through
// the busy pass (F5), not through this endpoint.
type UpdateOccurrenceRequest struct {
	Status string `json:"status"`
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
