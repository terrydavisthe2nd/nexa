package api

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

const (
	minLoginTime  = 300 * time.Millisecond
	defaultKDF    = `{"m":65536,"t":3,"p":1,"v":1}`
	maxWrappedKey = 512
	maxUserAgent  = 256
	// time between the two steps of account recovery: enough to pick a new password
	recoverTicketTTL = 15 * time.Minute
)

type kdfParams struct {
	M int `json:"m"`
	T int `json:"t"`
	P int `json:"p"`
	V int `json:"v"`
}

func validKDF(raw json.RawMessage) bool {
	var p kdfParams
	if err := json.Unmarshal(raw, &p); err != nil {
		return false
	}
	return p.V == 1 && p.M >= 32768 && p.M <= 1<<20 && p.T >= 2 && p.T <= 20 && p.P >= 1 && p.P <= 4
}

// MeUser is the authenticated user's own view.
type MeUser struct {
	store.PublicUser
	Flags           int64  `json:"flags"`
	PresencePref    string `json:"presence_pref"`
	DMPrivacy       string `json:"dm_privacy"`
	HasRecovery     bool   `json:"has_recovery"`
	MFAEnabled      bool   `json:"mfa_enabled"`
	BackupCodesLeft int    `json:"backup_codes_left"`
	// Nexa is the account's moderation standing, so the app knows about a
	// ban before it tries to connect and a mute before it shows a composer.
	Nexa *nexaState `json:"nexa,omitempty"`
}

func (s *Server) meView(ctx context.Context, u *store.User) MeUser {
	v := MeUser{PublicUser: u.Public(), Flags: u.Flags, PresencePref: u.PresencePref, DMPrivacy: u.DMPrivacy, HasRecovery: u.RecoveryAuthHash != ""}
	if m, err := s.Store.MFAFor(ctx, u.ID); err == nil && m != nil && m.Enabled {
		v.MFAEnabled = true
		if n, err := s.Store.BackupCodesLeft(ctx, u.ID); err == nil {
			v.BackupCodesLeft = n
		}
	}
	if st, err := s.nexaState(ctx, u); err == nil {
		v.Nexa = st
	} else {
		s.Log.Warn("nexa: state", "user", u.ID.String(), "err", err)
	}
	return v
}

func (s *Server) authRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/auth/register", ipLimited(s.limReg, s.handleRegister))
	mux.Handle("GET /api/v1/auth/kdf", s.stepUpIP(s.stepLookup, s.handleKDF))
	mux.Handle("POST /api/v1/auth/login", ipLimited(s.limAuthIP, s.handleLogin))
	mux.Handle("POST /api/v1/auth/recover/begin", ipLimited(s.limAuthIP, s.handleRecoverBegin))
	mux.Handle("POST /api/v1/auth/recover/finish", ipLimited(s.limAuthIP, s.handleRecoverFinish))
	mux.Handle("POST /api/v1/auth/password", s.authed(s.handlePasswordChange))
	mux.Handle("POST /api/v1/auth/recovery", s.authed(s.handleRecoveryReplace))
	mux.Handle("POST /api/v1/auth/logout", s.authed(s.handleLogout))
	mux.Handle("GET /api/v1/auth/sessions", s.authed(s.handleSessions))
	mux.Handle("DELETE /api/v1/auth/sessions/{id}", s.authed(s.handleSessionDelete))
}

func (s *Server) newSession(r *http.Request, userID ids.ID) (string, error) {
	token, hash := auth.NewToken()
	// cut to size and made valid UTF-8: a cut through a character, or a
	// header of raw bytes, is text Postgres refuses, and the session with it
	ua := clip(r.UserAgent(), maxUserAgent)
	se := &store.Session{UserID: userID, TokenHash: hash, UserAgent: ua, IP: auth.ClientIP(r)}
	if err := s.Store.CreateSession(r.Context(), se); err != nil {
		return "", err
	}
	return token, nil
}

