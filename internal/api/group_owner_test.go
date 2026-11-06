package api

import (
	"fmt"
	"testing"
	"time"
)

func listHas(list []any, id string) bool {
	for _, x := range list {
		if x == id {
			return true
		}
	}
	return false
}

// Removing someone is the group owner's to do, and it shuts them out of what
// is said after it: the conversation goes from their devices at once, everyone
// left is told why, every device holding the key is asked to move the group
// to a new one, that key is only for who is left, and a link they could see
// stops letting anyone in.
func TestOwnerRemovesAMember(t *testing.T) {
	_, ts := newTestServer(t)
	register(t, ts, "admin") // the first account runs Nexa; nobody in the group does
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}, "name": "crew"})
	if res.StatusCode != 201 || body["owner_id"] != idA {
		t.Fatalf("group: %d %v", res.StatusCode, body)
	}
	g := body["id"].(string)
	createEpoch(t, ts, tokA, g) // epoch 0, which all three hold
	res, body = do(t, ts, "POST", "/api/v1/channels/"+g+"/invites", tokA, map[string]any{})
	if res.StatusCode != 201 {
		t.Fatalf("invite: %d %v", res.StatusCode, body)
	}
	code := body["code"].(string)
	gb := connectGW(t, ts, tokB)
	gc := connectGW(t, ts, tokC)

	// a member puts out nobody but themselves
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC, tokB, nil); res.StatusCode != 403 || errCode(body) != "not_owner" {
		t.Fatalf("member removing another: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC+"?silent=true", tokA, nil); res.StatusCode != 204 {
		t.Fatalf("owner removing carol: %d %v", res.StatusCode, body)
	}

	if ev := gc.next("channel_delete", 3*time.Second); ev == nil || ev["id"] != g {
		t.Errorf("carol's devices never dropped the group: %v", ev)
	}
	if ev := gb.next("channel_update", 3*time.Second); ev == nil || len(ev["recipient_ids"].([]any)) != 2 || listHas(ev["recipient_ids"].([]any), idC) {
		t.Errorf("bob's copy of the group still has carol: %v", ev)
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_member_remove" || ev["author_id"] != idA || !listHas(ev["mentions"].([]any), idC) {
		t.Errorf("the line saying alice removed carol: %v", ev)
	}
	if ev := gb.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != g {
		t.Errorf("no key rotation asked for after the removal: %v", ev)
	}

	// a holder that was offline is asked on connect, the same as after a leave
	_, body = do(t, ts, "GET", "/api/v1/keys/needs", tokB, nil)
	needs := body["_list"].([]any)
	if len(needs) != 1 || needs[0].(map[string]any)["conv_id"] != g || needs[0].(map[string]any)["rotate"] != true {
		t.Fatalf("pending rotation not reported: %v", body)
	}
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokB, map[string]any{"conv_id": g, "reason": "membership change", "commitment": testCommitment})
	if res.StatusCode != 201 || body["epoch"].(float64) != 1 {
		t.Fatalf("rotation: %d %v", res.StatusCode, body)
	}
	if who := body["user_ids"].([]any); len(who) != 2 || !listHas(who, idA) || !listHas(who, idB) || listHas(who, idC) {
		t.Errorf("the new key is for %v, not alice and bob", who)
	}
	// once is enough
	if res, _ := do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": g, "reason": "membership change", "commitment": testCommitment}); res.StatusCode != 409 {
		t.Errorf("a second rotation for the same removal: %d", res.StatusCode)
	}

	// carol can neither get the new key nor reach anything said under it
	if res, _ := do(t, ts, "GET", "/api/v1/keys/epochs?conv_id="+g, tokC, nil); res.StatusCode != 404 {
		t.Errorf("carol lists the group's keys: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "POST", "/api/v1/keys/grants", tokC, []map[string]any{{"conv_id": g, "epoch": 1, "user_id": idC, "wrapped_key": b64([]byte("x"))}}); res.StatusCode == 200 {
		t.Errorf("carol granted herself the new key: %v", body)
	}
	if res, body := sendMsg(t, ts, tokB, g, g, 1, "after carol", "n-after", nil); res.StatusCode != 201 || body["key_epoch"].(float64) != 1 {
		t.Fatalf("bob's message under the new key: %d %v", res.StatusCode, body)
	}
	if ev := gc.next("message_create", time.Second); ev != nil {
		t.Errorf("carol was sent a message from the group after her removal: %v", ev)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+g+"/messages", tokC, nil); res.StatusCode != 404 {
		t.Errorf("carol reads the group's messages: %d", res.StatusCode)
	}
	if res, _ := sendMsg(t, ts, tokC, g, g, 1, "let me in", "n-carol", nil); res.StatusCode != 404 {
		t.Errorf("carol posts in the group: %d", res.StatusCode)
	}
	// every member can list the group's links, so the one she saw is spent
	if res, body := do(t, ts, "POST", "/api/v1/invites/"+code, tokC, nil); res.StatusCode != 404 {
		t.Errorf("carol comes back through the old link: %d %v", res.StatusCode, body)
	}

	// and the owner cannot be put out, even by a Nexa admin (admin_powers_test)
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idA, tokB, nil); res.StatusCode != 403 {
		t.Errorf("bob removing the owner: %d %v", res.StatusCode, body)
	}
}

