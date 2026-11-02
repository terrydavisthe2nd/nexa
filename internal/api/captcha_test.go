package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// turnstileStandIn answers siteverify the way Cloudflare does: tokens named
// "good-<action>" pass for that action, solved on chat.example, or on <host>
// for "good-<action>@<host>"; anything else fails.
func turnstileStandIn(t *testing.T, secret string) func() {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		out := map[string]any{"success": false, "error-codes": []string{"invalid-input-response"}}
		if r.PostForm.Get("secret") == "test-secret" {
			// what Cloudflare's test secret keys answer, whatever the token
			out = map[string]any{"success": true, "hostname": "example.com", "metadata": map[string]any{"result_with_testing_key": true}}
		} else if r.PostForm.Get("secret") == secret && strings.HasPrefix(r.PostForm.Get("response"), "good-") {
			action, host, found := strings.Cut(strings.TrimPrefix(r.PostForm.Get("response"), "good-"), "@")
			if !found {
				host = "chat.example"
			}
			out = map[string]any{"success": true, "action": action, "hostname": host}
		}
		_ = json.NewEncoder(w).Encode(out)
	}))
	prev := captchaVerifyURL
	captchaVerifyURL = srv.URL
	return func() {
		captchaVerifyURL = prev
		srv.Close()
	}
}

