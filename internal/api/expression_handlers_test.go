package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
)

func rawPost(t *testing.T, url, token, ctype string, body []byte) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest("POST", url, bytes.NewReader(body))
	req.Header.Set("Content-Type", ctype)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res, b
}

func TestServerEmoji(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Emo")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	gb := connectGW(t, ts, tokB)

	// members without Manage Expressions cannot upload
	if res, _ := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=party", tokB, "image/png", pngBytes(32, 32)); res.StatusCode != 403 {
		t.Errorf("member upload: %d", res.StatusCode)
	}
	if res, _ := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=bad%20name", tokA, "image/png", pngBytes(32, 32)); res.StatusCode != 400 {
		t.Errorf("bad name: %d", res.StatusCode)
	}
	res, raw := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=party", tokA, "image/png", pngBytes(32, 32))
	if res.StatusCode != 201 {
		t.Fatalf("upload: %d %s", res.StatusCode, raw)
	}
	ev := gb.next("emoji_update", 3*time.Second)
	if ev == nil || len(ev["emojis"].([]any)) != 1 || ev["emojis"].([]any)[0].(map[string]any)["name"] != "party" {
		t.Fatalf("emoji_update: %v", ev)
	}
	emojiID := ev["emojis"].([]any)[0].(map[string]any)["id"].(string)
	blobID := ev["emojis"].([]any)[0].(map[string]any)["blob_id"].(string)
	if res, _ := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=party", tokA, "image/png", pngBytes(32, 32)); res.StatusCode != 409 {
		t.Errorf("duplicate name: %d", res.StatusCode)
	}
	// the image is served publicly
	req, _ := http.NewRequest("GET", ts.URL+"/media/emoji/"+blobID, nil)
	if r2, err := http.DefaultClient.Do(req); err != nil || r2.StatusCode != 200 || r2.Header.Get("Content-Type") != "image/png" {
		t.Errorf("serve emoji: %v", err)
	}
	// listed for members and in bob's ready payload
	res2, body := do(t, ts, "GET", "/api/v1/servers/"+sid+"/emojis", tokB, nil)
	if res2.StatusCode != 200 || len(body["_list"].([]any)) != 1 {
		t.Errorf("list: %d %v", res2.StatusCode, body)
	}
	gb2 := connectGW(t, ts, tokB)
	_ = gb2
	// rename and delete
	if res2, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/emojis/"+emojiID, tokA, map[string]any{"name": "partying"}); res2.StatusCode != 200 || body["name"] != "partying" {
		t.Errorf("rename: %d %v", res2.StatusCode, body)
	}
	if res2, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/emojis/"+emojiID, tokB, nil); res2.StatusCode != 403 {
		t.Error("member delete")
	}
	if res2, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/emojis/"+emojiID, tokA, nil); res2.StatusCode != 204 {
		t.Error("delete")
	}
	res2, body = do(t, ts, "GET", "/api/v1/servers/"+sid+"/emojis", tokA, nil)
	if len(body["_list"].([]any)) != 0 {
		t.Errorf("after delete: %v", body)
	}
	// a role with Manage Expressions lets bob upload
	role := createRole(t, ts, tokA, sid, "Artist", perms.DefaultEveryone|perms.ManageEmojis)
	_, _ = do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+mustID(t, ts, tokB)+"/roles/"+role, tokA, nil)
	if res, _ := rawPost(t, ts.URL+"/api/v1/servers/"+sid+"/emojis?name=bobmoji", tokB, "image/png", pngBytes(32, 32)); res.StatusCode != 201 {
		t.Errorf("artist upload: %d", res.StatusCode)
	}
}

