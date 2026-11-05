package gateway

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/rpcapps"
)

// testApp is a Discord application with two art assets, as rpcapps would return it.
type testApp struct{}

func (testApp) name() string { return "Music Player" }
func (testApp) icon() string { return "https://cdn.test/app-icons/1/icon.png" }
func (testApp) asset(k string) string {
	switch strings.ToLower(k) {
	case "album", "1111111111111111111":
		return "https://cdn.test/app-assets/1/1111111111111111111.png"
	}
	return ""
}

func proxied(u string) string { return "/media/proxy/sig/" + u }

func normalize(t *testing.T, raw string, now time.Time) (Activity, bool) {
	t.Helper()
	var in activityInput
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		t.Fatal(err)
	}
	app := testApp{}
	return normalizeActivity(in, &appInfo{Name: app.name(), IconURL: app.icon(), Asset: app.asset}, now, proxied)
}

// normalizeUnknownApp cleans an activity whose application is not known.
func normalizeUnknownApp(t *testing.T, raw string, now time.Time) (Activity, bool) {
	t.Helper()
	var in activityInput
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		t.Fatal(err)
	}
	return normalizeActivity(in, nil, now, proxied)
}

func TestActivityKeepsWhatDiscordShows(t *testing.T) {
	now := time.UnixMilli(1_800_000_000_000)
	a, ok := normalize(t, `{
		"application_id": "123456789012345678", "type": 2, "status_display_type": 2,
		"details": "Never Gonna Give You Up", "details_url": "https://example.com/song",
		"state": "Rick Astley", "state_url": "https://example.com/artist",
		"timestamps": {"start": 1799999990000, "end": 1800000203000},
		"assets": {"large_image": "album", "large_text": "Whenever You Need Somebody", "large_url": "https://example.com/album",
			"small_image": "https://i.imgur.com/play.png", "small_text": "Playing"},
		"party": {"id": "room-1", "size": [2, 4]},
		"buttons": [{"label": "Listen along", "url": "https://example.com/listen"}, {"label": "Profile", "url": "http://example.com/me"}],
		"instance": true
	}`, now)
	if !ok {
		t.Fatal("dropped a valid activity")
	}
	if a.Name != "Music Player" || a.Type != 2 || a.StatusDisplayType == nil || *a.StatusDisplayType != 2 || a.ApplicationID != "123456789012345678" {
		t.Errorf("header: %+v", a)
	}
	if a.Details != "Never Gonna Give You Up" || a.DetailsURL != "https://example.com/song" || a.State != "Rick Astley" || a.StateURL != "https://example.com/artist" {
		t.Errorf("text: %+v", a)
	}
	if a.Timestamps == nil || *a.Timestamps.Start != 1799999990000 || *a.Timestamps.End != 1800000203000 {
		t.Errorf("timestamps: %+v", a.Timestamps)
	}
	if a.Assets == nil || a.Assets.LargeImage != proxied("https://cdn.test/app-assets/1/1111111111111111111.png") || a.Assets.SmallImage != proxied("https://i.imgur.com/play.png") || a.Assets.LargeText != "Whenever You Need Somebody" || a.Assets.LargeURL != "https://example.com/album" {
		t.Errorf("assets: %+v", a.Assets)
	}
	if a.Party == nil || a.Party.ID != "room-1" || len(a.Party.Size) != 2 || a.Party.Size[0] != 2 || a.Party.Size[1] != 4 {
		t.Errorf("party: %+v", a.Party)
	}
	if len(a.Buttons) != 2 || a.Buttons[1].URL != "http://example.com/me" || !a.Instance {
		t.Errorf("buttons/instance: %+v", a)
	}
	if a.IconURL != proxied("https://cdn.test/app-icons/1/icon.png") || a.CreatedAt != now.UnixMilli() {
		t.Errorf("icon/created: %+v", a)
	}
}

