package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/ratelimit"
)

func envelopeFor(conv string, epoch int, text string) map[string]any {
	return map[string]any{"v": 1, "conv": conv, "epoch": epoch, "iv": b64(bytes.Repeat([]byte{1}, 12)), "ct": b64([]byte("ct:" + text))}
}

func sendMsg(t *testing.T, ts *httptest.Server, token, ch, conv string, epoch int, text, nonce string, extra map[string]any) (*http.Response, map[string]any) {
	t.Helper()
	body := map[string]any{"ciphertext": envelopeFor(conv, epoch, text), "key_conv_id": conv, "key_epoch": epoch, "mentions": []string{}, "nonce": nonce}
	for k, v := range extra {
		body[k] = v
	}
	return do(t, ts, "POST", "/api/v1/channels/"+ch+"/messages", token, body)
}

// testCommitment stands in for a key's fingerprint where nothing checks it.
var testCommitment = b64(bytes.Repeat([]byte{7}, keyCommitmentBytes))

// askForRotation sets a conversation's key to be replaced, as someone leaving
// does, so that createEpoch can make its next epoch.
func askForRotation(t *testing.T, srv *Server, conv string) {
	t.Helper()
	id, err := ids.Parse(conv)
	if err != nil {
		t.Fatal(err)
	}
	tr := true
	if err := srv.Store.SetKeyFlags(t.Context(), id, &tr, nil); err != nil {
		t.Fatal(err)
	}
}

func createEpoch(t *testing.T, ts *httptest.Server, token, conv string) int {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/keys/epochs", token, map[string]any{"conv_id": conv, "commitment": testCommitment})
	if res.StatusCode != 201 {
		t.Fatalf("create epoch: %d %v", res.StatusCode, body)
	}
	epoch := int(body["epoch"].(float64))
	var grants []map[string]any
	for _, uid := range body["user_ids"].([]any) {
		grants = append(grants, map[string]any{"conv_id": conv, "epoch": epoch, "user_id": uid, "wrapped_key": b64([]byte("wrapped-for-" + uid.(string)))})
	}
	// the creator must grant itself first to be a holder; PutGrants requires holding, so grant self in a first call
	self := grants[:0:0]
	for _, g := range grants {
		self = append(self, g)
	}
	// find my id
	_, me := do(t, ts, "GET", "/api/v1/users/@me", token, nil)
	var mine, others []map[string]any
	for _, g := range grants {
		if g["user_id"] == me["id"] {
			mine = append(mine, g)
		} else {
			others = append(others, g)
		}
	}
	return epoch + 0*len(self) + func() int {
		srv := ts
		_ = srv
		return 0
	}() + grantAll(t, ts, token, mine, others)
}

// grantAll stores the creator's own grant (bootstrapping the holder check via the
// store directly is not possible from the API, so the epoch creator self-grants
// through the same endpoint: the server accepts self-grants for a fresh epoch).
func grantAll(t *testing.T, ts *httptest.Server, token string, mine, others []map[string]any) int {
	t.Helper()
	if len(mine) > 0 {
		res, body := do(t, ts, "POST", "/api/v1/keys/grants", token, mine)
		if res.StatusCode != 200 {
			t.Fatalf("self grant: %d %v", res.StatusCode, body)
		}
	}
	if len(others) > 0 {
		res, body := do(t, ts, "POST", "/api/v1/keys/grants", token, others)
		if res.StatusCode != 200 {
			t.Fatalf("grant others: %d %v", res.StatusCode, body)
		}
	}
	return 0
}

