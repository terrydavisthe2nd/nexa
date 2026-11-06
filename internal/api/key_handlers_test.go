package api

import (
	"bytes"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func TestARecipientCanRejectOnlyItsExactUnusableGrant(t *testing.T) {
	srv, ts := newTestServer(t)
	a, idA := register(t, ts, "alice")
	b, idB := register(t, ts, "bob")
	conv := dmBetween(t, ts, a, idB)
	// The usual fixture also grants everyone; create the epoch alone so the
	// poisoned wrapper really is the first stored for this recipient.
	if res, body := do(t, ts, "POST", "/api/v1/keys/epochs", a, map[string]any{"conv_id": conv}); res.StatusCode != 201 {
		t.Fatalf("epoch: %d %v", res.StatusCode, body)
	}
	bad := []byte("unopenable grant")
	for _, target := range []string{idA, idB} {
		if res, body := do(t, ts, "POST", "/api/v1/keys/grants", a, []map[string]any{{"conv_id": conv, "epoch": 0, "user_id": target, "wrapped_key": b64(bad)}}); res.StatusCode != 200 {
			t.Fatalf("grant: %d %v", res.StatusCode, body)
		}
	}
	cid, _ := ids.Parse(conv)
	bob, _ := ids.Parse(idB)
	// A stale report cannot remove a different wrapper that replaced it.
	if res, _ := do(t, ts, "POST", "/api/v1/keys/request", b, map[string]any{"conv_id": conv, "bad_epoch": 0, "bad_wrapped_key": b64([]byte("different"))}); res.StatusCode != 204 {
		t.Fatal(res.StatusCode)
	}
	if _, err := srv.Store.Grant(t.Context(), cid, 0, bob); err != nil {
		t.Fatal("different wrapper was deleted", err)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/keys/request", b, map[string]any{"conv_id": conv, "bad_epoch": 0, "bad_wrapped_key": b64(bad)}); res.StatusCode != 204 {
		t.Fatal(res.StatusCode)
	}
	if got, err := srv.Store.Grant(t.Context(), cid, 0, bob); err == nil {
		t.Fatalf("unusable grant still prevents a replacement: %q (expected %q)", got.WrappedKey, bad)
	}
	good := []byte("replacement grant")
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", a, []map[string]any{{"conv_id": conv, "epoch": 0, "user_id": idB, "wrapped_key": b64(good)}}); res.StatusCode != 200 {
		t.Fatalf("replacement: %d %v", res.StatusCode, body)
	}
	grant, err := srv.Store.Grant(t.Context(), cid, 0, bob)
	if err != nil || !bytes.Equal(grant.WrappedKey, good) {
		t.Fatalf("replacement not stored: %v", err)
	}
	alice, _ := ids.Parse(idA)
	if _, err := srv.Store.Grant(t.Context(), cid, 0, alice); err != nil {
		t.Fatal("recipient deleted someone else's grant", err)
	}
}

