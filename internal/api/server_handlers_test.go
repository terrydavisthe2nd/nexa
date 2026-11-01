package api

import (
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
)

func permStr(p perms.Perm) string { return strconv.FormatUint(uint64(p), 10) }

func createServer(t *testing.T, ts *httptest.Server, token, name string) map[string]any {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/servers", token, map[string]any{"name": name})
	if res.StatusCode != 201 {
		t.Fatalf("create server: %d %v", res.StatusCode, body)
	}
	return body
}

func channelNamed(view map[string]any, name string) map[string]any {
	for _, c := range view["channels"].([]any) {
		ch := c.(map[string]any)
		if ch["name"] == name {
			return ch
		}
	}
	return nil
}

func inviteFor(t *testing.T, ts *httptest.Server, token, channel string) string {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/channels/"+channel+"/invites", token, map[string]any{})
	if res.StatusCode != 201 {
		t.Fatalf("invite: %d %v", res.StatusCode, body)
	}
	return body["code"].(string)
}

func join(t *testing.T, ts *httptest.Server, token, code string) map[string]any {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/invites/"+code, token, nil)
	if res.StatusCode != 201 {
		t.Fatalf("join: %d %v", res.StatusCode, body)
	}
	return body["server"].(map[string]any)
}

func createRole(t *testing.T, ts *httptest.Server, token, server, name string, p perms.Perm) string {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/servers/"+server+"/roles", token, map[string]any{"name": name, "permissions": permStr(p)})
	if res.StatusCode != 201 {
		t.Fatalf("create role: %d %v", res.StatusCode, body)
	}
	return body["id"].(string)
}