func friend(t *testing.T, ts *httptest.Server, tokA, nameB, tokB, idA string) {
	t.Helper()
	if res, body := do(t, ts, "POST", "/api/v1/users/@me/relationships", tokA, map[string]any{"username": nameB}); res.StatusCode != 200 {
		t.Fatalf("request: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/users/@me/relationships/"+idA, tokB, nil); res.StatusCode != 200 {
		t.Fatalf("accept: %d %v", res.StatusCode, body)
	}
}

func TestDMCreationRules(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	if res.StatusCode != 201 || body["type"] != "dm" || len(body["recipient_ids"].([]any)) != 2 {
		t.Fatalf("create dm: %d %v", res.StatusCode, body)
	}
	dm := body["id"].(string)
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokB, map[string]any{"recipient_ids": []string{idA}}); res.StatusCode != 200 || body["id"] != dm {
		t.Errorf("existing dm: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idA}}); res.StatusCode != 400 {
		t.Errorf("self dm: %d %v", res.StatusCode, body)
	}
	do(t, ts, "PATCH", "/api/v1/users/@me", tokB, map[string]any{"dm_privacy": "friends"})
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokC, map[string]any{"recipient_ids": []string{idB}}); res.StatusCode != 403 || errCode(body) != "dm_friends_only" {
		t.Errorf("friends only: %d %v", res.StatusCode, body)
	}
	do(t, ts, "PATCH", "/api/v1/users/@me", tokB, map[string]any{"dm_privacy": "nobody"})
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokC, map[string]any{"recipient_ids": []string{idB}}); res.StatusCode != 403 || errCode(body) != "dm_closed" {
		t.Errorf("closed: %d %v", res.StatusCode, body)
	}
	do(t, ts, "PUT", "/api/v1/users/@me/blocks/"+idA, tokC, nil)
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idC}}); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("blocked: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/users/@me/channels", tokA, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("list: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+dm, tokC, nil); res.StatusCode != 404 {
		t.Error("non-participant must get 404")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+dm, tokA, nil); res.StatusCode != 200 {
		t.Error("participant get")
	}
}

