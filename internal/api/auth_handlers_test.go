package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ratelimit"
)

func TestConcurrentRegistrationCreatesOnlyOneAdministrator(t *testing.T) {
	srv, ts := newTestServer(t)
	// Hold the writer while all requests can read the empty instance. The
	// administrator decision must be made in the inserting transaction.
	locked, release, unlocked := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	var releaseOnce sync.Once
	releaseWriter := func() { releaseOnce.Do(func() { close(release) }) }
	defer releaseWriter()
	waits := srv.Store.DB().W.SQL().Stats().WaitCount
	go func() {
		unlocked <- srv.Store.Tx(t.Context(), func(_ *db.Tx) error {
			close(locked)
			<-release
			return nil
		})
	}()
	<-locked
	var wg sync.WaitGroup
	for _, name := range []string{"firsta", "firstb", "firstc", "firstd"} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody(name))
			if res.StatusCode != http.StatusCreated {
				t.Errorf("register %s: %d %v", name, res.StatusCode, body)
			}
		}()
	}
	deadline := time.Now().Add(10 * time.Second)
	for srv.Store.DB().W.SQL().Stats().WaitCount < waits+4 {
		if time.Now().After(deadline) {
			releaseWriter()
			wg.Wait()
			t.Fatal("registration requests did not reach the writer")
		}
		time.Sleep(10 * time.Millisecond)
	}
	releaseWriter()
	if err := <-unlocked; err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	if n, err := srv.Store.CountAdminsEver(t.Context()); err != nil || n != 1 {
		t.Fatalf("concurrent signups created %d administrators, want 1 (err %v)", n, err)
	}
}

var (
	key32   = bytes.Repeat([]byte{7}, 32)
	key32b  = bytes.Repeat([]byte{8}, 32)
	salt16  = bytes.Repeat([]byte{1}, 16)
	pub32   = bytes.Repeat([]byte{2}, 32)
	wrapped = []byte("wrapped-private-key")
)

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

func registerBody(username string) map[string]any {
	return map[string]any{"username": username, "display_name": "User " + username, "auth_key": b64(key32), "kdf_salt": b64(salt16),
		"kdf_params": map[string]int{"m": 65536, "t": 3, "p": 1, "v": 1}, "public_key": b64(pub32), "enc_private_key": b64(wrapped),
		"enc_private_key_recovery": b64([]byte("wrapped-recovery")), "recovery_auth_key": b64(key32b)}
}

// register signs up an account that anyone may message. A new account only
// takes messages from friends and people in a server with it
// (store.DefaultDMPrivacy), and most tests open DMs between strangers to test
// something else; the ones about that rule use registerDefault.
func register(t *testing.T, ts *httptest.Server, username string) (token string, id string) {
	t.Helper()
	token, id = registerDefault(t, ts, username)
	if res, body := do(t, ts, "PATCH", "/api/v1/users/@me", token, map[string]any{"dm_privacy": "everyone"}); res.StatusCode != 200 {
		t.Fatalf("register %s: open DMs: %d %v", username, res.StatusCode, body)
	}
	return token, id
}

// registerDefault signs up an account with the settings a new account gets.
func registerDefault(t *testing.T, ts *httptest.Server, username string) (token string, id string) {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody(username))
	if res.StatusCode != 201 {
		t.Fatalf("register %s: %d %v", username, res.StatusCode, body)
	}
	user := body["user"].(map[string]any)
	return body["token"].(string), user["id"].(string)
}