func TestServerCreateJoinAndDefaults(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	view := createServer(t, ts, tokA, "  Cool   Server ")
	sid := view["id"].(string)
	if view["name"] != "Cool Server" || view["owner_id"] != idA || view["my_permissions"] != permStr(perms.All) {
		t.Fatalf("view: %v", view)
	}
	if len(view["channels"].([]any)) != 4 || len(view["roles"].([]any)) != 1 || view["member_count"].(float64) != 1 {
		t.Fatalf("defaults: %v", view)
	}
	general := channelNamed(view, "general")
	if general == nil || general["type"] != "text" || general["parent_id"] == nil || general["key_conv_id"] != sid {
		t.Fatalf("general: %v", general)
	}
	if view["system_channel_id"] != general["id"] {
		t.Error("system channel defaults to #general")
	}
	if ev := ga.next("server_create", 3*time.Second); ev == nil || ev["id"] != sid {
		t.Error("server_create event for owner")
	}
	// outsiders see nothing
	if res, _ := do(t, ts, "GET", "/api/v1/servers/"+sid, tokB, nil); res.StatusCode != 404 {
		t.Error("outsider get")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+general["id"].(string)+"/messages", tokB, nil); res.StatusCode != 404 {
		t.Error("outsider messages")
	}
	if res, _ := do(t, ts, "PATCH", "/api/v1/servers/"+sid, tokB, map[string]any{"name": "x"}); res.StatusCode != 404 {
		t.Error("outsider patch")
	}
	// invite preview is public
	code := inviteFor(t, ts, tokA, general["id"].(string))
	res, body := do(t, ts, "GET", "/api/v1/invites/"+code, "", nil)
	if res.StatusCode != 200 || body["server"].(map[string]any)["name"] != "Cool Server" || body["server"].(map[string]any)["member_count"].(float64) != 1 || body["inviter"].(map[string]any)["id"] != idA {
		t.Fatalf("preview: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "GET", "/api/v1/invites/nope", "", nil); res.StatusCode != 404 || errCode(body) != "invite_invalid" {
		t.Error("bad invite preview")
	}
	// bob joins
	bview := join(t, ts, tokB, code)
	if bview["my_permissions"] != permStr(perms.DefaultEveryone) || bview["member_count"].(float64) != 2 || len(bview["members"].([]any)) != 2 {
		t.Fatalf("bob view: %v", bview)
	}
	if ev := ga.next("member_add", 3*time.Second); ev == nil || ev["user"].(map[string]any)["id"] != idB {
		t.Error("member_add for alice")
	}
	if ev := gb.next("server_create", 3*time.Second); ev == nil || ev["id"] != sid {
		t.Error("server_create for bob")
	}
	if ev := ga.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_join" {
		t.Error("system join message")
	}
	// joining twice is idempotent
	if res, _ := do(t, ts, "POST", "/api/v1/invites/"+code, tokB, nil); res.StatusCode != 200 {
		t.Error("rejoin")
	}
	if _, body = do(t, ts, "GET", "/api/v1/invites/"+code, "", nil); body["server"].(map[string]any)["member_count"].(float64) != 2 {
		t.Error("member count after join")
	}
	// ready payload carries the server
	gb2 := connectGW(t, ts, tokB)
	_ = gb2
	// server list on the user's profile mutual servers
	res, body = do(t, ts, "GET", "/api/v1/users/"+idB+"/profile", tokA, nil)
	if res.StatusCode != 200 {
		t.Fatalf("profile: %d %v", res.StatusCode, body)
	}
	if ms, _ := body["mutual_servers"].([]any); len(ms) != 1 || ms[0] != sid {
		t.Errorf("mutual servers: %v", body["mutual_servers"])
	}
	// plain member cannot manage
	if res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokB, map[string]any{"type": "text", "name": "x"}); res.StatusCode != 403 || errCode(body) != "missing_permission" {
		t.Errorf("member create channel: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid, tokB, map[string]any{"name": "x"}); res.StatusCode != 403 {
		t.Error("member patch server")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/servers/"+sid+"/audit-log", tokB, nil); res.StatusCode != 403 {
		t.Error("member audit log")
	}
	// owner edits
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid, tokA, map[string]any{"name": "Renamed", "description": "hi", "system_channel_id": nil})
	if res.StatusCode != 200 || body["name"] != "Renamed" || body["description"] != "hi" || body["system_channel_id"] != nil {
		t.Fatalf("patch: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("server_update", 3*time.Second); ev == nil || ev["name"] != "Renamed" {
		t.Error("server_update for bob")
	}
	// channel creation normalises text names, and everyone receives it
	res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "text", "name": "Mod  Chat", "parent_id": general["parent_id"]})
	if res.StatusCode != 201 || body["name"] != "Mod Chat" || body["position"].(float64) != 1 {
		t.Fatalf("create channel: %d %v", res.StatusCode, body)
	}
	modChat := body["id"].(string)
	if ev := gb.next("channel_create", 3*time.Second); ev == nil || ev["id"] != modChat {
		t.Error("channel_create for bob")
	}
	res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "text", "name": "x", "parent_id": general["id"]})
	if res.StatusCode != 400 || errCode(body) != "invalid_parent" {
		t.Error("parent must be a category")
	}
	// messages in a server channel encrypt under the server root
	epoch := createEpoch(t, ts, tokA, sid)
	res, body = sendMsg(t, ts, tokA, general["id"].(string), sid, epoch, "hello", "", nil)
	if res.StatusCode != 201 || body["key_conv_id"] != sid {
		t.Fatalf("server message: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["channel_id"] != general["id"] {
		t.Error("message_create for bob")
	}
	if res, body = sendMsg(t, ts, tokA, general["id"].(string), general["id"].(string), epoch, "wrong conv", "", nil); res.StatusCode != 400 {
		t.Errorf("wrong conv accepted: %d %v", res.StatusCode, body)
	}
	// a non-holder may self-grant a key received out of band (invite link fragment)
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": sid, "reason": "manual"})
	if res.StatusCode != 201 {
		t.Fatalf("rotate: %d %v", res.StatusCode, body)
	}
	next := int(body["epoch"].(float64))
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{{"conv_id": sid, "epoch": next, "user_id": idA, "wrapped_key": b64([]byte("x"))}}); res.StatusCode != 403 || errCode(body) != "not_holder" {
		t.Errorf("non-holder grants others: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{{"conv_id": sid, "epoch": next, "user_id": idB, "wrapped_key": b64([]byte("from-link"))}}); res.StatusCode != 200 || body["inserted"].(float64) != 1 {
		t.Fatalf("self grant: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokB, []map[string]any{{"conv_id": sid, "epoch": next + 5, "user_id": idB, "wrapped_key": b64([]byte("x"))}}); res.StatusCode != 404 {
		t.Errorf("self grant for unknown epoch: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/keys", tokB, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("server keys: %d %v", res.StatusCode, body)
	}
	// leave / delete
	if res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokA, nil); res.StatusCode != 400 || errCode(body) != "owner_cannot_leave" {
		t.Error("owner leave")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid, tokB, nil); res.StatusCode != 403 {
		t.Error("member delete")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokB, nil); res.StatusCode != 204 {
		t.Error("bob leave")
	}
	if ev := gb.next("server_delete", 3*time.Second); ev == nil || ev["id"] != sid {
		t.Error("server_delete for bob after leaving")
	}
	if ev := ga.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != sid {
		t.Error("rotation needed after leave")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid, tokA, nil); res.StatusCode != 204 {
		t.Error("owner delete")
	}
	if ev := ga.next("server_delete", 3*time.Second); ev == nil {
		t.Error("server_delete for owner")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/servers/"+sid, tokA, nil); res.StatusCode != 404 {
		t.Error("deleted server gone")
	}
}

func TestRoleHierarchyAndModeration(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Hier")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	ga := connectGW(t, ts, tokA)
	gc := connectGW(t, ts, tokC)

	modPerms := perms.DefaultEveryone | perms.KickMembers | perms.BanMembers | perms.ManageRoles | perms.ModerateMembers | perms.ManageNicknames | perms.ViewAuditLog
	mod := createRole(t, ts, tokA, sid, "Mod", modPerms)
	helper := createRole(t, ts, tokA, sid, "Helper", perms.DefaultEveryone)
	// new roles land at the bottom: helper (1) below mod (2)
	_, body := do(t, ts, "GET", "/api/v1/servers/"+sid+"/roles", tokA, nil)
	pos := map[string]float64{}
	for _, r := range body["_list"].([]any) {
		ro := r.(map[string]any)
		pos[ro["id"].(string)] = ro["position"].(float64)
	}
	if pos[mod] != 2 || pos[helper] != 1 || pos[sid] != 0 {
		t.Fatalf("positions: %v", pos)
	}
	// non-owner cannot grant permissions they lack
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+mod, tokC, nil); res.StatusCode != 403 || errCode(body) != "missing_permission" {
		t.Errorf("member assigns role: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+mod, tokA, nil)
	if res.StatusCode != 200 || len(body["role_ids"].([]any)) != 1 {
		t.Fatalf("assign mod: %d %v", res.StatusCode, body)
	}
	if ev := gc.next("member_update", 3*time.Second); ev == nil || ev["user_id"] != idB {
		t.Error("member_update for carol")
	}
	// bob (mod) cannot touch his own role or roles above him, nor grant Administrator
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+mod, tokB, map[string]any{"name": "Boss"}); res.StatusCode != 403 || errCode(body) != "role_hierarchy" {
		t.Errorf("edit own role: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/roles", tokB, map[string]any{"name": "Admins", "permissions": permStr(perms.Administrator)}); res.StatusCode != 403 || errCode(body) != "missing_permission" {
		t.Errorf("grant admin: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+helper, tokB, map[string]any{"name": "Helpers", "color": 0x123456}); res.StatusCode != 200 || body["name"] != "Helpers" || body["color"].(float64) != 0x123456 {
		t.Errorf("edit lower role: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+sid, tokB, map[string]any{"permissions": permStr(perms.DefaultEveryone &^ perms.SendMessages)}); res.StatusCode != 200 {
		t.Errorf("edit everyone perms: %d %v", res.StatusCode, body)
	}
	// carol lost SendMessages
	createEpoch(t, ts, tokA, sid)
	if res, body = sendMsg(t, ts, tokC, general, sid, 0, "hi", "", nil); res.StatusCode != 403 || errCode(body) != "missing_permission" {
		t.Errorf("send without permission: %d %v", res.StatusCode, body)
	}
	_, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+sid, tokA, map[string]any{"permissions": permStr(perms.DefaultEveryone)})
	// bob cannot reorder roles above himself
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles", tokB, []map[string]any{{"id": helper, "position": 5}}); res.StatusCode != 403 {
		t.Errorf("reorder above self: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/roles/"+sid, tokA, nil); res.StatusCode != 400 {
		t.Error("delete everyone")
	}
	// kicks respect hierarchy
	if res, body = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idA, tokB, nil); res.StatusCode != 403 || errCode(body) != "role_hierarchy" {
		t.Errorf("kick owner: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idB, tokC, nil); res.StatusCode != 403 {
		t.Error("kick without permission")
	}
	// nickname + timeout
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/@me", tokC, map[string]any{"nick": "Caz"}); res.StatusCode != 200 || body["nickname"] != "Caz" {
		t.Errorf("self nick: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB, tokC, map[string]any{"nick": "x"}); res.StatusCode != 403 {
		t.Error("nick others without permission")
	}
	until := time.Now().Add(time.Hour).UnixMilli()
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idC, tokB, map[string]any{"timeout_until": until}); res.StatusCode != 200 || body["timeout_until"].(float64) != float64(until) {
		t.Fatalf("timeout: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokC, general, sid, 0, "hi", "", nil); res.StatusCode != 403 {
		t.Errorf("timed out send: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+general+"/messages", tokC, nil); res.StatusCode != 200 {
		t.Error("timed out can still read")
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idC, tokB, map[string]any{"timeout_until": nil}); res.StatusCode != 200 {
		t.Error("clear timeout")
	}
	if res, _ = sendMsg(t, ts, tokC, general, sid, 0, "hi", "", nil); res.StatusCode != 201 {
		t.Error("send after timeout")
	}
	// kick carol
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idC+"?reason=spam", tokB, nil); res.StatusCode != 204 {
		t.Error("kick carol")
	}
	if ev := gc.next("server_delete", 3*time.Second); ev == nil || ev["id"] != sid {
		t.Error("server_delete for kicked")
	}
	if ev := ga.next("member_remove", 3*time.Second); ev == nil || ev["user_id"] != idC || ev["reason"] != "kick" {
		t.Error("member_remove for alice")
	}
	if ev := ga.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != sid {
		t.Error("rotation after kick")
	}
	// ban
	join(t, ts, tokC, code)
	if res, _ = do(t, ts, "PUT", "/api/v1/servers/"+sid+"/bans/"+idC, tokB, map[string]any{"reason": "again", "delete_message_days": 1}); res.StatusCode != 204 {
		t.Error("ban")
	}
	if res, body = do(t, ts, "POST", "/api/v1/invites/"+code, tokC, nil); res.StatusCode != 403 || errCode(body) != "banned" {
		t.Errorf("banned join: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/bans", tokB, nil)
	if res.StatusCode != 200 || len(body["bans"].([]any)) != 1 || body["bans"].([]any)[0].(map[string]any)["reason"] != "again" {
		t.Errorf("bans: %d %v", res.StatusCode, body)
	}
	if ev := ga.next("ban_add", 3*time.Second); ev == nil || ev["user_id"] != idC {
		t.Error("ban_add event")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/bans/"+idC, tokB, nil); res.StatusCode != 204 {
		t.Error("unban")
	}
	join(t, ts, tokC, code)
	// audit log
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/audit-log", tokB, nil)
	if res.StatusCode != 200 {
		t.Fatalf("audit: %d %v", res.StatusCode, body)
	}
	actions := map[string]int{}
	for _, e := range body["entries"].([]any) {
		actions[e.(map[string]any)["action"].(string)]++
	}
	for _, a := range []string{"role_create", "member_role_add", "role_update", "member_update", "member_kick", "member_ban", "member_unban", "invite_create"} {
		if actions[a] == 0 {
			t.Errorf("audit missing %s: %v", a, actions)
		}
	}
	if res, _ = do(t, ts, "GET", "/api/v1/servers/"+sid+"/audit-log", tokC, nil); res.StatusCode != 403 {
		t.Error("audit without permission")
	}
	// ownership transfer
	if res, _ = do(t, ts, "POST", "/api/v1/servers/"+sid+"/transfer", tokB, map[string]any{"user_id": idB}); res.StatusCode != 403 {
		t.Error("transfer by non-owner")
	}
	if res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/transfer", tokA, map[string]any{"user_id": idB}); res.StatusCode != 200 || body["owner_id"] != idB {
		t.Fatalf("transfer: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "POST", "/api/v1/servers/"+sid+"/leave", tokA, nil); res.StatusCode != 204 {
		t.Error("old owner leaves")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid, tokB, nil); res.StatusCode != 204 {
		t.Error("new owner deletes")
	}
}

func TestPrivateChannelsAndVisibility(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Priv")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	gb := connectGW(t, ts, tokB)
	gc := connectGW(t, ts, tokC)

	res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "text", "name": "staff"})
	if res.StatusCode != 201 {
		t.Fatal(body)
	}
	staff := body["id"].(string)
	gb.next("channel_create", 3*time.Second)
	gc.next("channel_create", 3*time.Second)
	// deny @everyone view → channel becomes private and needs its own key
	res, body = do(t, ts, "PUT", "/api/v1/channels/"+staff+"/permissions/"+sid, tokA, map[string]any{"type": "role", "deny": permStr(perms.ViewChannel)})
	if res.StatusCode != 200 || body["key_actions"] == nil || body["key_actions"].(map[string]any)["needs_own_key"] != true {
		t.Fatalf("overwrite: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("channel_delete", 3*time.Second); ev == nil || ev["id"] != staff {
		t.Error("bob loses channel")
	}
	if ev := gc.next("channel_delete", 3*time.Second); ev == nil || ev["id"] != staff {
		t.Error("carol loses channel")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+staff+"/messages", tokB, nil); res.StatusCode != 404 {
		t.Error("hidden channel is 404")
	}
	_, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/channels", tokB, nil)
	for _, c := range body["_list"].([]any) {
		if c.(map[string]any)["id"] == staff {
			t.Error("hidden channel listed")
		}
	}
	// member overwrite lets bob back in
	res, body = do(t, ts, "PUT", "/api/v1/channels/"+staff+"/permissions/"+idB, tokA, map[string]any{"type": "member", "allow": permStr(perms.ViewChannel)})
	if res.StatusCode != 200 {
		t.Fatalf("member overwrite: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("channel_create", 3*time.Second); ev == nil || ev["id"] != staff || len(ev["overwrites"].([]any)) != 2 {
		t.Errorf("bob regains channel: %v", ev)
	}
	// the private channel encrypts under its own conversation: alice + bob only
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": staff})
	if res.StatusCode != 201 || len(body["user_ids"].([]any)) != 2 {
		t.Fatalf("private epoch: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+staff, tokB, nil)
	if res.StatusCode != 200 || body["key_conv_id"] != staff {
		t.Fatalf("key conv: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": staff, "epoch": 0, "user_id": idA, "wrapped_key": b64([]byte("a"))}}); res.StatusCode != 200 {
		t.Fatalf("self grant: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": staff, "epoch": 0, "user_id": idC, "wrapped_key": b64([]byte("c"))}}); res.StatusCode != 403 || errCode(body) != "not_entitled" {
		t.Errorf("grant to non-viewer: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokA, staff, sid, 0, "wrong root", "", nil); res.StatusCode != 400 {
		t.Errorf("server root rejected for private channel: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokA, staff, staff, 0, "secret", "", nil); res.StatusCode != 201 {
		t.Errorf("private send: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["channel_id"] != staff {
		t.Error("bob receives private message")
	}
	if ev := gc.next("message_create", 500*time.Millisecond); ev != nil {
		t.Error("carol must not receive private message")
	}
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/keys", tokB, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 2 {
		t.Errorf("bob's key convs: %d %v", res.StatusCode, body)
	}
	// role-based access: carol gets a role with an allow overwrite
	role := createRole(t, ts, tokA, sid, "Staff", perms.DefaultEveryone)
	if res, _ = do(t, ts, "PUT", "/api/v1/channels/"+staff+"/permissions/"+role, tokA, map[string]any{"type": "role", "allow": permStr(perms.ViewChannel)}); res.StatusCode != 200 {
		t.Fatal("role overwrite")
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idC, tokA, map[string]any{"roles": []string{role}}); res.StatusCode != 200 {
		t.Fatal("assign staff")
	}
	if ev := gc.next("channel_create", 3*time.Second); ev == nil || ev["id"] != staff {
		t.Error("carol gains channel via role")
	}
	// alice is flagged to grant carol the private key
	res, body = do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil)
	found := false
	for _, n := range body["_list"].([]any) {
		need := n.(map[string]any)
		if need["conv_id"] == staff {
			for _, u := range need["user_ids"].([]any) {
				if u == idC {
					found = true
				}
			}
		}
	}
	if !found {
		t.Errorf("carol should need the staff key: %v", body)
	}
	// removing the role hides it again and flags rotation
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idC+"/roles/"+role, tokA, nil); res.StatusCode != 200 {
		t.Fatal("remove role")
	}
	if ev := gc.next("channel_delete", 3*time.Second); ev == nil || ev["id"] != staff {
		t.Error("carol loses channel again")
	}
	if ev := gb.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != staff {
		t.Error("private channel rotation")
	}
	// deleting the overwrite for bob removes him
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+staff+"/permissions/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatal("delete overwrite")
	}
	if ev := gb.next("channel_delete", 3*time.Second); ev == nil || ev["id"] != staff {
		t.Error("bob loses channel after overwrite removal")
	}
	// only the manager may touch overwrites
	if res, _ = do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+sid, tokB, map[string]any{"type": "role", "deny": permStr(perms.ViewChannel)}); res.StatusCode != 403 {
		t.Error("member edits overwrite")
	}
	// channel delete
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+staff, tokB, nil); res.StatusCode != 404 {
		t.Error("delete hidden channel")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+staff, tokA, nil); res.StatusCode != 204 {
		t.Error("delete channel")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+staff, tokA, nil); res.StatusCode != 404 {
		t.Error("deleted channel gone")
	}
}

func TestChannelPositionsAndSettings(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Order")
	sid := view["id"].(string)
	general := channelNamed(view, "general")
	textCat := channelNamed(view, "Text Channels")["id"].(string)
	code := inviteFor(t, ts, tokA, general["id"].(string))
	join(t, ts, tokB, code)
	gb := connectGW(t, ts, tokB)

	res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "category", "name": "Projects"})
	if res.StatusCode != 201 || body["position"].(float64) != 2 {
		t.Fatalf("category: %d %v", res.StatusCode, body)
	}
	projects := body["id"].(string)
	gb.next("channel_create", 3*time.Second)
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/channels", tokA, []map[string]any{{"id": general["id"], "position": 0, "parent_id": projects}, {"id": projects, "position": 0}, {"id": textCat, "position": 1}})
	if res.StatusCode != 200 {
		t.Fatalf("positions: %d %v", res.StatusCode, body)
	}
	moved := false
	for _, c := range body["_list"].([]any) {
		ch := c.(map[string]any)
		if ch["id"] == general["id"] && ch["parent_id"] == projects {
			moved = true
		}
	}
	if !moved {
		t.Error("general moved into Projects")
	}
	if ev := gb.next("channel_positions", 3*time.Second); ev == nil || ev["server_id"] != sid || len(ev["channels"].([]any)) != 5 {
		t.Errorf("channel_positions event: %v", ev)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/channels", tokA, []map[string]any{{"id": textCat, "position": 0, "parent_id": projects}}); res.StatusCode != 400 {
		t.Errorf("category into category: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/channels", tokB, []map[string]any{{"id": general["id"], "position": 3}}); res.StatusCode != 403 {
		t.Error("member reorders")
	}
	// channel settings
	if res, _ = do(t, ts, "PATCH", "/api/v1/channels/"+general["id"].(string), tokB, map[string]any{"topic": "x"}); res.StatusCode != 403 {
		t.Error("member edits channel")
	}
	res, body = do(t, ts, "PATCH", "/api/v1/channels/"+general["id"].(string), tokA, map[string]any{"topic": "Talk here", "slowmode_seconds": 5, "nsfw": true, "name": "General Chat"})
	if res.StatusCode != 200 || body["topic"] != "Talk here" || body["slowmode_seconds"].(float64) != 5 || body["nsfw"] != true || body["name"] != "General Chat" {
		t.Fatalf("channel patch: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("channel_update", 3*time.Second); ev == nil || ev["topic"] != "Talk here" {
		t.Error("channel_update for bob")
	}
	if res, _ = do(t, ts, "PATCH", "/api/v1/channels/"+general["id"].(string), tokA, map[string]any{"slowmode_seconds": 99999}); res.StatusCode != 400 {
		t.Error("slowmode bounds")
	}
	// slow mode applies to members without Manage Messages
	epoch := createEpoch(t, ts, tokA, sid)
	if res, body = sendMsg(t, ts, tokB, general["id"].(string), sid, epoch, "one", "", nil); res.StatusCode != 201 {
		t.Fatalf("first send: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokB, general["id"].(string), sid, epoch, "two", "", nil); res.StatusCode != 429 || errCode(body) != "slowmode" || res.Header.Get("Retry-After") == "" {
		t.Errorf("slowmode: %d %v", res.StatusCode, body)
	}
	if res, _ = sendMsg(t, ts, tokA, general["id"].(string), sid, epoch, "mod", "", nil); res.StatusCode != 201 {
		t.Error("owner bypasses slowmode")
	}
	if res, _ = sendMsg(t, ts, tokA, general["id"].(string), sid, epoch, "mod2", "", nil); res.StatusCode != 201 {
		t.Error("owner bypasses slowmode twice")
	}
	// deleting a category unparents its children
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+projects, tokA, nil); res.StatusCode != 204 {
		t.Fatal("delete category")
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+general["id"].(string), tokA, nil)
	if res.StatusCode != 200 || body["parent_id"] != nil {
		t.Errorf("unparented: %d %v", res.StatusCode, body)
	}
}

func TestInviteLimits(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	tokC, _ := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Inv")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	res, body := do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokA, map[string]any{"max_uses": 1})
	if res.StatusCode != 201 || body["max_uses"].(float64) != 1 {
		t.Fatalf("invite: %d %v", res.StatusCode, body)
	}
	once := body["code"].(string)
	join(t, ts, tokB, once)
	if res, body = do(t, ts, "POST", "/api/v1/invites/"+once, tokC, nil); res.StatusCode != 404 || errCode(body) != "invite_invalid" {
		t.Errorf("exhausted invite: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokA, map[string]any{"max_age": 1})
	expiring := body["code"].(string)
	if body["expires_at"] == nil {
		t.Fatalf("expires_at: %v", body)
	}
	time.Sleep(1100 * time.Millisecond)
	if res, _ = do(t, ts, "POST", "/api/v1/invites/"+expiring, tokC, nil); res.StatusCode != 404 {
		t.Error("expired invite")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokA, map[string]any{"max_age": 99999999}); res.StatusCode != 400 {
		t.Error("invite bounds")
	}
	// bob (no CreateInstantInvite after we strip it) and revocation rights
	_, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+sid, tokA, map[string]any{"permissions": permStr(perms.DefaultEveryone &^ perms.CreateInstantInvite)})
	if res, _ = do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokB, nil); res.StatusCode != 403 {
		t.Error("invite without permission")
	}
	_, _ = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+sid, tokA, map[string]any{"permissions": permStr(perms.DefaultEveryone)})
	res, body = do(t, ts, "POST", "/api/v1/channels/"+general+"/invites", tokB, nil)
	bobs := body["code"].(string)
	if res, _ = do(t, ts, "GET", "/api/v1/servers/"+sid+"/invites", tokB, nil); res.StatusCode != 403 {
		t.Error("list invites needs manage server")
	}
	res, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/invites", tokA, nil)
	// exhausted and expired invites are not listed
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("invite list: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/invites/"+bobs, tokC, nil); res.StatusCode != 404 {
		t.Error("outsider revokes")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/invites/"+bobs, tokB, nil); res.StatusCode != 204 {
		t.Error("creator revokes")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/invites/"+bobs, tokC, nil); res.StatusCode != 404 {
		t.Error("revoked invite")
	}
}

// Another tab of the account that makes or joins a server learns of it from
// server_create, and needs the people in its member list as well as their ids.
func TestServerCreateCarriesTheMembers(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)
	view := createServer(t, ts, tokA, "S")
	ev := ga.next("server_create", 3*time.Second)
	if ev == nil || ev["id"] != view["id"] {
		t.Fatalf("server_create for the owner: %v", ev)
	}
	named := func(ev map[string]any, id string) bool {
		users, _ := ev["users"].([]any)
		for _, u := range users {
			if u.(map[string]any)["id"] == id {
				return true
			}
		}
		return false
	}
	if !named(ev, idA) {
		t.Errorf("the owner's own server_create names nobody: %v", ev["users"])
	}
	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(view, "general")["id"].(string)))
	ev = gb.next("server_create", 3*time.Second)
	if ev == nil || !named(ev, idA) || !named(ev, idB) {
		t.Errorf("the joiner's server_create should carry both members: %v", ev)
	}
}
