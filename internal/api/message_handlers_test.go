package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
	"time"

	"github.com/coder/websocket"

	"evilion.xyz/chat/internal/perms"
)

// A block closes a conversation that already exists, not only the way to start one.
func TestBlockClosesAnExistingDM(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	res, body := sendMsg(t, ts, tokA, dm, dm, 0, "hi", "", nil)
	if res.StatusCode != 201 {
		t.Fatalf("send: %d %v", res.StatusCode, body)
	}
	mid := body["id"].(string)
	const thumbs = "/reactions/%F0%9F%91%8D/@me"
	if res, _ := do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+mid+thumbs, tokB, nil); res.StatusCode != 204 {
		t.Fatalf("react before the block: %d", res.StatusCode)
	}
	res, body = do(t, ts, "POST", "/api/v1/channels/"+dm+"/subchats", tokA, map[string]any{"name": "plans"})
	if res.StatusCode != 201 {
		t.Fatalf("subchat: %d %v", res.StatusCode, body)
	}
	sub := body["id"].(string)
	createEpoch(t, ts, tokA, sub)
	ga := connectGW(t, ts, tokA)

	if res, _ := do(t, ts, "PUT", "/api/v1/users/@me/blocks/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("block: %d", res.StatusCode)
	}
	if res, body := sendMsg(t, ts, tokB, sub, sub, 0, "round the side", "", nil); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("blocked user sends in a subchat: %d %v", res.StatusCode, body)
	}
	if res, body := sendMsg(t, ts, tokB, dm, dm, 0, "still here", "", nil); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("blocked user sends: %d %v", res.StatusCode, body)
	}
	if res, body := sendMsg(t, ts, tokA, dm, dm, 0, "and you", "", nil); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("the blocker sends: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"ciphertext": envelopeFor(dm, 0, "edited"), "key_conv_id": dm, "key_epoch": 0}); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("edit: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/channels/"+dm+"/typing", tokB, nil); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("typing: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/%E2%9D%A4/@me", tokB, nil); res.StatusCode != 403 || errCode(body) != "blocked" {
		t.Errorf("react: %d %v", res.StatusCode, body)
	}
	// taking back a reaction made before the block is still fine
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+mid+thumbs, tokB, nil); res.StatusCode != 204 {
		t.Errorf("unreact: %d", res.StatusCode)
	}
	// typing over the gateway does not reach alice either
	gb := connectGW(t, ts, tokB)
	op, _ := json.Marshal(map[string]any{"op": "typing", "d": map[string]any{"channel_id": dm}})
	if err := gb.ws.Write(context.Background(), websocket.MessageText, op); err != nil {
		t.Fatal(err)
	}
	if ev := ga.next("typing", 500*time.Millisecond); ev != nil {
		t.Errorf("alice sees a blocked user typing: %v", ev)
	}
	// history stays readable
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages", tokA, nil); res.StatusCode != 200 {
		t.Errorf("history: %d", res.StatusCode)
	}

	if res, _ := do(t, ts, "DELETE", "/api/v1/users/@me/blocks/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("unblock: %d", res.StatusCode)
	}
	if res, body := sendMsg(t, ts, tokB, dm, dm, 0, "hello again", "", nil); res.StatusCode != 201 {
		t.Errorf("send after unblock: %d %v", res.StatusCode, body)
	}
	_ = idA
}