func TestActivityIsCleaned(t *testing.T) {
	now := time.UnixMilli(1_800_000_000_000)
	a, ok := normalizeUnknownApp(t, `{
		"name": "  My Game\n‮ ", "type": 7, "status_display_type": 9,
		"details": "`+strings.Repeat("д", 300)+`", "state": "line\nbreak",
		"details_url": "javascript:alert(1)", "state_url": "ftp://example.com",
		"timestamps": {"start": 1799999990, "end": 1799999980},
		"assets": {"large_image": "unknown-asset", "small_image": "http://plain.example/x.png", "large_url": "notaurl"},
		"party": {"id": "p", "size": [5, 2]},
		"buttons": [{"label": "", "url": "https://example.com"}, {"label": "`+strings.Repeat("x", 40)+`", "url": "https://example.com/b"}, {"label": "Third", "url": "https://example.com/c"}]
	}`, now)
	if !ok {
		t.Fatal("dropped")
	}
	if a.Name != "My Game" || a.Type != 0 || a.StatusDisplayType != nil {
		t.Errorf("name/type/display: %q %d %v", a.Name, a.Type, a.StatusDisplayType)
	}
	if utf8.RuneCountInString(a.Details) != maxActivityText || a.State != "line break" {
		t.Errorf("text: %d runes, state %q", utf8.RuneCountInString(a.Details), a.State)
	}
	if a.DetailsURL != "" || a.StateURL != "" {
		t.Errorf("non-web links must be dropped: %q %q", a.DetailsURL, a.StateURL)
	}
	// seconds become milliseconds, and an end before the start is dropped
	if a.Timestamps == nil || *a.Timestamps.Start != 1799999990000 || a.Timestamps.End != nil {
		t.Errorf("timestamps: %+v", a.Timestamps)
	}
	if a.Assets != nil && (a.Assets.LargeImage != "" || a.Assets.SmallImage != "" || a.Assets.LargeURL != "") {
		t.Errorf("unknown asset, plain http image and a bad link must go: %+v", a.Assets)
	}
	// a known application's own name wins over the one sent, as on Discord
	if named, _ := normalize(t, `{"name": "Something Else"}`, now); named.Name != "Music Player" {
		t.Errorf("known app name: %q", named.Name)
	}
	if a.Party == nil || a.Party.Size != nil {
		t.Errorf("an impossible party size is dropped: %+v", a.Party)
	}
	if len(a.Buttons) != 1 || utf8.RuneCountInString(a.Buttons[0].Label) != maxButtonLabel || a.Buttons[0].URL != "https://example.com/b" {
		t.Errorf("buttons: %+v", a.Buttons)
	}
}

func TestActivityImageKinds(t *testing.T) {
	now := time.Now()
	cases := map[string]string{
		"mp:external/abc123/https/i.imgur.com/x.png": "https://i.imgur.com/x.png",
		"mp:attachments/1/2/cover.png":               "https://media.discordapp.net/attachments/1/2/cover.png",
		"spotify:ab67616d0000b273e0b2fdb6":           "https://i.scdn.co/image/ab67616d0000b273e0b2fdb6",
		"youtube:dQw4w9WgXcQ":                        "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
		"twitch:SomeStreamer":                        "https://static-cdn.jtvnw.net/previews-ttv/live_user_somestreamer-640x360.jpg",
		"1111111111111111111":                        "https://cdn.test/app-assets/1/1111111111111111111.png",
		"spotify:../../etc":                          "",
		"youtube:bad id":                             "",
	}
	for key, want := range cases {
		b, _ := json.Marshal(map[string]any{"name": "x", "assets": map[string]string{"large_image": key}})
		a, ok := normalize(t, string(b), now)
		got := ""
		if a.Assets != nil && a.Assets.LargeImage != "" {
			got = strings.TrimPrefix(a.Assets.LargeImage, "/media/proxy/sig/")
		}
		if !ok || got != want {
			t.Errorf("%s -> %q (ok %v), want %q", key, got, ok, want)
		}
	}
}