func TestMessagesEndToEndWithGateway(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{mustID(t, ts, tokB)}})
	dm := body["id"].(string)
	gb := connectGW(t, ts, tokB)

	// no epoch yet → unknown_epoch
	if res, body := sendMsg(t, ts, tokA, dm, dm, 0, "hi", "n0", nil); res.StatusCode != 400 || errCode(body) != "unknown_epoch" {
		t.Fatalf("no epoch: %d %v", res.StatusCode, body)
	}
	epoch := createEpoch(t, ts, tokA, dm)
	if epoch != 0 {
		t.Fatalf("first epoch should be 0, got %d", epoch)
	}
	if g := gb.next("key_grant", 3*time.Second); g == nil || g["conv_id"] != dm {
		t.Errorf("bob should receive his key grant: %v", g)
	}
	res, body := sendMsg(t, ts, tokA, dm, dm, 0, "hello", "n1", nil)
	if res.StatusCode != 201 || body["author_id"] != idA || body["channel_id"] != dm {
		t.Fatalf("send: %d %v", res.StatusCode, body)
	}
	mid := body["id"].(string)
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["id"] != mid {
		t.Errorf("bob did not get message_create: %v", ev)
	}
	if res, body = sendMsg(t, ts, tokA, dm, dm, 0, "hello", "n1", nil); res.StatusCode != 200 || body["id"] != mid {
		t.Errorf("nonce dedupe: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+dm+"/messages", tokA, map[string]any{"ciphertext": "junk", "key_conv_id": dm, "key_epoch": 0, "nonce": "n2"}); res.StatusCode != 400 || errCode(body) != "bad_envelope" {
		t.Errorf("bad envelope: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokA, dm, "12345", 0, "x", "n3", nil); res.StatusCode != 400 || errCode(body) != "wrong_conversation" {
		t.Errorf("wrong conv: %d %v", res.StatusCode, body)
	}
	if res, body = sendMsg(t, ts, tokA, dm, dm, 7, "x", "n4", nil); res.StatusCode != 400 || errCode(body) != "unknown_epoch" {
		t.Errorf("future epoch: %d %v", res.StatusCode, body)
	}
	// reply
	res, body = sendMsg(t, ts, tokB, dm, dm, 0, "re", "n5", map[string]any{"reply_to_id": mid, "mentions": []string{idA}})
	if res.StatusCode != 201 || body["type"] != "reply" || body["reply_to_id"] != mid {
		t.Fatalf("reply: %d %v", res.StatusCode, body)
	}
	replyID := body["id"].(string)
	if res, body = sendMsg(t, ts, tokB, dm, dm, 0, "re", "n6", map[string]any{"reply_to_id": "999"}); res.StatusCode != 400 || errCode(body) != "bad_reply" {
		t.Errorf("bad reply: %d %v", res.StatusCode, body)
	}
	// list ascending
	res, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages?limit=50", tokA, nil)
	list := body["_list"].([]any)
	if res.StatusCode != 200 || len(list) != 2 || list[0].(map[string]any)["id"] != mid {
		t.Errorf("list: %d %d", res.StatusCode, len(list))
	}
	// alice has an unread mention from bob's reply
	res, body = do(t, ts, "GET", "/api/v1/users/@me/channels", tokA, nil)
	_ = res
	// edit
	if res, _ = do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokB, map[string]any{"ciphertext": envelopeFor(dm, 0, "hax"), "key_conv_id": dm, "key_epoch": 0}); res.StatusCode != 403 {
		t.Errorf("edit by non-author: %d", res.StatusCode)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"ciphertext": envelopeFor(dm, 0, "edited"), "key_conv_id": dm, "key_epoch": 0, "mentions": []string{}})
	if res.StatusCode != 200 || body["edited_at"] == nil {
		t.Fatalf("edit: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("message_update", 3*time.Second); ev == nil || ev["id"] != mid {
		t.Error("message_update not delivered")
	}
	// flags-only edit (suppress embeds)
	res, body = do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"flags": 4})
	if res.StatusCode != 200 || body["flags"].(float64) != 4 {
		t.Errorf("suppress embeds: %d %v", res.StatusCode, body)
	}
	// reactions
	if res, _ = do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/%F0%9F%91%8D/@me", tokA, nil); res.StatusCode != 204 {
		t.Errorf("react: %d", res.StatusCode)
	}
	if ev := gb.next("reaction_add", 3*time.Second); ev == nil || ev["emoji"] != "👍" || ev["user_id"] != idA {
		t.Errorf("reaction_add: %v", ev)
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/%F0%9F%91%8D", tokB, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("reactors: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, nil)
	if rs := body["reactions"].([]any); len(rs) != 1 || rs[0].(map[string]any)["me"] != true {
		t.Errorf("reaction summary: %v", body["reactions"])
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/%F0%9F%91%8D/@me", tokA, nil); res.StatusCode != 204 {
		t.Error("unreact")
	}
	if ev := gb.next("reaction_remove", 3*time.Second); ev == nil {
		t.Error("reaction_remove not delivered")
	}
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/bad%20emoji/@me", tokA, nil); res.StatusCode != 400 || errCode(body) != "bad_emoji" {
		t.Errorf("bad emoji: %d %v", res.StatusCode, body)
	}
	// pins
	if res, _ = do(t, ts, "PUT", "/api/v1/channels/"+dm+"/pins/"+mid, tokB, nil); res.StatusCode != 204 {
		t.Errorf("pin: %d", res.StatusCode)
	}
	if ev := gb.next("pin_update", 3*time.Second); ev == nil || ev["pinned"] != true {
		t.Error("pin_update")
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_pin" || ev["reply_to_id"] != mid {
		t.Errorf("system pin message: %v", ev)
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/pins", tokA, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("pins: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/pins/"+mid, tokA, nil); res.StatusCode != 204 {
		t.Error("unpin")
	}
	// ack
	res, body = do(t, ts, "POST", "/api/v1/channels/"+dm+"/messages/"+replyID+"/ack", tokA, nil)
	if res.StatusCode != 200 || body["last_read_message_id"] != replyID || body["mention_count"].(float64) != 0 || body["manual"] != nil {
		t.Errorf("ack: %d %v", res.StatusCode, body)
	}
	// an ordinary ack never moves the marker back; Mark Unread does, and says so
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+dm+"/messages/"+mid+"/ack", tokA, nil); res.StatusCode != 200 || body["last_read_message_id"] != replyID {
		t.Errorf("older ack: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+dm+"/messages/"+mid+"/ack", tokA, map[string]any{"manual": true}); res.StatusCode != 200 || body["last_read_message_id"] != mid || body["manual"] != true {
		t.Errorf("mark unread: %d %v", res.StatusCode, body)
	}
	// typing via REST reaches bob
	if res, _ = do(t, ts, "POST", "/api/v1/channels/"+dm+"/typing", tokA, nil); res.StatusCode != 204 {
		t.Error("typing")
	}
	if ev := gb.next("typing", 3*time.Second); ev == nil || ev["user_id"] != idA {
		t.Error("typing event")
	}
	// delete
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+mid, tokB, nil); res.StatusCode != 403 {
		t.Error("delete by non-author in dm")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, nil); res.StatusCode != 204 {
		t.Error("delete by author")
	}
	// it says where the channel's newest message now is, never the one that went
	if ev := gb.next("message_delete", 3*time.Second); ev == nil || ev["id"] != mid || ev["last_message_id"] == nil || ev["last_message_id"] == mid {
		t.Errorf("message_delete event: %v", ev)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, nil); res.StatusCode != 404 {
		t.Error("deleted message 404")
	}
}