// An edit uses the current epoch and conversation, including after a channel
// becomes private: the old server key belongs to people now excluded.
func TestEditKeepsTheKeyThatOpensIt(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	_, body = sendMsg(t, ts, tokA, dm, dm, 0, "hi", "", nil)
	mid := body["id"].(string)
	askForRotation(t, srv, dm)
	if e := createEpoch(t, ts, tokA, dm); e != 1 {
		t.Fatalf("second epoch: %d", e)
	}
	res, body := do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"ciphertext": envelopeFor(dm, 1, "edited"), "key_conv_id": dm, "key_epoch": 1})
	if res.StatusCode != 200 || body["key_epoch"].(float64) != 1 {
		t.Errorf("edit under a newer epoch: %d %v", res.StatusCode, body)
	}
	_, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages/"+mid, tokB, nil)
	if body["key_epoch"].(float64) != 1 {
		t.Errorf("stored epoch: %v", body)
	}

	// A private channel must not publish new edits under the old server key.
	view := createServer(t, ts, tokA, "Keys")
	sid := view["id"].(string)
	createEpoch(t, ts, tokA, sid)
	res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "text", "name": "staff"})
	if res.StatusCode != 201 {
		t.Fatal(body)
	}
	staff := body["id"].(string)
	_, body = sendMsg(t, ts, tokA, staff, sid, 0, "before", "", nil)
	old := body["id"].(string)
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+staff+"/permissions/"+sid, tokA, map[string]any{"type": "role", "deny": permStr(perms.ViewChannel)}); res.StatusCode != 200 {
		t.Fatalf("make private: %d %v", res.StatusCode, body)
	}
	createEpoch(t, ts, tokA, staff)
	res, body = do(t, ts, "PATCH", "/api/v1/channels/"+staff+"/messages/"+old, tokA, map[string]any{"ciphertext": envelopeFor(sid, 0, "fixed"), "key_conv_id": sid, "key_epoch": 0})
	if res.StatusCode != 400 || errCode(body) != "wrong_conversation" {
		t.Errorf("edit disclosed under the old shared conversation: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/channels/"+staff+"/messages/"+old, tokA, map[string]any{"ciphertext": envelopeFor(staff, 0, "moved"), "key_conv_id": staff, "key_epoch": 0})
	if res.StatusCode != 200 || body["key_conv_id"] != staff {
		t.Errorf("edit under the channel's own key: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/channels/"+staff+"/messages/"+old, tokA, map[string]any{"ciphertext": envelopeFor(dm, 0, "elsewhere"), "key_conv_id": dm, "key_epoch": 0}); res.StatusCode != 400 || errCode(body) != "wrong_conversation" {
		t.Errorf("edit under an unrelated conversation: %d %v", res.StatusCode, body)
	}
}

func TestNewMessagesAndEditsRefuseAnOldEpoch(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	_, bob := register(t, ts, "bob")
	ch := dmBetween(t, ts, token, bob)
	createEpoch(t, ts, token, ch)
	_, original := sendMsg(t, ts, token, ch, ch, 0, "before rotation", "before", nil)
	id := original["id"].(string)
	askForRotation(t, srv, ch)
	createEpoch(t, ts, token, ch)
	if res, body := sendMsg(t, ts, token, ch, ch, 0, "stale new text", "stale", nil); res.StatusCode != 409 || errCode(body) != "stale_epoch" {
		t.Fatalf("new message with removed members' old key: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/channels/"+ch+"/messages/"+id, token, map[string]any{"ciphertext": envelopeFor(ch, 0, "stale edit"), "key_conv_id": ch, "key_epoch": 0}); res.StatusCode != 409 || errCode(body) != "stale_epoch" {
		t.Fatalf("edit with removed members' old key: %d %v", res.StatusCode, body)
	}
	if res, body := sendMsg(t, ts, token, ch, ch, 1, "current key", "current", nil); res.StatusCode != 201 {
		t.Fatalf("current epoch rejected: %d %v", res.StatusCode, body)
	}
}

// A message's ciphertext has one ceiling, for sending and editing alike, so an
// edit is no way round the length a message can be; past it the answer says
// the message is too long rather than that it is malformed.
func TestEditIsHeldToTheSameCeilingAsSending(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)

	// An envelope whose JSON, as the server receives it, is within four bytes
	// of n: the ciphertext is base64, so it grows four characters at a time.
	sized := func(n int) map[string]any {
		env := map[string]any{"v": 1, "conv": dm, "epoch": 0, "iv": b64(bytes.Repeat([]byte{1}, 12)), "ct": ""}
		raw, _ := json.Marshal(env)
		room := n - len(raw)
		env["ct"] = string(bytes.Repeat([]byte("A"), room-room%4))
		return env
	}
	sizeOf := func(env map[string]any) int {
		raw, _ := json.Marshal(env)
		return len(raw)
	}
	fits := sized(maxEnvelopeBytes)
	over := sized(maxEnvelopeBytes + 8)
	if sizeOf(fits) > maxEnvelopeBytes || sizeOf(over) <= maxEnvelopeBytes {
		t.Fatalf("envelopes of %d and %d bytes do not straddle the ceiling", sizeOf(fits), sizeOf(over))
	}
	send := func(env map[string]any, nonce string) (int, map[string]any) {
		res, body := do(t, ts, "POST", "/api/v1/channels/"+dm+"/messages", tokA, map[string]any{"ciphertext": env, "key_conv_id": dm, "key_epoch": 0, "mentions": []string{}, "nonce": nonce})
		return res.StatusCode, body
	}

	if code, body := send(fits, "a"); code >= 300 {
		t.Fatalf("a message right at the ceiling: %d %v", code, body)
	}
	if code, body := send(over, "b"); code != 413 || errCode(body) != "message_too_long" {
		t.Errorf("a message past the ceiling: %d %v", code, body)
	}

	_, body = sendMsg(t, ts, tokA, dm, dm, 0, "short", "c", nil)
	mid := body["id"].(string)
	edit := func(env map[string]any) (int, map[string]any) {
		res, body := do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"ciphertext": env, "key_conv_id": dm, "key_epoch": 0})
		return res.StatusCode, body
	}
	if code, body := edit(over); code != 413 || errCode(body) != "message_too_long" {
		t.Errorf("an edit past the ceiling: %d %v", code, body)
	}
	_, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages/"+mid, tokB, nil)
	if env, _ := body["ciphertext"].(map[string]any); env == nil || env["ct"] != b64([]byte("ct:short")) {
		t.Errorf("the refused edit changed the message: %v", body["ciphertext"])
	}
	if code, body := edit(fits); code != 200 {
		t.Errorf("an edit right at the ceiling: %d %v", code, body)
	}
}