func withCaptcha(t *testing.T, ts *httptest.Server, method, path, token, captcha string, body any) (*http.Response, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(method, ts.URL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if captcha != "" {
		req.Header.Set(CaptchaHeader, captcha)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := map[string]any{}
	_ = json.Unmarshal(raw, &out)
	return res, out
}

func TestCaptchaGuardsAbusableActions(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice") // before captchas are on
	srv.Cfg.TurnstileSiteKey = "site-key"
	srv.Cfg.TurnstileSecretKey = "secret-key"
	srv.Cfg.PublicURL = "https://chat.example"
	defer turnstileStandIn(t, "secret-key")()

	// the public info about Nexa says how to show it, and never gives the secret away
	res, info := do(t, ts, "GET", "/api/v1/instance", "", nil)
	captcha, _ := info["captcha"].(map[string]any)
	if res.StatusCode != 200 || captcha["provider"] != "turnstile" || captcha["site_key"] != "site-key" {
		t.Fatalf("instance: %v", info["captcha"])
	}
	if raw, _ := json.Marshal(info); bytes.Contains(raw, []byte("secret-key")) {
		t.Fatal("the secret key must never reach a browser")
	}

	// no token, a bad one, or one solved on another site: refused
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "", registerBody("bob")); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("register without a token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "forged", registerBody("bob")); res.StatusCode != 403 || errCode(body) != "captcha_failed" {
		t.Errorf("register with a bad token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "good-register@evil.example", registerBody("bob")); res.StatusCode != 403 || errCode(body) != "captcha_failed" {
		t.Errorf("a token solved on another site: %d %v", res.StatusCode, body)
	}
	// a token solved for another action does not count
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "good-login", registerBody("bob")); res.StatusCode != 403 || errCode(body) != "captcha_failed" {
		t.Fatalf("register with a login token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "good-register", registerBody("bob")); res.StatusCode != 201 {
		t.Fatalf("register with a good token: %d %v", res.StatusCode, body)
	}

	// the other guarded actions
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/login", "", "", map[string]any{"username": "alice", "auth_key": b64(key32)}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("login without a token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/login", "", "good-login", map[string]any{"username": "alice", "auth_key": b64(key32)}); res.StatusCode != 200 {
		t.Errorf("login with a good token: %d %v", res.StatusCode, body)
	}
	// recovery signs in too: its first step needs the captcha, and its second step the first
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/recover/begin", "", "", map[string]any{"username": "alice", "recovery_auth_key": b64(key32b)}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("recovery without a token: %d %v", res.StatusCode, body)
	}
	finish := map[string]any{"username": "alice", "recovery_auth_key": b64(key32b), "new_auth_key": b64(key32), "new_kdf_salt": b64(salt16), "new_kdf_params": map[string]int{"m": 65536, "t": 3, "p": 1, "v": 1}, "new_enc_private_key": b64(wrapped)}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/recover/finish", "", "good-recover", finish); res.StatusCode != 401 || errCode(body) != "recover_expired" {
		t.Errorf("recovery's second step without its first: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/users/@me/relationships", tokA, "", map[string]any{"username": "bob"}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("friend request without a token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/users/@me/relationships", tokA, "good-friend_request", map[string]any{"username": "bob"}); res.StatusCode >= 300 {
		t.Errorf("friend request with a good token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/servers", tokA, "", map[string]any{"name": "Spam"}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("server create without a token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/servers", tokA, "good-create_server", map[string]any{"name": "Fine"}); res.StatusCode != 201 {
		t.Errorf("server create with a good token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/invites/nope", tokA, "", nil); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("invite join without a token: %d %v", res.StatusCode, body)
	}

	if res, body := withCaptcha(t, ts, "POST", "/api/v1/applications", tokA, "", map[string]any{"name": "Spambot"}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("app create without a token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/applications", tokA, "good-create_app", map[string]any{"name": "Fine"}); res.StatusCode != http.StatusOK {
		t.Errorf("app create with one good token: %d %v", res.StatusCode, body)
	}

	// ordinary requests never ask
	if res, _ := do(t, ts, "GET", "/api/v1/users/@me", tokA, nil); res.StatusCode != 200 {
		t.Errorf("unguarded request: %d", res.StatusCode)
	}
}

func TestCaptchaTestKeysStillWork(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.TurnstileSiteKey = "1x00000000000000000000AA"
	srv.Cfg.TurnstileSecretKey = "test-secret"
	defer turnstileStandIn(t, "secret-key")()
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "XXXX.DUMMY.TOKEN.XXXX", registerBody("bob")); res.StatusCode != 201 {
		t.Errorf("register with Cloudflare's test keys: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/login", "", "", map[string]any{"username": "bob", "auth_key": b64(key32)}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("even with test keys a token is required: %d %v", res.StatusCode, body)
	}
}

func TestCaptchaUnreachableIsNotAPass(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.TurnstileSiteKey = "site-key"
	srv.Cfg.TurnstileSecretKey = "secret-key"
	prev := captchaVerifyURL
	captchaVerifyURL = "http://127.0.0.1:1/unreachable"
	defer func() { captchaVerifyURL = prev }()
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/auth/register", "", "good-register", registerBody("bob")); res.StatusCode != 503 || errCode(body) != "captcha_unavailable" {
		t.Errorf("unreachable verifier: %d %v", res.StatusCode, body)
	}
}

// What a stolen session would be used for, and the abusable writes of a
// signed-in account, each want a captcha made for them.
func TestCaptchaGuardsAccountChanges(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice") // before captchas are on
	_, bobID := register(t, ts, "bob")
	srv.Cfg.TurnstileSiteKey = "site-key"
	srv.Cfg.TurnstileSecretKey = "secret-key"
	srv.Cfg.PublicURL = "https://chat.example"
	defer turnstileStandIn(t, "secret-key")()

	for _, c := range []struct {
		name, method, path, action string
		body                       any
	}{
		{"new username", "PATCH", "/api/v1/users/@me", "username", map[string]any{"username": "alice2"}},
		{"new password", "POST", "/api/v1/auth/password", "password", map[string]any{"auth_key": b64(key32)}},
		{"new recovery key", "POST", "/api/v1/auth/recovery", "recovery_key", map[string]any{"auth_key": b64(key32)}},
		{"two-factor off", "POST", "/api/v1/auth/mfa/disable", "mfa_disable", map[string]any{"auth_key": b64(key32)}},
		{"account deletion", "POST", "/api/v1/users/@me/delete", "delete_account", map[string]any{"auth_key": b64(key32)}},
		{"first message to a stranger", "POST", "/api/v1/users/@me/channels", "dm", map[string]any{"recipient_ids": []string{bobID}}},
		{"bug report", "POST", "/api/v1/bugs", "bug_report", bugBody("Broken thing")},
	} {
		if res, body := withCaptcha(t, ts, c.method, c.path, tokA, "", c.body); res.StatusCode != 403 || errCode(body) != "captcha_required" || res.Header.Get(CaptchaActionHeader) != c.action {
			t.Errorf("%s without a token: %d %v, action %q", c.name, res.StatusCode, body, res.Header.Get(CaptchaActionHeader))
		}
	}
	// the right token lets each through to its own checks
	if res, body := withCaptcha(t, ts, "PATCH", "/api/v1/users/@me", tokA, "good-username", map[string]any{"username": "alice2"}); res.StatusCode != 200 {
		t.Errorf("new username with a good token: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "POST", "/api/v1/users/@me/delete", tokA, "good-delete_account", map[string]any{"auth_key": b64(key32b)}); res.StatusCode != 401 || errCode(body) != "invalid_credentials" {
		t.Errorf("deletion with a good token and a wrong password: %d %v", res.StatusCode, body)
	}
	res, dm := withCaptcha(t, ts, "POST", "/api/v1/users/@me/channels", tokA, "good-dm", map[string]any{"recipient_ids": []string{bobID}})
	if res.StatusCode != 201 {
		t.Fatalf("first message with a good token: %d %v", res.StatusCode, dm)
	}
	// an existing conversation, and a profile save that keeps the name, never ask
	if res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{bobID}}); res.StatusCode >= 300 {
		t.Errorf("opening the conversation again: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/users/@me", tokA, map[string]any{"username": "alice2", "display_name": "Alice"}); res.StatusCode != 200 {
		t.Errorf("a save that keeps the username: %d %v", res.StatusCode, body)
	}
}

// Every other check of a password, and what puts something in front of
// everyone on Nexa, ask for a captcha too; what was already public does not.
func TestCaptchaGuardsPublishingAndPasswords(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice") // before captchas are on
	view := createServer(t, ts, tokA, "Spam Club")
	sid := view["id"].(string)
	res, pack := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "Pack"})
	if res.StatusCode != 201 {
		t.Fatalf("pack: %d %v", res.StatusCode, pack)
	}
	pid := pack["id"].(string)
	srv.Cfg.TurnstileSiteKey = "site-key"
	srv.Cfg.TurnstileSecretKey = "secret-key"
	srv.Cfg.PublicURL = "https://chat.example"
	defer turnstileStandIn(t, "secret-key")()

	for _, c := range []struct {
		name, method, path, action string
		body                       any
	}{
		{"two-factor setup", "POST", "/api/v1/auth/mfa/totp/setup", "mfa_setup", map[string]any{"auth_key": b64(key32)}},
		{"new backup codes", "POST", "/api/v1/auth/mfa/backup", "mfa_backup", map[string]any{"auth_key": b64(key32)}},
		{"a server on Discovery", "PATCH", "/api/v1/servers/" + sid, "discovery_list", map[string]any{"discoverable": true}},
		{"a public pack", "PATCH", "/api/v1/stickers/packs/" + pid, "pack_publish", map[string]any{"public": true}},
	} {
		if res, body := withCaptcha(t, ts, c.method, c.path, tokA, "", c.body); res.StatusCode != 403 || errCode(body) != "captcha_required" || res.Header.Get(CaptchaActionHeader) != c.action {
			t.Errorf("%s without a token: %d %v, action %q", c.name, res.StatusCode, body, res.Header.Get(CaptchaActionHeader))
		}
	}
	if res, body := withCaptcha(t, ts, "PATCH", "/api/v1/servers/"+sid, tokA, "good-discovery_list", map[string]any{"discoverable": true}); res.StatusCode != 200 {
		t.Fatalf("listing with a good token: %d %v", res.StatusCode, body)
	}
	// already listed, the rest of its settings change without one
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid, tokA, map[string]any{"discoverable": true, "discovery_tags": []string{"games"}}); res.StatusCode != 200 {
		t.Errorf("a listed server's settings: %d %v", res.StatusCode, body)
	}
	if res, body := withCaptcha(t, ts, "PATCH", "/api/v1/stickers/packs/"+pid, tokA, "good-pack_publish", map[string]any{"public": true}); res.StatusCode != 200 {
		t.Fatalf("publishing with a good token: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pid, tokA, map[string]any{"name": "Renamed"}); res.StatusCode != 200 {
		t.Errorf("renaming a public pack: %d %v", res.StatusCode, body)
	}
}

// hcaptchaStandIn answers siteverify the way hCaptcha does: tokens named
// "good-<anything>" pass, solved on chat.example, or on <host> for
// "good-<anything>@<host>"; anything else fails. hCaptcha binds no action.
func hcaptchaStandIn(t *testing.T, secret string) func() {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		out := map[string]any{"success": false, "error-codes": []string{"invalid-input-response"}}
		if r.PostForm.Get("secret") == secret && r.PostForm.Get("sitekey") != "" && strings.HasPrefix(r.PostForm.Get("response"), "good-") {
			_, host, found := strings.Cut(strings.TrimPrefix(r.PostForm.Get("response"), "good-"), "@")
			if !found {
				host = "chat.example"
			}
			out = map[string]any{"success": true, "hostname": host}
		}
		_ = json.NewEncoder(w).Encode(out)
	}))
	prev := hcaptchaVerifyURL
	hcaptchaVerifyURL = srv.URL
	return func() {
		hcaptchaVerifyURL = prev
		srv.Close()
	}
}

