package handlers

import (
	"log"
	"net/http"
	"time"

	"task-manager-backend/internal/models"
)

// History (F6) — the whiteboard's memory.
//
// Two read-only views over records that have been kept since F1, and the whole
// design is in what these endpoints refuse to compute:
//
//   - **No "late" flag.** A completion carries its due date and its done-at,
//     and nothing else. Whether that was late is date arithmetic the reader can
//     do; returning a boolean would make it a verdict the app had reached.
//
//   - **No ordering by count.** People come back in the order they joined, not
//     best-first. Sorting by completions *is* a leaderboard, however it is
//     labelled, and constraint 4 rules those out.
//
//   - **No totals, percentages, averages or streaks.** The data is presented;
//     the conclusions are the household's business.
//
// Absences are returned alongside, because the alternative is worse than
// silence: a count that dips for three weeks with nothing to explain it reads
// as flaking, which is the exact misreading this feature exists to prevent.

// historyWindow turns the query parameter into a span.
//
// Rolling rather than calendar-aligned: "this week" on a Monday morning would
// otherwise show almost nothing, which looks like a bug and invites the reading
// that somebody has done nothing.
func historyWindow(param string, now time.Time) (from time.Time, label string, ok bool) {
	switch param {
	case "", "month":
		return now.AddDate(0, -1, 0), "month", true
	case "week":
		return now.AddDate(0, 0, -7), "week", true
	case "quarter":
		return now.AddDate(0, -3, 0), "quarter", true
	default:
		return time.Time{}, "", false
	}
}