func TestKeyGrantsAndNeeds(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	_, idD := register(t, ts, "dave")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}})
	g := body["id"].(string)
	// non-member cannot create an epoch
	if res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": "999"}); res.StatusCode != 404 {
		t.Error("unknown conv")
	}
	res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": g})
	if res.StatusCode != 201 || body["epoch"].(float64) != 0 || len(body["user_ids"].([]any)) != 3 {
		t.Fatalf("epoch: %d %v", res.StatusCode, body)
	}
	// bob holds nothing yet → cannot grant
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idC, "wrapped_key": b64([]byte("x"))}}); res.StatusCode != 403 || errCode(body) != "not_holder" {
		t.Errorf("non-holder grant: %d %v", res.StatusCode, body)
	}
	// alice self-grants
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idA, "wrapped_key": b64([]byte("a"))}}); res.StatusCode != 200 || body["inserted"].(float64) != 1 {
		t.Fatalf("self grant: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil)
	needs := body["_list"].([]any)
	if res.StatusCode != 200 || len(needs) != 1 || len(needs[0].(map[string]any)["user_ids"].([]any)) != 2 {
		t.Fatalf("needs: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idD, "wrapped_key": b64([]byte("d"))}}); res.StatusCode != 403 || errCode(body) != "not_entitled" {
		t.Errorf("grant to outsider: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idB, "wrapped_key": b64([]byte("b"))}}); res.StatusCode != 200 || body["inserted"].(float64) != 1 {
		t.Fatalf("grant bob: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/keys/grants", tokB, nil)
	if l := body["_list"].([]any); len(l) != 1 || l[0].(map[string]any)["wrapped_key"] != b64([]byte("b")) || l[0].(map[string]any)["wrapped_by"] != idA {
		t.Errorf("bob's grants: %v", body)
	}
	// bob now holds the latest epoch and can grant carol
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idC, "wrapped_key": b64([]byte("c"))}}); res.StatusCode != 200 {
		t.Errorf("bob grants carol: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil)
	if len(body["_list"].([]any)) != 0 {
		t.Errorf("no more needs: %v", body)
	}
	// rotation
	askForRotation(t, srv, g)
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": g, "reason": rotationReason, "commitment": testCommitment})
	if res.StatusCode != 201 || body["epoch"].(float64) != 1 {
		t.Fatalf("rotate: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+g, tokC, nil)
	if res.StatusCode != 200 || len(body["epochs"].([]any)) != 2 || body["flags"].(map[string]any)["needs_rotation"] != false {
		t.Errorf("epochs: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+g, "", nil); res.StatusCode != 401 {
		t.Error("auth required")
	}
	res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": 0, "user_id": idB, "wrapped_key": b64(make([]byte, 600))}})
	if res.StatusCode != 400 {
		t.Errorf("oversized grant: %d %v", res.StatusCode, body)
	}
}

// Someone joining a server is handed every key it has had, the older ones
// too, so they read what was said before they came; and a device still
// waiting can ask the holders again.
func TestJoiningHandsOnEveryKey(t *testing.T) {
	api, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	srv := createServer(t, ts, tokA, "Old Server")
	conv := srv["id"].(string)
	general := channelNamed(srv, "general")["id"].(string)
	// two keys: the first, and the one that replaced it when someone left
	for epoch := 0; epoch < 2; epoch++ {
		if epoch > 0 {
			askForRotation(t, api, conv)
		}
		if res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": conv, "commitment": testCommitment}); res.StatusCode != 201 {
			t.Fatalf("epoch %d: %d %v", epoch, res.StatusCode, body)
		}
		if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": conv, "epoch": epoch, "user_id": idA, "wrapped_key": b64([]byte("a"))}}); res.StatusCode != 200 {
			t.Fatalf("self grant %d: %d %v", epoch, res.StatusCode, body)
		}
	}
	ga := connectGW(t, ts, tokA)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	asked := map[float64]bool{}
	for i := 0; i < 2; i++ {
		ev := ga.nextWhere("keys_needed", 3*time.Second, func(d map[string]any) bool { return d["conv_id"] == conv })
		if ev == nil {
			t.Fatalf("keys asked for after the join: %v", asked)
		}
		if ids := ev["user_ids"].([]any); len(ids) != 1 || ids[0] != idB {
			t.Errorf("who lacks epoch %v: %v", ev["epoch"], ids)
		}
		asked[ev["epoch"].(float64)] = true
	}
	if !asked[0] || !asked[1] {
		t.Errorf("both keys asked for: %v", asked)
	}
	// alice's app missed it; bob's asks again, and alice hears once more
	if res, body := do(t, ts, "POST", "/api/v1/keys/request", tokB, map[string]any{"conv_id": conv}); res.StatusCode != 204 {
		t.Fatalf("ask again: %d %v", res.StatusCode, body)
	}
	if ev := ga.nextWhere("keys_needed", 3*time.Second, func(d map[string]any) bool { return d["conv_id"] == conv }); ev == nil {
		t.Error("asking again reached nobody")
	}
	// someone not in the conversation cannot make its holders hear about it
	tokC, _ := register(t, ts, "carol")
	if res, _ := do(t, ts, "POST", "/api/v1/keys/request", tokC, map[string]any{"conv_id": conv}); res.StatusCode != 404 {
		t.Errorf("outsider asking: %d", res.StatusCode)
	}
	// and nobody can ask without pause
	for i := 0; i < 3; i++ {
		do(t, ts, "POST", "/api/v1/keys/request", tokB, map[string]any{"conv_id": conv})
	}
	if res, _ := do(t, ts, "POST", "/api/v1/keys/request", tokB, map[string]any{"conv_id": conv}); res.StatusCode != 429 {
		t.Errorf("asking without pause: %d", res.StatusCode)
	}
	// granted both, bob needs nothing more
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{
		{"conv_id": conv, "epoch": 0, "user_id": idB, "wrapped_key": b64([]byte("b0"))},
		{"conv_id": conv, "epoch": 1, "user_id": idB, "wrapped_key": b64([]byte("b1"))},
	}); res.StatusCode != 200 || body["inserted"].(float64) != 2 {
		t.Fatalf("grants: %d %v", res.StatusCode, body)
	}
	if _, body := do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil); len(body["_list"].([]any)) != 0 {
		t.Errorf("needs after granting both: %v", body)
	}
}

// On 2026-11-06 a new member of a 350-person server posted one epoch with the
// reason "probe" and handed out no key. It became the only epoch messages
// could use, so nobody could send in any channel on the server's key.
func TestAMemberCannotReplaceAKeyNobodyAskedToReplace(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	srv := createServer(t, ts, tokA, "Keys")
	conv := srv["id"].(string)
	general := channelNamed(srv, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	createEpoch(t, ts, tokA, conv)

	post := func(tok, target, reason string) int {
		res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tok, map[string]any{"conv_id": target, "reason": reason, "commitment": testCommitment})
		return res.StatusCode
	}
	for _, reason := range []string{"probe", "", rotationReason} {
		if code := post(tokB, conv, reason); code != 409 {
			t.Errorf("a member replaced the server's key with reason %q: %d", reason, code)
		}
	}
	if code := post(tokB, conv, manualRotationReason); code != 403 {
		t.Errorf("by hand, without Manage Server: %d", code)
	}
	// A public channel shares the server's key. One of its own would move the
	// channel onto a key nobody holds, the same outage a channel at a time.
	if code := post(tokB, general, "probe"); code != 409 {
		t.Errorf("a key for a public channel: %d", code)
	}
	if _, body := do(t, ts, "GET", "/api/v1/channels/"+general, tokB, nil); body["key_conv_id"] != conv {
		t.Errorf("the channel moved off the server's key: %v", body["key_conv_id"])
	}
	if code := post(tokA, conv, manualRotationReason); code != 201 {
		t.Errorf("the owner replacing it by hand: %d", code)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/servers/"+conv+"/members/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("kick: %d", res.StatusCode)
	}
	if code := post(tokA, conv, rotationReason); code != 201 {
		t.Errorf("answering a real rotation: %d", code)
	}
}

// A key can be asked for and then never handed out: an app stopped halfway, or
// someone did it on purpose. Whoever next tries to send is told to replace it,
// which every app already does for a rotation it finds pending, and whoever made
// it does not get another go.
func TestAKeyThatNeverArrivedIsReplaced(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokD, _ := register(t, ts, "dave")
	s := createServer(t, ts, tokA, "Keys")
	conv := s["id"].(string)
	invite := inviteFor(t, ts, tokA, channelNamed(s, "general")["id"].(string))
	join(t, ts, tokB, invite)
	join(t, ts, tokC, invite)
	join(t, ts, tokD, invite)
	createEpoch(t, ts, tokA, conv)
	cid, _ := ids.Parse(conv)
	carol, _ := ids.Parse(idC)
	alice, _ := ids.Parse(idA)
	ctx := t.Context()

	flagged := func(tok string) bool {
		_, body := do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+conv, tok, nil)
		return body["flags"].(map[string]any)["needs_rotation"] == true
	}
	plant := func(epoch int, age time.Duration) {
		t.Helper()
		e := &store.KeyEpoch{ConvID: cid, Epoch: epoch, CreatedBy: carol, Reason: rotationReason, CreatedAt: time.Now().Add(-age).UnixMilli()}
		if err := srv.Store.CreateEpoch(ctx, e); err != nil {
			t.Fatal(err)
		}
	}
	answer := func(tok string) int {
		res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tok, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": testCommitment})
		return res.StatusCode
	}

	plant(1, 0)
	if flagged(tokB) {
		t.Error("a key still being handed out was called lost")
	}
	plant(2, time.Minute)
	if flagged(tokC) {
		t.Error("the person who made the key could call for it to be replaced")
	}
	if !flagged(tokB) {
		t.Fatal("a key nobody got was not called lost")
	}
	// someone holding only an older key hears of it on connecting
	_, body := do(t, ts, "GET", "/api/v1/keys/needs", tokD, nil)
	if needs := body["_list"].([]any); len(needs) != 1 || needs[0].(map[string]any)["rotate"] != true {
		t.Errorf("a holder of the older key was not asked to replace it: %v", needs)
	}
	if code := answer(tokC); code != 409 {
		t.Errorf("the person whose key was lost answered again: %d", code)
	}
	if code := answer(tokB); code != 201 {
		t.Fatalf("replacing it: %d", code)
	}
	if flagged(tokB) {
		t.Error("still pending once replaced")
	}
	if ok, err := srv.Store.MarkEpochLost(ctx, cid, 2); err != nil || ok {
		t.Errorf("marked a replaced key: %v %v", ok, err)
	}
	// the owner can always answer, or a raid could lock them out
	if err := srv.Store.CreateEpoch(ctx, &store.KeyEpoch{ConvID: cid, Epoch: 4, CreatedBy: alice, Reason: rotationReason, CreatedAt: time.Now().Add(-time.Minute).UnixMilli()}); err != nil {
		t.Fatal(err)
	}
	if !flagged(tokB) {
		t.Fatal("the owner's lost key was not called lost")
	}
	if code := answer(tokA); code != 201 {
		t.Errorf("the owner after losing a key: %d", code)
	}

	// Someone holding the key can send, so nothing is wrong for them. It takes
	// someone without it, while most people lack it.
	plant(6, time.Minute)
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokC, []map[string]any{{"conv_id": conv, "epoch": 6, "user_id": idA, "wrapped_key": b64([]byte("a"))}}); res.StatusCode != 200 {
		t.Fatalf("grant: %d %v", res.StatusCode, body)
	}
	if flagged(tokA) {
		t.Error("someone holding the key called it lost")
	}
	// Half of them have it now: those lacking it wait for a holder, as ever.
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokC, []map[string]any{{"conv_id": conv, "epoch": 6, "user_id": idB, "wrapped_key": b64([]byte("b"))}}); res.StatusCode != 200 {
		t.Fatalf("grant: %d %v", res.StatusCode, body)
	}
	if flagged(tokD) {
		t.Error("a key half the people hold was called lost")
	}
}