// An edit is broadcast without the editor's own reaction flags.
func TestEditBroadcastDoesNotCarryTheEditorsReactions(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	_, body = sendMsg(t, ts, tokA, dm, dm, 0, "hi", "", nil)
	mid := body["id"].(string)
	do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+mid+"/reactions/%F0%9F%91%8D/@me", tokA, nil)
	gb := connectGW(t, ts, tokB)
	res, body := do(t, ts, "PATCH", "/api/v1/channels/"+dm+"/messages/"+mid, tokA, map[string]any{"ciphertext": envelopeFor(dm, 0, "edited"), "key_conv_id": dm, "key_epoch": 0})
	if res.StatusCode != 200 || body["reactions"].([]any)[0].(map[string]any)["me"] != true {
		t.Fatalf("the editor's own copy: %d %v", res.StatusCode, body)
	}
	ev := gb.next("message_update", 3*time.Second)
	if ev == nil {
		t.Fatal("no message_update")
	}
	if r := ev["reactions"].([]any); len(r) != 1 || r[0].(map[string]any)["me"] != false {
		t.Errorf("bob is told the reaction is his: %v", ev["reactions"])
	}
}

// Only adding a reaction needs Add Reactions.
func TestReactionRoutesNeedOnlyWhatTheyDo(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Announcements")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	epoch := createEpoch(t, ts, tokA, sid)
	_, body := sendMsg(t, ts, tokA, general, sid, epoch, "news", "", nil)
	mid := body["id"].(string)
	const thumbs = "/reactions/%F0%9F%91%8D"
	base := "/api/v1/channels/" + general + "/messages/" + mid
	if res, _ := do(t, ts, "PUT", base+thumbs+"/@me", tokB, nil); res.StatusCode != 204 {
		t.Fatalf("react: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+general+"/permissions/"+sid, tokA, map[string]any{"type": "role", "deny": permStr(perms.AddReactions)}); res.StatusCode != 200 {
		t.Fatalf("deny reactions: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "PUT", base+"/reactions/%E2%9D%A4/@me", tokB, nil); res.StatusCode != 403 {
		t.Errorf("adding still needs Add Reactions: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "GET", base+thumbs, tokB, nil); res.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("who reacted: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "DELETE", base+thumbs+"/@me", tokB, nil); res.StatusCode != 204 {
		t.Errorf("taking a reaction back: %d", res.StatusCode)
	}
}

// The same file listed twice in one message is attached once.
func TestDuplicateAttachmentIsAttachedOnce(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	res, raw := rawPost(t, ts.URL+"/api/v1/channels/"+dm+"/attachments", tokA, "application/octet-stream", bytes.Repeat([]byte{7}, 100))
	if res.StatusCode != 201 {
		t.Fatalf("upload: %d %s", res.StatusCode, raw)
	}
	var up map[string]any
	_ = json.Unmarshal(raw, &up)
	blob := up["blob_id"].(string)
	att := []map[string]any{{"blob_id": blob, "size": 100}, {"blob_id": blob, "size": 100}}
	res, body = sendMsg(t, ts, tokA, dm, dm, 0, "", "", map[string]any{"attachments": att})
	if res.StatusCode != 201 || len(body["attachments"].([]any)) != 1 {
		t.Fatalf("send: %d %v", res.StatusCode, body)
	}
	id, _ := parseID(blob)
	if b, err := srv.Store.BlobByID(context.Background(), id); err != nil || b.RefCount != 1 {
		t.Errorf("ref count: %v %+v", err, b)
	}
	do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+body["id"].(string), tokA, nil)
	if b, err := srv.Store.BlobByID(context.Background(), id); err != nil || b.RefCount != 0 {
		t.Errorf("ref count after delete: %v %+v", err, b)
	}
	_ = tokB
}

// A picture's small copy goes with the message as an attachment of its own,
// so the same people can fetch it, but it is not one of the message's ten
// files: one copy per file at most, none alone, and small enough to be one.
func TestPicturePreviewsRideAlongButAreNotFiles(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	upload := func(n int) string {
		t.Helper()
		res, raw := rawPost(t, ts.URL+"/api/v1/channels/"+dm+"/attachments", tokA, "application/octet-stream", bytes.Repeat([]byte{7}, n))
		if res.StatusCode != 201 {
			t.Fatalf("upload: %d %s", res.StatusCode, raw)
		}
		var up map[string]any
		_ = json.Unmarshal(raw, &up)
		return up["blob_id"].(string)
	}
	file := func(id string) map[string]any { return map[string]any{"blob_id": id, "size": 100} }
	preview := func(id string) map[string]any { return map[string]any{"blob_id": id, "size": 100, "preview": true} }

	// ten files, each with its copy, is a message of ten files
	var att []map[string]any
	for i := 0; i < 10; i++ {
		att = append(att, file(upload(100)), preview(upload(100)))
	}
	res, body := sendMsg(t, ts, tokA, dm, dm, 0, "", "p1", map[string]any{"attachments": att})
	if res.StatusCode != 201 || len(body["attachments"].([]any)) != 20 {
		t.Fatalf("ten pictures with their copies: %d %v", res.StatusCode, body)
	}
	// the other side of the conversation can fetch a copy like any file in it
	req, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+att[1]["blob_id"].(string), nil)
	req.Header.Set("Authorization", "Bearer "+tokB)
	if got, err := http.DefaultClient.Do(req); err != nil || got.StatusCode != 200 {
		t.Fatalf("bob fetching the copy: %v %v", err, got)
	} else {
		got.Body.Close()
	}

	// a copy on its own, or more copies than files, is not a preview of anything
	lone := upload(100)
	if res, body := sendMsg(t, ts, tokA, dm, dm, 0, "", "p2", map[string]any{"attachments": []map[string]any{preview(lone)}}); res.StatusCode != 400 || errCode(body) != "bad_attachment" {
		t.Errorf("a copy alone: %d %v", res.StatusCode, body)
	}
	// nor is a big file called a copy to get past the limit
	big := upload(3 << 20)
	if res, body := sendMsg(t, ts, tokA, dm, dm, 0, "", "p3", map[string]any{"attachments": []map[string]any{file(upload(100)), preview(big)}}); res.StatusCode != 400 || errCode(body) != "bad_attachment" {
		t.Errorf("a big file passed off as a copy: %d %v", res.StatusCode, body)
	}
	// and eleven files are still one too many
	att = nil
	for i := 0; i < 11; i++ {
		att = append(att, file(upload(100)))
	}
	if res, body := sendMsg(t, ts, tokA, dm, dm, 0, "", "p4", map[string]any{"attachments": att}); res.StatusCode != 400 || errCode(body) != "too_many_attachments" {
		t.Errorf("eleven files: %d %v", res.StatusCode, body)
	}
}

// Several messages go in one request, and only when every one of them is the
// caller's to delete: one that is not refuses the lot.
func TestBulkDeleteTakesOnlyWhatYouMayDelete(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	tokD, _ := register(t, ts, "dave")
	view := createServer(t, ts, tokA, "Tidy")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	epoch := createEpoch(t, ts, tokA, sid)
	send := func(tok, text string) string {
		t.Helper()
		res, body := sendMsg(t, ts, tok, general, sid, epoch, text, "", nil)
		if res.StatusCode != 201 {
			t.Fatalf("send: %d %v", res.StatusCode, body)
		}
		return body["id"].(string)
	}
	b1, b2, b3 := send(tokB, "one"), send(tokB, "two"), send(tokB, "three")
	a1 := send(tokA, "alice's")
	bulk := "/api/v1/channels/" + general + "/messages/bulk-delete"
	exists := func(mid string) bool {
		res, _ := do(t, ts, "GET", "/api/v1/channels/"+general+"/messages/"+mid, tokA, nil)
		return res.StatusCode == 200
	}

	// someone else's message in the pick refuses the whole request
	if res, body := do(t, ts, "POST", bulk, tokB, map[string]any{"ids": []string{b1, a1}}); res.StatusCode != 403 {
		t.Errorf("member deleting another's message in bulk: %d %v", res.StatusCode, body)
	}
	if !exists(b1) || !exists(a1) {
		t.Fatal("a refused bulk delete removed something")
	}

	// your own go, and everyone in the channel hears about it once
	ga := connectGW(t, ts, tokA)
	res, body := do(t, ts, "POST", bulk, tokB, map[string]any{"ids": []string{b1, b2, b1}})
	if res.StatusCode != 200 || len(body["ids"].([]any)) != 2 {
		t.Fatalf("own messages: %d %v", res.StatusCode, body)
	}
	ev := ga.next("message_delete_bulk", 3*time.Second)
	if ev == nil || ev["channel_id"] != general || len(ev["ids"].([]any)) != 2 {
		t.Errorf("bulk delete event: %v", ev)
	}
	if exists(b1) || exists(b2) {
		t.Error("own messages still there")
	}

	// ids that are already gone, or from another channel, are skipped rather than refused
	res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "text", "name": "other"})
	if res.StatusCode != 201 {
		t.Fatal(body)
	}
	other := body["id"].(string)
	res, body = sendMsg(t, ts, tokB, other, sid, epoch, "elsewhere", "", nil)
	if res.StatusCode != 201 {
		t.Fatal(body)
	}
	elsewhere := body["id"].(string)
	res, body = do(t, ts, "POST", bulk, tokB, map[string]any{"ids": []string{b1, elsewhere, b3}})
	if res.StatusCode != 200 || len(body["ids"].([]any)) != 1 || body["ids"].([]any)[0] != b3 {
		t.Errorf("gone and foreign ids: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+other+"/messages/"+elsewhere, tokA, nil); res.StatusCode != 200 {
		t.Error("a message from another channel was deleted")
	}

	// Manage Messages deletes anyone's
	mod := createRole(t, ts, tokA, sid, "Mod", perms.DefaultEveryone|perms.ManageMessages)
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idC+"/roles/"+mod, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("assign mod: %d %v", res.StatusCode, body)
	}
	b4 := send(tokB, "four")
	if res, body := do(t, ts, "POST", bulk, tokC, map[string]any{"ids": []string{b4, a1}}); res.StatusCode != 200 || len(body["ids"].([]any)) != 2 {
		t.Errorf("moderator: %d %v", res.StatusCode, body)
	}

	// nothing, too many, and a channel you cannot see
	if res, body := do(t, ts, "POST", bulk, tokB, map[string]any{"ids": []string{}}); res.StatusCode != 400 {
		t.Errorf("empty pick: %d %v", res.StatusCode, body)
	}
	many := make([]string, 101)
	for i := range many {
		many[i] = strconv.Itoa(1000 + i)
	}
	if res, body := do(t, ts, "POST", bulk, tokB, map[string]any{"ids": many}); res.StatusCode != 400 {
		t.Errorf("more than 100: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "POST", bulk, tokD, map[string]any{"ids": []string{b3}}); res.StatusCode != 404 {
		t.Errorf("outsider: %d", res.StatusCode)
	}

	// in a group DM the owner may delete anyone's, the others only their own
	friend(t, ts, tokA, "bob", tokB, idA)
	friend(t, ts, tokA, "carol", tokC, idA)
	res, body = do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB, idC}})
	if res.StatusCode != 201 {
		t.Fatalf("group: %d %v", res.StatusCode, body)
	}
	g := body["id"].(string)
	createEpoch(t, ts, tokA, g)
	_, body = sendMsg(t, ts, tokB, g, g, 0, "bob in the group", "", nil)
	gb := body["id"].(string)
	_, body = sendMsg(t, ts, tokC, g, g, 0, "carol in the group", "", nil)
	gc := body["id"].(string)
	gbulk := "/api/v1/channels/" + g + "/messages/bulk-delete"
	if res, _ := do(t, ts, "POST", gbulk, tokC, map[string]any{"ids": []string{gb, gc}}); res.StatusCode != 403 {
		t.Errorf("group member deleting another's: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "POST", gbulk, tokA, map[string]any{"ids": []string{gb, gc}}); res.StatusCode != 200 || len(body["ids"].([]any)) != 2 {
		t.Errorf("group owner: %d %v", res.StatusCode, body)
	}
	_ = idB
}

