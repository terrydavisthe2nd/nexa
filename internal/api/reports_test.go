package api

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"encoding/json"
	"evilion.xyz/chat/internal/ids"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func reportBody(kind, id string) map[string]any {
	body := map[string]any{"request_id": "report-request-" + randomSuffix(), "target_kind": kind, "target_id": id, "part": "whole", "reason": "harassment", "description": "This content targets another person.", "excerpt": "Only this selected evidence."}
	if kind == "message" {
		body["message"] = map[string]any{"content": map[string]any{"t": "The complete reported message"}, "attachments": []any{}, "edited_at": nil}
	}
	return body
}
func sendReport(t *testing.T, ts *httptest.Server, tok string, body map[string]any) map[string]any {
	t.Helper()
	res, v := do(t, ts, "POST", "/api/v1/reports", tok, body)
	if res.StatusCode != 201 {
		t.Fatalf("report: %d %v", res.StatusCode, v)
	}
	return v["report"].(map[string]any)
}
func TestSafetyReportsLifecycleAndPrivacy(t *testing.T) {
	_, ts := newTestServer(t)
	owner, _ := register(t, ts, "owner")
	reporter, rid := register(t, ts, "reporter")
	target, tid := register(t, ts, "target")
	mod, mid := register(t, ts, "moddy")
	setRank(t, ts, owner, mid, "mod")
	_, _ = do(t, ts, "PATCH", "/api/v1/users/@me", target, map[string]any{"about_me": "Original profile detail"})
	req := reportBody("user", tid)
	req["part"] = "about_me"
	v := sendReport(t, ts, reporter, req)
	id := v["id"].(string)
	for _, key := range []string{"reporter_id", "snapshot", "excerpt", "assigned_to"} {
		if _, ok := v[key]; ok {
			t.Errorf("receipt reveals %s", key)
		}
	}
	// A retry returns the original receipt rather than creating another report.
	retry := sendReport(t, ts, reporter, req)
	if retry["id"] != id {
		t.Fatal("retry duplicated report")
	}
	_, _ = do(t, ts, "PATCH", "/api/v1/users/@me", target, map[string]any{"about_me": "Changed later"})
	res, detail := do(t, ts, "GET", "/api/v1/moderation/reports/"+id, mod, nil)
	if res.StatusCode != 200 {
		t.Fatalf("mod read: %d %v", res.StatusCode, detail)
	}
	full := detail["report"].(map[string]any)
	snapshot := full["snapshot"].(map[string]any)
	if snapshot["about_me"] != "Original profile detail" || full["reporter_id"] != rid || full["subject_id"] != tid {
		t.Fatalf("snapshot: %v", full)
	}
	for _, key := range []string{"public_key", "identity_private", "password", "key_sealed"} {
		if _, ok := snapshot[key]; ok {
			t.Errorf("snapshot leaked %s", key)
		}
	}
	for _, tok := range []string{reporter, target} {
		if res, _ := do(t, ts, "GET", "/api/v1/moderation/reports/"+id, tok, nil); res.StatusCode != 403 {
			t.Errorf("nonstaff detail: %d", res.StatusCode)
		}
	}
	_, mine := do(t, ts, "GET", "/api/v1/reports", target, nil)
	if len(mine["reports"].([]any)) != 0 {
		t.Fatal("reported user discovered report")
	}
	res, changed := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, mod, map[string]any{"version": 1, "status": "in_review", "note": "Checking the details", "assigned_to": mid})
	if res.StatusCode != 200 {
		t.Fatalf("review: %d %v", res.StatusCode, changed)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, owner, map[string]any{"version": 1, "status": "dismissed", "note": "Overwriting old state", "assigned_to": "0"}); res.StatusCode != 409 || errCode(body) != "report_changed" {
		t.Errorf("stale review: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, mod, map[string]any{"version": 2, "status": "actioned", "note": "", "assigned_to": mid}); res.StatusCode != 400 {
		t.Error("closed without outcome")
	}
	res, changed = do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, owner, map[string]any{"version": 2, "status": "dismissed", "note": "No rule violation found", "assigned_to": mid})
	if res.StatusCode != 200 {
		t.Fatalf("preserve another assignee: %d %v", res.StatusCode, changed)
	}
	if len(changed["events"].([]any)) != 2 {
		t.Fatalf("lost review history: %v", changed)
	}
	if res, _ := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, owner, map[string]any{"version": 3, "status": "dismissed", "note": "", "assigned_to": "0"}); res.StatusCode != 200 {
		t.Fatal("could not unassign resolved report without replacing outcome")
	}
	_, filtered := do(t, ts, "GET", "/api/v1/moderation/reports?status=dismissed&kind=user&q=targets", mod, nil)
	if len(filtered["reports"].([]any)) != 1 {
		t.Fatalf("filter: %v", filtered)
	}
	_, filtered = do(t, ts, "GET", "/api/v1/moderation/reports?q=%25", mod, nil)
	if len(filtered["reports"].([]any)) != 0 {
		t.Fatal("query wildcards not escaped")
	}
	_, mine = do(t, ts, "GET", "/api/v1/reports", reporter, nil)
	encoded, _ := json.Marshal(mine)
	if strings.Contains(string(encoded), "No rule violation") || strings.Contains(string(encoded), "Checking the details") {
		t.Fatal("internal notes exposed")
	}
}
func TestSafetyReportMessageAccess(t *testing.T) {
	_, ts := newTestServer(t)
	_, _ = register(t, ts, "owner")
	a, _ := register(t, ts, "alice")
	b, bid := register(t, ts, "bob")
	other, _ := register(t, ts, "outsider")
	_, dm := do(t, ts, "POST", "/api/v1/users/@me/channels", a, map[string]any{"recipient_ids": []string{bid}})
	cid := dm["id"].(string)
	createEpoch(t, ts, a, cid)
	res, m := sendMsg(t, ts, a, cid, cid, 0, "secret", "", nil)
	if res.StatusCode != 201 {
		t.Fatalf("message: %v", m)
	}
	mid := m["id"].(string)
	for _, kind := range []string{"message", "channel"} {
		id := mid
		if kind == "channel" {
			id = cid
		}
		if res, _ := do(t, ts, "POST", "/api/v1/reports", other, reportBody(kind, id)); res.StatusCode != 404 {
			t.Errorf("outsider %s: %d", kind, res.StatusCode)
		}
	}
	missing := reportBody("message", mid)
	delete(missing, "message")
	if res, _ := do(t, ts, "POST", "/api/v1/reports", b, missing); res.StatusCode != 400 {
		t.Fatal("message report accepted without full content")
	}
	partial := reportBody("message", mid)
	partial["part"] = "text"
	if res, _ := do(t, ts, "POST", "/api/v1/reports", b, partial); res.StatusCode != 400 {
		t.Fatal("message report accepted selected part")
	}
	changed := reportBody("message", mid)
	changed["message"].(map[string]any)["edited_at"] = int64(123)
	if res, _ := do(t, ts, "POST", "/api/v1/reports", b, changed); res.StatusCode != 409 {
		t.Fatal("message report accepted outdated version")
	}
	v := sendReport(t, ts, b, reportBody("message", mid))
	if v["target_id"] != mid {
		t.Fatal("wrong message target")
	}
	// A report does not change membership or give staff a key.
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+cid, other, nil); res.StatusCode != 404 {
		t.Error("report granted channel access")
	}
	_, sub := do(t, ts, "POST", "/api/v1/channels/"+cid+"/subchats", a, map[string]any{"name": "locked", "password_key": b64(make([]byte, 32)), "password_salt": b64(make([]byte, 16)), "password_params": json.RawMessage(`{"m":65536,"t":3,"p":1,"v":1}`)})
	subID := sub["id"].(string)
	if res, _ := do(t, ts, "POST", "/api/v1/reports", b, reportBody("channel", subID)); res.StatusCode != 404 {
		t.Error("locked subchat report leaked details")
	}
}
func TestSafetyReportFiles(t *testing.T) {
	_, ts := newTestServer(t)
	staff, _ := register(t, ts, "owner")
	a, _ := register(t, ts, "alice")
	b, bid := register(t, ts, "bob")
	code, file := caseUpload(t, ts, "/api/v1/reports/attachments", a, "proof.png", pngBytes(32, 32))
	if code != 201 {
		t.Fatalf("upload: %d %v", code, file)
	}
	fid := file["id"].(string)
	if code, _ := authedGet(t, ts, staff, "/media/reports/"+fid); code != 404 {
		t.Fatal("staff read unsent file")
	}
	bad := reportBody("user", bid)
	bad["files"] = []map[string]any{{"id": fid, "name": "proof.png"}}
	if res, _ := do(t, ts, "POST", "/api/v1/reports", b, bad); res.StatusCode != 400 {
		t.Fatal("attached someone else's file")
	}
	v := sendReport(t, ts, a, bad)
	id := v["id"].(string)
	if code, _ := authedGet(t, ts, staff, "/media/reports/"+fid); code != 200 {
		t.Errorf("staff cannot read evidence: %d", code)
	}
	if code, _ := authedGet(t, ts, b, "/media/reports/"+fid); code != 404 {
		t.Fatal("target read evidence")
	}
	if code, _ := authedGet(t, ts, "", "/media/report/"+fid); code != 404 {
		t.Fatal("public evidence")
	}
	// A retried report works even though its files are now referenced.
	if retry := sendReport(t, ts, a, bad); retry["id"] != id {
		t.Fatal("file report retry duplicated")
	}
	bad["request_id"] = "another-request-" + randomSuffix()
	if res, _ := do(t, ts, "POST", "/api/v1/reports", a, bad); res.StatusCode != 400 {
		t.Fatal("file reused for another report")
	}
}
func TestSafetyReportValidation(t *testing.T) {
	_, ts := newTestServer(t)
	_, _ = register(t, ts, "owner")
	a, aid := register(t, ts, "alice")
	for _, change := range []map[string]any{{"part": "password"}, {"reason": "arbitrary"}, {"description": " "}, {"description": strings.Repeat("x", 4001)}, {"excerpt": strings.Repeat("x", 16001)}, {"part": "custom", "custom_part": ""}, {"target_kind": "arbitrary"}, {"request_id": "bad"}, {"files": []map[string]any{{"id": "0"}}}} {
		req := reportBody("user", aid)
		for k, v := range change {
			req[k] = v
		}
		if res, body := do(t, ts, "POST", "/api/v1/reports", a, req); res.StatusCode != 400 {
			t.Errorf("accepted %v: %d %v", change, res.StatusCode, body)
		}
	}
	req := reportBody("other", "0")
	req["part"] = "custom"
	req["custom_part"] = "A specific widget and link"
	sendReport(t, ts, a, req)
}
func TestSafetyReportConcurrentReview(t *testing.T) {
	_, ts := newTestServer(t)
	staff, sid := register(t, ts, "owner")
	a, aid := register(t, ts, "alice")
	v := sendReport(t, ts, a, reportBody("user", aid))
	id := v["id"].(string)
	// Store-level race uses the same version in both reviewers.
	var wg sync.WaitGroup
	results := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			res, _ := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, staff, map[string]any{"version": 1, "status": "in_review", "assigned_to": sid, "note": fmt.Sprintf("Review %d", i)})
			results <- res.StatusCode
		}(i)
	}
	wg.Wait()
	close(results)
	counts := map[int]int{}
	for code := range results {
		counts[code]++
	}
	if counts[200] != 1 || counts[409] != 1 {
		t.Fatalf("concurrent review: %v", counts)
	}
}

