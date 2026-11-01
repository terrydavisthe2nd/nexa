package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
)

// community is a server with its owner (alice) and a member (bob), the
// server's key, its #general, and a message of alice's in it.
type community struct {
	ts              *httptest.Server
	tokA, tokB      string
	idA, idB        string
	server, general string
	message         string
}

func newCommunity(t *testing.T) *community {
	t.Helper()
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Town")
	server := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	createEpoch(t, ts, tokA, server)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	res, body := sendMsg(t, ts, tokA, general, server, 0, "hello town", "", nil)
	if res.StatusCode != 201 {
		t.Fatalf("send: %d %v", res.StatusCode, body)
	}
	return &community{ts: ts, tokA: tokA, tokB: tokB, idA: idA, idB: idB, server: server, general: general, message: body["id"].(string)}
}

func (c *community) thread(t *testing.T, token, parent string, body map[string]any) (int, map[string]any) {
	t.Helper()
	res, out := do(t, c.ts, "POST", "/api/v1/channels/"+parent+"/threads", token, body)
	return res.StatusCode, out
}

func listOf(body map[string]any, key string) []any {
	if v, ok := body[key].([]any); ok {
		return v
	}
	return nil
}

// A thread starts from a message once, is seen by everyone who sees its
// channel, takes in whoever writes in it, closes and opens again, and once
// locked hears only from moderators.
func TestThreadLifecycle(t *testing.T) {
	c := newCommunity(t)
	ga := connectGW(t, c.ts, c.tokA)
	code, body := c.thread(t, c.tokB, c.general, map[string]any{"name": "  about   hello ", "message_id": c.message})
	if code != 201 {
		t.Fatalf("thread from a message: %d %v", code, body)
	}
	th := body["thread"].(map[string]any)
	tid := th["id"].(string)
	if th["type"] != "thread" || th["name"] != "about hello" || th["parent_id"] != c.general {
		t.Errorf("thread: %v", th)
	}
	if ev := ga.next("thread_create", 2*time.Second); ev == nil || ev["id"] != tid {
		t.Errorf("thread_create to a viewer: %v", ev)
	}
	if code, body := c.thread(t, c.tokB, c.general, map[string]any{"name": "again", "message_id": c.message}); code != 409 || errCode(body) != "thread_exists" {
		t.Errorf("a second thread on one message: %d %v", code, body)
	}
	// the message it started from says so
	_, page := do(t, c.ts, "GET", "/api/v1/channels/"+c.general+"/messages", c.tokA, nil)
	var starter map[string]any
	for _, m := range listOf(page, "_list") {
		if m.(map[string]any)["id"] == c.message {
			starter = m.(map[string]any)
		}
	}
	if s, _ := starter["thread"].(map[string]any); s == nil || s["id"] != tid {
		t.Errorf("starter message without its thread: %v", starter)
	}
	// writing in it brings alice in, and counts
	if res, body := sendMsg(t, c.ts, c.tokA, tid, c.server, 0, "in the thread", "", nil); res.StatusCode != 201 {
		t.Fatalf("send in thread: %d %v", res.StatusCode, body)
	}
	// and the message it hangs from carries what was said last, sealed, for the line under it
	_, page = do(t, c.ts, "GET", "/api/v1/channels/"+c.general+"/messages", c.tokA, nil)
	for _, m := range listOf(page, "_list") {
		if m.(map[string]any)["id"] != c.message {
			continue
		}
		sum := m.(map[string]any)["thread"].(map[string]any)
		last, _ := sum["last_message"].(map[string]any)
		if last == nil || last["channel_id"] != tid || last["author_id"] != c.idA || last["ciphertext"] == nil || sum["message_count"].(float64) != 1 {
			t.Errorf("thread summary's last message: %v", sum)
		}
	}
	_, list := do(t, c.ts, "GET", "/api/v1/channels/"+c.general+"/threads", c.tokA, nil)
	threads := listOf(list, "threads")
	if len(threads) != 1 || threads[0].(map[string]any)["joined"] != true || threads[0].(map[string]any)["thread"].(map[string]any)["message_count"].(float64) != 1 {
		t.Errorf("thread list: %v", list)
	}
	// its starter closes it; writing opens it again
	if res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+tid, c.tokB, map[string]any{"archived": true}); res.StatusCode != 200 || body["thread"].(map[string]any)["archived"] != true {
		t.Fatalf("close: %d %v", res.StatusCode, body)
	}
	if res, _ := sendMsg(t, c.ts, c.tokB, tid, c.server, 0, "back again", "", nil); res.StatusCode != 201 {
		t.Fatalf("send in a closed thread: %d", res.StatusCode)
	}
	if _, ch := do(t, c.ts, "GET", "/api/v1/channels/"+tid, c.tokA, nil); ch["thread"].(map[string]any)["archived"] != false {
		t.Errorf("a message did not open the thread again: %v", ch)
	}
	// only moderators lock, and a locked thread hears from them alone
	if res, _ := do(t, c.ts, "PATCH", "/api/v1/channels/"+tid, c.tokB, map[string]any{"locked": true}); res.StatusCode != 403 {
		t.Errorf("a member locked a thread: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "PATCH", "/api/v1/channels/"+tid, c.tokA, map[string]any{"locked": true}); res.StatusCode != 200 {
		t.Fatalf("lock: %d", res.StatusCode)
	}
	if res, body := sendMsg(t, c.ts, c.tokB, tid, c.server, 0, "let me in", "", nil); res.StatusCode != 403 || errCode(body) != "thread_locked" {
		t.Errorf("a member wrote in a locked thread: %d %v", res.StatusCode, body)
	}
	if res, _ := sendMsg(t, c.ts, c.tokA, tid, c.server, 0, "moderating", "", nil); res.StatusCode != 201 {
		t.Errorf("a moderator could not write in a locked thread: %d", res.StatusCode)
	}
	// its starter cannot delete it once others have written in it; a moderator can
	if res, _ := do(t, c.ts, "DELETE", "/api/v1/channels/"+tid, c.tokB, nil); res.StatusCode != 403 {
		t.Errorf("starter deleted a thread others wrote in: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "DELETE", "/api/v1/channels/"+tid, c.tokA, nil); res.StatusCode != 204 {
		t.Fatalf("delete: %d", res.StatusCode)
	}
	if ev := ga.next("thread_delete", 2*time.Second); ev == nil || ev["id"] != tid {
		t.Errorf("thread_delete: %v", ev)
	}
}

// Talking in threads is its own permission, apart from talking in the channel.
func TestSendMessagesInThreads(t *testing.T) {
	c := newCommunity(t)
	_, body := c.thread(t, c.tokA, c.general, map[string]any{"name": "side talk"})
	tid := body["thread"].(map[string]any)["id"].(string)
	// @everyone keeps the channel but loses threads
	if res, body := do(t, c.ts, "PUT", "/api/v1/channels/"+c.general+"/permissions/"+c.server, c.tokA, map[string]any{"type": "role", "allow": "0", "deny": permStr(perms.SendMessagesInThreads)}); res.StatusCode != 200 {
		t.Fatalf("overwrite: %d %v", res.StatusCode, body)
	}
	if res, _ := sendMsg(t, c.ts, c.tokB, tid, c.server, 0, "nope", "", nil); res.StatusCode != 403 {
		t.Errorf("sent in a thread without Send Messages in Threads: %d", res.StatusCode)
	}
	if res, _ := sendMsg(t, c.ts, c.tokB, c.general, c.server, 0, "still here", "", nil); res.StatusCode != 201 {
		t.Errorf("lost the channel too: %d", res.StatusCode)
	}
	// and the other way round: no Send Messages, but threads still allowed
	if res, _ := do(t, c.ts, "PUT", "/api/v1/channels/"+c.general+"/permissions/"+c.server, c.tokA, map[string]any{"type": "role", "allow": permStr(perms.SendMessagesInThreads), "deny": permStr(perms.SendMessages)}); res.StatusCode != 200 {
		t.Fatal("overwrite")
	}
	if res, _ := sendMsg(t, c.ts, c.tokB, tid, c.server, 0, "threads only", "", nil); res.StatusCode != 201 {
		t.Errorf("Send Messages in Threads alone does not send in a thread: %d", res.StatusCode)
	}
}

// A private thread is seen only by the people in it and moderators, has a key
// of its own, and lets in the people its members bring or mention.
func TestPrivateThreads(t *testing.T) {
	c := newCommunity(t)
	tokC, idC := register(t, c.ts, "carol")
	join(t, c.ts, tokC, inviteFor(t, c.ts, c.tokA, c.general))
	code, body := c.thread(t, c.tokB, c.general, map[string]any{"name": "secret plans", "private": true})
	if code != 201 {
		t.Fatalf("private thread: %d %v", code, body)
	}
	tid := body["thread"].(map[string]any)["id"].(string)
	if ka, _ := body["key_actions"].(map[string]any); ka == nil || ka["needs_own_key"] != true {
		t.Errorf("a private thread asks for a key of its own: %v", body)
	}
	if res, _ := do(t, c.ts, "GET", "/api/v1/channels/"+tid+"/messages", tokC, nil); res.StatusCode != 404 {
		t.Errorf("an outsider read a private thread: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "GET", "/api/v1/channels/"+tid+"/messages", c.tokA, nil); res.StatusCode != 200 {
		t.Errorf("the owner could not read it: %d", res.StatusCode)
	}
	_, list := do(t, c.ts, "GET", "/api/v1/channels/"+c.general+"/threads", tokC, nil)
	if len(listOf(list, "threads")) != 0 {
		t.Errorf("an outsider sees the private thread listed: %v", list)
	}
	gc := connectGW(t, c.ts, tokC)
	if res, _ := do(t, c.ts, "PUT", "/api/v1/channels/"+tid+"/thread-members/"+idC, c.tokB, nil); res.StatusCode != 204 {
		t.Fatalf("add: %d", res.StatusCode)
	}
	if ev := gc.next("thread_create", 2*time.Second); ev == nil || ev["id"] != tid {
		t.Errorf("the person added was not shown the thread: %v", ev)
	}
	if res, _ := do(t, c.ts, "GET", "/api/v1/channels/"+tid+"/messages", tokC, nil); res.StatusCode != 200 {
		t.Errorf("the person added cannot read it: %d", res.StatusCode)
	}
	// only a moderator or its starter takes people out
	if res, _ := do(t, c.ts, "DELETE", "/api/v1/channels/"+tid+"/thread-members/"+c.idB, tokC, nil); res.StatusCode != 403 {
		t.Errorf("a member took its starter out: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "DELETE", "/api/v1/channels/"+tid+"/thread-members/"+idC, c.tokB, nil); res.StatusCode != 204 {
		t.Errorf("the starter could not take someone out: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "GET", "/api/v1/channels/"+tid+"/messages", tokC, nil); res.StatusCode != 404 {
		t.Errorf("still readable after being taken out: %d", res.StatusCode)
	}
	// announcement channels take public threads only
	if res, _ := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, map[string]any{"type": "announcement", "name": "News"}); res.StatusCode != 201 {
		t.Fatal("announcement channel")
	}
}

// A forum holds posts: each a thread with a title, tags and a first message.
func TestForumPosts(t *testing.T) {
	c := newCommunity(t)
	res, forum := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, map[string]any{"type": "forum", "name": "Ideas Board"})
	if res.StatusCode != 201 || forum["name"] != "Ideas Board" {
		t.Fatalf("forum: %d %v", res.StatusCode, forum)
	}
	fid := forum["id"].(string)
	if res, body := sendMsg(t, c.ts, c.tokB, fid, c.server, 0, "straight in", "", nil); res.StatusCode != 400 || errCode(body) != "forum_posts_only" {
		t.Errorf("a message straight into a forum: %d %v", res.StatusCode, body)
	}
	res, body := do(t, c.ts, "PATCH", "/api/v1/channels/"+fid, c.tokA, map[string]any{"forum": map[string]any{"tags": []map[string]any{{"name": "Feedback"}, {"name": "Staff", "moderated": true}}, "require_tag": true, "layout": "gallery"}})
	if res.StatusCode != 200 {
		t.Fatalf("forum settings: %d %v", res.StatusCode, body)
	}
	tags := body["forum"].(map[string]any)["tags"].([]any)
	feedback, staff := tags[0].(map[string]any)["id"].(string), tags[1].(map[string]any)["id"].(string)
	post := func(token string, tags []string) (int, map[string]any) {
		msg := map[string]any{"ciphertext": envelopeFor(c.server, 0, "my idea"), "key_conv_id": c.server, "key_epoch": 0, "mentions": []string{}}
		return c.thread(t, token, fid, map[string]any{"name": "A Better Lobby", "applied_tags": tags, "message": msg})
	}
	if code, body := post(c.tokB, nil); code != 400 || errCode(body) != "tag_required" {
		t.Errorf("a post without the tag it needs: %d %v", code, body)
	}
	if code, body := post(c.tokB, []string{staff}); code != 403 || errCode(body) != "moderated_tag" {
		t.Errorf("a member put a moderators' tag on: %d %v", code, body)
	}
	code, body := post(c.tokB, []string{feedback})
	if code != 201 || body["message"] == nil {
		t.Fatalf("post: %d %v", code, body)
	}
	pid := body["thread"].(map[string]any)["id"].(string)
	_, list := do(t, c.ts, "GET", "/api/v1/channels/"+fid+"/threads", c.tokA, nil)
	posts := listOf(list, "threads")
	if len(posts) != 1 || posts[0].(map[string]any)["first_message"] == nil {
		t.Fatalf("posts with their first messages: %v", list)
	}
	// filtering by a tag
	_, none := do(t, c.ts, "GET", "/api/v1/channels/"+fid+"/threads?tags="+staff, c.tokA, nil)
	if len(listOf(none, "threads")) != 0 {
		t.Errorf("tag filter: %v", none)
	}
	if res, _ := do(t, c.ts, "PUT", "/api/v1/channels/"+fid+"/pinned-post/"+pid, c.tokB, nil); res.StatusCode != 403 {
		t.Errorf("a member pinned a post: %d", res.StatusCode)
	}
	if res, body := do(t, c.ts, "PUT", "/api/v1/channels/"+fid+"/pinned-post/"+pid, c.tokA, nil); res.StatusCode != 200 || body["forum"].(map[string]any)["pinned_post"] != pid {
		t.Errorf("pin: %d %v", res.StatusCode, body)
	}
	// An app's post comes in two steps, its first message sealed for the
	// post once the post exists: until then it is its author's alone.
	ga := connectGW(t, c.ts, c.tokA)
	code, body = c.thread(t, c.tokB, fid, map[string]any{"name": "Two Steps", "applied_tags": []string{feedback}})
	if code != 201 || body["message"] != nil {
		t.Fatalf("a post without its message yet: %d %v", code, body)
	}
	two := body["thread"].(map[string]any)["id"].(string)
	countPosts := func(token string) int {
		_, page := do(t, c.ts, "GET", "/api/v1/channels/"+fid+"/threads", token, nil)
		return len(listOf(page, "threads"))
	}
	if n := countPosts(c.tokA); n != 1 {
		t.Errorf("someone else saw a post with nothing in it yet: %d posts", n)
	}
	if n := countPosts(c.tokB); n != 2 {
		t.Errorf("its author does not see the post waiting for its message: %d posts", n)
	}
	if res, body := sendMsg(t, c.ts, c.tokA, two, c.server, 0, "first!", "", nil); res.StatusCode != 403 || errCode(body) != "post_pending" {
		t.Errorf("someone wrote in a post before its author: %d %v", res.StatusCode, body)
	}
	if res, body := sendMsg(t, c.ts, c.tokB, two, c.server, 0, "the idea", "", nil); res.StatusCode != 201 {
		t.Fatalf("the post's first message: %d %v", res.StatusCode, body)
	}
	if ev := ga.next("thread_create", 2*time.Second); ev == nil || ev["id"] != two {
		t.Errorf("the post was not announced with its first message: %v", ev)
	}
	if n := countPosts(c.tokA); n != 2 {
		t.Errorf("the post is not listed once it has its message: %d posts", n)
	}
}

// An announcement channel followed into another server's channel: what it
// publishes arrives there sealed for that channel, follows edits, and says
// so once the original is gone.
func TestAnnouncementFollowing(t *testing.T) {
	c := newCommunity(t)
	res, news := do(t, c.ts, "POST", "/api/v1/servers/"+c.server+"/channels", c.tokA, map[string]any{"type": "announcement", "name": "news"})
	if res.StatusCode != 201 {
		t.Fatalf("announcement channel: %d %v", res.StatusCode, news)
	}
	newsID := news["id"].(string)
	// bob's own server, which follows alice's news
	theirs := createServer(t, c.ts, c.tokB, "Bob's")
	bobServer := theirs["id"].(string)
	target := channelNamed(theirs, "general")["id"].(string)
	createEpoch(t, c.ts, c.tokB, bobServer)
	pub := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	res, follow := do(t, c.ts, "POST", "/api/v1/channels/"+newsID+"/followers", c.tokB, map[string]any{"target_channel_id": target, "public_key": pub, "secret": envelopeFor(bobServer, 0, "sealed key")})
	if res.StatusCode != 201 {
		t.Fatalf("follow: %d %v", res.StatusCode, follow)
	}
	if res, _ := do(t, c.ts, "POST", "/api/v1/channels/"+newsID+"/followers", c.tokB, map[string]any{"target_channel_id": target, "public_key": pub, "secret": envelopeFor(bobServer, 0, "sealed key")}); res.StatusCode != 409 {
		t.Errorf("followed twice: %d", res.StatusCode)
	}
	// a post with a file, which the following channel's people can fetch once it is published
	file := bytes.Repeat([]byte{0x42}, 600)
	upReq, _ := http.NewRequest("POST", c.ts.URL+"/api/v1/channels/"+newsID+"/attachments", bytes.NewReader(file))
	upReq.Header.Set("Authorization", "Bearer "+c.tokA)
	upReq.Header.Set("Content-Type", "application/octet-stream")
	upRes, err := http.DefaultClient.Do(upReq)
	if err != nil {
		t.Fatal(err)
	}
	var up map[string]any
	json.NewDecoder(upRes.Body).Decode(&up)
	upRes.Body.Close()
	blob, _ := up["blob_id"].(string)
	fetch := func(tok string) int {
		r, _ := http.NewRequest("GET", c.ts.URL+"/media/attachments/"+blob, nil)
		r.Header.Set("Authorization", "Bearer "+tok)
		rs, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, rs.Body)
		rs.Body.Close()
		return rs.StatusCode
	}
	tokD, _ := register(t, c.ts, "dave")
	join(t, c.ts, tokD, inviteFor(t, c.ts, c.tokB, target))
	_, post := sendMsg(t, c.ts, c.tokA, newsID, c.server, 0, "big news", "", map[string]any{"attachments": []map[string]any{{"blob_id": blob, "size": 600}}})
	mid := post["id"].(string)
	if code := fetch(tokD); code != 404 {
		t.Errorf("someone outside fetched an unpublished post's file: %d", code)
	}
	_, keys := do(t, c.ts, "GET", "/api/v1/channels/"+newsID+"/followers", c.tokA, nil)
	followers := listOf(keys, "followers")
	if len(followers) != 1 || followers[0].(map[string]any)["target_id"] != nil {
		t.Fatalf("a publisher sees keys and nothing of where they go: %v", keys)
	}
	sealed := map[string]any{"v": 2, "key": b64([]byte("sealed-k")), "iv": b64(bytes.Repeat([]byte{2}, 12)), "ct": b64([]byte("copy"))}
	copies := map[string]any{"copies": []map[string]any{{"follow_id": follow["id"], "ciphertext": sealed}}}
	if res, body := do(t, c.ts, "POST", "/api/v1/channels/"+newsID+"/messages/"+mid+"/crosspost", c.tokB, copies); res.StatusCode != 403 {
		t.Errorf("someone else published alice's post: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, c.ts, "POST", "/api/v1/channels/"+newsID+"/messages/"+mid+"/crosspost", c.tokA, copies); res.StatusCode != 200 || body["copies"].(float64) != 1 {
		t.Fatalf("publish: %d %v", res.StatusCode, body)
	}
	findCopy := func() map[string]any {
		_, page := do(t, c.ts, "GET", "/api/v1/channels/"+target+"/messages", c.tokB, nil)
		for _, m := range listOf(page, "_list") {
			if m.(map[string]any)["type"] == "crosspost" {
				return m.(map[string]any)
			}
		}
		return nil
	}
	cp := findCopy()
	if cp == nil || cp["crosspost"].(map[string]any)["server_name"] != "Town" || cp["ciphertext"] == nil {
		t.Fatalf("copy in the following channel: %v", cp)
	}
	if code := fetch(tokD); code != 200 {
		t.Errorf("the following channel's people cannot fetch the post's file: %d", code)
	}
	_, page0 := do(t, c.ts, "GET", "/api/v1/channels/"+newsID+"/messages", c.tokA, nil)
	if first := listOf(page0, "_list"); len(first) == 0 || first[0].(map[string]any)["published"] != true {
		t.Errorf("the post does not say it was published: %v", first)
	}
	// published again after an edit: the same copy, brought up to date
	if res, _ := do(t, c.ts, "POST", "/api/v1/channels/"+newsID+"/messages/"+mid+"/crosspost", c.tokA, copies); res.StatusCode != 200 {
		t.Fatal("publish again")
	}
	_, page := do(t, c.ts, "GET", "/api/v1/channels/"+target+"/messages", c.tokB, nil)
	n := 0
	for _, m := range listOf(page, "_list") {
		if m.(map[string]any)["type"] == "crosspost" {
			n++
		}
	}
	if n != 1 {
		t.Errorf("publishing again made %d copies", n)
	}
	// a channel others follow stays an announcement channel
	if res, _ := do(t, c.ts, "PATCH", "/api/v1/channels/"+newsID, c.tokA, map[string]any{"type": "text"}); res.StatusCode != 400 {
		t.Errorf("turned a followed channel back into a text channel: %d", res.StatusCode)
	}
	if res, _ := do(t, c.ts, "DELETE", "/api/v1/channels/"+newsID+"/messages/"+mid, c.tokA, nil); res.StatusCode != 204 {
		t.Fatal("delete")
	}
	gone := findCopy()
	if gone["crosspost"].(map[string]any)["deleted"] != true || gone["ciphertext"] != nil {
		t.Errorf("the copy does not say its original is gone: %v", gone)
	}
	if code := fetch(tokD); code != 404 {
		t.Errorf("a deleted post's file can still be fetched through its copy: %d", code)
	}
	_ = strings.TrimSpace
}