// A message pings a role only when the role is this server's, and either
// anyone may mention it or the author may mention everyone: the list is what
// unread badges count, so a client listing more than that pings nobody extra.
func TestRoleMentionsAreHeldToWhoMayMakeThem(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "S")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	epoch := createEpoch(t, ts, tokA, sid)
	role := func(name string, mentionable bool) string {
		res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/roles", tokA, map[string]any{"name": name})
		if res.StatusCode != 201 && res.StatusCode != 200 {
			t.Fatalf("create role: %d %v", res.StatusCode, body)
		}
		id := body["id"].(string)
		if mentionable {
			if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/roles/"+id, tokA, map[string]any{"mentionable": true}); res.StatusCode != 200 {
				t.Fatalf("patch role: %d %v", res.StatusCode, body)
			}
		}
		return id
	}
	closed, open := role("Mods", false), role("Everyone Welcome", true)
	pinged := func(tok, nonce string, roles []string) []any {
		res, body := sendMsg(t, ts, tok, general, sid, epoch, "ping", nonce, map[string]any{"mention_roles": roles})
		if res.StatusCode != 201 {
			t.Fatalf("send: %d %v", res.StatusCode, body)
		}
		got, _ := body["mention_roles"].([]any)
		return got
	}
	// bob may ping the open role only; @everyone's own id and strangers are dropped
	if got := pinged(tokB, "b1", []string{closed, open, sid, "12345"}); len(got) != 1 || got[0] != open {
		t.Errorf("bob's role mentions: %v, want just %s", got, open)
	}
	// the owner may mention everyone, so any role of the server
	if got := pinged(tokA, "a1", []string{closed}); len(got) != 1 || got[0] != closed {
		t.Errorf("alice's role mentions: %v, want %s", got, closed)
	}
}