// Handing everyone a key that does not open, or does not match its fingerprint,
// is seen by the people it reaches. Once two of them say so everyone online is
// asked to make a new one, and its maker does not get another go.
func TestAKeyPeopleCannotOpenIsReplaced(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokM, idM := register(t, ts, "mallory")
	s := createServer(t, ts, tokA, "Keys")
	conv := s["id"].(string)
	invite := inviteFor(t, ts, tokA, channelNamed(s, "general")["id"].(string))
	join(t, ts, tokB, invite)
	join(t, ts, tokC, invite)
	join(t, ts, tokM, invite)
	createEpoch(t, ts, tokA, conv)
	askForRotation(t, srv, conv)
	res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokM, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": testCommitment})
	if res.StatusCode != 201 {
		t.Fatalf("mallory answers: %d %v", res.StatusCode, body)
	}
	junk := func(uid string) string { return b64([]byte("junk for " + uid)) }
	var grants []map[string]any
	for _, uid := range []string{idB, idC, idM} {
		grants = append(grants, map[string]any{"conv_id": conv, "epoch": 1, "user_id": uid, "wrapped_key": junk(uid)})
	}
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokM, grants); res.StatusCode != 200 {
		t.Fatalf("grants: %d %v", res.StatusCode, body)
	}
	// what apps need to check the key against comes with the grant
	if _, body := do(t, ts, "GET", "/api/v1/keys/grants", tokB, nil); !grantHasCommitment(body, conv, 1) {
		t.Errorf("the grant came without its fingerprint: %v", body)
	}
	flagged := func() bool {
		_, body := do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+conv, tokA, nil)
		return body["flags"].(map[string]any)["needs_rotation"] == true
	}
	reject := func(tok, uid string) {
		t.Helper()
		if res, body := do(t, ts, "POST", "/api/v1/keys/request", tok, map[string]any{"conv_id": conv, "bad_epoch": 1, "bad_wrapped_key": junk(uid)}); res.StatusCode != 204 {
			t.Fatalf("reject: %d %v", res.StatusCode, body)
		}
	}
	ga := connectGW(t, ts, tokA)
	// mallory cannot have her own key called bad, and one person is not enough
	reject(tokM, idM)
	reject(tokB, idB)
	if flagged() {
		t.Fatal("one person's word replaced the key")
	}
	reject(tokC, idC)
	if !flagged() {
		t.Fatal("a key two people could not open was left in place")
	}
	if ev := ga.nextWhere("key_rotation_needed", 3*time.Second, func(d map[string]any) bool { return d["conv_id"] == conv }); ev == nil {
		t.Error("nobody online was asked to replace it")
	}
	if res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tokM, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": testCommitment}); res.StatusCode != 409 {
		t.Errorf("mallory answered again: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": testCommitment}); res.StatusCode != 201 {
		t.Errorf("bob replacing it: %d", res.StatusCode)
	}
}

