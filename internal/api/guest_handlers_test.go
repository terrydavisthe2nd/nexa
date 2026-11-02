package api

import (
	"context"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
)

// guestJoin lets a guest into a call through a link and returns their token and id.
func guestJoin(t *testing.T, ts *httptest.Server, code, name string) (string, string) {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/guest/join", "", map[string]any{"code": code, "display_name": name})
	if res.StatusCode != 201 {
		t.Fatalf("guest join: %d %v", res.StatusCode, body)
	}
	g := body["guest"].(map[string]any)
	return body["token"].(string), g["id"].(string)
}

func guestLink(t *testing.T, ts *httptest.Server, token, channel string, epoch int) string {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/channels/"+channel+"/guest-links", token, map[string]any{"epoch": epoch})
	if res.StatusCode != 201 {
		t.Fatalf("guest link: %d %v", res.StatusCode, body)
	}
	return body["code"].(string)
}

func TestGuestsNeedTheSFU(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	befriend(t, ts, tokA, tokB, "bob", idA)
	dm := dmBetween(t, ts, tokA, idB)
	epoch := createEpoch(t, ts, tokA, dm)
	// peer to peer a link's code alone would let someone hear the call, so no guests unless asked for
	if res, body := do(t, ts, "POST", "/api/v1/channels/"+dm+"/guest-links", tokA, map[string]any{"epoch": epoch}); res.StatusCode != 403 || errCode(body) != "guests_off" {
		t.Errorf("link without the SFU: %d %v", res.StatusCode, body)
	}
	if _, body := do(t, ts, "GET", "/api/v1/voice/ice", tokA, nil); body["guests"] != false {
		t.Errorf("ice should say guests are off: %v", body)
	}
	srv.Cfg.GuestLinksP2P = true
	code := guestLink(t, ts, tokA, dm, epoch)
	srv.Cfg.GuestLinksP2P = false
	if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+code, "", nil); res.StatusCode != 404 {
		t.Errorf("link check with guests off: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/guest/join", "", map[string]any{"code": code, "display_name": "X"}); res.StatusCode != 404 {
		t.Errorf("join with guests off: %d", res.StatusCode)
	}
}