type registerReq struct {
	Username              string          `json:"username"`
	DisplayName           string          `json:"display_name"`
	AuthKey               []byte          `json:"auth_key"`
	KDFSalt               []byte          `json:"kdf_salt"`
	KDFParams             json.RawMessage `json:"kdf_params"`
	PublicKey             []byte          `json:"public_key"`
	EncPrivateKey         []byte          `json:"enc_private_key"`
	EncPrivateKeyRecovery []byte          `json:"enc_private_key_recovery"`
	RecoveryAuthKey       []byte          `json:"recovery_auth_key"`
	InviteCode            string          `json:"invite_code"`
	RegistrationKey       string          `json:"registration_key"`
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "register") {
		return
	}
	var req registerReq
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	ctx := r.Context()
	mode, regKey := s.registrationPolicy(ctx)
	// While sign-ups are invite-only, an invite lets in as many accounts as it has
	// uses: signing up spends one, and the account's first join through the
	// invite then rides on it. Checking only that the invite was live let
	// anyone who saw a one-use code sign up until someone joined with it.
	signupInvite := ""
	switch mode {
	case "closed":
		WriteError(w, E(http.StatusForbidden, "registration_closed", "This server is not accepting new accounts."))
		return
	case "invite":
		keyOK := regKey != "" && hmac.Equal([]byte(req.RegistrationKey), []byte(regKey))
		inviteOK := !keyOK && req.InviteCode != "" && s.InviteCheck != nil && s.InviteCheck(ctx, req.InviteCode)
		if !keyOK && !inviteOK {
			WriteError(w, E(http.StatusForbidden, "invite_required", "You need an invite to register here."))
			return
		}
		if inviteOK {
			signupInvite = req.InviteCode
			// a vanity code may be typed in any case; its row has it lowercase
			if in, err := s.Store.InviteByCode(ctx, req.InviteCode); err == nil {
				signupInvite = in.Code
			}
		}
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if !ValidUsername(req.Username) {
		WriteError(w, E(http.StatusBadRequest, "invalid_username", "Usernames are 2–32 characters of a-z, 0-9, _ and . (no leading, trailing or double dots)."))
		return
	}
	if reservedDisplayName(req.Username) {
		WriteError(w, E(http.StatusBadRequest, "reserved_username", "That username belongs to the official Nexa account."))
		return
	}
	display, ok := CleanDisplayName(req.DisplayName)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_display_name", "Display names are 1–32 characters."))
		return
	}
	if reservedDisplayName(display) {
		WriteError(w, E(http.StatusBadRequest, "reserved_display_name", "That name belongs to the official Nexa account."))
		return
	}
	if len(req.AuthKey) != 32 || len(req.PublicKey) != 32 || len(req.KDFSalt) < 16 || len(req.KDFSalt) > 64 || len(req.EncPrivateKey) == 0 || len(req.EncPrivateKey) > maxWrappedKey || !validKDF(req.KDFParams) {
		WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The key material is malformed."))
		return
	}
	hasRecovery := len(req.RecoveryAuthKey) > 0 || len(req.EncPrivateKeyRecovery) > 0
	if hasRecovery && (len(req.RecoveryAuthKey) != 32 || len(req.EncPrivateKeyRecovery) == 0 || len(req.EncPrivateKeyRecovery) > maxWrappedKey) {
		WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The recovery key material is malformed."))
		return
	}
	authHash, err := auth.HashAuthKey(req.AuthKey)
	if err != nil {
		WriteError(w, err)
		return
	}
	u := &store.User{Username: req.Username, DisplayName: display, AuthHash: authHash, KDFSalt: req.KDFSalt, KDFParams: string(req.KDFParams),
		PublicKey: req.PublicKey, EncPrivateKey: req.EncPrivateKey}
	if hasRecovery {
		rh, err := auth.HashAuthKey(req.RecoveryAuthKey)
		if err != nil {
			WriteError(w, err)
			return
		}
		u.RecoveryAuthHash = rh
		u.EncPrivateKeyRecovery = req.EncPrivateKeyRecovery
	}
	count, err := s.Store.CountUsers(ctx)
	if err != nil {
		WriteError(w, err)
		return
	}
	// This count only exempts initial setup from the signup limit. Bootstrap
	// privileges are decided inside CreateRegisteredUser's insert transaction.
	first := count == 0
	// at most two new accounts a day from one IP address, spent
	// only once the request has passed every check above. Nexa's first
	// account is never held back, and admins can turn the limit off.
	if !first && s.signupIPLimitOn(ctx) {
		if ok, retry := s.limSignup.Allow(auth.RateKey(r)); !ok {
			w.Header().Set("Retry-After", retryAfter(retry))
			WriteError(w, E(http.StatusTooManyRequests, "too_many_signups", "Two accounts have already been made from this IP address today. Try again tomorrow."))
			return
		}
	}
	if signupInvite != "" {
		if err := s.Store.UseInvite(ctx, signupInvite); errors.Is(err, store.ErrNotFound) {
			// spent by someone else a moment ago
			WriteError(w, E(http.StatusForbidden, "invite_required", "You need an invite to register here."))
			return
		} else if err != nil {
			WriteError(w, err)
			return
		}
	}
	if err := s.Store.CreateRegisteredUser(ctx, u, s.Cfg.AdminUsername); err != nil {
		if signupInvite != "" {
			_ = s.Store.UnuseInvite(context.WithoutCancel(ctx), signupInvite)
		}
		WriteError(w, err)
		return
	}
	if signupInvite != "" {
		if err := s.Store.SetSignupInvite(ctx, u.ID, signupInvite); err != nil {
			// the account exists; its join will just spend a use of its own
			s.Log.Warn("record signup invite", "user", u.ID.String(), "err", err)
		}
	}

	token, err := s.newSession(r, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"user": s.meView(ctx, u), "token": token})
}

