package api

import "testing"

// A vanity link is managed only with Manage Server, belongs to one server,
// is found in any case, joins as its own method and survives Revoke All.
func TestVanityLink(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	sid := createServer(t, ts, tokA, "Club")["id"].(string)
	other := createServer(t, ts, tokA, "Other")["id"].(string)
	path := "/api/v1/servers/" + sid + "/vanity-url"

	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(createServer(t, ts, tokA, "Lobby"), "general")["id"].(string)))
	if res, _ := do(t, ts, "PATCH", path, tokB, map[string]any{"code": "mine"}); res.StatusCode != 404 && res.StatusCode != 403 {
		t.Fatalf("a non-member set it: %d", res.StatusCode)
	}
	for _, bad := range []string{"ab", "-club", "club-", "has space", "nexa"} {
		if res, body := do(t, ts, "PATCH", path, tokA, map[string]any{"code": bad}); res.StatusCode != 400 {
			t.Fatalf("%q accepted: %d %v", bad, res.StatusCode, body)
		}
	}
	if res, body := do(t, ts, "PATCH", path, tokA, map[string]any{"code": " The-Club "}); res.StatusCode != 200 || body["code"] != "the-club" {
		t.Fatalf("set: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+other+"/vanity-url", tokA, map[string]any{"code": "THE-CLUB"}); res.StatusCode != 409 || errCode(body) != "vanity_taken" {
		t.Fatalf("taken twice: %d %v", res.StatusCode, body)
	}

	if res, body := do(t, ts, "GET", "/api/v1/invites/The-CLUB", "", nil); res.StatusCode != 200 || body["vanity"] != true || body["inviter"] != nil {
		t.Fatalf("preview: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/invites", tokA, nil); res.StatusCode != 200 {
		t.Fatalf("revoke all: %d", res.StatusCode)
	}
	if got := join(t, ts, tokB, "THE-club"); got["id"] != sid {
		t.Fatalf("joined %v", got["id"])
	}
	if res, body := do(t, ts, "GET", "/api/v1/servers/"+sid+"/members/"+idB+"/mod", tokA, nil); res.StatusCode != 200 || body["member"].(map[string]any)["join"].(map[string]any)["join_method"] != "vanity" {
		t.Fatalf("join method: %d %v", res.StatusCode, body)
	}
	if _, body := do(t, ts, "GET", path, tokA, nil); body["code"] != "the-club" || body["uses"].(float64) != 1 {
		t.Fatalf("after join: %v", body)
	}

	if res, body := do(t, ts, "PATCH", path, tokA, map[string]any{"code": nil}); res.StatusCode != 200 || body["code"] != nil {
		t.Fatalf("clear: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/invites/the-club", "", nil); res.StatusCode != 404 {
		t.Fatalf("cleared link still works: %d", res.StatusCode)
	}
}