func grantHasCommitment(body map[string]any, conv string, epoch float64) bool {
	for _, g := range body["_list"].([]any) {
		m := g.(map[string]any)
		if m["conv_id"] == conv && m["epoch"] == epoch {
			return m["commitment"] == testCommitment
		}
	}
	return false
}

// A rotation answered without a fingerprint could hand each person a different
// key with nothing to show for it, so it waits for an app that makes one.
func TestAnswerNeedsAFingerprint(t *testing.T) {
	serial(t)
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	s := createServer(t, ts, tokA, "Keys")
	conv := s["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(s, "general")["id"].(string)))
	createEpoch(t, ts, tokA, conv)
	askForRotation(t, srv, conv)
	res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": conv, "reason": rotationReason})
	if res.StatusCode != 409 || errCode(body) != "commitment_needed" {
		t.Errorf("no fingerprint: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": b64([]byte("short"))})
	if res.StatusCode != 400 || errCode(body) != "bad_commitment" {
		t.Errorf("a short fingerprint: %d %v", res.StatusCode, body)
	}
	// an old app is answered if nothing better has come along
	defer func(d time.Duration) { oldAppAnswerWait = d }(oldAppAnswerWait)
	oldAppAnswerWait = 0
	if res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": conv, "reason": rotationReason}); res.StatusCode != 201 {
		t.Errorf("an old app after the wait: %d %v", res.StatusCode, body)
	}
}