// A reply comes with the message it answers, wherever that is in the
// conversation, and with null once that has been deleted; a reader can also
// ask for particular messages by id, and what is missing from the answer is gone.
func TestRepliesCarryTheirOriginal(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	createEpoch(t, ts, tokA, dm)
	_, body = sendMsg(t, ts, tokA, dm, dm, 0, "the original", "", nil)
	orig := body["id"].(string)
	_, body = sendMsg(t, ts, tokB, dm, dm, 0, "doomed", "", nil)
	doomed := body["id"].(string)
	res, body := sendMsg(t, ts, tokB, dm, dm, 0, "a reply", "", map[string]any{"reply_to_id": orig})
	if res.StatusCode != 201 {
		t.Fatalf("reply: %d %v", res.StatusCode, body)
	}
	ref, _ := body["referenced_message"].(map[string]any)
	if ref == nil || ref["id"] != orig || ref["ciphertext"] == nil {
		t.Fatalf("the reply as sent has no original: %v", body["referenced_message"])
	}
	reply := body["id"].(string)
	_, body = sendMsg(t, ts, tokA, dm, dm, 0, "a reply to what goes", "", map[string]any{"reply_to_id": doomed})
	toDoomed := body["id"].(string)
	if res, _ := do(t, ts, "DELETE", "/api/v1/channels/"+dm+"/messages/"+doomed, tokB, nil); res.StatusCode != 204 {
		t.Fatalf("delete: %d", res.StatusCode)
	}
	// a page that starts after the original still carries it
	_, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages?after="+orig, tokA, nil)
	byID := map[string]map[string]any{}
	for _, v := range body["_list"].([]any) {
		m := v.(map[string]any)
		byID[m["id"].(string)] = m
	}
	if r, _ := byID[reply]["referenced_message"].(map[string]any); r == nil || r["id"] != orig {
		t.Errorf("listed reply without its original: %v", byID[reply])
	}
	if v, ok := byID[toDoomed]["referenced_message"]; !ok || v != nil {
		t.Errorf("a reply to a deleted message should carry null, got %v (present %v)", v, ok)
	}
	if _, ok := byID[orig]; ok {
		t.Error("after= returned the original itself")
	}
	// by id: what exists comes back, what was deleted does not
	_, body = do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages?ids="+orig+","+doomed, tokA, nil)
	list := body["_list"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["id"] != orig {
		t.Errorf("lookup by id: %v", list)
	}
	// someone outside the conversation learns nothing
	tokC, _ := register(t, ts, "carol")
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages?ids="+orig, tokC, nil); res.StatusCode != 404 {
		t.Errorf("an outsider's lookup: %d", res.StatusCode)
	}
}