func TestActivityWithoutAnyNameIsDropped(t *testing.T) {
	var in activityInput
	_ = json.Unmarshal([]byte(`{"details": "no name and no app"}`), &in)
	if _, ok := normalizeActivity(in, nil, time.Now(), proxied); ok {
		t.Error("an activity with no name and no known application must be dropped")
	}
	_ = json.Unmarshal([]byte(`{"name": "Named", "timestamps": {"start": 99999999999999999}}`), &in)
	a, ok := normalizeActivity(in, nil, time.Now(), proxied)
	if !ok || a.Timestamps != nil {
		t.Errorf("a timestamp far outside now is dropped, the activity kept: %v %+v", ok, a.Timestamps)
	}
}

// fakeApps answers lookups the way rpcapps would for one known application.
type fakeApps struct{}

func (fakeApps) Lookup(_ context.Context, id string) (*rpcapps.App, error) {
	if id != "123456789012345678" {
		return nil, rpcapps.ErrUnknown
	}
	return rpcapps.TestApp(id, "Music Player", "https://cdn.test/app-icons/"+id+"/icon.png", map[string]string{"album": "1111111111111111111"}, "https://cdn.test"), nil
}

// activitiesFor waits for a presence frame about uid that satisfies ok.
func (c *client) activitiesFor(uid ids.ID, ok func(d map[string]any) bool, timeout time.Duration) map[string]any {
	deadline := time.After(timeout)
	for {
		select {
		case f := <-c.frames:
			if f["t"] != "presence" {
				continue
			}
			d, _ := f["d"].(map[string]any)
			if d["user_id"] == uid.String() && ok(d) {
				return d
			}
		case <-deadline:
			return nil
		case <-c.done:
			return nil
		}
	}
}

func firstActivity(d map[string]any) map[string]any {
	list, _ := d["activities"].([]any)
	if len(list) == 0 {
		return nil
	}
	a, _ := list[0].(map[string]any)
	return a
}

func TestActivitiesReachCoMembersAndEndWithTheirConnection(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	a, tokA := e.user(t, "a")
	b, tokB := e.user(t, "b")
	_, tokC := e.user(t, "c")
	if _, _, err := e.st.CreateDM(ctx, a.ID, b.ID); err != nil {
		t.Fatal(err)
	}
	_ = e.st.SetRelationshipPair(ctx, a.ID, b.ID, "friend", "friend")

	cb := e.dial(t)
	if cb.identify(tokB) == nil {
		t.Fatal("ready b")
	}
	cc := e.dial(t)
	if cc.identify(tokC) == nil {
		t.Fatal("ready c")
	}
	ca := e.dial(t)
	if ca.identify(tokA) == nil {
		t.Fatal("ready a")
	}
	ca2 := e.dial(t) // a's phone, which says nothing about activities
	if ca2.identify(tokA) == nil {
		t.Fatal("ready a2")
	}

	ca.send("activities", map[string]any{"activities": []any{map[string]any{"name": "Some Game", "type": 0, "details": "In a match", "timestamps": map[string]any{"start": time.Now().Add(-time.Minute).UnixMilli()}}}})
	got := cb.activitiesFor(a.ID, func(d map[string]any) bool { return firstActivity(d) != nil }, 3*time.Second)
	if got == nil {
		t.Fatal("b never saw a's activity")
	}
	if act := firstActivity(got); act["name"] != "Some Game" || act["details"] != "In a match" || got["status"] != "online" {
		t.Errorf("activity as b sees it: %v", got)
	}
	if cc.activitiesFor(a.ID, func(map[string]any) bool { return true }, 400*time.Millisecond) != nil {
		t.Error("c shares nothing with a and must not see a's presence")
	}
	if p := e.hub.PresenceOf(ctx, a); len(p.Activities) != 1 {
		t.Errorf("presence for new viewers: %+v", p)
	}

	// invisible: others see a offline with nothing going on; a's own devices still see it
	ca.send("presence", map[string]any{"status": "invisible"})
	if cb.activitiesFor(a.ID, func(d map[string]any) bool { return d["status"] == "offline" && d["activities"] == nil }, 3*time.Second) == nil {
		t.Error("an invisible user's activity must not be shown")
	}
	if ca2.activitiesFor(a.ID, func(d map[string]any) bool { return d["status"] == "invisible" && firstActivity(d) != nil }, 3*time.Second) == nil {
		t.Error("a's own devices keep seeing the activity while invisible")
	}
	ca.send("presence", map[string]any{"status": "online"})
	if cb.activitiesFor(a.ID, func(d map[string]any) bool { return d["status"] == "online" && firstActivity(d) != nil }, 3*time.Second) == nil {
		t.Fatal("the activity comes back with the status")
	}

	// the connection that reported it goes: the activity goes, a stays online on the phone
	_ = ca.ws.Close(websocket.StatusNormalClosure, "")
	if cb.activitiesFor(a.ID, func(d map[string]any) bool { return d["status"] == "online" && d["activities"] == nil }, 3*time.Second) == nil {
		t.Error("closing the reporting connection must clear the activity")
	}
}