func TestSafetyReportRemovalIsScopedAndAudited(t *testing.T) {
	_, ts := newTestServer(t)
	staff, _ := register(t, ts, "owner")
	a, aid := register(t, ts, "alice")
	b, bid := register(t, ts, "bob")
	mod, modID := register(t, ts, "moddy")
	setRank(t, ts, staff, modID, "mod")
	helper, helperID := register(t, ts, "helper")
	setRank(t, ts, staff, helperID, "helper")
	_, dm := do(t, ts, "POST", "/api/v1/users/@me/channels", a, map[string]any{"recipient_ids": []string{bid}})
	cid := dm["id"].(string)
	createEpoch(t, ts, a, cid)
	_, m := sendMsg(t, ts, a, cid, cid, 0, "reported", "", nil)
	mid := m["id"].(string)
	_, m = sendMsg(t, ts, a, cid, cid, 0, "unrelated", "", nil)
	other := m["id"].(string)
	v := sendReport(t, ts, b, reportBody("message", mid))
	id := v["id"].(string)
	path := "/api/v1/moderation/reports/" + id + "/remove-message"
	body := map[string]any{"version": 1, "reason": "The reported message violates the rules."}
	// a helper triages but takes nothing down, nor closes a report as Action Taken
	for _, tok := range []string{a, b, helper} {
		if res, _ := do(t, ts, "POST", path, tok, body); res.StatusCode != 403 {
			t.Fatalf("removal below mod: %d", res.StatusCode)
		}
	}
	if res, out := do(t, ts, "PATCH", "/api/v1/moderation/reports/"+id, helper, map[string]any{"version": 1, "status": "actioned", "note": "done it"}); res.StatusCode != 403 {
		t.Fatalf("helper closes as actioned: %d %v", res.StatusCode, out)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+cid, staff, nil); res.StatusCode != 404 {
		t.Fatal("staff unexpectedly joined private conversation")
	}
	res, action := do(t, ts, "POST", path, mod, body)
	if res.StatusCode != 201 {
		t.Fatalf("mod removes: %d %v", res.StatusCode, action)
	}
	caseView := action["case"].(map[string]any)
	if caseView["user_id"] != aid || caseView["action"] != "message_remove" {
		t.Fatalf("wrong case: %v", caseView)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+cid+"/messages/"+mid, b, nil); res.StatusCode != 404 {
		t.Fatal("reported message survived")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+cid+"/messages/"+other, b, nil); res.StatusCode != 200 {
		t.Fatal("unrelated message removed")
	}
	if res, _ := do(t, ts, "GET", "/api/v1/channels/"+cid, staff, nil); res.StatusCode != 404 {
		t.Fatal("removal granted channel access")
	}
	_, detail := do(t, ts, "GET", "/api/v1/moderation/reports/"+id, staff, nil)
	if r := detail["report"].(map[string]any); r["version"] != float64(2) || r["status"] != "actioned" || len(detail["events"].([]any)) != 1 {
		t.Fatalf("removal did not close the report with an audit entry: %v", detail)
	}
	if res, _ := do(t, ts, "POST", path, staff, body); res.StatusCode != 404 {
		t.Fatal("repeated removal made another case")
	}
}
func TestStaffGateDoesNotChangeAfterRefusals(t *testing.T) {
	_, ts := newTestServer(t)
	owner, _ := register(t, ts, "owner")
	a, _ := register(t, ts, "alice")
	b, _ := register(t, ts, "bob")
	mod, mid := register(t, ts, "moddy")
	setRank(t, ts, owner, mid, "mod")
	for _, path := range []string{"/api/v1/moderation/reports", "/api/v1/moderation/users", "/api/v1/moderation/announcements", "/api/v1/admin/stats"} {
		for i := 0; i < 3; i++ {
			for _, tok := range []string{a, b} {
				if res, _ := do(t, ts, "GET", path, tok, nil); res.StatusCode != 403 {
					t.Fatalf("staff gate changed after rejection: %s %d", path, res.StatusCode)
				}
			}
		}
	}
	if res, _ := do(t, ts, "GET", "/api/v1/moderation/announcements", mod, nil); res.StatusCode != 403 {
		t.Error("mod gained admin rights after rejection")
	}
}

