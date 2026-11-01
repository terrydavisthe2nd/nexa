package api

import (
	"testing"
)

// A link channel: a name in the channel list that opens an address. It takes
// only whole http and https addresses, holds no messages, takes no invites,
// and its address can be changed but given to no other kind of channel.
func TestLinkChannels(t *testing.T) {
	c := newCommunity(t)
	create := func(body map[string]any) (int, map[string]any) {
		res, out := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, body)
		return res.StatusCode, out
	}
	for _, bad := range []string{"", "nexa.fish", "javascript:alert(1)", "ftp://example.com/x", "https://user:pw@example.com/", "https://", "https://exa mple.com/", "mailto:a@example.com"} {
		if code, body := create(map[string]any{"type": "link", "name": "Website", "url": bad}); code != 400 || errCode(body) != "invalid_url" {
			t.Errorf("a link to %q: %d %v", bad, code, body)
		}
	}
	if code, body := create(map[string]any{"type": "text", "name": "x", "url": "https://example.com"}); code != 400 || errCode(body) != "invalid_url" {
		t.Errorf("a text channel with an address: %d %v", code, body)
	}
	code, link := create(map[string]any{"type": "link", "name": "Our Website", "url": " https://example.com/about?x=1#top "})
	if code != 201 || link["type"] != "link" || link["url"] != "https://example.com/about?x=1#top" || link["name"] != "Our Website" {
		t.Fatalf("link channel: %d %v", code, link)
	}
	lid := link["id"].(string)
	// members see it, address included
	_, chans := do(t, c.ts, "GET", "/api/v1/servers/"+c.server+"/channels", c.tokB, nil)
	found := false
	for _, ch := range listOf(chans, "_list") {
		if m := ch.(map[string]any); m["id"] == lid {
			found = m["url"] == "https://example.com/about?x=1#top"
		}
	}
	if !found {
		t.Errorf("a member does not see the link with its address: %v", chans)
	}
	if res, body := sendMsg(t, c.ts, c.tokB, lid, c.server, 0, "hello", "", nil); res.StatusCode != 400 || errCode(body) != "not_text" {
		t.Errorf("a message in a link channel: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "POST", "/api/v1/channels/"+lid+"/invites", c.tokA, map[string]any{}); res.StatusCode != 400 || errCode(body) != "not_text" {
		t.Errorf("an invite to a link channel: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+lid, c.tokA, map[string]any{"url": "http://example.org/"}); res.StatusCode != 200 || body["url"] != "http://example.org/" {
		t.Errorf("changing the address: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+lid, c.tokA, map[string]any{"url": "data:text/html,hi"}); res.StatusCode != 400 || errCode(body) != "invalid_url" {
		t.Errorf("a bad address on change: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+lid, c.tokB, map[string]any{"url": "https://evil.example/"}); res.StatusCode != 403 {
		t.Errorf("a member changed the address: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+c.general, c.tokA, map[string]any{"url": "https://example.com/"}); res.StatusCode != 400 || errCode(body) != "invalid_url" {
		t.Errorf("an address on a text channel: %d %v", res.StatusCode, body)
	}
}

// A media channel is a forum drawn as a gallery: posts as in a forum, no
// messages straight in, the gallery layout whatever is asked, and a switch
// that hides download buttons, which a forum does not keep.
func TestMediaChannels(t *testing.T) {
	c := newCommunity(t)
	res, media := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, map[string]any{"type": "media", "name": "Fan Art"})
	if res.StatusCode != 201 || media["type"] != "media" || media["name"] != "Fan Art" {
		t.Fatalf("media channel: %d %v", res.StatusCode, media)
	}
	mid := media["id"].(string)
	if f, _ := media["forum"].(map[string]any); f == nil || f["layout"] != "gallery" {
		t.Errorf("a media channel is not a gallery: %v", media["forum"])
	}
	if res, body := sendMsg(t, c.ts, c.tokB, mid, c.server, 0, "straight in", "", nil); res.StatusCode != 400 || errCode(body) != "forum_posts_only" {
		t.Errorf("a message straight into a media channel: %d %v", res.StatusCode, body)
	}
	res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+mid, c.tokA, map[string]any{"forum": map[string]any{"layout": "list", "hide_downloads": true}})
	if res.StatusCode != 200 {
		t.Fatalf("media settings: %d %v", res.StatusCode, body)
	}
	if f := body["forum"].(map[string]any); f["layout"] != "gallery" || f["hide_downloads"] != true {
		t.Errorf("media settings kept: %v", f)
	}
	msg := map[string]any{"ciphertext": envelopeFor(c.server, 0, "my drawing"), "key_conv_id": c.server, "key_epoch": 0, "mentions": []string{}}
	if code, body := c.thread(t, c.tokB, mid, map[string]any{"name": "Sunset", "message": msg}); code != 201 || body["message"] == nil {
		t.Fatalf("a post in a media channel: %d %v", code, body)
	}
	_, list := do(t, c.ts, "GET", "/api/v1/channels/"+mid+"/threads", c.tokA, nil)
	if len(listOf(list, "threads")) != 1 {
		t.Errorf("media posts: %v", list)
	}
	// a forum never keeps the media channel's switch
	_, forum := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, map[string]any{"type": "forum", "name": "ideas"})
	_, body = do(t, c.ts, "PATCH", "/api/v1/channels/"+forum["id"].(string), c.tokA, map[string]any{"forum": map[string]any{"layout": "list", "hide_downloads": true}})
	if f := body["forum"].(map[string]any); f["layout"] != "list" || f["hide_downloads"] != nil {
		t.Errorf("a forum's settings: %v", f)
	}
}
