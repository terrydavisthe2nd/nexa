package api

import (
	"context"
	"strconv"
	"strings"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// A request rejected for one field must leave every field alone. Validating
// and writing one at a time meant an admin who read "registration_key is too
// long" and reasonably concluded nothing had changed had in fact closed
// registration on Nexa.
func TestRejectedSettingsPatchChangesNothing(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")

	tooLong := strings.Repeat("k", 129)
	res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{
		"registration": "closed", "registration_key": tooLong,
	})
	if res.StatusCode != 400 || errCode(body) != "invalid_value" {
		t.Fatalf("expected the whole patch refused: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/admin/settings", tok, nil)
	if res.StatusCode != 200 || body["registration"] != "open" {
		t.Errorf("registration is %v after a rejected patch, want open", body["registration"])
	}
	if body["registration_key_set"] != false {
		t.Errorf("registration_key was written by a rejected patch: %v", body)
	}
	// registration still works, which is the thing the admin would have lost
	if res, body := do(t, ts, "POST", "/api/v1/auth/register", "", registerBody("bob")); res.StatusCode != 201 {
		t.Errorf("registration closed by a rejected patch: %d %v", res.StatusCode, body)
	}
}

// Banning with a purge deletes the messages in one transaction and reports
// what went, so the delete events that tell every client actually go out.
func TestBanPurgeDeletesAndAnnounces(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	srv := createServer(t, ts, tokA, "place")
	code := inviteFor(t, ts, tokA, channelNamed(srv, "general")["id"].(string))
	join(t, ts, tokB, code)
	general := channelNamed(srv, "general")["id"].(string)
	epoch := createEpoch(t, ts, tokA, srv["id"].(string))

	var sent []string
	for i := 0; i < 3; i++ {
		res, body := sendMsg(t, ts, tokB, general, srv["id"].(string), epoch, "hello", "n"+strconv.Itoa(i), nil)
		if res.StatusCode != 201 {
			t.Fatalf("send %d: %d %v", i, res.StatusCode, body)
		}
		sent = append(sent, body["id"].(string))
	}
	ga := connectGWReady(t, ts, tokA)

	res, body := do(t, ts, "PUT", "/api/v1/servers/"+srv["id"].(string)+"/bans/"+idB, tokA, map[string]any{"delete_message_days": 7})
	if res.StatusCode != 204 {
		t.Fatalf("ban with purge: %d %v", res.StatusCode, body)
	}
	ev := ga.next("message_delete_bulk", 3*time.Second)
	if ev == nil {
		t.Fatal("no message_delete_bulk after a purge")
	}
	gone := map[string]bool{}
	for _, id := range ev["ids"].([]any) {
		gone[id.(string)] = true
	}
	for _, id := range sent {
		if !gone[id] {
			t.Errorf("message %s was deleted but never announced", id)
		}
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+general+"/messages", tokA, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 0 {
		t.Errorf("messages left after the purge: %v", body["_list"])
	}
}

// The relay credential cache belongs to one Server and forgets what has gone
// stale; as a package-level map it grew for the life of the process and two
// Servers in one process handed out each other's signatures.
func TestTURNCacheIsPerServerAndSwept(t *testing.T) {
	a, ts := newTestServer(t)
	b, _ := newTestServer(t)
	newFakeTURN(t, a)
	tok, id := register(t, ts, "alice")
	uid, _ := ids.Parse(id)
	name := iceUsername(t, ts, tok)
	if name == "" {
		t.Fatal("no credential")
	}
	b.turnMu.Lock()
	_, leaked := b.turnCreds[uid]
	b.turnMu.Unlock()
	if leaked {
		t.Error("a second server holds the first one's credential")
	}
	// an expired credential is forgotten by the sweep and never handed out again
	a.turnMu.Lock()
	a.turnLive[name].expires = time.Now().Add(-time.Second)
	hit := a.turnCreds[uid]
	hit.expires = time.Now().Add(-time.Second)
	a.turnCreds[uid] = hit
	a.turnMu.Unlock()
	a.sweepTURN(context.Background())
	a.turnMu.Lock()
	_, still := a.turnLive[name]
	a.turnMu.Unlock()
	if still {
		t.Error("an expired credential was kept")
	}
	if again := iceUsername(t, ts, tok); again == name || again == "" {
		t.Errorf("an expired credential was handed out again: %q", again)
	}
}

// Who is left in a group comes from the removal's own transaction, so an empty
// group is always noticed and deleted rather than left behind with an owner
// who is not in it.
func TestLastMemberLeavingDeletesTheGroup(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}, "name": "three"})
	if res.StatusCode != 201 || body["type"] != "group_dm" {
		t.Fatalf("group: %d %v", res.StatusCode, body)
	}
	g := body["id"].(string)
	for _, who := range []struct {
		tok, id string
	}{{tokB, idB}, {tokC, idC}, {tokA, idA}} {
		if res, body := do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+who.id, who.tok, nil); res.StatusCode != 204 {
			t.Fatalf("leave: %d %v", res.StatusCode, body)
		}
	}
	gid, err := ids.Parse(g)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := srv.Store.ChannelByID(context.Background(), gid); err == nil {
		t.Error("the group survived everybody leaving it")
	}
}

