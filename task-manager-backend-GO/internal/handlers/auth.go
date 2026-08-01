package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"task-manager-backend/internal/auth"
	"task-manager-backend/internal/database"
	"task-manager-backend/internal/models"

	"github.com/google/uuid"
)

type AuthHandler struct {
	DB *database.DB
}

func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	var req models.RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Username == "" || req.Email == "" || req.Password == "" {
		jsonError(w, "username, email, and password are required", http.StatusBadRequest)
		return
	}
	if len(req.Password) < 8 {
		jsonError(w, "password must be at least 8 characters", http.StatusBadRequest)
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	user := models.User{
		ID:           uuid.New().String(),
		Username:     req.Username,
		Email:        req.Email,
		PasswordHash: hash,
		CreatedAt:    time.Now(),
	}

	_, err = h.DB.Exec(
		`INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, ?)`,
		user.ID, user.Username, user.Email, user.PasswordHash,
	)
	if err != nil {
		// SQLite returns a constraint error for duplicate username/email
		jsonError(w, "username or email already taken", http.StatusConflict)
		return
	}

	token, err := auth.GenerateToken(user.ID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusCreated, models.AuthResponse{Token: token, User: user})
}

func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	var req models.LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	var user models.User
	err := h.DB.QueryRow(
		`SELECT id, username, email, password_hash, created_at FROM users WHERE email = ?`,
		req.Email,
	).Scan(&user.ID, &user.Username, &user.Email, &user.PasswordHash, &user.CreatedAt)
	if err != nil {
		jsonError(w, "invalid email or password", http.StatusUnauthorized)
		return
	}

	if !auth.CheckPassword(user.PasswordHash, req.Password) {
		jsonError(w, "invalid email or password", http.StatusUnauthorized)
		return
	}

	token, err := auth.GenerateToken(user.ID)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonResponse(w, http.StatusOK, models.AuthResponse{Token: token, User: user})
}