func TestGuestInADMCall(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.GuestLinksP2P = true
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	befriend(t, ts, tokA, tokB, "bob", idA)
	dm := dmBetween(t, ts, tokA, idB)
	epoch := createEpoch(t, ts, tokA, dm)

	// a link carries the newest key, so an older epoch is refused
	if res, body := do(t, ts, "POST", "/api/v1/channels/"+dm+"/guest-links", tokA, map[string]any{"epoch": epoch + 1}); res.StatusCode != 409 || errCode(body) != "stale_epoch" {
		t.Errorf("stale epoch: %d %v", res.StatusCode, body)
	}
	code := guestLink(t, ts, tokA, dm, epoch)
	if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+code, "", nil); res.StatusCode != 200 {
		t.Errorf("link check: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "GET", "/api/v1/guest/links/nope", "", nil); res.StatusCode != 404 || errCode(body) != "invalid_guest_link" {
		t.Errorf("bad link check: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/guest/join", "", map[string]any{"code": code, "display_name": "  "}); res.StatusCode != 400 {
		t.Errorf("empty name: %d %v", res.StatusCode, body)
	}
	tokG, idG := guestJoin(t, ts, code, "Visitor")

	// a guest token opens the call's routes and nothing else
	for _, path := range []string{"/api/v1/users/@me", "/api/v1/users/@me/channels", "/api/v1/users/@me/relationships", "/api/v1/channels/" + dm + "/messages", "/api/v1/users/" + idA, "/api/v1/keys/grants"} {
		if res, _ := do(t, ts, "GET", path, tokG, nil); res.StatusCode != 401 {
			t.Errorf("guest reached %s: %d", path, res.StatusCode)
		}
	}
	if res, _ := do(t, ts, "POST", "/api/v1/channels/"+dm+"/guest-links", tokG, map[string]any{"epoch": epoch}); res.StatusCode != 401 {
		t.Errorf("guest made a link: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/voice/ice", tokG, nil); res.StatusCode != 200 {
		t.Errorf("guest ice: %d", res.StatusCode)
	}

	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)
	gg := connectGWReady(t, ts, tokG)
	if g, _ := gg.ready["guest"].(map[string]any); g == nil || g["channel_id"] != dm || g["display_name"] != "Visitor" {
		t.Errorf("guest ready: %v", gg.ready["guest"])
	}
	for _, k := range []string{"channels", "users", "relationships", "read_states", "key_grants", "servers"} {
		if list, _ := gg.ready[k].([]any); len(list) != 0 {
			t.Errorf("guest ready carries %s: %v", k, gg.ready[k])
		}
	}

	// the guest waits in the call: the people in the DM see them, and nobody is rung
	gg.op(t, "voice_state", map[string]any{"channel_id": dm})
	for _, g := range []*gw{ga, gb, gg} {
		ev := g.next("voice_state", 3*time.Second)
		u, _ := ev["user"].(map[string]any)
		mark, _ := ev["guest"].(map[string]any)
		if ev == nil || ev["user_id"] != idG || u == nil || u["display_name"] != "Visitor" || u["flags"].(float64) != float64(store.FlagGuest) || mark == nil || int(mark["epoch"].(float64)) != epoch {
			t.Fatalf("guest join event: %v", ev)
		}
	}
	if ev := gb.next("call_ring", 500*time.Millisecond); ev != nil {
		t.Errorf("a guest rang bob: %v", ev)
	}
	// a guest has no other channel to be in
	gg.op(t, "voice_state", map[string]any{"channel_id": ids.New().String()})
	if ev := gg.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "not_found" {
		t.Errorf("guest in another channel: %v", ev)
	}

	// alice joining is still the start of the call: bob is rung, and the guest sees her
	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gb.next("call_ring", 3*time.Second); ev == nil || ev["from"] != idA {
		t.Errorf("bob not rung when alice joined the guest: %v", ev)
	}
	if ev := gg.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idA || ev["user"].(map[string]any)["display_name"] != "User alice" {
		t.Errorf("guest did not see alice: %v", ev)
	}
	// typing and presence are not a guest's to send
	gg.op(t, "typing", map[string]any{"channel_id": dm})
	gg.op(t, "presence", map[string]any{"status_text": "hello"})
	if ev := ga.next("typing", 500*time.Millisecond); ev != nil {
		t.Errorf("guest typing reached alice: %v", ev)
	}
	// someone connecting now finds the guest in the call, marked as one
	ga2 := connectGWReady(t, ts, tokA)
	found := false
	for _, x := range ga2.ready["voice_states"].([]any) {
		vs := x.(map[string]any)
		if vs["user_id"] == idG && vs["guest"] != nil {
			found = true
		}
	}
	if !found {
		t.Errorf("alice's ready misses the guest: %v", ga2.ready["voice_states"])
	}
	ga2.close()
	gg.op(t, "voice_signal", map[string]any{"to": idA, "payload": map[string]any{"guest_keys": "x"}})
	if ev := ga.next("voice_signal", 3*time.Second); ev == nil || ev["from"] != idG {
		t.Errorf("guest signal: %v", ev)
	}

	// bob removes the guest: out of the call, told why, and the token is dead
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/guests/"+idG, tokB, nil); res.StatusCode != 204 {
		t.Fatalf("remove guest: %d %v", res.StatusCode, body)
	}
	if ev := gg.next("guest_ended", 3*time.Second); ev == nil || ev["reason"] != "removed" {
		t.Errorf("guest not told: %v", ev)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/voice/ice", tokG, nil); res.StatusCode != 401 {
		t.Errorf("removed guest still in: %d", res.StatusCode)
	}
	// the guest's link carried the call's key; alice, still in the call, is asked to change it
	if ev := gb.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != dm {
		t.Errorf("removing a guest from a live call rotates the key: %v", ev)
	}
	for {
		ev := ga.next("voice_state", 3*time.Second)
		if ev == nil {
			t.Fatal("alice did not see the guest leave")
		}
		if ev["user_id"] == idG && ev["channel_id"] == nil {
			break
		}
	}
}