func TestActivitiesNameTheirAppAndProxyTheirArt(t *testing.T) {
	e := newEnv(t)
	e.hub.Apps = fakeApps{}
	e.hub.ProxyURL = func(u string) string { return "/media/proxy/x/" + u }
	ctx := context.Background()
	a, tokA := e.user(t, "a")
	b, tokB := e.user(t, "b")
	_ = e.st.SetRelationshipPair(ctx, a.ID, b.ID, "friend", "friend")
	cb := e.dial(t)
	cb.identify(tokB)
	ca := e.dial(t)
	ca.identify(tokA)

	start := time.Now().Add(-30 * time.Second).Unix()
	ca.send("activities", map[string]any{"activities": []any{map[string]any{
		"application_id": "123456789012345678", "type": 2, "details": "Song", "state": "Artist",
		"timestamps": map[string]any{"start": start, "end": start + 200},
		"assets":     map[string]any{"large_image": "album", "small_image": "spotify:abc123"},
	}}})
	got := cb.activitiesFor(a.ID, func(d map[string]any) bool { return firstActivity(d) != nil }, 3*time.Second)
	if got == nil {
		t.Fatal("no activity")
	}
	act := firstActivity(got)
	assets, _ := act["assets"].(map[string]any)
	ts, _ := act["timestamps"].(map[string]any)
	if act["name"] != "Music Player" || act["icon_url"] != "/media/proxy/x/https://cdn.test/app-icons/123456789012345678/icon.png" {
		t.Errorf("app name and icon: %v", act)
	}
	if assets["large_image"] != "/media/proxy/x/https://cdn.test/app-assets/123456789012345678/1111111111111111111.png" || assets["small_image"] != "/media/proxy/x/https://i.scdn.co/image/abc123" {
		t.Errorf("art: %v", assets)
	}
	if ts["start"] != float64(start*1000) || ts["end"] != float64((start+200)*1000) {
		t.Errorf("seconds must become milliseconds: %v", ts)
	}
}

func TestRapidActivityUpdatesSettleOnTheLast(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	a, tokA := e.user(t, "a")
	b, tokB := e.user(t, "b")
	_ = e.st.SetRelationshipPair(ctx, a.ID, b.ID, "friend", "friend")
	cb := e.dial(t)
	cb.identify(tokB)
	ca := e.dial(t)
	ca.identify(tokA)
	for i := 1; i <= 15; i++ {
		ca.send("activities", map[string]any{"activities": []any{map[string]any{"name": "Player", "details": "track " + strconv.Itoa(i)}}})
	}
	frames := 0
	last := ""
	deadline := time.After(9 * time.Second)
	for last != "track 15" {
		select {
		case f := <-cb.frames:
			d, _ := f["d"].(map[string]any)
			if f["t"] != "presence" || d["user_id"] != a.ID.String() {
				continue
			}
			if act := firstActivity(d); act != nil {
				frames++
				last, _ = act["details"].(string)
			}
		case <-deadline:
			t.Fatalf("never settled on the last update (last %q after %d frames)", last, frames)
		}
	}
	if frames > activityBurst+2 {
		t.Errorf("%d presence frames for 15 quick updates", frames)
	}
}

