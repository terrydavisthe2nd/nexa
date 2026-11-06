package api

import (
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
)

// nextFor returns the next event of type t about the given id (under key).
func (g *gw) nextFor(t, key, id string, timeout time.Duration) map[string]any {
	deadline := time.Now().Add(timeout)
	for {
		left := time.Until(deadline)
		if left <= 0 {
			return nil
		}
		ev := g.next(t, left)
		if ev == nil || ev[key] == id {
			return ev
		}
	}
}

func TestSubchatsFollowGroupMembership(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokD, idD := register(t, ts, "dave")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	friend(t, ts, tokA, "dave", tokD, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}})
	g := body["id"].(string)
	res, body := do(t, ts, "POST", "/api/v1/channels/"+g+"/subchats", tokA, map[string]any{"name": "Plans"})
	if res.StatusCode != 201 {
		t.Fatalf("subchat: %d %v", res.StatusCode, body)
	}
	sub := body["id"].(string)
	code := inviteFor(t, ts, tokC, g)

	gb := connectGW(t, ts, tokB)
	gc := connectGW(t, ts, tokC)
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("remove carol: %d", res.StatusCode)
	}
	if ev := gc.nextFor("channel_delete", "id", sub, 3*time.Second); ev == nil || ev["parent_id"] != g {
		t.Errorf("carol should lose the subchat: %v", ev)
	}
	if ev := gb.nextFor("key_rotation_needed", "conv_id", sub, 3*time.Second); ev == nil {
		t.Error("the subchat key should rotate once carol is out")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+sub+"/messages", tokC, nil); res.StatusCode != 404 {
		t.Errorf("carol still reads the subchat: %d", res.StatusCode)
	}
	if _, body := do(t, ts, "GET", "/api/v1/channels/"+sub, tokA, nil); len(body["recipient_ids"].([]any)) != 2 {
		t.Errorf("subchat members after removal: %v", body["recipient_ids"])
	}
	// the links she saw while in the group stop working
	if res, body := do(t, ts, "POST", "/api/v1/invites/"+code, tokC, nil); res.StatusCode != 404 {
		t.Errorf("removed member rejoined by invite: %d %v", res.StatusCode, body)
	}

	// someone added later is in the subchats too
	if res, body := do(t, ts, "POST", "/api/v1/channels/"+g+"/recipients/"+idD, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("add dave: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+sub+"/messages", tokD, nil); res.StatusCode != 200 {
		t.Errorf("dave cannot open the subchat: %d", res.StatusCode)
	}
	res, list := do(t, ts, "GET", "/api/v1/channels/"+g+"/subchats", tokD, nil)
	if rows, _ := list["_list"].([]any); res.StatusCode != 200 || len(rows) != 1 {
		t.Errorf("dave's subchat list: %d %v", res.StatusCode, list)
	}

	// deleting the group takes its subchats with it
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+g+"?purge=true", tokA, nil); res.StatusCode != 204 {
		t.Fatalf("delete group: %d", res.StatusCode)
	}
	if ev := gb.nextFor("channel_delete", "id", sub, 3*time.Second); ev == nil {
		t.Error("bob should hear the subchat is gone")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+sub, tokB, nil); res.StatusCode != 404 {
		t.Errorf("subchat outlived its group: %d", res.StatusCode)
	}
	res, list = do(t, ts, "GET", "/api/v1/users/@me/channels", tokB, nil)
	for _, row := range list["_list"].([]any) {
		if row.(map[string]any)["id"] == sub {
			t.Error("the orphaned subchat is still listed")
		}
	}
}

func TestLeavingLastMemberDeletesSubchats(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, bID := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{bID, idC}})
	g := body["id"].(string)
	_, body = do(t, ts, "POST", "/api/v1/channels/"+g+"/subchats", tokA, map[string]any{"name": "Plans"})
	sub := body["id"].(string)
	for _, tok := range []string{tokB, tokC, tokA} {
		if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+g, tok, nil); res.StatusCode != 204 {
			t.Fatalf("leave: %d", res.StatusCode)
		}
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+sub, tokA, nil); res.StatusCode != 404 {
		t.Errorf("subchat outlived the empty group: %d", res.StatusCode)
	}
}