func mustID(t *testing.T, ts *httptest.Server, token string) string {
	t.Helper()
	_, me := do(t, ts, "GET", "/api/v1/users/@me", token, nil)
	return me["id"].(string)
}

func TestMessageRateLimit(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.limMsg = ratelimit.New(ratelimit.Every(5, 5*time.Second), 5, time.Minute)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{mustID(t, ts, tokB)}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	var last *http.Response
	for i := 0; i < 6; i++ {
		last, _ = sendMsg(t, ts, tokA, dm, dm, 0, "spam", "", nil)
	}
	if last.StatusCode != 429 || last.Header.Get("Retry-After") == "" {
		t.Errorf("6th message in 5s should be 429 with Retry-After, got %d", last.StatusCode)
	}
}

func TestAttachments(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	tokC, _ := register(t, ts, "carol")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{mustID(t, ts, tokB)}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	cipher := bytes.Repeat([]byte{0x42}, 5000)
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/channels/"+dm+"/attachments", bytes.NewReader(cipher))
	req.Header.Set("Authorization", "Bearer "+tokA)
	req.Header.Set("Content-Type", "application/octet-stream")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var up map[string]any
	json.NewDecoder(res.Body).Decode(&up)
	res.Body.Close()
	if res.StatusCode != 201 || up["blob_id"] == nil || up["size"].(float64) != 5000 {
		t.Fatalf("upload: %d %v", res.StatusCode, up)
	}
	blob := up["blob_id"].(string)
	get := func(tok string) (int, []byte) {
		r, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+blob, nil)
		r.Header.Set("Authorization", "Bearer "+tok)
		rs, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer rs.Body.Close()
		b, _ := io.ReadAll(rs.Body)
		return rs.StatusCode, b
	}
	if code, _ := get(tokB); code != 404 {
		t.Error("unattached blob must be private to the uploader")
	}
	if code, b := get(tokA); code != 200 || !bytes.Equal(b, cipher) {
		t.Error("uploader can fetch unattached blob")
	}
	res2, body := sendMsg(t, ts, tokA, dm, dm, 0, "file", "f1", map[string]any{"attachments": []map[string]any{{"blob_id": blob, "size": 4900}}})
	if res2.StatusCode != 201 || len(body["attachments"].([]any)) != 1 || body["attachments"].([]any)[0].(map[string]any)["blob_id"] != blob {
		t.Fatalf("message with attachment: %d %v", res2.StatusCode, body)
	}
	if code, b := get(tokB); code != 200 || !bytes.Equal(b, cipher) {
		t.Error("participant can download after attach")
	}
	if code, _ := get(tokC); code != 404 {
		t.Error("stranger must not download")
	}
	if res2, body = sendMsg(t, ts, tokA, dm, dm, 0, "again", "f2", map[string]any{"attachments": []map[string]any{{"blob_id": blob, "size": 1}}}); res2.StatusCode != 400 || errCode(body) != "bad_attachment" {
		t.Errorf("reuse: %d %v", res2.StatusCode, body)
	}
	if res2, body = sendMsg(t, ts, tokB, dm, dm, 0, "steal", "f3", map[string]any{"attachments": []map[string]any{{"blob_id": "424242", "size": 1}}}); res2.StatusCode != 400 {
		t.Errorf("unknown blob: %d %v", res2.StatusCode, body)
	}
	// range request
	r, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+blob, nil)
	r.Header.Set("Authorization", "Bearer "+tokA)
	r.Header.Set("Range", "bytes=0-9")
	rs, _ := http.DefaultClient.Do(r)
	b, _ := io.ReadAll(rs.Body)
	rs.Body.Close()
	if rs.StatusCode != 206 || len(b) != 10 {
		t.Errorf("range: %d %d", rs.StatusCode, len(b))
	}
	_ = base64.StdEncoding
}