func TestMergeActivitiesPrefersTheDesktopAppsCopy(t *testing.T) {
	own := []Activity{{ApplicationID: "356875570916753438", Name: "Minecraft", Type: 0}, {Name: "Music Player", Type: 2}}
	linked := []Activity{
		{ApplicationID: "356875570916753438", Name: "Minecraft", Type: 0, Source: "discord"},
		{Name: "music player", Type: 2, Source: "discord"},
		{Name: "Spotify", Type: 2, Source: "discord"},
		{Name: "Music Player", Type: 3, Source: "discord"},
	}
	got := mergeActivities(own, linked)
	if len(got) != 4 || got[0].Source != "" || got[1].Source != "" || got[2].Name != "Spotify" || got[3].Type != 3 {
		t.Fatalf("merged: %+v", got)
	}
	if got := mergeActivities(nil, linked[2:3]); len(got) != 1 || got[0].Name != "Spotify" {
		t.Fatalf("linked alone: %+v", got)
	}
	five := []Activity{{Name: "a"}, {Name: "b"}, {Name: "c"}, {Name: "d"}, {Name: "e"}}
	if got := mergeActivities(five, linked[2:3]); len(got) != 5 {
		t.Fatalf("over the cap: %d", len(got))
	}
}

func TestNormalizeKeepsStreaming(t *testing.T) {
	a, ok := normalizeActivity(activityInput{Name: "Twitch", Type: 1, DetailsURL: "https://www.twitch.tv/someone"}, nil, time.Now(), nil)
	if !ok || a.Type != 1 || a.DetailsURL == "" {
		t.Fatalf("stream: %+v %v", a, ok)
	}
}

func TestRichPresenceWinsOverADetectedGame(t *testing.T) {
	asked := []string{}
	h := &Hub{IdentifyGame: func(_ context.Context, name, store, id, _ string) (string, string) {
		asked = append(asked, name+"/"+store+"/"+id)
		return "77", "/media/game/abcdef?size=s"
	}}
	raw := func(s string) json.RawMessage { return json.RawMessage(s) }
	list := h.resolveActivities([]json.RawMessage{
		raw(`{"name":"Hades II","type":0,"details":"In the Underworld"}`),
		raw(`{"name":"HADES II\u2122","type":0,"detected":true,"store":"steam","store_id":"1145350"}`),
		raw(`{"name":"Balatro","type":0,"detected":true,"store":"steam","store_id":"2379780"}`),
	}, nil, nil)
	if len(list) != 2 || list[0].Details == "" || list[1].Name != "Balatro" {
		t.Fatalf("the game's own report should replace its detected copy: %+v", list)
	}
	if list[1].GameID != "77" || list[1].IconURL == "" || !list[1].Detected {
		t.Fatalf("a detected game should carry its IGDB id and cover: %+v", list[1])
	}
	if len(asked) != 1 || asked[0] != "Balatro/steam/2379780" {
		t.Fatalf("only the game still shown is looked up, by its store's id: %v", asked)
	}

	// a game that carries on is not asked about again, and an empty allowance stops new ones
	again := h.resolveActivities([]json.RawMessage{
		raw(`{"name":"Balatro","type":0,"detected":true,"store":"steam","store_id":"2379780"}`),
		raw(`{"name":"Celeste","type":0,"detected":true}`),
	}, list, func() bool { return false })
	if len(asked) != 1 || again[0].GameID != "77" || again[1].GameID != "" {
		t.Fatalf("lookups past the allowance: %v %+v", asked, again)
	}

	// Discord's copy replaces a detected game only when it says more
	detected := Activity{Name: "Balatro", Detected: true}
	bare := Activity{Name: "balatro", Source: "discord"}
	rich := Activity{Name: "balatro", Source: "discord", Details: "Ante 8"}
	if got := mergeActivities([]Activity{detected}, []Activity{bare}); len(got) != 1 || !got[0].Detected {
		t.Fatalf("a bare Discord copy adds nothing: %+v", got)
	}
	if got := mergeActivities([]Activity{detected}, []Activity{rich}); len(got) != 1 || got[0].Details != "Ante 8" {
		t.Fatalf("Discord's fuller copy should win: %+v", got)
	}
}