func TestGuestLinksInAServer(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.GuestLinksP2P = true
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Guests")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	epoch := createEpoch(t, ts, tokA, sid)

	// only calls take guests
	if res, body := do(t, ts, "POST", "/api/v1/channels/"+general+"/guest-links", tokA, map[string]any{"epoch": epoch}); res.StatusCode != 400 || errCode(body) != "no_call" {
		t.Errorf("text channel link: %d %v", res.StatusCode, body)
	}
	codeB := guestLink(t, ts, tokB, voiceCh, epoch)
	codeA := guestLink(t, ts, tokA, voiceCh, epoch)
	// everyone sees only their own links' codes, even alice who manages the channel:
	// a code is half a working link to whoever holds the key
	_, body := do(t, ts, "GET", "/api/v1/channels/"+voiceCh+"/guest-links", tokB, nil)
	if list := body["_list"].([]any); len(list) != 1 || list[0].(map[string]any)["code"] != codeB {
		t.Errorf("bob's links: %v", body)
	}
	_, body = do(t, ts, "GET", "/api/v1/channels/"+voiceCh+"/guest-links", tokA, nil)
	if list := body["_list"].([]any); len(list) != 1 || list[0].(map[string]any)["code"] != codeA {
		t.Errorf("alice's view of the links: %v", body)
	}
	// bob cannot revoke alice's
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+voiceCh+"/guest-links/"+codeA, tokB, nil); res.StatusCode != 403 {
		t.Errorf("bob revoked alice's link: %d", res.StatusCode)
	}

	// @everyone may not speak there, so neither may a guest
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+voiceCh+"/permissions/"+sid, tokA, map[string]any{"type": "role", "allow": "0", "deny": strconv.FormatUint(uint64(perms.Speak), 10)}); res.StatusCode >= 300 {
		t.Fatalf("overwrite: %d %v", res.StatusCode, body)
	}
	tokG, idG := guestJoin(t, ts, codeB, "Guest One")
	gg := connectGW(t, ts, tokG)
	gg.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gg.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idG || ev["server_mute"] != true {
		t.Errorf("guest could speak where @everyone cannot: %v", ev)
	}

	// alice revokes bob's link: its guest is put out
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+voiceCh+"/guest-links/"+codeB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("revoke: %d", res.StatusCode)
	}
	if ev := gg.next("guest_ended", 3*time.Second); ev == nil || ev["reason"] != "revoked" {
		t.Errorf("revoked guest not told: %v", ev)
	}
	if _, ok := srv.Voice.Get(mustParseID(t, idG)); ok {
		t.Error("revoked guest still in the call")
	}

	// revoking all: bob's reaches only his own links, alice's every link of the channel
	codeB3 := guestLink(t, ts, tokB, voiceCh, epoch)
	codeA3 := guestLink(t, ts, tokA, voiceCh, epoch)
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+voiceCh+"/guest-links", tokB, nil); res.StatusCode != 200 || body["revoked"].(float64) != 1 {
		t.Errorf("bob revokes all of his: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+codeA3, "", nil); res.StatusCode != 200 {
		t.Errorf("bob's revoke took alice's link: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+codeB3, "", nil); res.StatusCode != 404 {
		t.Errorf("bob's link survived: %d", res.StatusCode)
	}
	codeB4 := guestLink(t, ts, tokB, voiceCh, epoch)
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+voiceCh+"/guest-links", tokA, nil); res.StatusCode != 200 || body["revoked"].(float64) < 2 {
		t.Errorf("alice revokes every link: %d %v", res.StatusCode, body)
	}
	for _, c := range []string{codeA, codeA3, codeB4} {
		if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+c, "", nil); res.StatusCode != 404 {
			t.Errorf("link %s survived alice's revoke: %d", c, res.StatusCode)
		}
	}
	codeA = guestLink(t, ts, tokA, voiceCh, epoch)

	// a link stops working once whoever made it cannot make it any more
	tokG2, idG2 := guestJoin(t, ts, codeA, "Guest Two")
	_ = tokG2
	codeB2 := guestLink(t, ts, tokB, voiceCh, epoch)
	if res, _ := do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokB, nil); res.StatusCode >= 300 {
		t.Fatalf("bob leaves: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/guest/links/"+codeB2, "", nil); res.StatusCode != 404 {
		t.Errorf("link of someone who left still works: %d", res.StatusCode)
	}
	// the sweep lets go of guests whose page is gone, and keeps alice's link's guest until then
	ctx := context.Background()
	now := time.Now()
	srv.sweepGuests(ctx, now)
	if _, err := srv.Store.GuestByID(ctx, mustParseID(t, idG2)); err != nil {
		t.Fatalf("guest swept at once: %v", err)
	}
	srv.sweepGuests(ctx, now.Add(guestAwayGrace+time.Second))
	if _, err := srv.Store.GuestByID(ctx, mustParseID(t, idG2)); err == nil {
		t.Error("a guest with no connection was kept")
	}
}

func TestGuestLinkJoinNeedsCaptchaAndLimits(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.GuestLinksP2P = true
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	befriend(t, ts, tokA, tokB, "bob", mustID(t, ts, tokA))
	dm := dmBetween(t, ts, tokA, idB)
	code := guestLink(t, ts, tokA, dm, createEpoch(t, ts, tokA, dm))
	for i := 0; i < maxGuestsPerLink; i++ {
		guestJoin(t, ts, code, "Guest")
	}
	if res, body := do(t, ts, "POST", "/api/v1/guest/join", "", map[string]any{"code": code, "display_name": "One Too Many"}); res.StatusCode != 403 || errCode(body) != "guest_limit" {
		t.Errorf("guest limit: %d %v", res.StatusCode, body)
	}

	secret := "1x0000000000000000000000000000000AA"
	restore := turnstileStandIn(t, secret)
	defer restore()
	srv.Cfg.TurnstileSiteKey, srv.Cfg.TurnstileSecretKey = "site", secret
	if res, body := do(t, ts, "POST", "/api/v1/guest/join", "", map[string]any{"code": code, "display_name": "Bot"}); res.StatusCode != 403 || errCode(body) != "captcha_required" {
		t.Errorf("join without a captcha: %d %v", res.StatusCode, body)
	}
}
