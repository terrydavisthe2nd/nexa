package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/music"
)

func TestWidgetsAreCheckedSavedAndShown(t *testing.T) {
	srv, ts := newTestServer(t)
	alice, aliceID := register(t, ts, "alice")
	bob, _ := register(t, ts, "bob")
	put := func(widgets any) (*http.Response, map[string]any) {
		return do(t, ts, "PUT", "/api/v1/users/@me/widgets", alice, map[string]any{"widgets": widgets})
	}
	for name, bad := range map[string]any{
		"two favourites": []any{
			map[string]any{"type": "favorite", "games": []any{map[string]any{"name": "Stardew Valley"}}},
			map[string]any{"type": "favorite", "games": []any{map[string]any{"name": "Celeste"}}},
		},
		"favourite of two":   []any{map[string]any{"type": "favorite", "games": []any{map[string]any{"name": "A"}, map[string]any{"name": "B"}}}},
		"nameless game":      []any{map[string]any{"type": "played", "games": []any{map[string]any{"name": " "}}}},
		"card with no title": []any{map[string]any{"type": "custom", "text": "hi"}},
		"bare card":          []any{map[string]any{"type": "custom", "title": "Just a title"}},
		"script link":        []any{map[string]any{"type": "custom", "title": "Link", "link": map[string]any{"url": "javascript:alert(1)"}}},
		"half a stat":        []any{map[string]any{"type": "custom", "title": "Stats", "stats": []any{map[string]any{"label": "Rank", "value": ""}}}},
		"unknown kind":       []any{map[string]any{"type": "poll"}},
		"too many tags":      []any{map[string]any{"type": "playing", "games": []any{map[string]any{"name": "A", "tags": []string{"a", "b", "c", "d"}}}}},
		"missing picture":    []any{map[string]any{"type": "custom", "title": "Pic", "image": "123456789"}},
	} {
		if res, body := put(bad); res.StatusCode != 400 || errCode(body) != "invalid_widgets" {
			t.Errorf("%s: %d %v", name, res.StatusCode, body)
		}
	}
	// a card's picture, cut on the server
	still, err := os.ReadFile("../media/testdata/crop/still.png")
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/users/@me/widgets/image?shape=wide", bytes.NewReader(still))
	req.Header.Set("Authorization", "Bearer "+alice)
	req.Header.Set("Content-Type", "image/png")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(res.Body)
	res.Body.Close()
	var pic map[string]any
	_ = json.Unmarshal(raw, &pic)
	picID, _ := pic["id"].(string)
	if res.StatusCode != 200 || picID == "" || pic["shape"] != "wide" {
		t.Fatalf("picture: %d %s", res.StatusCode, raw)
	}
	good := []any{
		map[string]any{"id": "fav1", "type": "favorite", "games": []any{map[string]any{"name": "Stardew Valley"}}, "note": "Cosy farming forever."},
		map[string]any{"id": "play1", "type": "playing", "games": []any{map[string]any{"name": "Celeste", "tags": []string{"Speedrun", " "}}}},
		map[string]any{"id": "card1", "type": "custom", "title": "My Setup", "image": picID, "shape": "wide", "text": "Mechanical keyboard, two screens.", "stats": []any{map[string]any{"label": "Keys", "value": "87"}}, "link": map[string]any{"url": "https://example.com/setup", "label": "More"}},
	}
	res, body := put(good)
	if res.StatusCode != 200 {
		t.Fatalf("save: %d %v", res.StatusCode, body)
	}
	list, _ := body["widgets"].([]any)
	if len(list) != 3 {
		t.Fatalf("saved: %v", body)
	}
	if tags := list[1].(map[string]any)["games"].([]any)[0].(map[string]any)["tags"].([]any); len(tags) != 1 {
		t.Errorf("an empty tag was kept: %v", tags)
	}
	// the picture is held while a card names it
	if b, _ := srv.Store.BlobByID(context.Background(), parsedID(t, picID)); b.RefCount != 1 {
		t.Errorf("picture refs after saving: %d", b.RefCount)
	}
	// anyone opening the profile sees them, and so does a visitor following its link
	_, prof := do(t, ts, "GET", "/api/v1/users/"+aliceID+"/profile", bob, nil)
	if w, _ := prof["widgets"].([]any); len(w) != 3 || w[2].(map[string]any)["title"] != "My Setup" {
		t.Errorf("profile widgets: %v", prof["widgets"])
	}
	_, shared := do(t, ts, "GET", "/api/v1/profiles/alice", "", nil)
	if w, _ := shared["widgets"].([]any); len(w) != 3 || shared["username"] != "alice" {
		t.Errorf("shared profile: %v", shared)
	}
	// taking the card out lets its picture go
	if res, body := put(good[:2]); res.StatusCode != 200 {
		t.Fatalf("save fewer: %d %v", res.StatusCode, body)
	}
	if b, _ := srv.Store.BlobByID(context.Background(), parsedID(t, picID)); b.RefCount != 0 {
		t.Errorf("picture refs after taking it out: %d", b.RefCount)
	}
	// none at all is an empty list
	if res, body := put(nil); res.StatusCode != 200 || len(body["widgets"].([]any)) != 0 {
		t.Errorf("clear: %d %v", res.StatusCode, body)
	}
}