func TestRegisterValidationAndAdminFlag(t *testing.T) {
	_, ts := newTestServer(t)
	res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("Alice"))
	if res.StatusCode != 201 {
		t.Fatalf("%d %v", res.StatusCode, body)
	}
	user := body["user"].(map[string]any)
	if user["username"] != "alice" || user["flags"].(float64) != 1 || user["has_recovery"] != true {
		t.Errorf("first user should be lowercased admin with recovery: %v", user)
	}
	if _, ok := user["auth_hash"]; ok {
		t.Error("auth_hash leaked")
	}
	res, body = do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("alice"))
	if res.StatusCode != 409 || errCode(body) != "username_taken" {
		t.Errorf("dup: %d %v", res.StatusCode, body)
	}
	for _, bad := range []string{"a", "..x", "has space", "UPPER-x", "toolong_toolong_toolong_toolong_x", ".dot", "a..b"} {
		res, body = do(t, ts, "POST", "/api/v1/auth/register", "", registerBody(bad))
		if res.StatusCode != 400 || errCode(body) != "invalid_username" {
			t.Errorf("username %q: %d %v", bad, res.StatusCode, body)
		}
	}
	b := registerBody("bob")
	b["kdf_params"] = map[string]int{"m": 1024, "t": 1, "p": 1, "v": 1}
	if res, body = do(t, ts, "POST", "/api/v1/auth/register", "", b); res.StatusCode != 400 || errCode(body) != "invalid_keys" {
		t.Errorf("weak kdf: %d %v", res.StatusCode, body)
	}
	b = registerBody("bob")
	delete(b, "recovery_auth_key")
	if res, body = do(t, ts, "POST", "/api/v1/auth/register", "", b); res.StatusCode != 400 {
		t.Errorf("half recovery pair: %d %v", res.StatusCode, body)
	}
	b = registerBody("bob")
	delete(b, "recovery_auth_key")
	delete(b, "enc_private_key_recovery")
	res, body = do(t, ts, "POST", "/api/v1/auth/register", "", b)
	if res.StatusCode != 201 || body["user"].(map[string]any)["flags"].(float64) != 0 || body["user"].(map[string]any)["has_recovery"] != false {
		t.Errorf("second user without recovery: %d %v", res.StatusCode, body)
	}
}

func TestRegistrationModes(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.Registration = "closed"
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("x1")); res.StatusCode != 403 || errCode(body) != "registration_closed" {
		t.Errorf("closed: %d %v", res.StatusCode, body)
	}
	srv.Cfg.Registration = "invite"
	srv.Cfg.RegistrationKey = "sesame"
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("x1")); res.StatusCode != 403 || errCode(body) != "invite_required" {
		t.Errorf("invite without key: %d %v", res.StatusCode, body)
	}
	b := registerBody("x1")
	b["registration_key"] = "sesame"
	if res, _ := do(t, ts, "POST", "/api/v1/auth/register", "", b); res.StatusCode != 201 {
		t.Errorf("invite with key: %d", res.StatusCode)
	}
	// the first account set Nexa up, so the configured admin name
	// is an ordinary name by now
	srv.Cfg.AdminUsername = "boss"
	srv.Cfg.Registration = "open"
	res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("boss"))
	if res.StatusCode != 201 || body["user"].(map[string]any)["flags"].(float64) != 0 {
		t.Errorf("ADMIN_USERNAME once Nexa is set up: %d %v", res.StatusCode, body)
	}
}

func TestKDFAndLogin(t *testing.T) {
	_, ts := newTestServer(t)
	register(t, ts, "alice")
	res, body := do(t, ts, "GET", "/api/v1/auth/kdf?username=ALICE", "", nil)
	if res.StatusCode != 200 || body["salt"] != b64(salt16) {
		t.Errorf("kdf known: %d %v", res.StatusCode, body)
	}
	_, b1 := do(t, ts, "GET", "/api/v1/auth/kdf?username=ghost", "", nil)
	_, b2 := do(t, ts, "GET", "/api/v1/auth/kdf?username=ghost", "", nil)
	s1, _ := base64.StdEncoding.DecodeString(b1["salt"].(string))
	if len(s1) != 16 || b1["salt"] != b2["salt"] || b1["params"] == nil {
		t.Errorf("fake kdf must be stable 16-byte salt with params: %v %v", b1, b2)
	}
	start := time.Now()
	res, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32)})
	if res.StatusCode != 200 || body["token"] == nil || body["enc_private_key"] != b64(wrapped) || body["user"].(map[string]any)["username"] != "alice" {
		t.Fatalf("login: %d %v", res.StatusCode, body)
	}
	if time.Since(start) < minLoginTime {
		t.Error("login should take at least the minimum time")
	}
	start = time.Now()
	res, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32b)})
	if res.StatusCode != 401 || errCode(body) != "invalid_credentials" || time.Since(start) < minLoginTime {
		t.Errorf("wrong key: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "nobody", "auth_key": b64(key32)})
	if res.StatusCode != 401 || errCode(body) != "invalid_credentials" {
		t.Errorf("unknown user: %d %v", res.StatusCode, body)
	}
	token := ""
	res, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32)})
	token = body["token"].(string)
	res, body = do(t, ts, "GET", "/api/v1/users/@me", token, nil)
	if res.StatusCode != 200 || body["username"] != "alice" {
		t.Errorf("me: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", "", nil); res.StatusCode != 401 {
		t.Error("me without token should be 401")
	}
	// per-username limiter: 5 failures then 429
	for i := 0; i < 6; i++ {
		res, _ = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "bob", "auth_key": b64(key32b)})
	}
	if res.StatusCode != 429 {
		t.Errorf("expected 429 after repeated failures, got %d", res.StatusCode)
	}
}