// Apps from before Turnstile came back send unmarked hCaptcha tokens and read
// only "captcha"; current apps mark Turnstile tokens and read "turnstile".
func TestHCaptchaFallbackForOlderApps(t *testing.T) {
	srv, ts := newTestServer(t)
	register(t, ts, "alice")
	srv.Cfg.PublicURL = "https://chat.example"
	srv.Cfg.TurnstileSiteKey, srv.Cfg.TurnstileSecretKey = "t-site", "t-secret"
	srv.Cfg.HCaptchaSiteKey, srv.Cfg.HCaptchaSecretKey = "h-site", "h-secret"
	defer turnstileStandIn(t, "t-secret")()
	defer hcaptchaStandIn(t, "h-secret")()
	res, info := do(t, ts, "GET", "/api/v1/instance", "", nil)
	captcha, _ := info["captcha"].(map[string]any)
	turnstile, _ := info["turnstile"].(map[string]any)
	if res.StatusCode != 200 || captcha["provider"] != "hcaptcha" || captcha["site_key"] != "h-site" || turnstile["site_key"] != "t-site" {
		t.Fatalf("instance info: %v", info)
	}
	send := func(name, provider, token string) int {
		b, _ := json.Marshal(registerBody(name))
		req, _ := http.NewRequest("POST", ts.URL+"/api/v1/auth/register", bytes.NewReader(b))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(CaptchaHeader, token)
		if provider != "" {
			req.Header.Set(CaptchaProviderHeader, provider)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if c := send("old", "", "good-anything"); c != 201 {
		t.Errorf("an older app's hCaptcha token: %d", c)
	}
	if c := send("old2", "", "good-x@evil.example"); c != 403 {
		t.Errorf("an hCaptcha token from another site: %d", c)
	}
	if c := send("new", "turnstile", "good-register"); c != 201 {
		t.Errorf("a current app's Turnstile token: %d", c)
	}
	if c := send("new2", "turnstile", "good-login"); c != 403 {
		t.Errorf("a Turnstile token for another action: %d", c)
	}
	if c := send("new3", "turnstile", "good-anything"); c != 403 {
		t.Errorf("an hCaptcha-style token marked Turnstile: %d", c)
	}
}