func TestReopeningAClosedDMKeepsItOpen(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	befriend(t, ts, tokA, tokB, "bob", idA)
	dm := dmBetween(t, ts, tokA, idB)
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+dm, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("close: %d", res.StatusCode)
	}
	if _, list := do(t, ts, "GET", "/api/v1/users/@me/channels", tokA, nil); len(list["_list"].([]any)) != 0 {
		t.Fatalf("closed DM still listed: %v", list)
	}
	if again := dmBetween(t, ts, tokA, idB); again != dm {
		t.Fatalf("reopening made a new DM: %s vs %s", again, dm)
	}
	// what a reconnect rebuilds the sidebar from
	if _, list := do(t, ts, "GET", "/api/v1/users/@me/channels", tokA, nil); len(list["_list"].([]any)) != 1 {
		t.Errorf("reopened DM is missing from the list: %v", list)
	}
}

func TestChannelManagersEditWithoutManageMessages(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "srv")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	role := createRole(t, ts, tokA, sid, "channels", perms.ManageChannels)
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+role, tokA, nil); res.StatusCode >= 300 {
		t.Fatalf("assign role: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+general, tokB, map[string]any{"topic": "hello"}); res.StatusCode != 200 {
		t.Errorf("channel manager edit: %d %v", res.StatusCode, body)
	}
	tokC, _ := register(t, ts, "carol")
	join(t, ts, tokC, inviteFor(t, ts, tokA, general))
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+general, tokC, map[string]any{"topic": "nope"}); res.StatusCode != 403 {
		t.Errorf("plain member edit: %d %v", res.StatusCode, body)
	}
}

func TestKeyGrantsNeedARealHolder(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}})
	g := body["id"].(string)
	if res, body := do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": g}); res.StatusCode != 201 {
		t.Fatalf("epoch: %d %v", res.StatusCode, body)
	}
	grant := func(uid, key string) map[string]any {
		return map[string]any{"conv_id": g, "epoch": 0, "user_id": uid, "wrapped_key": b64([]byte(key))}
	}
	// a keyless member cannot vouch for themselves and share junk in one go
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{grant(idB, "junk"), grant(idC, "junk")}); res.StatusCode != 403 || errCode(body) != "not_holder" {
		t.Errorf("self-grant then share in one batch: %d %v", res.StatusCode, body)
	}
	// nor in two
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{grant(idB, "junk")}); res.StatusCode != 200 {
		t.Fatalf("self grant: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{grant(idC, "junk")}); res.StatusCode != 403 || errCode(body) != "not_holder" {
		t.Errorf("share from a self-planted grant: %d %v", res.StatusCode, body)
	}
	if _, body := do(t, ts, "GET", "/api/v1/keys/needs", tokB, nil); len(body["_list"].([]any)) != 0 {
		t.Errorf("a self-planted grant should not be asked to share: %v", body)
	}
	// the real key still gets through
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{grant(idA, "a"), grant(idC, "real")}); res.StatusCode != 200 || body["inserted"].(float64) != 2 {
		t.Fatalf("real grant: %d %v", res.StatusCode, body)
	}
	// someone who left keeps their grant but cannot share it
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+g, tokC, nil); res.StatusCode != 204 {
		t.Fatalf("carol leaves: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokC, []map[string]any{grant(idA, "junk")}); res.StatusCode != 403 || errCode(body) != "not_entitled" {
		t.Errorf("grant from a former member: %d %v", res.StatusCode, body)
	}
}

func TestPendingRotationHappensOnce(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}})
	g := body["id"].(string)
	createEpoch(t, ts, tokA, g)
	rotate := func(tok string) int {
		res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tok, map[string]any{"conv_id": g, "reason": "membership change", "commitment": testCommitment})
		return res.StatusCode
	}
	if code := rotate(tokB); code != 409 {
		t.Errorf("rotation nobody asked for: %d", code)
	}
	// carol leaves while nobody is online
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+g, tokC, nil); res.StatusCode != 204 {
		t.Fatalf("carol leaves: %d", res.StatusCode)
	}
	// the next holder to show up is told to rotate
	_, body = do(t, ts, "GET", "/api/v1/keys/needs", tokB, nil)
	needs := body["_list"].([]any)
	if len(needs) != 1 || needs[0].(map[string]any)["rotate"] != true || needs[0].(map[string]any)["conv_id"] != g {
		t.Fatalf("pending rotation not reported: %v", body)
	}
	if code := rotate(tokB); code != 201 {
		t.Fatalf("rotate: %d", code)
	}
	if code := rotate(tokA); code != 409 {
		t.Errorf("a second device rotated again: %d", code)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": g, "reason": "manual"}); res.StatusCode != 403 {
		t.Errorf("a group's key replaced by hand, with no removal to answer: %d", res.StatusCode)
	}
}
