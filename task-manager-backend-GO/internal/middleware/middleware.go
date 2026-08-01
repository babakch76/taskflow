package middleware

import (
	"context"
	"net/http"
	"strings"

	"task-manager-backend/internal/auth"
	"task-manager-backend/internal/database"
)

type contextKey string

const UserIDKey contextKey = "user_id"

// Auth extracts and validates the JWT from the Authorization header.
func Auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			http.Error(w, `{"error":"missing or invalid authorization header"}`, http.StatusUnauthorized)
			return
		}
		claims, err := auth.ValidateToken(strings.TrimPrefix(header, "Bearer "))
		if err != nil {
			http.Error(w, `{"error":"invalid token"}`, http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), UserIDKey, claims.UserID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// GetUserID extracts the authenticated user's ID from the request context.
func GetUserID(r *http.Request) string {
	id, _ := r.Context().Value(UserIDKey).(string)
	return id
}

// RequireMembership is the membership guard from the architecture diagram.
// It checks that the authenticated user belongs to the group identified by
// the "group_id" path segment before allowing the request through.
func RequireMembership(db *database.DB, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("group_id")
		if groupID == "" {
			http.Error(w, `{"error":"missing group_id"}`, http.StatusBadRequest)
			return
		}
		userID := GetUserID(r)
		ok, err := db.IsMember(groupID, userID)
		if err != nil {
			http.Error(w, `{"error":"internal error"}`, http.StatusInternalServerError)
			return
		}
		if !ok {
			// Data siloing: non-members get a 404, not 403, so they can't
			// even confirm the group exists.
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
			return
		}
		next.ServeHTTP(w, r)
	})
}