func TestStickerPacks(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	// alice makes a personal pack
	res, body := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "  Cats  ", "description": "meow"})
	if res.StatusCode != 201 || body["name"] != "Cats" || body["owner_user_id"] != idA || body["public"] != false {
		t.Fatalf("create pack: %d %v", res.StatusCode, body)
	}
	pack := body["id"].(string)
	if ev := ga.next("sticker_update", 3*time.Second); ev == nil || ev["pack_id"] != pack {
		t.Error("sticker_update for owner")
	}
	if res, _ = do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": ""}); res.StatusCode != 400 {
		t.Error("empty name")
	}
	// stickers
	res, raw := rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name=Grumpy&tags=cat%20angry", tokA, "image/png", pngBytes(64, 48))
	if res.StatusCode != 201 {
		t.Fatalf("add sticker: %d %s", res.StatusCode, raw)
	}
	res, raw = rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name=Happy", tokA, "image/png", pngBytes(48, 48))
	if res.StatusCode != 201 {
		t.Fatalf("add sticker 2: %d %s", res.StatusCode, raw)
	}
	if res, _ = rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name=Nope", tokB, "image/png", pngBytes(8, 8)); res.StatusCode != 404 {
		t.Errorf("outsider adds sticker to private pack: %d", res.StatusCode)
	}
	res, body = do(t, ts, "GET", "/api/v1/stickers/packs/"+pack, tokA, nil)
	stickers := body["stickers"].([]any)
	if res.StatusCode != 200 || len(stickers) != 2 || body["cover_sticker_id"] != stickers[0].(map[string]any)["id"] {
		t.Fatalf("pack: %d %v", res.StatusCode, body)
	}
	first := stickers[0].(map[string]any)
	second := stickers[1].(map[string]any)
	if first["name"] != "Grumpy" || first["tags"] != "cat angry" || first["format"] != "png" || first["width"].(float64) != 64 {
		t.Errorf("sticker fields: %v", first)
	}
	// reorder, rename
	if res, body = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack+"/stickers", tokA, []map[string]any{{"id": second["id"], "position": 0}, {"id": first["id"], "position": 1}}); res.StatusCode != 200 || body["stickers"].([]any)[0].(map[string]any)["name"] != "Happy" {
		t.Errorf("reorder: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack+"/stickers/"+first["id"].(string), tokA, map[string]any{"name": "Grump", "tags": "cat"}); res.StatusCode != 200 || body["name"] != "Grump" {
		t.Errorf("rename sticker: %d %v", res.StatusCode, body)
	}
	// bob cannot see or install a private pack
	if res, _ = do(t, ts, "GET", "/api/v1/stickers/packs/"+pack, tokB, nil); res.StatusCode != 404 {
		t.Error("private pack hidden")
	}
	if res, _ = do(t, ts, "PUT", "/api/v1/stickers/packs/"+pack+"/install", tokB, nil); res.StatusCode != 403 {
		t.Error("install private")
	}
	// public → discoverable, installable
	if res, body = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack, tokA, map[string]any{"public": true, "description": "meow meow"}); res.StatusCode != 200 || body["public"] != true {
		t.Fatalf("publish: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/stickers/packs/public?q=cat", tokB, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 1 || body["_list"].([]any)[0].(map[string]any)["installed"] != false {
		t.Errorf("public search: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "PUT", "/api/v1/stickers/packs/"+pack+"/install", tokB, nil); res.StatusCode != 200 || body["installed"] != true {
		t.Fatalf("install: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("sticker_update", 3*time.Second); ev == nil || ev["pack_id"] != pack {
		t.Error("sticker_update for installer")
	}
	res, body = do(t, ts, "GET", "/api/v1/stickers/packs", tokB, nil)
	if len(body["_list"].([]any)) != 1 || body["_list"].([]any)[0].(map[string]any)["installed"] != true {
		t.Errorf("bob's packs: %v", body)
	}
	// installers hear about changes; bob cannot edit
	if res, _ = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack, tokB, map[string]any{"name": "Mine"}); res.StatusCode != 403 {
		t.Error("installer edits")
	}
	_, _ = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack, tokA, map[string]any{"name": "Cats!"})
	if ev := gb.next("sticker_update", 3*time.Second); ev == nil || ev["pack"].(map[string]any)["name"] != "Cats!" {
		t.Error("installer receives rename")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/stickers/packs/"+pack+"/install", tokB, nil); res.StatusCode != 204 {
		t.Error("uninstall")
	}
	if ev := gb.next("sticker_update", 3*time.Second); ev == nil || ev["deleted"] != true {
		t.Error("uninstall removes the pack client-side")
	}
	// ready carries alice's packs and their stickers
	ga2 := connectGW(t, ts, tokA)
	_ = ga2
	// a server pack needs Manage Expressions; members see it in ready and listings
	view := createServer(t, ts, tokA, "Pack Server")
	sid := view["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, channelNamed(view, "general")["id"].(string)))
	if res, _ = do(t, ts, "POST", "/api/v1/stickers/packs", tokB, map[string]any{"name": "Server Pack", "server_id": sid}); res.StatusCode != 403 {
		t.Errorf("member creates server pack: %d", res.StatusCode)
	}
	res, body = do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "Server Pack", "server_id": sid})
	if res.StatusCode != 201 || body["server_id"] != sid {
		t.Fatalf("server pack: %d %v", res.StatusCode, body)
	}
	spack := body["id"].(string)
	res, body = do(t, ts, "GET", "/api/v1/stickers/packs", tokB, nil)
	found := false
	for _, p := range body["_list"].([]any) {
		if p.(map[string]any)["id"] == spack {
			found = true
		}
	}
	if !found {
		t.Errorf("member sees server pack: %v", body)
	}
	// a member who installs their own server's pack and removes it again still has it
	if res, _ = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+spack, tokA, map[string]any{"public": true}); res.StatusCode != 200 {
		t.Fatalf("publish server pack: %d", res.StatusCode)
	}
	if res, _ = do(t, ts, "PUT", "/api/v1/stickers/packs/"+spack+"/install", tokB, nil); res.StatusCode != 200 {
		t.Fatalf("install server pack: %d", res.StatusCode)
	}
	for ev := gb.next("sticker_update", 3*time.Second); ev != nil && ev["pack"] != nil && ev["pack"].(map[string]any)["installed"] != true; ev = gb.next("sticker_update", 3*time.Second) {
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/stickers/packs/"+spack+"/install", tokB, nil); res.StatusCode != 204 {
		t.Fatalf("uninstall server pack: %d", res.StatusCode)
	}
	if ev := gb.next("sticker_update", 3*time.Second); ev == nil || ev["deleted"] != false || ev["pack"].(map[string]any)["installed"] != false {
		t.Errorf("uninstalling a pack of your own server keeps it listed: %v", ev)
	}
	// delete drops blob references
	if res, _ = do(t, ts, "DELETE", "/api/v1/stickers/packs/"+pack, tokB, nil); res.StatusCode != 403 && res.StatusCode != 404 {
		t.Errorf("outsider delete: %d", res.StatusCode)
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/stickers/packs/"+pack, tokA, nil); res.StatusCode != 204 {
		t.Error("delete pack")
	}
	if res, _ = do(t, ts, "GET", "/api/v1/stickers/packs/"+pack, tokA, nil); res.StatusCode != 404 {
		t.Error("deleted pack gone")
	}
	if res, body = do(t, ts, "GET", "/api/v1/gif/status", tokA, nil); res.StatusCode != 200 || body["enabled"] != false {
		t.Errorf("gif status: %d %v", res.StatusCode, body)
	}
	if res, body = do(t, ts, "GET", "/api/v1/gif/search?q=cat", tokA, nil); res.StatusCode != 404 || errCode(body) != "gif_disabled" {
		t.Errorf("gif disabled: %d %v", res.StatusCode, body)
	}
	_ = srv
}