func TestSafetyReportSnapshotPicturesAndExpressionVisibility(t *testing.T) {
	srv, ts := newTestServer(t)
	staff, _ := register(t, ts, "owner")
	a, aid := register(t, ts, "alice")
	b, _ := register(t, ts, "bob")
	if res, raw := rawPut(t, ts.URL+"/api/v1/users/@me/avatar", a, "image/png", pngBytes(32, 32)); res.StatusCode != 200 {
		t.Fatalf("avatar: %d %s", res.StatusCode, raw)
	}
	_, me := do(t, ts, "GET", "/api/v1/users/@me", a, nil)
	avatar := me["avatar_id"].(string)
	req := reportBody("user", aid)
	req["part"] = "avatar"
	sendReport(t, ts, b, req)
	_, _ = do(t, ts, "DELETE", "/api/v1/users/@me/avatar", a, nil)
	blobID, _ := ids.Parse(avatar)
	blob, err := srv.Store.BlobByID(t.Context(), blobID)
	if err != nil || blob.RefCount < 1 {
		t.Fatalf("snapshot lost its picture: %v %v", blob, err)
	}
	if code, _ := authedGet(t, ts, staff, "/media/reports/"+avatar); code != 200 {
		t.Fatalf("snapshot image inaccessible to staff: %d", code)
	}
	if code, _ := authedGet(t, ts, b, "/media/reports/"+avatar); code != 404 {
		t.Fatal("member fetched private retained snapshot")
	}
	_, pack := do(t, ts, "POST", "/api/v1/stickers/packs", a, map[string]any{"name": "Private pack"})
	pid := pack["id"].(string)
	sticker, stickerBlob := addSticker(t, ts.URL, a, pid, "proof", pngBytes(24, 24))
	for _, target := range []struct{ kind, id string }{{"pack", pid}, {"sticker", sticker}} {
		if res, _ := do(t, ts, "GET", "/api/v1/reports/target?kind="+target.kind+"&id="+target.id, b, nil); res.StatusCode != 404 {
			t.Errorf("private %s revealed: %d", target.kind, res.StatusCode)
		}
	}

	packReport := sendReport(t, ts, a, reportBody("pack", pid))
	_, packDetail := do(t, ts, "GET", "/api/v1/moderation/reports/"+packReport["id"].(string), staff, nil)
	packSnapshot := packDetail["report"].(map[string]any)["snapshot"].(map[string]any)
	if packSnapshot["cover_blob_id"] != stickerBlob || packSnapshot["cover_format"] != "png" {
		t.Fatalf("cover not captured: %v", packSnapshot)
	}
	_, _ = do(t, ts, "DELETE", "/api/v1/stickers/packs/"+pid+"/stickers/"+sticker, a, nil)
	if code, _ := authedGet(t, ts, staff, "/media/reports/"+stickerBlob); code != 200 {
		t.Fatalf("cover lost after removal: %d", code)
	}
	view := createServer(t, ts, a, "Private server")
	sid := view["id"].(string)
	res, raw := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=private", a, "image/png", pngBytes(20, 20))
	if res.StatusCode != 201 {
		t.Fatalf("emoji: %s", raw)
	}
	var emoji map[string]any
	_ = json.Unmarshal(raw, &emoji)
	if res, _ := do(t, ts, "GET", "/api/v1/reports/target?kind=emoji&id="+emoji["id"].(string), b, nil); res.StatusCode != 404 {
		t.Fatal("private emoji metadata revealed")
	}
}

