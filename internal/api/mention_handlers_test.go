package api

import (
	"testing"
)

// The Mentions list holds what named you, your roles and everyone, from
// conversations and servers alike, sorted and narrowed as asked; never your
// own messages, someone you blocked, or a channel you cannot read.
func TestMentionsList(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "S")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	epoch := createEpoch(t, ts, tokA, sid)
	_, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/roles", tokA, map[string]any{"name": "Crew"})
	crew := body["id"].(string)
	if res, b := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idB+"/roles/"+crew, tokA, nil); res.StatusCode >= 300 {
		t.Fatalf("give role: %d %v", res.StatusCode, b)
	}
	send := func(tok, ch, conv string, ep int, nonce string, extra map[string]any) string {
		t.Helper()
		res, body := sendMsg(t, ts, tok, ch, conv, ep, "hi", nonce, extra)
		if res.StatusCode != 201 {
			t.Fatalf("send: %d %v", res.StatusCode, body)
		}
		return body["id"].(string)
	}
	direct := send(tokA, general, sid, epoch, "m1", map[string]any{"mentions": []string{idB}})
	byRole := send(tokA, general, sid, epoch, "m2", map[string]any{"mention_roles": []string{crew}})
	everyone := send(tokA, general, sid, epoch, "m3", map[string]any{"mention_everyone": true})
	send(tokA, general, sid, epoch, "m4", nil)                                       // mentions nobody
	send(tokB, general, sid, epoch, "m5", map[string]any{"mentions": []string{idB}}) // bob's own
	_, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	inDM := send(tokA, dm, dm, 0, "d1", map[string]any{"mentions": []string{idB}})

	list := func(query string) []string {
		t.Helper()
		res, body := do(t, ts, "GET", "/api/v1/users/@me/mentions"+query, tokB, nil)
		if res.StatusCode != 200 {
			t.Fatalf("mentions%s: %d %v", query, res.StatusCode, body)
		}
		raw, _ := body["_list"].([]any)
		out := []string{}
		for _, m := range raw {
			out = append(out, m.(map[string]any)["id"].(string))
		}
		return out
	}
	same := func(name string, got []string, want ...string) {
		t.Helper()
		if len(got) != len(want) {
			t.Fatalf("%s: got %v, want %v", name, got, want)
		}
		for i := range got {
			if got[i] != want[i] {
				t.Fatalf("%s: got %v, want %v", name, got, want)
			}
		}
	}
	same("newest", list(""), inDM, everyone, byRole, direct)
	same("oldest", list("?sort=oldest"), direct, byRole, everyone, inDM)
	same("only me", list("?kinds=me"), inDM, direct)
	same("roles", list("?kinds=roles"), byRole)
	same("everyone", list("?kinds=everyone"), everyone)
	same("one server", list("?server_id="+sid), everyone, byRole, direct)
	same("conversations", list("?server_id=@me"), inDM)
	same("one channel", list("?channel_id="+general), everyone, byRole, direct)
	same("page back", list("?before="+everyone+"&limit=1"), byRole)
	same("page forward", list("?sort=oldest&after="+byRole+"&limit=1"), everyone)

	// blocking alice hides all of hers
	if res, _ := do(t, ts, "PUT", "/api/v1/users/@me/blocks/"+idA, tokB, nil); res.StatusCode >= 300 {
		t.Fatalf("block: %d", res.StatusCode)
	}
	same("blocked", list(""))
	do(t, ts, "DELETE", "/api/v1/users/@me/blocks/"+idA, tokB, nil)

	// a channel bob can no longer see takes its mentions with it
	if res, b := do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+sid, tokA, map[string]any{"type": "role", "allow": "0", "deny": "1024"}); res.StatusCode >= 300 {
		t.Fatalf("hide channel: %d %v", res.StatusCode, b)
	}
	same("hidden channel", list(""), inDM)
}