func TestGameSearchWithoutAList(t *testing.T) {
	_, ts := newTestServer(t)
	alice, _ := register(t, ts, "alice")
	res, body := do(t, ts, "GET", "/api/v1/games/search?q=stardew", alice, nil)
	if res.StatusCode != 200 || body["ready"] != false || len(body["games"].([]any)) != 0 {
		t.Errorf("search: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/media/game/co1wyy?size=l", "", nil); res.StatusCode != 404 {
		t.Errorf("cover without IGDB: %d", res.StatusCode)
	}
	if !strings.Contains(ts.URL, "127.0.0.1") {
		t.Log(ts.URL)
	}
}

func parsedID(t *testing.T, s string) ids.ID {
	t.Helper()
	id, err := ids.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// With IGDB set up, a search goes through this server, and a game saved from
// it is named and drawn as IGDB has it, whatever the app sent.
func TestWidgetsTakeGamesFromIGDB(t *testing.T) {
	srv, ts := newTestServer(t)
	alice, _ := register(t, ts, "alice")
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"access_token":"tok","expires_in":5000000}`)
	})
	mux.HandleFunc("POST /v4/games", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `[{"id":17000,"name":"Stardew Valley","cover":{"image_id":"xrpmydnu9rpxvxfjkiu7"}}]`)
	})
	fake := httptest.NewServer(mux)
	defer fake.Close()
	srv.Games = games.New("id", "secret", t.TempDir())
	srv.Games.API, srv.Games.Auth = fake.URL+"/v4", fake.URL+"/oauth2/token"
	res, body := do(t, ts, "GET", "/api/v1/games/search?q=stardew", alice, nil)
	found, _ := body["games"].([]any)
	if res.StatusCode != 200 || body["ready"] != true || len(found) != 1 || found[0].(map[string]any)["cover"] != "xrpmydnu9rpxvxfjkiu7" {
		t.Fatalf("search: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "PUT", "/api/v1/users/@me/widgets", alice, map[string]any{"widgets": []any{
		map[string]any{"type": "favorite", "games": []any{map[string]any{"id": "17000", "name": "Totally Different", "cover": "badcover1"}}},
	}})
	if res.StatusCode != 200 {
		t.Fatalf("save: %d %v", res.StatusCode, body)
	}
	game := body["widgets"].([]any)[0].(map[string]any)["games"].([]any)[0].(map[string]any)
	if game["name"] != "Stardew Valley" || game["cover"] != "xrpmydnu9rpxvxfjkiu7" {
		t.Errorf("saved game: %v", game)
	}
}

// Music widgets: a search through this server, and artists, albums and songs
// saved named and drawn as Deezer has them, whatever the app sent.
func TestMusicWidgetsTakeWhatDeezerSays(t *testing.T) {
	srv, ts := newTestServer(t)
	alice, aliceID := register(t, ts, "alice")
	const art = "05a186e0a859a36f9cd51cdae2158fe1"
	mux := http.NewServeMux()
	mux.HandleFunc("GET /search/{what}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("what") != "album" {
			_, _ = io.WriteString(w, `{"data":[]}`)
			return
		}
		_, _ = io.WriteString(w, `{"data":[{"id":14879699,"title":"OK Computer","record_type":"album","md5_image":"`+art+`","artist":{"id":399,"name":"Radiohead"}}]}`)
	})
	mux.HandleFunc("GET /track/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "138539981" {
			_, _ = io.WriteString(w, `{"error":{"type":"DataException","message":"no data","code":800}}`)
			return
		}
		_, _ = io.WriteString(w, `{"id":138539981,"title":"Karma Police","duration":264,"md5_image":"`+art+`","artist":{"id":399,"name":"Radiohead"}}`)
	})
	mux.HandleFunc("GET /images/cover/{art}/{file}", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("\xff\xd8\xff\xe0 a jpeg, near enough"))
	})
	fake := httptest.NewServer(mux)
	defer fake.Close()

	// without a client there is no search, only adding by name
	res, body := do(t, ts, "GET", "/api/v1/music/search?kind=albums&q=ok", alice, nil)
	if res.StatusCode != 200 || body["ready"] != false {
		t.Errorf("search without Deezer: %d %v", res.StatusCode, body)
	}
	srv.Music = music.New(t.TempDir())
	srv.Music.API, srv.Music.Images = fake.URL, fake.URL+"/images"

	res, body = do(t, ts, "GET", "/api/v1/music/search?kind=albums&q=ok%20computer", alice, nil)
	found, _ := body["results"].([]any)
	if res.StatusCode != 200 || body["ready"] != true || len(found) != 1 || found[0].(map[string]any)["by"] != "Radiohead" || found[0].(map[string]any)["art"] != art {
		t.Fatalf("search: %d %v", res.StatusCode, body)
	}
	if res, _ := do(t, ts, "GET", "/api/v1/music/search?kind=playlists&q=x", alice, nil); res.StatusCode != 400 {
		t.Errorf("odd kind: %d", res.StatusCode)
	}

	put := func(widgets ...any) (*http.Response, map[string]any) {
		return do(t, ts, "PUT", "/api/v1/users/@me/widgets", alice, map[string]any{"widgets": widgets})
	}
	for name, bad := range map[string][]any{
		"unknown song":  {map[string]any{"type": "songs", "music": []any{map[string]any{"id": "42", "name": "Not Real"}}}},
		"empty":         {map[string]any{"type": "artists", "music": []any{}}},
		"nameless":      {map[string]any{"type": "artists", "music": []any{map[string]any{"name": " "}}}},
		"two of a kind": {map[string]any{"type": "songs", "music": []any{map[string]any{"name": "A"}}}, map[string]any{"type": "songs", "music": []any{map[string]any{"name": "B"}}}},
		"thirteen": {map[string]any{"type": "albums", "music": func() []any {
			l := []any{}
			for i := 0; i < 13; i++ {
				l = append(l, map[string]any{"name": strings.Repeat("x", i+1)})
			}
			return l
		}()}},
		"gone picture": {map[string]any{"type": "artists", "music": []any{map[string]any{"name": "My Band", "image": "123456789"}}}},
	} {
		if res, body := put(bad...); res.StatusCode != 400 || errCode(body) != "invalid_widgets" {
			t.Errorf("%s: %d %v", name, res.StatusCode, body)
		}
	}

	// a picture of their own, for a band Deezer does not have
	still, err := os.ReadFile("../media/testdata/crop/still.png")
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/users/@me/widgets/image?shape=square", bytes.NewReader(still))
	req.Header.Set("Authorization", "Bearer "+alice)
	req.Header.Set("Content-Type", "image/png")
	up, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var pic map[string]any
	_ = json.NewDecoder(up.Body).Decode(&pic)
	up.Body.Close()
	picID, _ := pic["id"].(string)

	res, body = put(
		map[string]any{"type": "albums", "music": []any{map[string]any{"id": "14879699", "name": "Something Else", "by": "Nobody", "art": "ffffffffffffffffffffffffffffffff"}}},
		// a song no search brought here, asked about as it is saved
		map[string]any{"type": "songs", "music": []any{map[string]any{"id": "138539981", "name": "Karma Police", "duration": 1}, map[string]any{"id": "138539981", "name": "Karma Police"}}},
		map[string]any{"type": "artists", "music": []any{map[string]any{"name": "My Band", "by": "dropped", "image": picID}}},
	)
	if res.StatusCode != 200 {
		t.Fatalf("save: %d %v", res.StatusCode, body)
	}
	list := body["widgets"].([]any)
	album := list[0].(map[string]any)["music"].([]any)[0].(map[string]any)
	if album["name"] != "OK Computer" || album["by"] != "Radiohead" || album["art"] != art {
		t.Errorf("album: %v", album)
	}
	songs := list[1].(map[string]any)["music"].([]any)
	if len(songs) != 1 || songs[0].(map[string]any)["duration"] != float64(264) || songs[0].(map[string]any)["by"] != "Radiohead" {
		t.Errorf("songs: %v", songs)
	}
	band := list[2].(map[string]any)["music"].([]any)[0].(map[string]any)
	if band["image"] != picID || band["by"] != nil {
		t.Errorf("band: %v", band)
	}
	if b, _ := srv.Store.BlobByID(context.Background(), parsedID(t, picID)); b.RefCount != 1 {
		t.Errorf("picture refs: %d", b.RefCount)
	}
	kept, err := srv.Store.UserWidgets(context.Background(), parsedID(t, aliceID))
	if err != nil {
		t.Fatal(err)
	}
	if text := widgetsText(kept); !strings.Contains(text, "albums: OK Computer (Radiohead)") || !strings.Contains(text, "artists: My Band") {
		t.Errorf("for the staff: %q", text)
	}

	// the pictures come from this server, fetched from Deezer once; one kept is
	// served however many a page of results asks for, as an avatar is
	for i := 0; i < 100; i++ {
		if res, _ := do(t, ts, "GET", "/media/music/cover/"+art+"?size=s", "", nil); res.StatusCode != 200 || res.Header.Get("Content-Type") != "image/jpeg" {
			t.Fatalf("cover %d: %d %s", i, res.StatusCode, res.Header.Get("Content-Type"))
		}
	}
	for _, bad := range []string{"/media/music/avatar/" + art, "/media/music/cover/nothex"} {
		if res, _ := do(t, ts, "GET", bad, "", nil); res.StatusCode != 404 {
			t.Errorf("%s: %d", bad, res.StatusCode)
		}
	}
}

// A song's preview comes through this server, ranges and all, and nothing
// on the way may keep it.
func TestSongPreviewsComeThroughNexa(t *testing.T) {
	srv, ts := newTestServer(t)
	song := append([]byte("ID3\x04"), bytes.Repeat([]byte{0xff, 0xfb, 0x90, 0x64}, 250)...)
	mux := http.NewServeMux()
	var fake *httptest.Server
	mux.HandleFunc("GET /track/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "777" {
			_, _ = io.WriteString(w, `{"id":778,"title":"No Preview","preview":""}`)
			return
		}
		_, _ = io.WriteString(w, `{"id":777,"title":"Karma Police","preview":"`+fake.URL+`/preview/song.mp3?hdnea=exp=9999999999~hmac=ab12"}`)
	})
	mux.HandleFunc("GET /preview/song.mp3", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Header().Set("Cache-Control", "public, max-age=12521824")
		w.Header().Set("X-Deezer-Client-Ip", "203.0.113.9")
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(song))
	})
	fake = httptest.NewTLSServer(mux)
	defer fake.Close()
	srv.Music = music.New(t.TempDir())
	srv.Music.API, srv.Music.PreviewHost = fake.URL, "127.0.0.1"
	srv.Music.HTTP.Transport = fake.Client().Transport

	get := func(path, span string) (*http.Response, []byte) {
		t.Helper()
		req, _ := http.NewRequest("GET", ts.URL+path, nil)
		if span != "" {
			req.Header.Set("Range", span)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		body, _ := io.ReadAll(res.Body)
		return res, body
	}
	// anyone who can see a profile can play it, signed in or not
	res, body := get("/media/music/preview/777", "bytes=0-1")
	if res.StatusCode != 206 || string(body) != "ID" || res.Header.Get("Content-Range") != "bytes 0-1/1004" || res.Header.Get("Content-Type") != "audio/mpeg" {
		t.Fatalf("range: %d %q %v", res.StatusCode, body, res.Header)
	}
	if res.Header.Get("Cache-Control") != "no-store" || res.Header.Get("X-Deezer-Client-Ip") != "" {
		t.Errorf("headers passed on: %v", res.Header)
	}
	if res, body := get("/media/music/preview/777", ""); res.StatusCode != 200 || !bytes.Equal(body, song) || res.Header.Get("Accept-Ranges") != "bytes" {
		t.Errorf("whole: %d %d %v", res.StatusCode, len(body), res.Header)
	}
	// a range that is not one span of bytes is left out, and the whole of it comes
	if res, body := get("/media/music/preview/777", "bytes=0-1, 5-9"); res.StatusCode != 200 || len(body) != len(song) {
		t.Errorf("odd range: %d %d", res.StatusCode, len(body))
	}
	for _, path := range []string{"/media/music/preview/778", "/media/music/preview/nope"} {
		if res, _ := get(path, ""); res.StatusCode != 404 {
			t.Errorf("%s: %d", path, res.StatusCode)
		}
	}
}

// An app from before cards had several links, a colour and bars sends a card
// back with only its first link; saving from it must not drop the rest. A
// current app always sends "links", so taking them off sticks.
func TestOldAppsKeepWhatCardsGained(t *testing.T) {
	_, ts := newTestServer(t)
	alice, _ := register(t, ts, "alice")
	put := func(card map[string]any) map[string]any {
		res, body := do(t, ts, "PUT", "/api/v1/users/@me/widgets", alice, map[string]any{"widgets": []any{card}})
		if res.StatusCode != 200 {
			t.Fatalf("save: %d %v", res.StatusCode, body)
		}
		return body["widgets"].([]any)[0].(map[string]any)
	}
	put(map[string]any{"id": "card1", "type": "custom", "title": "Japanese",
		"stats": []any{map[string]any{"label": "Kanji", "value": "", "bar": 60}},
		"links": []any{map[string]any{"url": "https://a.example"}, map[string]any{"url": "https://b.example"}},
		"color": 0xE0457B, "color2": 0x7B61FF})
	// as an old app saves it: the first link, the bar's words, no colour
	got := put(map[string]any{"id": "card1", "type": "custom", "title": "Japanese",
		"stats": []any{map[string]any{"label": "Kanji", "value": "60%"}},
		"link":  map[string]any{"url": "https://a.example"}})
	if links := got["links"].([]any); len(links) != 2 || got["color"] == nil || got["color2"] == nil {
		t.Fatalf("an old app's save dropped what it could not show: %v", got)
	}
	if bar := got["stats"].([]any)[0].(map[string]any)["bar"]; bar != float64(60) {
		t.Fatalf("bar dropped: %v", got["stats"])
	}
	// a current app taking them off
	got = put(map[string]any{"id": "card1", "type": "custom", "title": "Japanese", "text": "words", "links": []any{}})
	if got["links"] != nil || got["link"] != nil || got["color"] != nil {
		t.Fatalf("a current app's removal did not stick: %v", got)
	}
}
