package handlers

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"

	"task-manager-backend/internal/models"
)

func getMe(t *testing.T, h *MeHandler, userID string) models.User {
	t.Helper()
	rec := httptest.NewRecorder()
	h.GetMe(rec, request("GET", "/me", "", userID, nil))
	if rec.Code != 200 {
		t.Fatalf("GetMe: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	var u models.User
	if err := json.Unmarshal(rec.Body.Bytes(), &u); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return u
}

func patchMe(t *testing.T, h *MeHandler, userID, body string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.UpdateMe(rec, request("PATCH", "/me", body, userID, nil))
	return rec
}

// Every user starts with the spec's window, and it must survive being added to
// a database that already had users in it.
func TestQuietHoursDefaultToTheSpecWindow(t *testing.T) {
	db := newTestDB(t)
	h := &MeHandler{DB: db}
	// newUser inserts without naming the quiet columns, exactly as an existing
	// row predating the migration would look.
	userID := newUser(t, db, "ann")

	me := getMe(t, h, userID)
	if me.QuietFrom != models.DefaultQuietFrom || me.QuietTo != models.DefaultQuietTo {
		t.Errorf("quiet hours: got %s-%s, want %s-%s",
			me.QuietFrom, me.QuietTo, models.DefaultQuietFrom, models.DefaultQuietTo)
	}
	if me.Username != "ann" {
		t.Errorf("username: got %q", me.Username)
	}
}

// The password hash must never leave the server, whatever else /me grows.
func TestGetMeNeverReturnsThePasswordHash(t *testing.T) {
	db := newTestDB(t)
	h := &MeHandler{DB: db}
	userID := newUser(t, db, "ann")

	rec := httptest.NewRecorder()
	h.GetMe(rec, request("GET", "/me", "", userID, nil))

	body := rec.Body.String()
	if strings.Contains(body, "password") || strings.Contains(body, "hash") {
		t.Errorf("/me leaked something password-shaped: %s", body)
	}
}

func TestUpdateQuietHours(t *testing.T) {
	db := newTestDB(t)
	h := &MeHandler{DB: db}
	userID := newUser(t, db, "ann")

	t.Run("both ends at once", func(t *testing.T) {
		rec := patchMe(t, h, userID, `{"quiet_from":"22:30","quiet_to":"07:15"}`)
		if rec.Code != 200 {
			t.Fatalf("got %d (%s)", rec.Code, rec.Body.String())
		}
		me := getMe(t, h, userID)
		if me.QuietFrom != "22:30" || me.QuietTo != "07:15" {
			t.Errorf("got %s-%s, want 22:30-07:15", me.QuietFrom, me.QuietTo)
		}
	})

	t.Run("one end without restating the other", func(t *testing.T) {
		rec := patchMe(t, h, userID, `{"quiet_to":"08:00"}`)
		if rec.Code != 200 {
			t.Fatalf("got %d (%s)", rec.Code, rec.Body.String())
		}
		me := getMe(t, h, userID)
		if me.QuietFrom != "22:30" {
			t.Errorf("the untouched end changed to %s", me.QuietFrom)
		}
		if me.QuietTo != "08:00" {
			t.Errorf("quiet_to: got %s, want 08:00", me.QuietTo)
		}
	})

	t.Run("a window that does not wrap midnight is fine", func(t *testing.T) {
		// Unusual but coherent: quiet from 01:00 to 06:00.
		if rec := patchMe(t, h, userID, `{"quiet_from":"01:00","quiet_to":"06:00"}`); rec.Code != 200 {
			t.Fatalf("got %d (%s)", rec.Code, rec.Body.String())
		}
	})
}

func TestUpdateQuietHoursRejectsBadInput(t *testing.T) {
	db := newTestDB(t)
	h := &MeHandler{DB: db}
	userID := newUser(t, db, "ann")

	cases := []struct {
		name string
		body string
		want string
	}{
		{"not a time", `{"quiet_from":"half nine"}`, "quiet_from must be HH:MM"},
		{"hour out of range", `{"quiet_to":"25:00"}`, "quiet_to must be HH:MM"},
		{"12-hour clock", `{"quiet_from":"9pm"}`, "quiet_from must be HH:MM"},
		{"empty patch", `{}`, "no fields to update"},
		{
			// Zero-length or whole-day, depending on which reading you take.
			// Refusing beats silently picking one.
			name: "both ends the same",
			body: `{"quiet_from":"09:00","quiet_to":"09:00"}`,
			want: "quiet hours cannot start and end at the same time",
		},
		{
			// Same trap, reached by moving only one end onto the other.
			name: "one end moved onto the existing other",
			body: fmt.Sprintf(`{"quiet_to":%q}`, models.DefaultQuietFrom),
			want: "quiet hours cannot start and end at the same time",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := patchMe(t, h, userID, tc.body)
			if rec.Code != 400 {
				t.Fatalf("got %d, want 400 (%s)", rec.Code, rec.Body.String())
			}
			if got := decodeError(t, rec); got != tc.want {
				t.Errorf("error: got %q, want %q", got, tc.want)
			}
		})
	}

	// None of that may have moved the window.
	me := getMe(t, h, userID)
	if me.QuietFrom != models.DefaultQuietFrom || me.QuietTo != models.DefaultQuietTo {
		t.Errorf("a rejected patch changed the window to %s-%s", me.QuietFrom, me.QuietTo)
	}
}

// One person's window is their own.
func TestQuietHoursArePerUser(t *testing.T) {
	db := newTestDB(t)
	h := &MeHandler{DB: db}
	ann := newUser(t, db, "ann")
	bo := newUser(t, db, "bo")

	if rec := patchMe(t, h, ann, `{"quiet_from":"23:00","quiet_to":"06:00"}`); rec.Code != 200 {
		t.Fatalf("got %d", rec.Code)
	}

	if me := getMe(t, h, bo); me.QuietFrom != models.DefaultQuietFrom || me.QuietTo != models.DefaultQuietTo {
		t.Errorf("ann's change reached bo: %s-%s", me.QuietFrom, me.QuietTo)
	}
}