func TestSessionsLogoutPasswordChange(t *testing.T) {
	_, ts := newTestServer(t)
	tok1, _ := register(t, ts, "alice")
	_, body := do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32)})
	tok2 := body["token"].(string)
	res, body := do(t, ts, "GET", "/api/v1/auth/sessions", tok1, nil)
	list := body["_list"].([]any)
	if res.StatusCode != 200 || len(list) != 2 {
		t.Fatalf("sessions: %d %v", res.StatusCode, body)
	}
	var otherID string
	for _, it := range list {
		m := it.(map[string]any)
		if m["current"] != true {
			otherID = m["id"].(string)
		}
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/auth/sessions/"+otherID, tok1, nil); res.StatusCode != 204 {
		t.Errorf("delete session: %d", res.StatusCode)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", tok2, nil); res.StatusCode != 401 {
		t.Error("deleted session should be invalid")
	}
	// password change with new salt invalidates other sessions and updates kdf
	_, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32)})
	tok3 := body["token"].(string)
	newSalt := bytes.Repeat([]byte{9}, 16)
	res, body = do(t, ts, "POST", "/api/v1/auth/password", tok1, map[string]any{"auth_key": b64(key32), "new_auth_key": b64(key32b), "new_kdf_salt": b64(newSalt), "new_kdf_params": map[string]int{"m": 65536, "t": 3, "p": 1, "v": 1}, "new_enc_private_key": b64([]byte("rewrapped"))})
	if res.StatusCode != 204 {
		t.Fatalf("password change: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", tok3, nil); res.StatusCode != 401 {
		t.Error("other sessions should be logged out after password change")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", tok1, nil); res.StatusCode != 200 {
		t.Error("current session should survive password change")
	}
	_, body = do(t, ts, "GET", "/api/v1/auth/kdf?username=alice", "", nil)
	if body["salt"] != b64(newSalt) {
		t.Error("kdf salt should be updated")
	}
	res, body = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(key32b)})
	if res.StatusCode != 200 || body["enc_private_key"] != b64([]byte("rewrapped")) {
		t.Errorf("login with new key: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/auth/password", tok1, map[string]any{"auth_key": b64(key32), "new_auth_key": b64(key32b), "new_enc_private_key": b64([]byte("x"))}); res.StatusCode != 401 {
		t.Errorf("old password should be rejected now: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "POST", "/api/v1/auth/logout", tok1, nil); res.StatusCode != 204 {
		t.Error("logout")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", tok1, nil); res.StatusCode != 401 {
		t.Error("logged out token should be invalid")
	}
}

func TestRecoveryFlow(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	res, body := do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "alice", "recovery_auth_key": b64(key32b)})
	if res.StatusCode != 200 || body["enc_private_key_recovery"] != b64([]byte("wrapped-recovery")) || body["public_key"] != b64(pub32) {
		t.Fatalf("begin: %d %v", res.StatusCode, body)
	}
	ticket, _ := body["ticket"].(string)
	if ticket == "" {
		t.Fatalf("begin hands out the ticket finishing needs: %v", body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "alice", "recovery_auth_key": b64(key32)}); res.StatusCode != 401 || body["ticket"] != nil {
		t.Errorf("wrong recovery key: %d %v", res.StatusCode, body)
	}
	newKey := bytes.Repeat([]byte{3}, 32)
	newRec := bytes.Repeat([]byte{4}, 32)
	finish := map[string]any{"username": "alice", "recovery_auth_key": b64(key32b), "new_auth_key": b64(newKey), "new_kdf_salt": b64(salt16), "new_kdf_params": map[string]int{"m": 65536, "t": 3, "p": 1, "v": 1}, "new_enc_private_key": b64([]byte("rw2")), "new_recovery_auth_key": b64(newRec), "new_enc_private_key_recovery": b64([]byte("rr2"))}
	// going straight to the second step, which signs in, skips begin and its captcha: refused
	if res, body = do(t, ts, "POST", "/api/v1/auth/recover/finish", "", finish); res.StatusCode != 401 || errCode(body) != "recover_expired" {
		t.Fatalf("finish without a ticket: %d %v", res.StatusCode, body)
	}
	finish["ticket"] = "forged." + ticket
	if res, body = do(t, ts, "POST", "/api/v1/auth/recover/finish", "", finish); res.StatusCode != 401 || errCode(body) != "recover_expired" {
		t.Fatalf("finish with a forged ticket: %d %v", res.StatusCode, body)
	}
	// a ticket opens only the account it was given for
	register(t, ts, "mallory")
	mallory := map[string]any{}
	for k, v := range finish {
		mallory[k] = v
	}
	mallory["username"] = "mallory"
	mallory["ticket"] = ticket
	if res, body = do(t, ts, "POST", "/api/v1/auth/recover/finish", "", mallory); res.StatusCode != 401 || errCode(body) != "recover_expired" {
		t.Fatalf("finish with someone else's ticket: %d %v", res.StatusCode, body)
	}
	finish["ticket"] = ticket
	res, body = do(t, ts, "POST", "/api/v1/auth/recover/finish", "", finish)
	if res.StatusCode != 200 || body["token"] == nil || body["enc_private_key"] != b64([]byte("rw2")) {
		t.Fatalf("finish: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/users/@me", tok, nil); res.StatusCode != 401 {
		t.Error("old sessions must be invalidated by recovery")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/auth/login", "", map[string]any{"username": "alice", "auth_key": b64(newKey)}); res.StatusCode != 200 {
		t.Error("login with new password after recovery")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "alice", "recovery_auth_key": b64(key32b)}); res.StatusCode != 401 {
		t.Error("old recovery key must stop working")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "alice", "recovery_auth_key": b64(newRec)}); res.StatusCode != 200 {
		t.Error("new recovery key should work")
	}
	// an account without a recovery key answers exactly as one that does not
	// exist, so the answer says nothing about which usernames are taken
	b := registerBody("norec")
	delete(b, "recovery_auth_key")
	delete(b, "enc_private_key_recovery")
	do(t, ts, "POST", "/api/v1/auth/register", "", b)
	res, noKey := do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "norec", "recovery_auth_key": b64(key32b)})
	_, nobody := do(t, ts, "POST", "/api/v1/auth/recover/begin", "", map[string]any{"username": "nobody_here", "recovery_auth_key": b64(key32b)})
	if res.StatusCode != 401 || errCode(noKey) != "invalid_credentials" || errCode(nobody) != errCode(noKey) {
		t.Errorf("no recovery: %d %v / %v", res.StatusCode, noKey, nobody)
	}
}