// klipyStub stands in for api.klipy.com so the mapping can be checked without
// a key or a network.
func klipyStub(t *testing.T, body string) func() {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	prev := gifClient.Transport
	gifClient.Transport = &redirectTransport{to: srv.Listener.Addr().String()}
	return func() {
		gifClient.Transport = prev
		srv.Close()
	}
}

// redirectTransport sends every request to one address, whatever the host says.
type redirectTransport struct{ to string }

func (t *redirectTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	clone := r.Clone(r.Context())
	clone.URL.Scheme = "http"
	clone.URL.Host = t.to
	return http.DefaultTransport.RoundTrip(clone)
}

const klipyBody = `{"result":true,"data":{"current_page":1,"per_page":30,"has_next":true,"data":[
  {"id":7,"slug":"happy-cat","title":"Happy cat","type":"gif","file":{
    "xs":{"gif":{"url":"https://static.klipy.com/xs.gif","width":80,"height":60,"size":1}},
    "md":{"gif":{"url":"https://static.klipy.com/md.gif","width":320,"height":240,"size":9}}}},
  {"id":8,"slug":"no-gif-here","title":"Only webp","type":"gif","file":{
    "md":{"webp":{"url":"https://static.klipy.com/md.webp","width":320,"height":240,"size":9}}}}
]}}`