// Handing a group on: only its owner can, only to someone in it, everyone's
// copy of the group moves the crown, and a line in it says who did it. The old
// owner is a member like any other afterwards.
func TestOwnerHandsTheGroupOn(t *testing.T) {
	_, ts := newTestServer(t)
	register(t, ts, "admin")
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	_, idD := register(t, ts, "dave")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}, "name": "crew"})
	g := body["id"].(string)
	gc := connectGW(t, ts, tokC)

	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+g, tokB, map[string]any{"owner_id": idB}); res.StatusCode != 403 || errCode(body) != "not_owner" {
		t.Errorf("a member taking the group: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"owner_id": idD}); res.StatusCode != 400 || errCode(body) != "not_member" {
		t.Errorf("handing it to someone outside it: %d %v", res.StatusCode, body)
	}
	// naming the owner it already has is no change, and nothing is said
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"owner_id": idA}); res.StatusCode != 200 || body["owner_id"] != idA {
		t.Errorf("same owner: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"owner_id": idB}); res.StatusCode != 200 || body["owner_id"] != idB {
		t.Fatalf("transfer: %d %v", res.StatusCode, body)
	}

	moved := false
	for ev := gc.next("channel_update", 3*time.Second); ev != nil; ev = gc.next("channel_update", 3*time.Second) {
		if ev["owner_id"] == idB {
			moved = true
			break
		}
	}
	if !moved {
		t.Error("carol's copy of the group never got its new owner")
	}
	if ev := gc.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_owner_change" || ev["author_id"] != idA || len(ev["mentions"].([]any)) != 1 || ev["mentions"].([]any)[0] != idB {
		t.Errorf("the line saying alice made bob the owner: %v", ev)
	}
	_, body = do(t, ts, "GET", "/api/v1/channels/"+g+"/messages", tokC, nil)
	lines := 0
	for _, m := range body["_list"].([]any) {
		if m.(map[string]any)["type"] == "system_owner_change" {
			lines++
		}
	}
	if lines != 1 {
		t.Errorf("%d ownership lines in the history, want 1 (a no-op says nothing): %v", lines, body)
	}

	// alice has none of the owner's powers now, and bob has them all
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC, tokA, nil); res.StatusCode != 403 || errCode(body) != "not_owner" {
		t.Errorf("the old owner removing someone: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"owner_id": idA}); res.StatusCode != 403 {
		t.Errorf("the old owner taking it back: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idA, tokB, nil); res.StatusCode != 204 {
		t.Errorf("the new owner removing the old one: %d %v", res.StatusCode, body)
	}
}

// A silent exit still removes access and rotates keys. The option only hides
// someone's own departure, never an owner's removal of somebody else.
func TestGroupSilentLeave(t *testing.T) {
	for _, route := range []string{"channel", "recipient"} {
		for _, silent := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/silent=%t", route, silent), func(t *testing.T) {
				_, ts := newTestServer(t)
				a, aid := register(t, ts, "alice")
				b, bid := register(t, ts, "bob")
				c, cid := register(t, ts, "carol")
				friend(t, ts, a, "bob", b, aid)
				friend(t, ts, a, "carol", c, aid)
				_, group := do(t, ts, "POST", "/api/v1/users/@me/channels", a, map[string]any{"recipient_ids": []string{bid, cid}, "name": "crew"})
				g := group["id"].(string)
				gw := connectGW(t, ts, b)
				path := "/api/v1/channels/" + g
				if route == "recipient" {
					path += "/recipients/" + aid
				}
				if silent {
					path += "?silent=true"
				}
				if res, body := do(t, ts, "DELETE", path, a, nil); res.StatusCode != 204 {
					t.Fatalf("leave: %d %v", res.StatusCode, body)
				}
				if ev := gw.next("key_rotation_needed", 3*time.Second); ev == nil {
					t.Fatal("silent leave must still rotate keys")
				}
				if res, _ := do(t, ts, "GET", "/api/v1/channels/"+g, a, nil); res.StatusCode != 404 {
					t.Fatal("departed member retains access")
				}
				_, history := do(t, ts, "GET", "/api/v1/channels/"+g+"/messages", b, nil)
				count := 0
				for _, raw := range history["_list"].([]any) {
					m := raw.(map[string]any)
					if m["type"] == "system_member_remove" && m["author_id"] == aid {
						count++
					}
				}
				want := 1
				if silent {
					want = 0
				}
				if count != want {
					t.Fatalf("leave messages: got %d, want %d", count, want)
				}
			})
		}
	}
}