func TestReadJSONRejectsTrailingAndBig(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/", bytes.NewReader([]byte(`{"a":1} {"b":2}`)))
	var dst map[string]any
	if err := ReadJSON(rec, req, &dst, 1024); err != ErrBadJSON {
		t.Errorf("trailing data: %v", err)
	}
	big, _ := json.Marshal(map[string]string{"x": string(bytes.Repeat([]byte("y"), 2000))})
	req = httptest.NewRequest("POST", "/", bytes.NewReader(big))
	if err := ReadJSON(rec, req, &dst, 1024); err != ErrTooLarge {
		t.Errorf("too large: %v", err)
	}
	req = httptest.NewRequest("POST", "/", bytes.NewReader([]byte(`{"ok":true}`)))
	if err := ReadJSON(rec, req, &dst, 1024); err != nil || dst["ok"] != true {
		t.Errorf("valid: %v", err)
	}
	_ = http.StatusOK
}

// While sign-ups are invite-only an invite lets in as many accounts as it has
// uses. A one-use code seen by many used to let all of them sign up, since
// signing up only checked that it was still live; now the sign-up spends the
// use, and the new account's first join through it rides on that use.
func TestAnInviteLetsInAsManyAccountsAsItHasUses(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	view := createServer(t, ts, tokA, "S")
	general := channelNamed(view, "general")["id"].(string)
	res, body := do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokA, map[string]any{"max_uses": 1, "max_age": 0})
	if res.StatusCode != 201 && res.StatusCode != 200 {
		t.Fatalf("invite: %d %v", res.StatusCode, body)
	}
	code := body["code"].(string)
	srv.Cfg.Registration = "invite"
	srv.Cfg.RegistrationKey = ""
	signUp := func(name string) (int, map[string]any) {
		b := registerBody(name)
		b["invite_code"] = code
		res, body := do(t, ts, "POST", "/api/v1/auth/register", "", b)
		return res.StatusCode, body
	}
	st, body := signUp("carol")
	if st != 201 {
		t.Fatalf("carol signs up with the invite: %d %v", st, body)
	}
	tokC := body["token"].(string)
	if st, body := signUp("dave"); st != 403 || errCode(body) != "invite_required" {
		t.Errorf("a second sign-up on a one-use invite: %d %v", st, body)
	}
	// the invite is spent for everyone else, and still open to carol
	if res, _ := do(t, ts, "GET", "/api/v1/invites/"+code, "", nil); res.StatusCode != 404 {
		t.Errorf("anonymous preview of a spent invite: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "GET", "/api/v1/invites/"+code, tokC, nil); res.StatusCode != 200 {
		t.Errorf("carol's preview: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/invites/"+code, tokC, map[string]any{}); res.StatusCode != 201 {
		t.Fatalf("carol joins on the use her sign-up spent: %d %v", res.StatusCode, body)
	}
	// that was her one ride: leaving and coming back needs a use of its own
	sid := view["id"].(string)
	if res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokC, nil); res.StatusCode != 204 && res.StatusCode != 200 {
		t.Fatalf("leave: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/invites/"+code, tokC, map[string]any{}); res.StatusCode != 404 {
		t.Errorf("rejoining on a spent invite: %d", res.StatusCode)
	}
	// the registration key still works on its own, spending no invite
	srv.Cfg.RegistrationKey = "sesame"
	b := registerBody("erin")
	b["registration_key"] = "sesame"
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", b); res.StatusCode != 201 {
		t.Errorf("sign-up with the key: %d %v", res.StatusCode, body)
	}
}

