package api

import (
	"testing"

	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// Nexa's staff, Developers included, are ordinary members of other people's
// servers and group chats: no permissions, private channels or ownership the
// owner did not give them (the owner's rule, 2026-11-04).
func TestStaffHaveNoRightsInOthersServersOrGroups(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice") // the first account: owner and Developer
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")

	view := createServer(t, ts, tokB, "Bob's")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	code := inviteFor(t, ts, tokB, general)
	join(t, ts, tokA, code)
	join(t, ts, tokC, code)
	epoch := createEpoch(t, ts, tokB, sid)
	res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokB, map[string]any{"type": "text", "name": "secret"})
	if res.StatusCode != 201 {
		t.Fatal(body)
	}
	secret := body["id"].(string)
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+secret+"/permissions/"+sid, tokB, map[string]any{"type": "role", "deny": permStr(perms.ViewChannel)}); res.StatusCode != 200 {
		t.Fatalf("overwrite: %d %v", res.StatusCode, body)
	}

	// her permissions are @everyone's, and the private channel stays private
	if _, body = do(t, ts, "GET", "/api/v1/servers/"+sid, tokA, nil); body["server"].(map[string]any)["my_permissions"] == permStr(perms.All) {
		t.Error("a Developer holds every permission in someone else's server")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+secret+"/messages", tokA, nil); res.StatusCode != 403 && res.StatusCode != 404 {
		t.Errorf("a Developer reads a private channel: %d", res.StatusCode)
	}

	// she cannot take down someone else's message or remove anyone
	res, body = sendMsg(t, ts, tokC, general, sid, epoch, "carol's", "", nil)
	if res.StatusCode != 201 {
		t.Fatalf("send: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+general+"/messages/"+body["id"].(string), tokA, nil); res.StatusCode != 403 {
		t.Errorf("a Developer deletes another's message: %d", res.StatusCode)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idC, tokA, nil); res.StatusCode != 403 {
		t.Errorf("a Developer kicks a member: %d", res.StatusCode)
	}

	// in a group chat she is a member like any other
	friend(t, ts, tokB, "alice", tokA, idB)
	friend(t, ts, tokB, "carol", tokC, idB)
	res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokB, map[string]any{"recipient_ids": []string{idA, idC}, "name": "bob's"})
	if res.StatusCode != 201 {
		t.Fatalf("group: %d %v", res.StatusCode, body)
	}
	g := body["id"].(string)
	if res, body = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC, tokA, nil); res.StatusCode != 403 || errCode(body) != "not_owner" {
		t.Errorf("a Developer removes a group member: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"owner_id": idA}); res.StatusCode != 403 {
		t.Errorf("a Developer takes a group: %d %v", res.StatusCode, body)
	}
}

func TestAdminDeletesAnAccount(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokD, _ := register(t, ts, "dave")

	// bob owns a server alice and dave are in, and a server nobody else is in
	shared := createServer(t, ts, tokB, "Shared")
	sid := shared["id"].(string)
	code := inviteFor(t, ts, tokB, channelNamed(shared, "general")["id"].(string))
	join(t, ts, tokD, code)
	join(t, ts, tokA, code)
	createServer(t, ts, tokB, "Alone")
	_, stats := do(t, ts, "GET", "/api/v1/admin/stats", tokA, nil)
	serversBefore := stats["servers"].(float64)

	// what may not be deleted
	if res, body := do(t, ts, "DELETE", "/api/v1/admin/users/"+idA, tokA, nil); res.StatusCode != 400 || errCode(body) != "self" {
		t.Errorf("deleting yourself: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/admin/users/"+idA, tokB, nil); res.StatusCode != 403 {
		t.Errorf("non-admin deleting: %d", res.StatusCode)
	}
	do(t, ts, "PATCH", "/api/v1/admin/users/"+idC, tokA, map[string]any{"admin": true})
	if res, body := do(t, ts, "DELETE", "/api/v1/admin/users/"+idC, tokA, nil); res.StatusCode != 400 || errCode(body) != "is_admin" {
		t.Errorf("deleting another admin: %d %v", res.StatusCode, body)
	}
	_ = tokC

	gb := connectGW(t, ts, tokB)
	if res, body := do(t, ts, "DELETE", "/api/v1/admin/users/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("delete: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("session_expired", 3e9); ev == nil {
		t.Error("the deleted account's open session is told to sign out")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/users/@me", tokB, nil); res.StatusCode != 401 {
		t.Errorf("deleted token still works: %d", res.StatusCode)
	}
	// both servers it owned went with it, the shared one for everyone in it
	if res, _ := do(t, ts, "GET", "/api/v1/servers/"+sid, tokA, nil); res.StatusCode != 404 {
		t.Errorf("shared server after deletion: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/servers/"+sid, tokD, nil); res.StatusCode != 404 {
		t.Errorf("a member still sees the shared server: %d", res.StatusCode)
	}
	_, stats = do(t, ts, "GET", "/api/v1/admin/stats", tokA, nil)
	if stats["servers"].(float64) != serversBefore-2 {
		t.Errorf("servers: before %v after %v", serversBefore, stats["servers"])
	}
	// the account itself stays, as an empty stand-in nobody can sign in as
	if res, u := do(t, ts, "GET", "/api/v1/users/"+idB, tokA, nil); res.StatusCode != 200 || u["display_name"] != "Deleted User" {
		t.Errorf("the deleted account should remain as an anonymous record: %d %v", res.StatusCode, u)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/admin/users/"+idB, tokA, nil); res.StatusCode != 404 {
		t.Errorf("deleting twice: %d", res.StatusCode)
	}
}

func TestTheConfiguredAdminNameIsOnlyForSettingUp(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.AdminUsername = "boss"
	ownerTok, _ := register(t, ts, "owner")
	_, me := do(t, ts, "GET", "/api/v1/users/@me", ownerTok, nil)
	if int64(me["flags"].(float64))&store.FlagAdmin == 0 {
		t.Fatal("the first account is Nexa's admin")
	}
	bossTok, _ := register(t, ts, "boss")
	_, me = do(t, ts, "GET", "/api/v1/users/@me", bossTok, nil)
	if int64(me["flags"].(float64))&store.FlagAdmin != 0 {
		t.Error("registering the configured name made an admin once Nexa was set up")
	}
}