func TestKlipyGifSearch(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.KlipyAPIKey = "test-key"
	defer klipyStub(t, klipyBody)()
	token, _ := register(t, ts, "alice")

	res, body := do(t, ts, "GET", "/api/v1/gif/search?q=cat", token, nil)
	if res.StatusCode != 200 {
		t.Fatalf("search: %d %v", res.StatusCode, body)
	}
	results, _ := body["results"].([]any)
	if len(results) != 1 {
		t.Fatalf("a GIF with no gif rendition should be skipped: %v", body)
	}
	first, _ := results[0].(map[string]any)
	if first["id"] != "happy-cat" || first["title"] != "Happy cat" || first["w"].(float64) != 320 || first["h"].(float64) != 240 {
		t.Errorf("mapped result: %v", first)
	}
	// every picture goes through this server, never straight to the source
	for _, key := range []string{"url", "preview"} {
		u, _ := first[key].(string)
		if !strings.HasPrefix(u, "/media/proxy/") {
			t.Errorf("%s should be proxied, got %q", key, u)
		}
	}
	// nothing here is wide enough to call for a lighter copy, so the preview is
	// the sharpest rendition there is, never the 80-pixel thumbnail
	if first["url"] != first["preview"] {
		t.Error("the preview should be the sharp rendition, not the thumbnail")
	}
	if body["next"] != "2" {
		t.Errorf("next page: %v", body["next"])
	}
}

func TestGifSearchOffWithoutAKey(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.KlipyAPIKey = ""
	token, _ := register(t, ts, "alice")
	res, body := do(t, ts, "GET", "/api/v1/gif/trending", token, nil)
	if res.StatusCode != 404 || errCode(body) != "gif_disabled" {
		t.Fatalf("without a key: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/gif/status", token, nil)
	if res.StatusCode == 200 && body["enabled"] != false {
		t.Errorf("status should say it is off: %v", body)
	}
}

// Deleting a pack tells whoever installed it, and lets go of every sticker's
// picture, even one added twice.
func TestPackDeleteReachesInstallers(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	_, body := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "Dogs"})
	pack := body["id"].(string)
	img := pngBytes(32, 32)
	for _, name := range []string{"One", "Two"} {
		if res, raw := rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name="+name, tokA, "image/png", img); res.StatusCode != 201 {
			t.Fatalf("add sticker: %d %s", res.StatusCode, raw)
		}
	}
	_, body = do(t, ts, "GET", "/api/v1/stickers/packs/"+pack, tokA, nil)
	blob, _ := parseID(body["stickers"].([]any)[0].(map[string]any)["blob_id"].(string))
	if b, _ := srv.Store.BlobByID(context.Background(), blob); b == nil || b.RefCount != 2 {
		t.Fatalf("the same picture twice holds two references: %+v", b)
	}
	do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack, tokA, map[string]any{"public": true})
	if res, _ := do(t, ts, "PUT", "/api/v1/stickers/packs/"+pack+"/install", tokB, nil); res.StatusCode != 200 {
		t.Fatalf("install: %d", res.StatusCode)
	}
	gb := connectGW(t, ts, tokB)
	if res, _ := do(t, ts, "DELETE", "/api/v1/stickers/packs/"+pack, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("delete: %d", res.StatusCode)
	}
	if ev := gb.next("sticker_update", 3*time.Second); ev == nil || ev["pack_id"] != pack || ev["deleted"] != true {
		t.Errorf("installer hears of the delete: %v", ev)
	}
	if b, _ := srv.Store.BlobByID(context.Background(), blob); b == nil || b.RefCount != 0 {
		t.Errorf("references after delete: %+v", b)
	}
}

func TestStickerInfoShowsOnlyPublicPacks(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	res, body := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "Cats", "description": "meow"})
	if res.StatusCode != 201 {
		t.Fatalf("create pack: %d %v", res.StatusCode, body)
	}
	pack := body["id"].(string)
	res, raw := rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name=Grumpy", tokA, "image/png", pngBytes(64, 48))
	if res.StatusCode != 201 {
		t.Fatalf("add sticker: %d %s", res.StatusCode, raw)
	}
	var sticker map[string]any
	_ = json.Unmarshal(raw, &sticker)
	sid := sticker["id"].(string)
	if res, _ = rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name=Happy", tokA, "image/png", pngBytes(48, 48)); res.StatusCode != 201 {
		t.Fatalf("add sticker 2: %d", res.StatusCode)
	}

	// a private pack is not shown, not even to whoever made it
	for _, tok := range []string{tokA, tokB} {
		if res, _ = do(t, ts, "GET", "/api/v1/stickers/"+sid, tok, nil); res.StatusCode != 404 {
			t.Errorf("sticker from a private pack: %d", res.StatusCode)
		}
	}
	if res, _ = do(t, ts, "GET", "/api/v1/stickers/123456789", tokB, nil); res.StatusCode != 404 {
		t.Errorf("unknown sticker: %d", res.StatusCode)
	}

	// public: the pack with all its stickers, and who made them
	if res, body = do(t, ts, "PATCH", "/api/v1/stickers/packs/"+pack, tokA, map[string]any{"public": true}); res.StatusCode != 200 {
		t.Fatalf("make public: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/stickers/"+sid, tokB, nil)
	if res.StatusCode != 200 {
		t.Fatalf("sticker info: %d %v", res.StatusCode, body)
	}
	p := body["pack"].(map[string]any)
	if body["sticker"].(map[string]any)["name"] != "Grumpy" || p["name"] != "Cats" || len(p["stickers"].([]any)) != 2 || p["installed"] != false {
		t.Errorf("sticker info: %v", body)
	}
	users := body["users"].([]any)
	if len(users) != 1 || users[0].(map[string]any)["id"] != idA || users[0].(map[string]any)["username"] != "alice" {
		t.Errorf("creators: %v", users)
	}
	if res, _ = do(t, ts, "PUT", "/api/v1/stickers/packs/"+pack+"/install", tokB, nil); res.StatusCode != 200 {
		t.Fatalf("install: %d", res.StatusCode)
	}
	if _, body = do(t, ts, "GET", "/api/v1/stickers/"+sid, tokB, nil); body["pack"].(map[string]any)["installed"] != true {
		t.Errorf("installed after installing: %v", body["pack"])
	}
}