// ChoreHistory handles GET /groups/{group_id}/chores/{chore_id}/history — one
// chore's completions, newest first, with the absences that overlap them.
func (h *ChoreHandler) ChoreHistory(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")
	choreID := r.PathValue("chore_id")

	var existingGroupID string
	if err := h.DB.QueryRow(`SELECT group_id FROM chores WHERE id = ?`, choreID).
		Scan(&existingGroupID); err != nil || existingGroupID != groupID {
		jsonError(w, "chore not found", http.StatusNotFound)
		return
	}

	rows, err := h.DB.Query(`
		SELECT o.id, o.assigned_to, assignee.username,
		       o.done_by, doer.username,
		       o.passed_from, o.due_date, o.done_at
		FROM occurrences o
		JOIN users assignee ON assignee.id = o.assigned_to
		JOIN users doer     ON doer.id = o.done_by
		WHERE o.chore_id = ? AND o.status = 'done' AND o.done_at IS NOT NULL
		-- rowid breaks the tie, and it has to be rowid rather than created_at.
		--
		-- Two completions of the same chore can share a done_at: it comes from
		-- time.Now(), whose resolution on Windows is coarse enough that two
		-- writes a few milliseconds apart get the identical value. SQLite is
		-- then free to return them in either order — a timeline that reshuffles
		-- between refreshes, and a test that failed about one run in four.
		--
		-- created_at is no help: it defaults to CURRENT_TIMESTAMP, which is
		-- whole seconds, so it ties in exactly the cases done_at does. rowid is
		-- insertion order and always distinct, and for a chore's cycles that is
		-- spawn order — the later cycle cannot have been inserted first.
		ORDER BY o.done_at DESC, o.rowid DESC`, choreID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	entries := []models.ChoreHistoryEntry{}
	for rows.Next() {
		var e models.ChoreHistoryEntry
		if err := rows.Scan(
			&e.OccurrenceID, &e.AssignedTo, &e.AssigneeName,
			&e.DoneBy, &e.DoneByName,
			&e.PassedFrom, &e.DueDate, &e.DoneAt,
		); err != nil {
			log.Printf("ChoreHistory: scan failed for chore %s: %v", choreID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		entries = append(entries, e)
	}
	if err := rows.Err(); err != nil {
		log.Printf("ChoreHistory: rows error for chore %s: %v", choreID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Absences go back with the timeline so a client can show them in place —
	// the spec's "marked distinctly", so a gap in someone's completions is
	// visibly a gap in their being there.
	absences, err := h.absences(groupID)
	if err != nil {
		log.Printf("ChoreHistory: absence lookup failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, models.ChoreHistory{
		ChoreID:  choreID,
		Entries:  entries,
		Absences: absences,
	})
}

// GroupHistory handles GET /groups/{group_id}/history?window=week|month|quarter
// — completions per person over a window.
//
// Counted by done_by, so covering counts for whoever actually did it. That is
// the point of keeping both names since F1: the person who quietly did someone
// else's chore is the one the record credits.
func (h *ChoreHandler) GroupHistory(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("group_id")

	now := time.Now()
	from, label, ok := historyWindow(r.URL.Query().Get("window"), now)
	if !ok {
		jsonError(w, "window must be week, month or quarter", http.StatusBadRequest)
		return
	}

	// Every member appears, including those who completed nothing. Omitting
	// them would turn the list into a ranking of people who did something.
	rows, err := h.DB.Query(`
		SELECT u.id, u.username,
		       (SELECT COUNT(*) FROM occurrences o
		         WHERE o.group_id = gm.group_id
		           AND o.done_by = u.id
		           AND o.done_at >= ?) AS completed
		FROM group_members gm
		JOIN users u ON u.id = gm.user_id
		WHERE gm.group_id = ?
		ORDER BY gm.joined_at`,
		from.UTC(), groupID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	people := []models.PersonHistory{}
	for rows.Next() {
		var p models.PersonHistory
		if err := rows.Scan(&p.UserID, &p.Username, &p.Completed); err != nil {
			log.Printf("GroupHistory: scan failed for group %s: %v", groupID, err)
			jsonError(w, "internal error", http.StatusInternalServerError)
			return
		}
		people = append(people, p)
	}
	if err := rows.Err(); err != nil {
		log.Printf("GroupHistory: rows error for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	absences, err := h.absences(groupID)
	if err != nil {
		log.Printf("GroupHistory: absence lookup failed for group %s: %v", groupID, err)
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	awayDays := awayDaysWithin(absences, from, now)
	for i := range people {
		people[i].AwayDays = awayDays[people[i].UserID]
	}

	jsonResponse(w, http.StatusOK, models.GroupHistory{
		Window: label,
		From:   from,
		To:     now,
		People: people,
	})
}

// absences lists every away period recorded for a group, newest first.
func (h *ChoreHandler) absences(groupID string) ([]models.Absence, error) {
	rows, err := h.DB.Query(`
		SELECT ap.user_id, u.username, ap.started_at, ap.ends_at, ap.ended_at
		FROM away_periods ap
		JOIN users u ON u.id = ap.user_id
		WHERE ap.group_id = ?
		ORDER BY ap.started_at DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []models.Absence{}
	for rows.Next() {
		var a models.Absence
		if err := rows.Scan(&a.UserID, &a.Username, &a.StartedAt, &a.EndsAt, &a.EndedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// awayDaysWithin totals how many days of each person's absences fall inside
// [from, to].
//
// A period's effective end is the earliest of when they actually returned and
// when they said they would; an open-ended one that is still running ends at
// the window's end. Someone who said a week and came back in three days was
// away three days, not seven.
func awayDaysWithin(absences []models.Absence, from, to time.Time) map[string]int {
	days := map[string]float64{}

	for _, a := range absences {
		end := to
		if a.EndedAt != nil && a.EndedAt.Before(end) {
			end = *a.EndedAt
		}
		if a.EndsAt != nil && a.EndsAt.Before(end) {
			end = *a.EndsAt
		}

		start := a.StartedAt
		if start.Before(from) {
			start = from
		}
		if !end.After(start) {
			continue // no overlap with the window
		}
		days[a.UserID] += end.Sub(start).Hours() / 24
	}

	rounded := map[string]int{}
	for userID, d := range days {
		// Rounded up: any part of a day away is a day you were not there, and
		// reporting "0 days away" for someone who was gone all morning invites
		// the misreading this exists to prevent.
		whole := int(d)
		if d > float64(whole) {
			whole++
		}
		rounded[userID] = whole
	}
	return rounded
}
