package api

import (
	"fmt"
	"testing"

	"evilion.xyz/chat/internal/store"
)

// The limits a script would run into first: reactions, messages spread over
// many channels, searches, and building a server without end.
func TestAbuseLimits(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, aliceID := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Home")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	epoch := createEpoch(t, ts, tok, sid)

	res, msg := sendMsg(t, ts, tok, general, sid, epoch, "hi", "n0", nil)
	if res.StatusCode != 201 {
		t.Fatalf("send: %d %v", res.StatusCode, msg)
	}
	mid := msg["id"].(string)

	// reactions: a burst, then 429 with Retry-After
	path := "/api/v1/channels/" + general + "/messages/" + mid + "/reactions/%F0%9F%91%8D/@me"
	limited := false
	for i := 0; i < 40 && !limited; i++ {
		method := "PUT"
		if i%2 == 1 {
			method = "DELETE"
		}
		res, _ := do(t, ts, method, path, tok, nil)
		limited = res.StatusCode == 429 && res.Header.Get("Retry-After") != ""
	}
	if !limited {
		t.Error("toggling a reaction forty times was never limited")
	}

	// messages: five a channel is fine, but not the same in channel after channel
	sent, limited := 1, false
	for c := 0; c < 8 && !limited; c++ {
		res, ch := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tok, map[string]any{"name": fmt.Sprintf("c%d", c), "type": "text"})
		if res.StatusCode != 201 {
			t.Fatalf("channel create: %d %v", res.StatusCode, ch)
		}
		for i := 0; i < 5 && !limited; i++ {
			res, _ := sendMsg(t, ts, tok, ch["id"].(string), sid, epoch, "spam", fmt.Sprintf("n%d-%d", c, i), nil)
			if res.StatusCode == 429 {
				limited = true
			} else {
				sent++
			}
		}
	}
	if !limited || sent > 31 {
		t.Errorf("messages across channels: limited %v after %d", limited, sent)
	}

	// searching people
	limited = false
	for i := 0; i < 40 && !limited; i++ {
		res, _ := do(t, ts, "GET", "/api/v1/users/search?q=al", tok, nil)
		limited = res.StatusCode == 429
	}
	if !limited {
		t.Error("user search was never limited")
	}

	// a server holds at most 250 roles, whatever the rate
	var last map[string]any
	made := 0
	for i := 0; i < store.MaxServerRoles+1; i++ {
		if i%25 == 0 {
			srv.limBuild.Reset(aliceID)
			srv.limGeneric.Reset("u:" + aliceID)
		}
		var res2 = 0
		r, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/roles", tok, map[string]any{"name": "r"})
		res2, last = r.StatusCode, body
		if res2 != 200 && res2 != 201 {
			break
		}
		made++
	}
	if made != store.MaxServerRoles-1 || errCode(last) != "too_many_roles" {
		t.Errorf("roles: made %d, then %v", made, last)
	}
}

// Past the free allowance a captcha buys a batch more, and the captchas
// themselves run out; with captchas off the free allowance is all there is.
func TestStepUpLimits(t *testing.T) {
	serial(t)
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Home")
	general := channelNamed(view, "general")["id"].(string)
	invite := func(captcha string) (int, string) {
		res, body := withCaptcha(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tok, captcha, map[string]any{})
		return res.StatusCode, errCode(body)
	}
	free := srv.limBuild.Burst()
	for i := 0; i < free; i++ {
		if code, e := invite(""); code != 200 && code != 201 {
			t.Fatalf("free invite %d: %d %s", i, code, e)
		}
	}
	// captchas off: past the free allowance is a plain 429
	if code, _ := invite(""); code != 429 {
		t.Fatalf("past the free allowance without captchas: %d", code)
	}

	srv.Cfg.TurnstileSiteKey = "site-key"
	srv.Cfg.TurnstileSecretKey = "secret-key"
	srv.Cfg.PublicURL = "https://chat.example"
	defer turnstileStandIn(t, "secret-key")()
	if code, e := invite(""); code != 403 || e != "captcha_required" {
		t.Fatalf("past the free allowance: %d %s", code, e)
	}
	if code, e := invite("forged"); code != 403 || e != "captcha_failed" {
		t.Fatalf("a bad captcha: %d %s", code, e)
	}
	// one captcha, then the rest of its batch with none
	for i := 0; i < srv.stepBuild.batch; i++ {
		captcha := ""
		if i == 0 {
			captcha = "good-build"
		}
		if code, e := invite(captcha); code != 200 && code != 201 {
			t.Fatalf("batch request %d: %d %s", i, code, e)
		}
	}
	if code, e := invite(""); code != 403 || e != "captcha_required" {
		t.Fatalf("after the batch: %d %s", code, e)
	}
	// the captchas run out too: one was spent above, the rest buy a batch each
	solves := 1
	for ; solves <= srv.stepBuild.solves.Burst(); solves++ {
		srv.stepBuild.left = map[string]permit{} // as though each batch were used up
		if code, _ := invite("good-build"); code == 429 {
			break
		}
	}
	if solves != srv.stepBuild.solves.Burst() {
		t.Errorf("captchas that counted: %d, want %d", solves, srv.stepBuild.solves.Burst())
	}
}