func TestTwoAccountsADayFromOneAddress(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.limSignup = ratelimit.New(ratelimit.Every(2, 24*time.Hour), 2, 25*time.Hour)
	register(t, ts, "first") // the first account never counts
	register(t, ts, "second")
	register(t, ts, "third")
	res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("fourth"))
	if res.StatusCode != 429 || errCode(body) != "too_many_signups" || res.Header.Get("Retry-After") == "" {
		t.Fatalf("a third account today: %d %v", res.StatusCode, body)
	}
}

// A reported bug: trying names people already had used up the two accounts a day.
func TestTakenUsernameSpendsNoSignup(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.limSignup = ratelimit.New(ratelimit.Every(2, 24*time.Hour), 2, 25*time.Hour)
	register(t, ts, "owner")
	for i := 0; i < 5; i++ {
		if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("owner")); res.StatusCode != 409 || errCode(body) != "username_taken" {
			t.Fatalf("taken name: %d %v", res.StatusCode, body)
		}
	}
	for _, name := range []string{"first", "second"} {
		if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody(name)); res.StatusCode != 201 {
			t.Fatalf("%s: %d %v", name, res.StatusCode, body)
		}
	}
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("third")); res.StatusCode != 429 || errCode(body) != "too_many_signups" {
		t.Fatalf("third: %d %v", res.StatusCode, body)
	}
}

func TestAdminCanTurnTheSignupLimitOff(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.limSignup = ratelimit.New(ratelimit.Every(2, 24*time.Hour), 2, 25*time.Hour)
	tokA, _ := register(t, ts, "first")
	register(t, ts, "second")
	register(t, ts, "third")
	if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tokA, map[string]any{"signup_ip_limit": "no"}); res.StatusCode != 400 {
		t.Fatalf("a non-boolean: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tokA, map[string]any{"signup_ip_limit": false})
	if res.StatusCode != 200 || body["signup_ip_limit"] != false {
		t.Fatalf("turning it off: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("fourth")); res.StatusCode != 201 {
		t.Fatalf("a third account with the limit off: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tokA, map[string]any{"signup_ip_limit": true}); res.StatusCode != 200 || body["signup_ip_limit"] != true {
		t.Fatalf("turning it on: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("fifth")); res.StatusCode != 429 || errCode(body) != "too_many_signups" {
		t.Fatalf("with the limit back on: %d %v", res.StatusCode, body)
	}
}

func TestSignupAllowanceCountsAnIPv6Prefix(t *testing.T) {
	srv, ts := newTestServer(t)
	register(t, ts, "first")
	srv.limSignup = ratelimit.New(ratelimit.Every(2, 24*time.Hour), 2, 25*time.Hour)
	for i, ip := range []string{"2001:4860:1234:5678::1", "2001:4860:1234:5678::2", "2001:4860:1234:5678::3"} {
		raw, _ := json.Marshal(registerBody([]string{"ipv6a", "ipv6b", "ipv6c"}[i]))
		req, _ := http.NewRequest("POST", ts.URL+"/api/v1/auth/register", bytes.NewReader(raw))
		req.Header.Set("CF-Connecting-IP", ip)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		want := http.StatusCreated
		if i == 2 {
			want = http.StatusTooManyRequests
		}
		if res.StatusCode != want {
			t.Errorf("signup from %s: got %d, want %d", ip, res.StatusCode, want)
		}
	}
}