func TestSafetyReportedMessageSharesCompleteFilesWithoutConversationAccess(t *testing.T) {
	_, ts := newTestServer(t)
	staff, _ := register(t, ts, "owner")
	author, _ := register(t, ts, "alice")
	reporter, rid := register(t, ts, "bob")
	outsider, _ := register(t, ts, "carol")
	_, dm := do(t, ts, "POST", "/api/v1/users/@me/channels", author, map[string]any{"recipient_ids": []string{rid}})
	cid := dm["id"].(string)
	createEpoch(t, ts, author, cid)
	key := bytes.Repeat([]byte{0x64}, 32)
	plain := []byte("Entire private attachment, including the ending.")
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	nonce := make([]byte, 12)
	ciphertext := gcm.Seal(nil, nonce, plain, []byte("file/v1"))
	var encoded bytes.Buffer
	encoded.WriteString("ECF1")
	encoded.Write(nonce[:8])
	_ = binary.Write(&encoded, binary.BigEndian, uint32(len(ciphertext)))
	encoded.Write(ciphertext)
	request, _ := http.NewRequest("POST", ts.URL+"/api/v1/channels/"+cid+"/attachments", bytes.NewReader(encoded.Bytes()))
	request.Header.Set("Authorization", "Bearer "+author)
	request.Header.Set("Content-Type", "application/octet-stream")
	uploaded, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	var up map[string]any
	_ = json.NewDecoder(uploaded.Body).Decode(&up)
	uploaded.Body.Close()
	if uploaded.StatusCode != 201 {
		t.Fatal(up)
	}
	blob := up["blob_id"].(string)
	response, m := sendMsg(t, ts, author, cid, cid, 0, "encrypted message", "", map[string]any{"attachments": []map[string]any{{"blob_id": blob, "size": len(plain)}}})
	if response.StatusCode != 201 {
		t.Fatal(m)
	}
	mid := m["id"].(string)
	body := reportBody("message", mid)
	evidence := body["message"].(map[string]any)
	evidence["attachments"] = []map[string]any{}
	if res, _ := do(t, ts, "POST", "/api/v1/reports", reporter, body); res.StatusCode != 400 {
		t.Fatal("accepted omitted attachment")
	}
	file := map[string]any{"blob_id": blob, "name": "full.txt", "type": "text/html", "size": len(plain), "key": b64(key), "preview": true}
	evidence["attachments"] = []map[string]any{file}
	evidence["content"] = map[string]any{"t": "The complete text including its ending", "att": []map[string]any{file}, "embeds": []map[string]any{{"fields": []map[string]any{{"name": "Full field", "value": "Full value"}}}}}
	file["key"] = b64(bytes.Repeat([]byte{1}, 32))
	if res, _ := do(t, ts, "POST", "/api/v1/reports", reporter, body); res.StatusCode != 400 {
		t.Fatal("accepted incorrect file key")
	}
	file["key"] = b64(key)
	if status, _ := authedGet(t, ts, staff, "/media/reports/"+blob); status != 404 {
		t.Fatal("staff read unreported attachment")
	}
	report := sendReport(t, ts, reporter, body)
	id := report["id"].(string)
	res, detail := do(t, ts, "GET", "/api/v1/moderation/reports/"+id, staff, nil)
	if res.StatusCode != 200 {
		t.Fatal(detail)
	}
	data, _ := json.Marshal(detail)
	if strings.Contains(string(data), b64(key)) || strings.Contains(string(data), "_message_files") {
		t.Fatal("file keys leaked into staff JSON")
	}
	if !strings.Contains(string(data), "The complete text including its ending") || !strings.Contains(string(data), "Full value") || len(detail["files"].([]any)) != 1 {
		t.Fatal("missing full evidence", detail)
	}
	_, queue := do(t, ts, "GET", "/api/v1/moderation/reports", staff, nil)
	data, _ = json.Marshal(queue)
	if strings.Contains(string(data), b64(key)) {
		t.Fatal("file keys leaked into queue")
	}
	if status, _ := authedGet(t, ts, staff, "/api/v1/channels/"+cid); status != 404 {
		t.Fatal("staff joined conversation")
	}
	if status, _ := authedGet(t, ts, outsider, "/media/reports/"+blob); status != 404 {
		t.Fatal("stranger read evidence")
	}
	if status, _ := authedGet(t, ts, reporter, "/media/reports/"+blob); status != 404 {
		t.Fatal("staff evidence route bypassed membership")
	}
	get := func(rangeHeader string) (int, []byte, http.Header) {
		r, _ := http.NewRequest("GET", ts.URL+"/media/reports/"+blob, nil)
		r.Header.Set("Authorization", "Bearer "+staff)
		if rangeHeader != "" {
			r.Header.Set("Range", rangeHeader)
		}
		res, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, b, res.Header
	}
	status, full, headers := get("")
	if status != 200 || !bytes.Equal(full, plain) {
		t.Fatal("staff cannot read full attachment", status, string(full))
	}
	if headers.Get("Content-Type") != "application/octet-stream" || headers.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("document could execute in app origin")
	}
	status, part, _ := get("bytes=7-19")
	if status != 206 || !bytes.Equal(part, plain[7:20]) {
		t.Fatal("range request lost bytes")
	}
	if res, v := do(t, ts, "DELETE", "/api/v1/channels/"+cid+"/messages/"+mid, author, nil); res.StatusCode != 204 {
		t.Fatal("delete source", res.StatusCode, v)
	}
	status, full, _ = get("")
	if status != 200 || !bytes.Equal(full, plain) {
		t.Fatal("deletion lost reported file")
	}
}