// One account cannot keep answering rotations: past its own allowance the
// rotation is left for someone else's app.
func TestOneAccountCannotAnswerEveryRotation(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	s := createServer(t, ts, tokA, "Keys")
	conv := s["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(s, "general")["id"].(string)))
	createEpoch(t, ts, tokA, conv)
	answer := func(tok string) int {
		t.Helper()
		askForRotation(t, srv, conv)
		res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tok, map[string]any{"conv_id": conv, "reason": rotationReason, "commitment": testCommitment})
		return res.StatusCode
	}
	code := 201
	for i := 0; i < 40 && code == 201; i++ {
		code = answer(tokB)
	}
	if code != 409 {
		t.Fatalf("one account answered every rotation: %d", code)
	}
	if code := answer(tokA); code != 201 {
		t.Errorf("someone else answering: %d", code)
	}
}

// A pending rotation stops everyone from sending until it is answered, so joining
// and leaving in a loop must not be able to keep one pending. Being removed always
// costs a new key.
func TestLeavingInALoopCannotHoldTheKeyUp(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	s := createServer(t, ts, tokA, "Keys")
	conv := s["id"].(string)
	invite := inviteFor(t, ts, tokA, channelNamed(s, "general")["id"].(string))
	createEpoch(t, ts, tokA, conv)
	flagged := func() bool {
		_, body := do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+conv, tokA, nil)
		return body["flags"].(map[string]any)["needs_rotation"] == true
	}
	stopped := false
	for i := 0; i < 20 && !stopped; i++ {
		join(t, ts, tokB, invite)
		if res, _ := do(t, ts, "POST", "/api/v1/servers/"+conv+"/leave", tokB, nil); res.StatusCode != 204 {
			t.Fatalf("leave: %d", res.StatusCode)
		}
		if stopped = !flagged(); !stopped {
			createEpoch(t, ts, tokA, conv)
		}
	}
	if !stopped {
		t.Fatal("every leave asked for a new key, without end")
	}
	join(t, ts, tokB, invite)
	if res, _ := do(t, ts, "DELETE", "/api/v1/servers/"+conv+"/members/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("kick: %d", res.StatusCode)
	}
	if !flagged() {
		t.Error("a removal did not ask for a new key")
	}
}