func TestGroupDMLifecycle(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokD, idD := register(t, ts, "dave")
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC, idD}})
	if res.StatusCode != 403 || errCode(body) != "not_friends" {
		t.Fatalf("group with non-friend: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}, "name": "gang"})
	if res.StatusCode != 201 || body["type"] != "group_dm" || len(body["recipient_ids"].([]any)) != 3 || body["owner_id"] != idA || body["name"] != "gang" {
		t.Fatalf("group: %d %v", res.StatusCode, body)
	}
	g := body["id"].(string)
	gb := connectGW(t, ts, tokB)
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+g+"/recipients/"+idD, tokC, nil); res.StatusCode != 403 || errCode(body) != "not_friends" {
		t.Errorf("carol adds non-friend: %d %v", res.StatusCode, body)
	}
	friend(t, ts, tokB, "dave", tokD, idB)
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+g+"/recipients/"+idD, tokB, nil); res.StatusCode != 200 || len(body["recipient_ids"].([]any)) != 4 {
		t.Fatalf("bob adds dave: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_member_add" || ev["mentions"].([]any)[0] != idD {
		t.Errorf("system_member_add: %v", ev)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+g, tokD, nil); res.StatusCode != 200 {
		t.Error("dave can see the group")
	}
	if res, body = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idA, tokB, nil); res.StatusCode != 403 || errCode(body) != "not_owner" {
		t.Errorf("bob removes owner: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idC, tokC, nil); res.StatusCode != 204 {
		t.Error("carol leaves")
	}
	if ev := gb.next("key_rotation_needed", 3*time.Second); ev == nil || ev["conv_id"] != g {
		t.Errorf("key_rotation_needed after leave: %v", ev)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+g, tokC, nil); res.StatusCode != 404 {
		t.Error("carol no longer sees the group")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idD, tokA, nil); res.StatusCode != 204 {
		t.Error("owner removes dave")
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/channels/"+g, tokB, map[string]any{"owner_id": idB}); res.StatusCode != 403 {
		t.Errorf("non-owner transfer: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/channels/"+g, tokA, map[string]any{"name": "renamed", "owner_id": idB}); res.StatusCode != 200 || body["name"] != "renamed" || body["owner_id"] != idB {
		t.Errorf("patch: %d %v", res.StatusCode, body)
	}
	var renamed map[string]any
	for ev := gb.next("channel_update", 3*time.Second); ev != nil; ev = gb.next("channel_update", 3*time.Second) {
		if ev["name"] == "renamed" {
			renamed = ev
			break
		}
	}
	if renamed == nil {
		t.Error("channel_update with the new name never arrived")
	}
	// icon
	req, _ := http.NewRequest("PUT", ts.URL+"/api/v1/channels/"+g+"/icon", bytes.NewReader(pngBytes(16, 16)))
	req.Header.Set("Authorization", "Bearer "+tokB)
	req.Header.Set("Content-Type", "image/png")
	rs, _ := http.DefaultClient.Do(req)
	var iconBody map[string]any
	json.NewDecoder(rs.Body).Decode(&iconBody)
	rs.Body.Close()
	if rs.StatusCode != 200 || iconBody["icon_id"] == nil {
		t.Errorf("icon: %d %v", rs.StatusCode, iconBody)
	}
	// Repeating the name and deleting an absent icon must not add activity.
	do(t, ts, "PATCH", "/api/v1/channels/"+g, tokB, map[string]any{"name": "renamed"})
	do(t, ts, "DELETE", "/api/v1/channels/"+g+"/icon", tokB, nil)
	do(t, ts, "DELETE", "/api/v1/channels/"+g+"/icon", tokB, nil)
	_, history := do(t, ts, "GET", "/api/v1/channels/"+g+"/messages", tokB, nil)
	counts := map[string]int{}
	for _, raw := range history["_list"].([]any) {
		m := raw.(map[string]any)
		counts[m["type"].(string)]++
	}
	if counts["system_name_change"] != 1 || counts["system_icon_change"] != 2 {
		t.Errorf("name and icon activity: %v", counts)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idA, tokA, nil); res.StatusCode != 204 {
		t.Error("alice leaves")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+g+"/recipients/"+idB, tokB, nil); res.StatusCode != 204 {
		t.Error("last member leaves")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+g, tokB, nil); res.StatusCode != 404 {
		t.Error("empty group is deleted")
	}
}

// Creating a group with nobody picked gives a group of one: its owner, who
// adds people later through the usual add, which hands them the key.
func TestEmptyGroup(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	friend(t, ts, tokA, "bob", tokB, idA)
	ga := connectGW(t, ts, tokA)

	res, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{}, "name": "Plans"})
	if res.StatusCode != 201 || body["type"] != "group_dm" || body["owner_id"] != idA || body["name"] != "Plans" {
		t.Fatalf("empty group: %d %v", res.StatusCode, body)
	}
	if r := body["recipient_ids"].([]any); len(r) != 1 || r[0] != idA {
		t.Fatalf("an empty group holds only its owner: %v", r)
	}
	g := body["id"].(string)
	if ev := ga.next("channel_create", 3*time.Second); ev == nil || ev["id"] != g {
		t.Errorf("channel_create to the owner: %v", ev)
	}
	// no list at all means the same, and each press makes a new group
	res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{})
	if res.StatusCode != 201 || body["type"] != "group_dm" || body["id"] == g || body["name"] != "" {
		t.Fatalf("second empty group: %d %v", res.StatusCode, body)
	}
	// naming only yourself is still a mistake, not a group
	if res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idA}}); res.StatusCode != 400 || errCode(body) != "no_recipients" {
		t.Errorf("only yourself: %d %v", res.StatusCode, body)
	}

	// the owner's key for the group is made for the owner alone
	res, body = do(t, ts, "POST", "/api/v1/keys/epochs", tokA, map[string]any{"conv_id": g})
	if res.StatusCode != 201 || len(body["user_ids"].([]any)) != 1 || body["user_ids"].([]any)[0] != idA {
		t.Fatalf("epoch of a group of one: %d %v", res.StatusCode, body)
	}
	epoch := body["epoch"]
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": epoch, "user_id": idA, "wrapped_key": b64([]byte("a"))}}); res.StatusCode != 200 {
		t.Fatalf("self grant: %d %v", res.StatusCode, body)
	}
	if _, body = do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil); len(body["_list"].([]any)) != 0 {
		t.Errorf("nobody else needs the key yet: %v", body)
	}

	// adding bob later lets him in and asks the key holder for a grant
	gb := connectGW(t, ts, tokB)
	if res, body = do(t, ts, "POST", "/api/v1/channels/"+g+"/recipients/"+idB, tokA, nil); res.StatusCode != 200 || len(body["recipient_ids"].([]any)) != 2 {
		t.Fatalf("add bob: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("channel_create", 3*time.Second); ev == nil || ev["id"] != g {
		t.Errorf("bob hears of the group: %v", ev)
	}
	if ev := ga.next("keys_needed", 3*time.Second); ev == nil || ev["conv_id"] != g || len(ev["user_ids"].([]any)) != 1 || ev["user_ids"].([]any)[0] != idB {
		t.Errorf("keys_needed for bob: %v", ev)
	}
	_, body = do(t, ts, "GET", "/api/v1/keys/needs", tokA, nil)
	needs := body["_list"].([]any)
	if len(needs) != 1 || needs[0].(map[string]any)["conv_id"] != g || needs[0].(map[string]any)["user_ids"].([]any)[0] != idB {
		t.Fatalf("needs after adding bob: %v", body)
	}
	if res, body = do(t, ts, "POST", "/api/v1/keys/grants", tokA, []map[string]any{{"conv_id": g, "epoch": epoch, "user_id": idB, "wrapped_key": b64([]byte("b"))}}); res.StatusCode != 200 {
		t.Fatalf("grant bob: %d %v", res.StatusCode, body)
	}
	if res, _ = do(t, ts, "GET", "/api/v1/channels/"+g, tokB, nil); res.StatusCode != 200 {
		t.Error("bob can open the group")
	}
}
