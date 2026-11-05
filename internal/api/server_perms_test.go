package api

import (
	"net/http"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
)

func TestQuietMembersAndTimedOutModerators(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Quiet")
	sid := view["id"].(string)
	code := inviteFor(t, ts, tokA, channelNamed(view, "general")["id"].(string))
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)

	// a moderator in timeout keeps no moderation powers
	mod := createRole(t, ts, tokA, sid, "Mod", perms.DefaultEveryone|perms.KickMembers|perms.BanMembers|perms.ModerateMembers)
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+mod, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("assign mod: %d %v", res.StatusCode, body)
	}
	until := time.Now().Add(time.Hour).UnixMilli()
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB, tokA, map[string]any{"timeout_until": until}); res.StatusCode != 200 {
		t.Fatalf("timeout: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idC, tokB, nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("timed-out moderator kicks: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/bans/"+idC, tokB, nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("timed-out moderator bans: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idC, tokB, map[string]any{"timeout_until": until}); res.StatusCode != http.StatusForbidden {
		t.Errorf("timed-out moderator times out: %d", res.StatusCode)
	}

	// with nothing on @everyone, a member without roles is still a member
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+sid, tokA, map[string]any{"permissions": "0"}); res.StatusCode != 200 {
		t.Fatalf("clear everyone: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/servers/"+sid, tokC, nil); res.StatusCode != 200 {
		t.Errorf("member with no permissions opens the server: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokC, nil); res.StatusCode != 204 {
		t.Errorf("member with no permissions leaves: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/servers/"+sid, tokC, nil); res.StatusCode != 404 {
		t.Errorf("after leaving: %d", res.StatusCode)
	}
}

func TestManagersReorderRoles(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Ranks")
	sid := view["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(view, "general")["id"].(string)))
	gb := connectGW(t, ts, tokB)

	admin := createRole(t, ts, tokA, sid, "Admin", perms.DefaultEveryone|perms.ManageRoles)
	mod := createRole(t, ts, tokA, sid, "Mod", perms.DefaultEveryone|perms.ManageRoles)
	// creating Mod pushed Admin up, and everyone hears about it
	for {
		ev := gb.next("role_update", 3*time.Second)
		if ev == nil {
			t.Fatal("no role_update for the role pushed up")
		}
		if ev["id"] == admin {
			if ev["position"].(float64) != 2 {
				t.Errorf("admin position after create: %v", ev["position"])
			}
			break
		}
	}
	temp := createRole(t, ts, tokA, sid, "Temp", 0)
	helper := createRole(t, ts, tokA, sid, "Helper", 0)
	member := createRole(t, ts, tokA, sid, "Member", 0)
	// Admin 5, Mod 4, Temp 3, Helper 2, Member 1; deleting Temp leaves a gap
	if res, _ := do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/roles/"+temp, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("delete temp: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+mod, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("assign mod: %d %v", res.StatusCode, body)
	}
	// bob drags Member above Helper; the client sends every role renumbered
	res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles", tokB, []map[string]any{
		{"id": admin, "position": 4}, {"id": mod, "position": 3}, {"id": member, "position": 2}, {"id": helper, "position": 1},
	})
	if res.StatusCode != 200 {
		t.Fatalf("manager reorders below themselves: %d %v", res.StatusCode, body)
	}
	pos := map[string]float64{}
	for _, r := range body["_list"].([]any) {
		ro := r.(map[string]any)
		pos[ro["id"].(string)] = ro["position"].(float64)
	}
	if pos[admin] != 5 || pos[mod] != 4 || pos[member] != 2 || pos[helper] != 1 {
		t.Errorf("positions: %v", pos)
	}
	// moving a role up to or past their own is still refused
	if res, _ := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles", tokB, []map[string]any{{"id": helper, "position": 4}}); res.StatusCode != 403 {
		t.Errorf("reorder to own rank: %d", res.StatusCode)
	}
}

func TestInviteCodesAndBanReasonsStayWithStaff(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	_, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Staff")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	inviteFor(t, ts, tokA, general)
	if ev := ga.next("invite_create", 3*time.Second); ev == nil {
		t.Error("the owner hears of new invites")
	}
	if ev := gb.next("invite_create", 500*time.Millisecond); ev != nil {
		t.Errorf("a plain member was sent an invite code: %v", ev)
	}
	// the creator of an invite hears of it even without Manage Server
	inviteFor(t, ts, tokB, general)
	if ev := gb.next("invite_create", 3*time.Second); ev == nil {
		t.Error("the inviter hears of their own invite")
	}
	if res, _ := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/bans/"+idC, tokA, map[string]any{"reason": "private"}); res.StatusCode != 204 {
		t.Fatalf("ban: %d", res.StatusCode)
	}
	if ev := ga.next("ban_add", 3*time.Second); ev == nil {
		t.Error("the owner hears of bans")
	}
	if ev := gb.next("ban_add", 500*time.Millisecond); ev != nil {
		t.Errorf("a plain member was sent a ban reason: %v", ev)
	}
}

func TestOneUseInviteUnderConcurrentJoins(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	view := createServer(t, ts, tokA, "Once")
	sid := view["id"].(string)
	res, body := do(t, ts, "POST", "/api/v1/channels/"+channelNamed(view, "general")["id"].(string)+"/invites", tokA, map[string]any{"max_uses": 1})
	if res.StatusCode != 201 {
		t.Fatalf("invite: %d %v", res.StatusCode, body)
	}
	code := body["code"].(string)
	var tokens []string
	for _, name := range []string{"bob", "carol", "dave", "erin", "frank"} {
		tok, _ := register(t, ts, name)
		tokens = append(tokens, tok)
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	joined := 0
	for _, tok := range tokens {
		wg.Add(1)
		go func(tok string) {
			defer wg.Done()
			res, _ := do(t, ts, "POST", "/api/v1/invites/"+code, tok, nil)
			if res.StatusCode == 201 {
				mu.Lock()
				joined++
				mu.Unlock()
			}
		}(tok)
	}
	wg.Wait()
	if joined != 1 {
		t.Errorf("%d people joined through a one-use invite", joined)
	}
	_, members := do(t, ts, "GET", "/api/v1/servers/"+sid+"/members?limit=100", tokA, nil)
	if list, _ := members["members"].([]any); len(list) != 2 {
		t.Errorf("members: %v", members["members"])
	}
}