// A channel overwrite decides who can see a channel, so it answers to the same
// role hierarchy as every other role-touching route. Without that, somebody
// given Manage Roles in one channel could deny View Channel to a moderator
// role far above their own and lock them out of it.
func TestOverwritesRespectTheRoleHierarchy(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "hier")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)

	// bob is a moderator; carol only has Manage Roles, and sits below him
	mod := createRole(t, ts, tokA, sid, "Mod", perms.DefaultEveryone|perms.KickMembers|perms.ManageRoles)
	low := createRole(t, ts, tokA, sid, "Low", perms.DefaultEveryone|perms.ManageRoles)
	// new roles land at the bottom, so this one is below carol's
	lowest := createRole(t, ts, tokA, sid, "Lowest", perms.DefaultEveryone)
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+mod, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("give bob mod: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idC+"/roles/"+low, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("give carol low: %d %v", res.StatusCode, body)
	}

	deny := map[string]any{"type": "role", "allow": "0", "deny": permStr(perms.ViewChannel)}
	res, body := do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+mod, tokC, deny)
	if res.StatusCode != 403 || errCode(body) != "role_hierarchy" {
		t.Errorf("carol denied a role above her: %d %v", res.StatusCode, body)
	}
	member := map[string]any{"type": "member", "allow": "0", "deny": permStr(perms.ViewChannel)}
	res, body = do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+idB, tokC, member)
	if res.StatusCode != 403 || errCode(body) != "role_hierarchy" {
		t.Errorf("carol denied a member above her: %d %v", res.StatusCode, body)
	}
	// her own highest role is not hers to manage either, as everywhere else
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+low, tokC, deny); res.StatusCode != 403 {
		t.Errorf("carol on her own role: %d %v", res.StatusCode, body)
	}
	// a role below her is, and she can take the overwrite off again
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+lowest, tokC, deny); res.StatusCode != 200 {
		t.Errorf("carol on a role below her: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "DELETE", "/api/v1/channels/"+general+"/permissions/"+lowest, tokC, nil); res.StatusCode != 204 {
		t.Errorf("carol removes the overwrite she set: %d %v", res.StatusCode, body)
	}
	// and the owner is not held to any of it
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+mod, tokA, deny); res.StatusCode != 200 {
		t.Errorf("owner: %d %v", res.StatusCode, body)
	}
}

// Moderating a voice state with a body that names nothing used to fall through
// to the reply and the audit entry, so any member could read another member's
// full voice state — including a channel they cannot view — and write an
// audit row for every attempt.
func TestVoiceModerateNeedsSomethingToDo(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "voice")
	sid := view["id"].(string)
	code := inviteFor(t, ts, tokA, channelNamed(view, "general")["id"].(string))
	join(t, ts, tokB, code)

	res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokB, map[string]any{})
	if res.StatusCode != 400 || errCode(body) != "nothing_to_do" {
		t.Errorf("empty moderation body: %d %v", res.StatusCode, body)
	}
	// and it is refused before anything is written to the audit log
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/audit-log", tokA, nil)
	if res.StatusCode != 200 {
		t.Fatalf("audit log: %d %v", res.StatusCode, body)
	}
	list, _ := body["_list"].([]any)
	for _, e := range list {
		if e.(map[string]any)["action"] == "member_voice" {
			t.Error("a no-op moderation wrote an audit entry")
		}
	}
}

// A value of the wrong type is a mistake, not an instruction to clear the
// field. {"about_me": 42} used to empty the person's bio and answer 200.
func TestWrongTypedProfileFieldIsRefused(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	if res, body := do(t, ts, "PATCH", "/api/v1/users/@me", tok, map[string]any{"about_me": "I like trains"}); res.StatusCode != 200 || body["about_me"] != "I like trains" {
		t.Fatalf("set about me: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "PATCH", "/api/v1/users/@me", tok, map[string]any{"about_me": 42})
	if res.StatusCode != 400 {
		t.Errorf("a number for about_me: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/users/@me", tok, nil)
	if res.StatusCode != 200 || body["about_me"] != "I like trains" {
		t.Errorf("about me is %v after a refused patch", body["about_me"])
	}
	// an explicit null still clears it, which is what the clients send
	if res, body = do(t, ts, "PATCH", "/api/v1/users/@me", tok, map[string]any{"about_me": nil}); res.StatusCode != 200 || body["about_me"] != "" {
		t.Errorf("null clears about me: %d %v", res.StatusCode, body)
	}
}