func TestSafetyReportSexualReasonFollowsContentContext(t *testing.T) {
	c := newCommunity(t)
	check := func(kind, id string, allowed bool) {
		t.Helper()
		res, target := do(t, c.ts, "GET", "/api/v1/reports/target?kind="+kind+"&id="+id, c.tokA, nil)
		if res.StatusCode != 200 {
			t.Fatalf("target: %d %v", res.StatusCode, target)
		}
		found := false
		for _, reason := range target["reasons"].([]any) {
			if reason == "sexual" {
				found = true
			}
		}
		if found != allowed {
			t.Fatalf("%s sexual reason: %v, want %v", kind, found, allowed)
		}
		body := reportBody(kind, id)
		body["reason"] = "sexual"
		res, out := do(t, c.ts, "POST", "/api/v1/reports", c.tokA, body)
		want := 400
		if allowed {
			want = 201
		}
		if res.StatusCode != want {
			t.Fatalf("submit %s: %d %v, want %d", kind, res.StatusCode, out, want)
		}
	}
	check("channel", c.general, true)
	res, out := do(t, c.ts, "PATCH", "/api/v1/channels/"+c.general, c.tokA, map[string]any{"nsfw": true})
	if res.StatusCode != 200 {
		t.Fatalf("nsfw channel: %d %v", res.StatusCode, out)
	}
	check("channel", c.general, false)
	_, _ = do(t, c.ts, "PATCH", "/api/v1/channels/"+c.general, c.tokA, map[string]any{"nsfw": false})
	_, _ = do(t, c.ts, "PATCH", "/api/v1/servers/"+c.server, c.tokA, map[string]any{"nsfw": true})
	check("channel", c.general, false)
	check("server", c.server, false)
	ctok, cid := register(t, c.ts, "carol")
	friend(t, c.ts, c.tokA, "bob", c.tokB, c.idA)
	friend(t, c.ts, c.tokA, "carol", ctok, c.idA)
	for _, recipients := range [][]string{{c.idB}, {c.idB, cid}} {
		res, dm := do(t, c.ts, "POST", "/api/v1/users/@me/channels", c.tokA, map[string]any{"recipient_ids": recipients})
		if res.StatusCode != 200 && res.StatusCode != 201 {
			t.Fatalf("dm: %d %v", res.StatusCode, dm)
		}
		check("channel", dm["id"].(string), false)
		createEpoch(t, c.ts, c.tokA, dm["id"].(string))
		res, msg := sendMsg(t, c.ts, c.tokA, dm["id"].(string), dm["id"].(string), 0, "secret", "", nil)
		if res.StatusCode != 201 {
			t.Fatalf("message: %d %v", res.StatusCode, msg)
		}
		check("message", msg["id"].(string), false)
	}
}