func (s *Server) fakeSalt(username string) []byte {
	mac := hmac.New(sha256.New, s.Secret)
	mac.Write([]byte("kdf:" + username))
	return mac.Sum(nil)[:16]
}

func (s *Server) handleKDF(w http.ResponseWriter, r *http.Request) {
	username := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("username")))
	if username == "" || len(username) > 32 {
		WriteError(w, E(http.StatusBadRequest, "missing_username", "username is required"))
		return
	}
	u, err := s.Store.UserBySignInName(r.Context(), username)
	if err != nil || u.Flags&(store.FlagDeleted|store.NotPeople) != 0 {
		WriteJSON(w, http.StatusOK, map[string]any{"salt": s.fakeSalt(username), "params": json.RawMessage(defaultKDF)})
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"salt": u.KDFSalt, "params": json.RawMessage(u.KDFParams)})
}

func keyBundle(u *store.User) map[string]any {
	return map[string]any{"public_key": u.PublicKey, "enc_private_key": u.EncPrivateKey, "kdf_salt": u.KDFSalt, "kdf_params": json.RawMessage(u.KDFParams)}
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "login") {
		return
	}
	start := time.Now()
	fail := func() {
		if d := time.Since(start); d < minLoginTime {
			time.Sleep(minLoginTime - d)
		}
		WriteError(w, E(http.StatusUnauthorized, "invalid_credentials", "Username or password is wrong."))
	}
	var req struct {
		Username string `json:"username"`
		AuthKey  []byte `json:"auth_key"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if len(req.Username) > 32 {
		fail()
		return
	}
	if ok, retry := s.limAuthUsr.Allow("login:" + req.Username); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	// A username a moderator reset still signs its account in, so someone
	// who was away when it happened is not locked out (UserBySignInName).
	u, err := s.Store.UserBySignInName(r.Context(), req.Username)
	if err != nil || u.Flags&(store.FlagDeleted|store.FlagDisabled|store.NotPeople) != 0 || len(req.AuthKey) != 32 {
		fail()
		return
	}
	ok, err := auth.VerifyAuthKey(req.AuthKey, u.AuthHash)
	if err != nil || !ok {
		fail()
		return
	}
	needsMFA, err := s.Store.MFAEnabled(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if needsMFA {
		// The password was right, so stop punishing it; the code has its own limiter.
		s.limAuthUsr.Reset("login:" + req.Username)
		s.mfaChallenge(w, u, start)
		return
	}
	token, err := s.newSession(r, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.limAuthUsr.Reset("login:" + req.Username)
	if d := time.Since(start); d < minLoginTime {
		time.Sleep(minLoginTime - d)
	}
	resp := keyBundle(u)
	resp["user"] = s.meView(r.Context(), u)
	resp["token"] = token
	WriteJSON(w, http.StatusOK, resp)
}

func retryAfter(d time.Duration) string {
	secs := int(d.Seconds() + 0.999)
	if secs < 1 {
		secs = 1
	}
	return time.Duration(secs).String()[:0] + itoa(secs)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

// verifyRecovery loads the user and checks the recovery auth key, with the same
// timing floor as login.
func (s *Server) verifyRecovery(w http.ResponseWriter, r *http.Request, username string, key []byte) (*store.User, bool) {
	start := time.Now()
	username = strings.ToLower(strings.TrimSpace(username))
	if len(username) > 32 {
		if d := time.Since(start); d < minLoginTime {
			time.Sleep(minLoginTime - d)
		}
		WriteError(w, E(http.StatusUnauthorized, "invalid_credentials", "Username or recovery key is wrong."))
		return nil, false
	}
	if ok, retry := s.limAuthUsr.Allow("recover:" + username); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return nil, false
	}
	u, err := s.Store.UserBySignInName(r.Context(), username)
	valid := err == nil && u.Flags&(store.FlagDeleted|store.FlagDisabled|store.NotPeople) == 0 && len(key) == 32 && u.RecoveryAuthHash != ""
	if valid {
		ok, verr := auth.VerifyAuthKey(key, u.RecoveryAuthHash)
		valid = verr == nil && ok
	}
	if d := time.Since(start); d < minLoginTime {
		time.Sleep(minLoginTime - d)
	}
	// One answer for every failure: saying that an account has no recovery
	// key would tell anyone asking that the account exists.
	if !valid {
		WriteError(w, E(http.StatusUnauthorized, "invalid_credentials", "Username or recovery key is wrong."))
		return nil, false
	}
	return u, true
}

func (s *Server) handleRecoverBegin(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "recover") {
		return
	}
	var req struct {
		Username        string `json:"username"`
		RecoveryAuthKey []byte `json:"recovery_auth_key"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	u, ok := s.verifyRecovery(w, r, req.Username, req.RecoveryAuthKey)
	if !ok {
		return
	}
	// Finishing signs the account in, so it asks for this ticket: without it a
	// script could skip the captcha above by going straight to the second step.
	tk := s.signTicket(ticket{Purpose: "recover", User: u.ID.String(), Exp: time.Now().Add(recoverTicketTTL).Unix()})
	WriteJSON(w, http.StatusOK, map[string]any{"public_key": u.PublicKey, "enc_private_key_recovery": u.EncPrivateKeyRecovery, "ticket": tk})
}