// Discovery lists only public packs with something in them, of the kind asked
// for, with no more than a preview of their stickers.
func TestDiscoverPacksListsOnlyPublicOnes(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	newPack := func(name, kind string, stickers int, public bool) string {
		t.Helper()
		res, body := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": name, "kind": kind})
		if res.StatusCode != 201 {
			t.Fatalf("create %s: %d %v", name, res.StatusCode, body)
		}
		id := body["id"].(string)
		for i := range stickers {
			res, raw := rawPost(t, ts.URL+"/api/v1/stickers/packs/"+id+"/stickers?name="+name+strconv.Itoa(i)+"&tags=findme", tokA, "image/png", pngBytes(32+i, 32))
			if res.StatusCode != 201 {
				t.Fatalf("add to %s: %d %s", name, res.StatusCode, raw)
			}
		}
		if public {
			if res, body := do(t, ts, "PATCH", "/api/v1/stickers/packs/"+id, tokA, map[string]any{"public": true}); res.StatusCode != 200 {
				t.Fatalf("publish %s: %d %v", name, res.StatusCode, body)
			}
		}
		return id
	}
	big := newPack("big", "sticker", 10, true)
	small := newPack("small", "sticker", 1, true)
	newPack("secret", "sticker", 1, false)
	newPack("hollow", "sticker", 0, true)
	newPack("faces", "emoji", 1, true)
	if res, body := do(t, ts, "PUT", "/api/v1/stickers/packs/"+small+"/install", tokB, nil); res.StatusCode != 200 {
		t.Fatalf("install: %d %v", res.StatusCode, body)
	}

	list := func(query string) []map[string]any {
		t.Helper()
		res, body := do(t, ts, "GET", "/api/v1/stickers/discover?"+query, tokB, nil)
		if res.StatusCode != 200 {
			t.Fatalf("discover %s: %d %v", query, res.StatusCode, body)
		}
		out := []map[string]any{}
		for _, p := range body["packs"].([]any) {
			out = append(out, p.(map[string]any))
		}
		return out
	}
	got := list("kind=sticker")
	if len(got) != 2 || got[0]["id"] != small || got[1]["id"] != big {
		t.Fatalf("popular stickers: %v", got)
	}
	if got[0]["install_count"] != float64(1) || got[0]["installed"] != true || got[0]["owner"].(map[string]any)["username"] != "alice" {
		t.Errorf("counts and owner: %v", got[0])
	}
	if got[1]["sticker_count"] != float64(10) || len(got[1]["stickers"].([]any)) != 8 {
		t.Errorf("preview: %v", got[1])
	}
	if emoji := list("kind=emoji"); len(emoji) != 1 || emoji[0]["name"] != "faces" {
		t.Errorf("emoji: %v", emoji)
	}
	// a private pack's sticker names and tags are never what finds it
	if found := list("kind=sticker&q=secret0"); len(found) != 0 {
		t.Errorf("private pack found by a sticker's name: %v", found)
	}
	if found := list("kind=sticker&q=small0"); len(found) != 1 || found[0]["id"] != small {
		t.Errorf("sticker name search: %v", found)
	}
	res, body := do(t, ts, "GET", "/api/v1/stickers/discover?kind=sticker&limit=1", tokB, nil)
	if res.StatusCode != 200 || body["has_more"] != true || body["next_offset"] != float64(1) {
		t.Errorf("first page: %d %v", res.StatusCode, body)
	}
}