// Closing a report can close the other open reports about the same item, and
// only those; reporters see the new status but never notes, and only staff see
// the reporter's record and the other reports.
func TestSafetyReportResolveRelatedAndPrivacy(t *testing.T) {
	_, ts := newTestServer(t)
	owner, _ := register(t, ts, "owner")
	r1, _ := register(t, ts, "reporter1")
	r2, _ := register(t, ts, "reporter2")
	_, tid := register(t, ts, "target")
	_, oid := register(t, ts, "other")
	first := sendReport(t, ts, r1, reportBody("user", tid))["id"].(string)
	if _, target := do(t, ts, "GET", "/api/v1/reports/target?kind=user&id="+tid, r1, nil); target["already_reported"].(map[string]any)["id"] != first {
		t.Fatalf("reporter not told of their open report: %v", target)
	}
	if _, target := do(t, ts, "GET", "/api/v1/reports/target?kind=user&id="+tid, r2, nil); target["already_reported"] != nil {
		t.Fatal("another person's report revealed")
	}
	second := sendReport(t, ts, r2, reportBody("user", tid))["id"].(string)
	elsewhere := sendReport(t, ts, r2, reportBody("user", oid))["id"].(string)
	res, detail := do(t, ts, "GET", "/api/v1/moderation/reports/"+first, owner, nil)
	if res.StatusCode != 200 || len(detail["related"].([]any)) != 1 || detail["report"].(map[string]any)["duplicates"] != float64(2) || detail["reporter_stats"].(map[string]any)["open"] != float64(1) {
		t.Fatalf("staff context: %v", detail)
	}
	res, _ = do(t, ts, "PATCH", "/api/v1/moderation/reports/"+first, owner, map[string]any{"version": 1, "status": "actioned", "note": "Private staff outcome", "reporter_message": "Thanks, we dealt with it.", "assigned_to": "0", "resolve_related": true})
	if res.StatusCode != 200 {
		t.Fatalf("resolve: %d", res.StatusCode)
	}
	for id, want := range map[string]string{second: "actioned", elsewhere: "open"} {
		if _, d := do(t, ts, "GET", "/api/v1/moderation/reports/"+id, owner, nil); d["report"].(map[string]any)["status"] != want {
			t.Errorf("report %s: want %s, got %v", id, want, d["report"])
		}
	}
	_, mine := do(t, ts, "GET", "/api/v1/reports", r2, nil)
	encoded, _ := json.Marshal(mine)
	if !strings.Contains(string(encoded), `"actioned"`) || !strings.Contains(string(encoded), `"target_label":"User target"`) || strings.Contains(string(encoded), "Private staff outcome") || strings.Contains(string(encoded), "reporter_stats") {
		t.Fatalf("receipts: %s", encoded)
	}
	// the second reporter hears the outcome in their Nexa conversation, unread
	_, feed := do(t, ts, "GET", "/api/v1/nexa/feed", r2, nil)
	told := false
	for _, it := range feed["items"].([]any) {
		item := it.(map[string]any)
		if item["kind"] == "report_outcome" && item["report"].(map[string]any)["outcome"] == "Thanks, we dealt with it." {
			told = true
		}
	}
	if encoded, _ := json.Marshal(feed); !told || strings.Contains(string(encoded), "Private staff outcome") {
		t.Fatalf("reporter feed: %s", encoded)
	}
	if _, st := do(t, ts, "GET", "/api/v1/nexa", r2, nil); st["state"].(map[string]any)["unread"].(float64) < 1 {
		t.Fatalf("outcome not unread: %v", st)
	}
	_, sv := do(t, ts, "POST", "/api/v1/servers", r1, map[string]any{"name": "Place"})
	res, body := do(t, ts, "GET", "/api/v1/reports/target?kind=server&id="+sv["id"].(string), r1, nil)
	if res.StatusCode != 200 {
		t.Fatalf("server target: %d %v", res.StatusCode, body)
	}
	for _, r := range body["reasons"].([]any) {
		if r == "underage" {
			t.Fatal("a server offered the under-13 reason")
		}
	}
}
