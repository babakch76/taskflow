package handlers

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"

	"task-manager-backend/internal/database"
	"task-manager-backend/internal/middleware"
	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

type GroupHandler struct {
	DB *database.DB
}

// CreateGroup creates a new group and adds the creator as owner.
func (h *GroupHandler) CreateGroup(w http.ResponseWriter, r *http.Request) {
	userID := middleware.GetUserID(r)
	var req models.CreateGroupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Name == "" {
		jsonError(w, "group name is required", http.StatusBadRequest)
		return
	}

	group := models.Group{
		ID:          uuid.New().String(),
		Name:        req.Name,
		Description: req.Description,
		CreatedBy:   userID,
		CreatedAt:   time.Now(),
	}

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	_, err = tx.Exec(
		`INSERT INTO groups (id, name, description, created_by) VALUES (?, ?, ?, ?)`,
		group.ID, group.Name, group.Description, group.CreatedBy,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Creator becomes the owner automatically
	_, err = tx.Exec(
		`INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, 'owner')`,
		uuid.New().String(), group.ID, userID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusCreated, group)
}

// GetGroup returns group info with task progress (guarded by membership middleware).
func (h *GroupHandler) GetGroup(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	var g models.GroupWithProgress
	err := h.DB.QueryRow(
		`SELECT id, name, description, created_by, created_at FROM groups WHERE id = ?`,
		groupID,
	).Scan(&g.ID, &g.Name, &g.Description, &g.CreatedBy, &g.CreatedAt)
	if err != nil {
		jsonError(w, "group not found", http.StatusNotFound)
		return
	}

	// Compute progress for the progress bar widget. A failure here must not be
	// swallowed: the zero values would render as "0 tasks, 0% done", which is
	// indistinguishable from a genuinely empty group.
	//
	// Both halves of the board are counted. Tasks alone would under-report the
	// moment a group has chores — the header would say "1 of 5" over a screen
	// showing eight rows, which is worse than no counter at all.
	if err := h.DB.QueryRow(
		`SELECT
			(SELECT COUNT(*) FROM tasks WHERE group_id = ?) +
			(SELECT COUNT(*) FROM occurrences WHERE group_id = ?),
			(SELECT COALESCE(SUM(CASE WHEN status='done' THEN 1 ELSE 0 END), 0) FROM tasks WHERE group_id = ?) +
			(SELECT COALESCE(SUM(CASE WHEN status='done' THEN 1 ELSE 0 END), 0) FROM occurrences WHERE group_id = ?)`,
		groupID, groupID, groupID, groupID,
	).Scan(&g.TotalTasks, &g.DoneTasks); err != nil {
		log.Printf("GetGroup: progress scan failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if g.TotalTasks > 0 {
		g.Progress = float64(g.DoneTasks) / float64(g.TotalTasks)
	}

	// The caller's own role, so a client can gate its UI without fetching the
	// member list and working out which row is itself.
	role, err := h.DB.GetMemberRole(groupID, middleware.GetUserID(r))
	if err != nil {
		log.Printf("GetGroup: role lookup failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	g.MyRole = role

	jsonResponse(w, http.StatusOK, g)
}

// ListMyGroups returns all groups the authenticated user belongs to.
func (h *GroupHandler) ListMyGroups(w http.ResponseWriter, r *http.Request) {
	userID := middleware.GetUserID(r)

	rows, err := h.DB.Query(`
		SELECT g.id, g.name, g.description, g.created_by, g.created_at
		FROM groups g
		JOIN group_members gm ON g.id = gm.group_id
		WHERE gm.user_id = ?
		ORDER BY g.created_at DESC
	`, userID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	groups := []models.Group{}
	for rows.Next() {
		var g models.Group
		rows.Scan(&g.ID, &g.Name, &g.Description, &g.CreatedBy, &g.CreatedAt)
		groups = append(groups, g)
	}
	jsonResponse(w, http.StatusOK, groups)
}

// ListMembers returns all members of a group (guarded).
func (h *GroupHandler) ListMembers(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	// Away is returned here because the spec makes it deliberately impossible to
	// hide: it shows on the member list and anywhere a name appears in a
	// rotation. The app cannot enforce that away means away — it makes the
	// claim visible to everyone instead, and lets the household do the rest.
	rows, err := h.DB.Query(`
		SELECT u.id, u.username, u.email, gm.role, gm.joined_at,
		       ap.started_at, ap.ends_at
		FROM group_members gm
		JOIN users u ON u.id = gm.user_id
		LEFT JOIN away_periods ap
		       ON ap.group_id = gm.group_id
		      AND ap.user_id = gm.user_id
		      AND ap.ended_at IS NULL
		      AND (ap.ends_at IS NULL OR ap.ends_at > ?)
		WHERE gm.group_id = ?
		ORDER BY gm.joined_at
	`, time.Now().UTC(), groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type MemberInfo struct {
		ID       string    `json:"id"`
		Username string    `json:"username"`
		Email    string    `json:"email"`
		Role     string    `json:"role"`
		JoinedAt time.Time `json:"joined_at"`
		// Away right now, as opposed to merely having a period on record —
		// a finished one is not away, and clients should not have to work that
		// out from the dates themselves.
		Away      bool       `json:"away"`
		AwayUntil *time.Time `json:"away_until,omitempty"`
	}

	now := time.Now()
	members := []MemberInfo{}
	for rows.Next() {
		var m MemberInfo
		var awaySince, awayUntil sql.NullTime
		if err := rows.Scan(&m.ID, &m.Username, &m.Email, &m.Role, &m.JoinedAt,
			&awaySince, &awayUntil); err != nil {
			log.Printf("ListMembers: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		m.Away = awaySince.Valid && (!awayUntil.Valid || awayUntil.Time.After(now))
		if m.Away && awayUntil.Valid {
			m.AwayUntil = &awayUntil.Time
		}
		members = append(members, m)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ListMembers: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonResponse(w, http.StatusOK, members)
}

// SetAway handles PUT /groups/{group_id}/members/me/away — F5's second honest
// exit, for being physically absent rather than merely overloaded.
//
// Away lifts you out of every rotation in this household until you return, and
// re-enters you at the same position — which needs no bookkeeping, because the
// order never changes and assignment simply steps over you. **No turns are owed
// back.** That is the difference from busy: a pass defers a turn you still owe,
// away means the turns that would have been yours were never yours.
//
// Per household, not per person: you can be away from one flat and still be
// living in another. It is also not enforceable — the app cannot tell whether
// you are really gone — so the spec makes it impossible to hide instead, and
// ListMembers reports it to everyone.
func (h *GroupHandler) SetAway(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	var req models.SetAwayRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	now := time.Now().UTC()

	if !req.Away {
		// Coming back closes the period rather than erasing it. The absence
		// still happened, and F6 has to be able to say so — a completion count
		// that dips for three weeks with no explanation reads as flaking.
		if _, err := h.DB.Exec(
			`UPDATE away_periods SET ended_at = ?
			 WHERE group_id = ? AND user_id = ? AND ended_at IS NULL`,
			now, groupID, userID,
		); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		jsonResponse(w, http.StatusOK, map[string]any{"away": false})
		return
	}

	var until *time.Time
	if req.Until != nil {
		parsed, err := time.Parse(time.RFC3339, *req.Until)
		if err != nil {
			jsonError(w, "until must be RFC3339 format", http.StatusBadRequest)
			return
		}
		if !parsed.After(time.Now()) {
			// An away period that is already over would store as away and read
			// as present, which is the kind of state nobody can debug from the
			// UI.
			jsonError(w, "until must be in the future", http.StatusBadRequest)
			return
		}
		until = &parsed
	}

	// Close any period still open before starting a new one, so declaring away
	// twice cannot leave two overlapping records for one person.
	if _, err := h.DB.Exec(
		`UPDATE away_periods SET ended_at = ?
		 WHERE group_id = ? AND user_id = ? AND ended_at IS NULL`,
		now, groupID, userID,
	); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if _, err := h.DB.Exec(
		`INSERT INTO away_periods (id, group_id, user_id, started_at, ends_at)
		 VALUES (?,?,?,?,?)`,
		uuid.New().String(), groupID, userID, now, until,
	); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// No activity event, and no notification. Away is a state the household
	// reads off the member list, not an announcement — the same posture the
	// spec takes for passes.
	response := map[string]any{"away": true}
	if until != nil {
		response["away_until"] = until
	}
	jsonResponse(w, http.StatusOK, response)
}

// LeaveGroup handles DELETE /groups/{group_id}/members/me — the caller removes
// their own membership. Guarded by RequireMembership, so a non-member already
// gets a 404 before reaching here.
//
// Three outcomes:
//   - last member leaving  → the group is deleted, and ON DELETE CASCADE takes
//     its tasks, invites and activity with it
//   - owner leaving with others still in the group → 409; transferring
//     ownership is out of scope, and orphaning a group with no owner would
//     leave it in a state nothing else in the API can repair
//   - anyone else → membership row removed
func (h *GroupHandler) LeaveGroup(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var role string
	if err := tx.QueryRow(
		`SELECT role FROM group_members WHERE group_id = ? AND user_id = ?`,
		groupID, userID,
	).Scan(&role); err != nil {
		if err == sql.ErrNoRows {
			// Raced with another leave request, or with removal by someone else.
			jsonError(w, "not found", http.StatusNotFound)
			return
		}
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	var memberCount int
	if err := tx.QueryRow(
		`SELECT COUNT(*) FROM group_members WHERE group_id = ?`, groupID,
	).Scan(&memberCount); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	lastMember := memberCount == 1
	if role == "owner" && !lastMember {
		jsonError(
			w,
			"the group owner cannot leave while other members remain; transfer ownership or remove the other members first",
			http.StatusConflict,
		)
		return
	}

	// Record before the delete: activity_events.group_id cascades from groups,
	// so on the last-member path the event goes away with the group anyway, and
	// on every other path the FK target still exists.
	if err := recordActivity(tx, groupID, userID, EventMemberLeft, nil, "left the group"); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	res, err := tx.Exec(
		`DELETE FROM group_members WHERE group_id = ? AND user_id = ?`,
		groupID, userID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		// Same atomic rows-affected pattern used by the invite handlers:
		// someone else removed the row between the SELECT and here.
		jsonError(w, "not found", http.StatusNotFound)
		return
	}

	message := "left group successfully"
	if lastMember {
		if _, err := tx.Exec(`DELETE FROM groups WHERE id = ?`, groupID); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		message = "left group successfully; the group was deleted because you were its last member"
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, map[string]string{"message": message})
}

// --- Invite system ---

// InviteByUsername sends a direct invite to a user (Path A from the flow diagram).
func (h *GroupHandler) InviteByUsername(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	var req models.InviteByUsernameRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	// Look up the target user
	var targetID string
	err := h.DB.QueryRow(`SELECT id FROM users WHERE username = ?`, req.Username).Scan(&targetID)
	if err != nil {
		jsonError(w, "user not found", http.StatusNotFound)
		return
	}

	// Check they're not already a member
	already, _ := h.DB.IsMember(groupID, targetID)
	if already {
		jsonError(w, "user is already a member of this group", http.StatusConflict)
		return
	}

	// Check for existing pending invite
	var existingCount int
	h.DB.QueryRow(
		`SELECT COUNT(*) FROM invites WHERE group_id = ? AND invited_user = ? AND status = 'pending'`,
		groupID, targetID,
	).Scan(&existingCount)
	if existingCount > 0 {
		jsonError(w, "invite already pending for this user", http.StatusConflict)
		return
	}

	invite := models.Invite{
		ID:          uuid.New().String(),
		GroupID:     groupID,
		InvitedBy:   userID,
		InvitedUser: &targetID,
		Status:      "pending",
		CreatedAt:   time.Now(),
	}
	expires := time.Now().Add(7 * 24 * time.Hour) // 7-day expiry
	invite.ExpiresAt = &expires

	_, err = h.DB.Exec(
		`INSERT INTO invites (id, group_id, invited_by, invited_user, status, expires_at) VALUES (?, ?, ?, ?, ?, ?)`,
		invite.ID, invite.GroupID, invite.InvitedBy, invite.InvitedUser, invite.Status, invite.ExpiresAt,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// TODO: trigger push notification to targetID here
	jsonResponse(w, http.StatusCreated, invite)
}

// GenerateInviteCode creates a shareable, multi-use code for the group (Path B).
// The code stays active until it expires — it is NOT consumed on first use.
func (h *GroupHandler) GenerateInviteCode(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	userID := middleware.GetUserID(r)

	code, err := generateCode(inviteCodeLength)
	if err != nil {
		log.Printf("GenerateInviteCode: %v", err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	expires := time.Now().Add(48 * time.Hour) // 48-hour expiry for codes

	invite := models.Invite{
		ID:         uuid.New().String(),
		GroupID:    groupID,
		InvitedBy:  userID,
		Status:     "active", // NOT "pending" — shareable codes stay active
		InviteCode: code,
		MaxUses:    0, // 0 = unlimited uses
		UseCount:   0,
		CreatedAt:  time.Now(),
		ExpiresAt:  &expires,
	}

	_, err = h.DB.Exec(
		`INSERT INTO invites (id, group_id, invited_by, status, invite_code, max_uses, use_count, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		invite.ID, invite.GroupID, invite.InvitedBy, invite.Status, invite.InviteCode, invite.MaxUses, invite.UseCount, invite.ExpiresAt,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusCreated, map[string]string{
		"code":       code,
		"expires_at": expires.Format(time.RFC3339),
	})
}

// RedeemInviteCode allows a user to join a group via a shared code.
// The code is NOT consumed — it stays active for other users until it expires
// or hits its max_uses limit (0 = unlimited).
//
// The max_uses check is enforced atomically inside the transaction via a
// conditional UPDATE, preventing TOCTOU races where two concurrent requests
// both read use_count < max_uses and both proceed.
func (h *GroupHandler) RedeemInviteCode(w http.ResponseWriter, r *http.Request) {
	userID := middleware.GetUserID(r)

	var req models.RedeemInviteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	// Pre-flight read: fast-fail on obviously invalid codes.
	// This is NOT the authoritative check — the real enforcement is in the tx below.
	var invite models.Invite
	err := h.DB.QueryRow(
		`SELECT id, group_id, status, max_uses, use_count, expires_at FROM invites WHERE invite_code = ?`,
		req.Code,
	).Scan(&invite.ID, &invite.GroupID, &invite.Status, &invite.MaxUses, &invite.UseCount, &invite.ExpiresAt)
	if err == sql.ErrNoRows {
		jsonError(w, "invalid invite code", http.StatusNotFound)
		return
	}
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if invite.Status != "active" {
		jsonError(w, "invite code is no longer active", http.StatusGone)
		return
	}
	if invite.ExpiresAt != nil && time.Now().After(*invite.ExpiresAt) {
		h.DB.Exec(`UPDATE invites SET status = 'expired' WHERE id = ?`, invite.ID)
		jsonError(w, "invite code has expired", http.StatusGone)
		return
	}

	// Check not already a member (pre-flight — also enforced by UNIQUE constraint)
	already, _ := h.DB.IsMember(invite.GroupID, userID)
	if already {
		jsonError(w, "you are already a member of this group", http.StatusConflict)
		return
	}

	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// ATOMIC max_uses enforcement: increment use_count only if the code is still
	// active, not expired, and hasn't hit its limit. max_uses=0 means unlimited.
	// If a concurrent request already claimed the last use, RowsAffected() == 0.
	//
	// The expiry test wraps both sides in datetime(). expires_at is written by
	// the Go driver in its own layout ("2006-01-02 15:04:05.999999999-07:00"),
	// while CURRENT_TIMESTAMP is SQLite's "YYYY-MM-DD HH:MM:SS" in UTC — a bare
	// `>` between those two is a lexicographic comparison of differently
	// formatted strings, which silently gives the wrong answer (a code with a
	// timezone offset sorts after a UTC timestamp regardless of the real
	// instant). datetime() normalises both to a common form first.
	res, err := tx.Exec(`
		UPDATE invites
		SET use_count = use_count + 1
		WHERE id = ?
		  AND status = 'active'
		  AND (expires_at IS NULL OR datetime(expires_at) > datetime('now'))
		  AND (max_uses = 0 OR use_count < max_uses)
	`, invite.ID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		// Lost the race, or code expired/exhausted between pre-flight and now
		jsonError(w, "invite code has expired or reached its usage limit", http.StatusGone)
		return
	}

	// Add to group — INSERT OR IGNORE handles the edge case where the user
	// joined via another path between pre-flight check and here
	result, err := tx.Exec(
		`INSERT OR IGNORE INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, 'member')`,
		uuid.New().String(), invite.GroupID, userID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		// Already a member — the use_count bump was wasted, but that's safe
		// (it's a usage counter, not a billing meter)
		jsonError(w, "you are already a member of this group", http.StatusConflict)
		return
	}

	// Recorded inside the transaction: if the join rolls back, so does the
	// claim that someone joined.
	if err := recordActivity(tx, invite.GroupID, userID, EventMemberJoined, nil, "joined via invite code"); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, map[string]string{"message": "joined group successfully"})
}

// RespondToInvite lets a user accept or decline a direct invite.
// Uses INSERT OR IGNORE + rows-affected check to handle double-click race conditions.
func (h *GroupHandler) RespondToInvite(w http.ResponseWriter, r *http.Request) {
	userID := middleware.GetUserID(r)
	inviteID := r.PathValue("invite_id")

	var body struct {
		Action string `json:"action"` // "accept" or "decline"
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || (body.Action != "accept" && body.Action != "decline") {
		jsonError(w, `action must be "accept" or "decline"`, http.StatusBadRequest)
		return
	}

	var invite models.Invite
	err := h.DB.QueryRow(
		`SELECT id, group_id, invited_user, status, expires_at FROM invites WHERE id = ?`,
		inviteID,
	).Scan(&invite.ID, &invite.GroupID, &invite.InvitedUser, &invite.Status, &invite.ExpiresAt)
	if err != nil || invite.InvitedUser == nil || *invite.InvitedUser != userID {
		jsonError(w, "invite not found", http.StatusNotFound)
		return
	}
	if invite.Status != "pending" {
		jsonError(w, "invite is no longer pending", http.StatusConflict)
		return
	}
	if invite.ExpiresAt != nil && time.Now().After(*invite.ExpiresAt) {
		if _, err := h.DB.Exec(`UPDATE invites SET status = 'expired' WHERE id = ?`, invite.ID); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		jsonError(w, "invite has expired", http.StatusGone)
		return
	}

	if body.Action == "decline" {
		if _, err := h.DB.Exec(`UPDATE invites SET status = 'declined' WHERE id = ?`, invite.ID); err != nil {
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		jsonResponse(w, http.StatusOK, map[string]string{"message": "invite declined"})
		return
	}

	// Accept: add to group in a properly error-checked transaction
	tx, err := h.DB.Begin()
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Atomically flip the invite status first — if a concurrent request already
	// changed it from 'pending', this UPDATE affects 0 rows and we know we lost the race.
	res, err := tx.Exec(
		`UPDATE invites SET status = 'accepted' WHERE id = ? AND status = 'pending'`,
		invite.ID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		// Another request already processed this invite (double-click race)
		jsonError(w, "invite has already been processed", http.StatusConflict)
		return
	}

	// INSERT OR IGNORE handles the edge case where the user is somehow already
	// a member (e.g., joined via a shareable code between viewing and accepting)
	_, err = tx.Exec(
		`INSERT OR IGNORE INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, 'member')`,
		uuid.New().String(), invite.GroupID, userID,
	)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Two events, both inside the transaction: one about the invite's lifecycle
	// (useful to whoever sent it) and one about membership, so the "who joined
	// this group" feed reads the same regardless of which invite path was used.
	if err := recordActivity(tx, invite.GroupID, userID, EventInviteAccepted, nil, "accepted a direct invite"); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	if err := recordActivity(tx, invite.GroupID, userID, EventMemberJoined, nil, "joined via direct invite"); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, map[string]string{"message": "joined group successfully"})
}

// ListMyInvites returns all pending invites for the authenticated user.
func (h *GroupHandler) ListMyInvites(w http.ResponseWriter, r *http.Request) {
	userID := middleware.GetUserID(r)

	rows, err := h.DB.Query(`
		SELECT i.id, i.group_id, g.name, u.username, i.status, i.created_at, i.expires_at
		FROM invites i
		JOIN groups g ON g.id = i.group_id
		JOIN users u ON u.id = i.invited_by
		WHERE i.invited_user = ? AND i.status = 'pending'
		ORDER BY i.created_at DESC
	`, userID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type InviteInfo struct {
		ID        string     `json:"id"`
		GroupID   string     `json:"group_id"`
		GroupName string     `json:"group_name"`
		InvitedBy string     `json:"invited_by"`
		Status    string     `json:"status"`
		CreatedAt time.Time  `json:"created_at"`
		ExpiresAt *time.Time `json:"expires_at"`
	}
	invites := []InviteInfo{}
	for rows.Next() {
		var inv InviteInfo
		rows.Scan(&inv.ID, &inv.GroupID, &inv.GroupName, &inv.InvitedBy, &inv.Status, &inv.CreatedAt, &inv.ExpiresAt)
		invites = append(invites, inv)
	}
	jsonResponse(w, http.StatusOK, invites)
}

// inviteCodeLength is the number of hex characters in a shareable invite code.
// 12 chars = 48 bits of entropy, which makes guessing a live code infeasible
// within its 48-hour lifetime.
const inviteCodeLength = 12

// generateCode returns a random hex invite code.
//
// A failure from crypto/rand means the system entropy source is unavailable.
// The old code ignored that error and handed back the zero-filled buffer —
// a fully predictable code, and one that would collide with every other code
// generated during the outage (invites.invite_code is UNIQUE). Callers must
// fail the request instead.
func generateCode(length int) (string, error) {
	b := make([]byte, length)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("generate invite code: %w", err)
	}
	return hex.EncodeToString(b)[:length], nil
}