func (s *Server) handleRecoverFinish(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username                 string          `json:"username"`
		RecoveryAuthKey          []byte          `json:"recovery_auth_key"`
		NewAuthKey               []byte          `json:"new_auth_key"`
		NewKDFSalt               []byte          `json:"new_kdf_salt"`
		NewKDFParams             json.RawMessage `json:"new_kdf_params"`
		NewEncPrivateKey         []byte          `json:"new_enc_private_key"`
		NewRecoveryAuthKey       []byte          `json:"new_recovery_auth_key"`
		NewEncPrivateKeyRecovery []byte          `json:"new_enc_private_key_recovery"`
		Ticket                   string          `json:"ticket"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	expired := E(http.StatusUnauthorized, "recover_expired", "That recovery timed out. Please start again.")
	tk, ok := s.readTicket(req.Ticket, "recover")
	if !ok {
		WriteError(w, expired)
		return
	}
	u, ok := s.verifyRecovery(w, r, req.Username, req.RecoveryAuthKey)
	if !ok {
		return
	}
	if tk.User != u.ID.String() {
		WriteError(w, expired)
		return
	}
	if len(req.NewAuthKey) != 32 || len(req.NewKDFSalt) < 16 || len(req.NewKDFSalt) > 64 || !validKDF(req.NewKDFParams) || len(req.NewEncPrivateKey) == 0 || len(req.NewEncPrivateKey) > maxWrappedKey {
		WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The key material is malformed."))
		return
	}
	upd := store.AuthUpdate{KDFSalt: req.NewKDFSalt, KDFParams: string(req.NewKDFParams), EncPrivateKey: req.NewEncPrivateKey}
	var err error
	if upd.AuthHash, err = auth.HashAuthKey(req.NewAuthKey); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.NewRecoveryAuthKey) > 0 || len(req.NewEncPrivateKeyRecovery) > 0 {
		if len(req.NewRecoveryAuthKey) != 32 || len(req.NewEncPrivateKeyRecovery) == 0 || len(req.NewEncPrivateKeyRecovery) > maxWrappedKey {
			WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The recovery key material is malformed."))
			return
		}
		if upd.RecoveryAuthHash, err = auth.HashAuthKey(req.NewRecoveryAuthKey); err != nil {
			WriteError(w, err)
			return
		}
		upd.EncPrivateKeyRecovery = req.NewEncPrivateKeyRecovery
	}
	if err := s.Store.UpdateAuth(r.Context(), u.ID, upd); err != nil {
		WriteError(w, err)
		return
	}
	_ = s.Store.DeleteUserSessions(r.Context(), u.ID, 0)
	s.Bcast.CloseUserSessions(u.ID, 0)
	// The recovery key is the way back in after losing a phone, so it clears the
	// second factor too. It already unwraps the identity, so it grants nothing
	// new; leaving the authenticator on would only lock the owner out for good.
	_ = s.Store.DisableMFA(r.Context(), u.ID)
	token, err := s.newSession(r, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// The account has changed and its old sessions are gone, so this answer
	// is the only way in: a failed read-back keeps what was just written
	// rather than answering with nothing (or crashing on it).
	if fresh, err := s.Store.UserByID(r.Context(), u.ID); err == nil {
		u = fresh
	} else {
		u.AuthHash, u.KDFSalt, u.KDFParams, u.EncPrivateKey = upd.AuthHash, upd.KDFSalt, upd.KDFParams, upd.EncPrivateKey
		if upd.RecoveryAuthHash != "" {
			u.RecoveryAuthHash, u.EncPrivateKeyRecovery = upd.RecoveryAuthHash, upd.EncPrivateKeyRecovery
		}
	}
	resp := keyBundle(u)
	resp["user"] = s.meView(r.Context(), u)
	resp["token"] = token
	WriteJSON(w, http.StatusOK, resp)
}

func (s *Server) handlePasswordChange(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	var req struct {
		AuthKey          []byte          `json:"auth_key"`
		NewAuthKey       []byte          `json:"new_auth_key"`
		NewKDFSalt       []byte          `json:"new_kdf_salt"`
		NewKDFParams     json.RawMessage `json:"new_kdf_params"`
		NewEncPrivateKey []byte          `json:"new_enc_private_key"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	// a session someone else got hold of cannot guess its way to the password
	if !s.requireCaptcha(w, r, "password") {
		return
	}
	if err := s.requirePassword(w, u, req.AuthKey, "The current password is wrong."); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.NewAuthKey) != 32 || len(req.NewEncPrivateKey) == 0 || len(req.NewEncPrivateKey) > maxWrappedKey || (len(req.NewKDFSalt) > 0 && (len(req.NewKDFSalt) < 16 || len(req.NewKDFSalt) > 64 || !validKDF(req.NewKDFParams))) {
		WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The key material is malformed."))
		return
	}
	upd := store.AuthUpdate{EncPrivateKey: req.NewEncPrivateKey, KDFSalt: req.NewKDFSalt, KDFParams: string(req.NewKDFParams)}
	var err error
	if upd.AuthHash, err = auth.HashAuthKey(req.NewAuthKey); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.UpdateAuth(r.Context(), u.ID, upd); err != nil {
		WriteError(w, err)
		return
	}
	_ = s.Store.DeleteUserSessions(r.Context(), u.ID, auth.SessionFrom(r.Context()).ID)
	s.Bcast.CloseUserSessions(u.ID, auth.SessionFrom(r.Context()).ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleRecoveryReplace(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	var req struct {
		AuthKey               []byte `json:"auth_key"`
		RecoveryAuthKey       []byte `json:"recovery_auth_key"`
		EncPrivateKeyRecovery []byte `json:"enc_private_key_recovery"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !s.requireCaptcha(w, r, "recovery_key") {
		return
	}
	if err := s.requirePassword(w, u, req.AuthKey, "The current password is wrong."); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.RecoveryAuthKey) != 32 || len(req.EncPrivateKeyRecovery) == 0 || len(req.EncPrivateKeyRecovery) > maxWrappedKey {
		WriteError(w, E(http.StatusBadRequest, "invalid_keys", "The recovery key material is malformed."))
		return
	}
	rh, err := auth.HashAuthKey(req.RecoveryAuthKey)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.UpdateRecovery(r.Context(), u.ID, rh, req.EncPrivateKeyRecovery); err != nil {
		WriteError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	se := auth.SessionFrom(r.Context())
	_ = s.Store.DeleteSession(r.Context(), se.ID)
	s.Bcast.CloseSession(se.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	cur := auth.SessionFrom(r.Context())
	list, err := s.Store.SessionsByUser(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	type sessionView struct {
		*store.Session
		Current bool `json:"current"`
	}
	out := make([]sessionView, 0, len(list))
	for _, se := range list {
		out = append(out, sessionView{Session: se, Current: se.ID == cur.ID})
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) handleSessionDelete(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	se, err := s.Store.SessionByID(r.Context(), id)
	if err != nil || se.UserID != u.ID {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.DeleteSession(r.Context(), id); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.CloseSession(id)
	w.WriteHeader(http.StatusNoContent)
}

// signupIPLimitOn reports whether the two-accounts-a-day limit per address
// applies. It is on unless an admin turned it off (instance setting
// signup_ip_limit = "off"); a failed read keeps it on.
func (s *Server) signupIPLimitOn(ctx context.Context) bool {
	v, err := s.Store.InstanceSetting(ctx, "signup_ip_limit")
	return err != nil || v != "off"
}

// registrationPolicy is the config default, overridden by what an admin set at runtime.
func (s *Server) registrationPolicy(ctx context.Context) (mode, key string) {
	mode, key = s.Cfg.Registration, s.Cfg.RegistrationKey
	if v, err := s.Store.InstanceSetting(ctx, "registration"); err == nil && v != "" {
		mode = v
	}
	if v, err := s.Store.InstanceSetting(ctx, "registration_key"); err == nil && v != "" {
		key = v
	}
	return mode, key
}
