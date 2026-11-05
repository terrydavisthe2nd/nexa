package api

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

// Sharing goes through staff before it reaches anyone, the panel never says who
// shared, and only admins decide.
func TestSharedGamesGoThroughStaff(t *testing.T) {
	srv, ts := newTestServer(t)
	admin, _ := register(t, ts, "admin_first")
	alice, _ := register(t, ts, "alice")
	bob, _ := register(t, ts, "bob")

	get := func(path, token string) (*http.Response, string) {
		req, _ := http.NewRequest("GET", ts.URL+path, nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		body, _ := io.ReadAll(res.Body)
		return res, string(body)
	}
	list := func() string {
		_, body := get("/api/v1/games/known", "")
		return body
	}
	if res, _ := do(t, ts, "POST", "/api/v1/games/shared", "", map[string]any{"process": "CoolGame.exe", "name": "x"}); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("sharing needs an account, got %d", res.StatusCode)
	}
	for _, tok := range []string{alice, bob} {
		if res, _ := do(t, ts, "POST", "/api/v1/games/shared", tok, map[string]any{"process": "CoolGame.exe", "os": "win32", "name": "Cool Game"}); res.StatusCode != http.StatusNoContent {
			t.Fatalf("sharing: %d", res.StatusCode)
		}
	}
	_, m := do(t, ts, "POST", "/api/v1/games/shared", alice, map[string]any{"process": "launcher.exe", "name": "Anything"})
	if code := errCode(m); code != "common_process" {
		t.Fatalf("a file name everything uses should be refused, got %q", code)
	}
	_, m = do(t, ts, "POST", "/api/v1/games/shared", alice, map[string]any{"process": `C:\Users\alice\game.exe`, "name": "Path"})
	if code := errCode(m); code != "common_process" {
		t.Fatalf("a path is not a file name, got %q", code)
	}
	if _, mine := get("/api/v1/games/shared", alice); !strings.Contains(mine, "coolgame.exe") {
		t.Fatalf("someone's export should hold what they shared: %s", mine)
	}
	if strings.Contains(list(), "coolgame.exe") {
		t.Fatal("a shared game reached the list before staff saw it")
	}

	if res, _ := get("/api/v1/moderation/games", alice); res.StatusCode == http.StatusOK {
		t.Fatal("someone with no rank read the staff page")
	}
	if res, _ := do(t, ts, "POST", "/api/v1/moderation/games/list", alice, map[string]any{"process": "coolgame.exe", "name": "Mine"}); res.StatusCode == http.StatusNoContent {
		t.Fatal("someone with no rank listed a game")
	}
	_, raw := get("/api/v1/moderation/games", admin)
	var page struct {
		Waiting []struct {
			Process string `json:"process"`
			Name    string `json:"name"`
			Count   int    `json:"count"`
		} `json:"waiting"`
	}
	if err := json.Unmarshal([]byte(raw), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Waiting) != 1 || page.Waiting[0].Count != 2 || page.Waiting[0].Process != "coolgame.exe" {
		t.Fatalf("waiting: %s", raw)
	}
	if strings.Contains(raw, "alice") || strings.Contains(raw, "user_id") {
		t.Fatalf("the panel should say how many shared, not who: %s", raw)
	}

	// staff choose the name and game, whatever was shared
	if res, _ := do(t, ts, "POST", "/api/v1/moderation/games/list", admin, map[string]any{"process": "coolgame.exe", "name": "The Real Name", "game_id": "1234"}); res.StatusCode != http.StatusNoContent {
		t.Fatalf("listing: %d", res.StatusCode)
	}
	if got := list(); !strings.Contains(got, `"The Real Name"`) || !strings.Contains(got, `"1234"`) {
		t.Fatalf("the list should carry what staff chose: %s", got)
	}
	first, _ := get("/api/v1/games/known", "")
	etag := first.Header.Get("ETag")
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/games/known", nil)
	req.Header.Set("If-None-Match", etag)
	again, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	again.Body.Close()
	if again.StatusCode != http.StatusNotModified {
		t.Fatalf("an unchanged list should cost nothing, got %d", again.StatusCode)
	}

	// turned down stays turned down, until staff take that back
	do(t, ts, "POST", "/api/v1/moderation/games/reject", admin, map[string]any{"process": "spam.exe", "name": "Spam"})
	do(t, ts, "POST", "/api/v1/games/shared", bob, map[string]any{"process": "spam.exe", "name": "Spam Again"})
	n, _ := srv.Store.WaitingGamesCount(t.Context())
	if n != 0 {
		t.Fatalf("a program staff turned down came back: %d waiting", n)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/moderation/games/forget", admin, map[string]any{"process": "coolgame.exe"}); res.StatusCode != http.StatusNoContent {
		t.Fatalf("forgetting: %d", res.StatusCode)
	}
	if strings.Contains(list(), "The Real Name") {
		t.Fatal("a game taken off the list stayed on it")
	}
}
