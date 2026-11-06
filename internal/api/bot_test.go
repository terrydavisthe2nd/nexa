package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"evilion.xyz/chat/internal/botproto"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/unfurl"
)

// The bot API's own behaviour is put through real bot libraries by
// tools/bot_e2e.py; these are the server's side of what people's apps do with
// apps: making them, adding them, the copies sealed for them.

// makeApp makes an app as its owner, returning it and its bot's token.
func makeApp(t *testing.T, ts *httptest.Server, token, name string) (map[string]any, string) {
	t.Helper()
	res, body := do(t, ts, "POST", "/api/v1/applications", token, map[string]any{"name": name})
	if res.StatusCode != 200 {
		t.Fatalf("make app: %d %v", res.StatusCode, body)
	}
	return body["application"].(map[string]any), body["token"].(string)
}

// botDo makes a request as a bot, the way bot libraries sign in.
func botDo(t *testing.T, ts *httptest.Server, method, path, botToken string, body any) (*http.Response, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, ts.URL+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Authorization", "Bot "+botToken)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := map[string]any{}
	var v any
	if json.Unmarshal(raw, &v) == nil {
		if m, ok := v.(map[string]any); ok {
			out = m
		} else {
			out["_list"] = v
		}
	}
	return res, out
}

// addBot adds an app's bot to a server, as its consent page does.
func addBot(t *testing.T, ts *httptest.Server, token, appID, serverID string) {
	t.Helper()
	q := url.Values{"client_id": {appID}, "scope": {"bot applications.commands"}, "permissions": {"8"}}
	res, body := do(t, ts, "POST", "/api/v1/oauth2/authorize", token, map[string]any{"query": q.Encode(), "guild_id": serverID})
	if res.StatusCode != 200 {
		t.Fatalf("add bot: %d %v", res.StatusCode, body)
	}
}

func TestBotSignsInWithItsToken(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	app, botToken := makeApp(t, ts, tok, "Lab Bot")
	res, me := botDo(t, ts, "GET", "/api/v10/users/@me", botToken, nil)
	if res.StatusCode != 200 || me["bot"] != true || me["id"] != botproto.OutString(snowID(t, app["id"].(string))) {
		t.Fatalf("users/@me as the bot: %d %v", res.StatusCode, me)
	}
	if res, body := botDo(t, ts, "GET", "/api/v10/users/@me", botToken+"x", nil); res.StatusCode != 401 || body["code"] != float64(0) {
		t.Fatalf("a wrong token: %d %v", res.StatusCode, body)
	}
}

func TestBareInstallLinkUsesTheAppsSettings(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	app, _ := makeApp(t, ts, tok, "Lab Bot")
	id := app["id"].(string)
	if res, body := do(t, ts, "PATCH", "/api/v1/applications/"+id, tok, map[string]any{"integration_types": map[string]any{
		"0": map[string]any{"oauth2_install_params": map[string]any{"scopes": []string{"bot", "applications.commands"}, "permissions": "2048"}},
	}}); res.StatusCode != 200 {
		t.Fatalf("install settings: %d %v", res.StatusCode, body)
	}
	res, info := do(t, ts, "GET", "/api/v1/oauth2/authorize?client_id="+id, tok, nil)
	if res.StatusCode != 200 {
		t.Fatalf("bare link: %d %v", res.StatusCode, info)
	}
	scopes, _ := json.Marshal(info["scopes"])
	if string(scopes) != `["bot","applications.commands"]` || info["permissions"] != "2048" || info["integration_type"] != float64(0) {
		t.Fatalf("the bare link should ask for the app's own settings: %v", info)
	}
	// an app only for accounts goes that way when the link does not say
	if res, body := do(t, ts, "PATCH", "/api/v1/applications/"+id, tok, map[string]any{"integration_types": map[string]any{
		"1": map[string]any{"oauth2_install_params": map[string]any{"scopes": []string{"applications.commands"}, "permissions": "0"}},
	}}); res.StatusCode != 200 {
		t.Fatalf("account only: %d %v", res.StatusCode, body)
	}
	res, info = do(t, ts, "GET", "/api/v1/oauth2/authorize?client_id="+id, tok, nil)
	if res.StatusCode != 200 || info["integration_type"] != float64(1) || info["type_chosen"] != false {
		t.Fatalf("an account-only app's bare link: %d %v", res.StatusCode, info)
	}
}

func TestCopiesGoOnlyToBotsAMessageIsAddressedTo(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	app, _ := makeApp(t, ts, tok, "Lab Bot")
	bot := app["id"].(string)
	addBot(t, ts, tok, bot, sid)
	copy := base64.StdEncoding.EncodeToString([]byte("sealed for the bot, opaque here"))

	epoch := createEpoch(t, ts, tok, sid)
	send := func(mentions []string) string {
		if mentions == nil {
			mentions = []string{}
		}
		res, body := sendMsg(t, ts, tok, general, sid, epoch, "hi", "", map[string]any{"mentions": mentions, "bot_copies": map[string]string{bot: copy}})
		if res.StatusCode != 200 && res.StatusCode != 201 {
			t.Fatalf("send: %d %v", res.StatusCode, body)
		}
		return body["id"].(string)
	}
	copies := func(mid string) int {
		got, err := srv.Store.BotCopies(context.Background(), snowID(t, bot), []ids.ID{snowID(t, mid)})
		if err != nil {
			t.Fatal(err)
		}
		return len(got)
	}
	// not addressed to it: the message goes, its copy does not
	if n := copies(send(nil)); n != 0 {
		t.Fatalf("a copy for a bot the message is not for was kept (%d)", n)
	}
	if n := copies(send([]string{bot})); n != 1 {
		t.Fatalf("a mention's copy was not kept (%d)", n)
	}
}

func TestOwnersSeeCommandsMadeForOneServer(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	app, botToken := makeApp(t, ts, tok, "Lab Bot")
	id := app["id"].(string)
	addBot(t, ts, tok, id, sid)
	appOut := botproto.OutString(snowID(t, id))
	guildOut := botproto.OutString(snowID(t, sid))
	if res, body := botDo(t, ts, "PUT", "/api/v10/applications/"+appOut+"/guilds/"+guildOut+"/commands", botToken, []map[string]any{{"name": "ping", "description": "Pong"}}); res.StatusCode != 200 {
		t.Fatalf("server commands: %d %v", res.StatusCode, body)
	}
	if res, body := botDo(t, ts, "PUT", "/api/v10/applications/"+appOut+"/commands", botToken, []map[string]any{{"name": "help", "description": "Helps"}}); res.StatusCode != 200 {
		t.Fatalf("global commands: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "GET", "/api/v1/applications/"+id+"/commands", tok, nil)
	if res.StatusCode != 200 {
		t.Fatalf("owner's commands: %d %v", res.StatusCode, body)
	}
	list := body["_list"].([]any)
	seen := map[string]map[string]any{}
	for _, c := range list {
		cm := c.(map[string]any)
		seen[cm["name"].(string)] = cm
	}
	if seen["help"] == nil || seen["help"]["guild_id"] != nil {
		t.Fatalf("the global command: %v", seen["help"])
	}
	if seen["ping"] == nil || seen["ping"]["guild_id"] != sid || seen["ping"]["guild_name"] != "Lab" {
		t.Fatalf("the server's command: %v", seen["ping"])
	}

	// the owner removes one by hand, and nobody else can
	bob, _ := register(t, ts, "bob")
	if res, _ := do(t, ts, "DELETE", "/api/v1/applications/"+id+"/commands/"+seen["help"]["id"].(string), bob, nil); res.StatusCode != 404 {
		t.Fatalf("someone else removed a command: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "DELETE", "/api/v1/applications/"+id+"/commands/"+seen["help"]["id"].(string), tok, nil); res.StatusCode != 204 {
		t.Fatalf("remove one: %d %v", res.StatusCode, body)
	}
	_, body = do(t, ts, "GET", "/api/v1/applications/"+id+"/commands", tok, nil)
	if l := body["_list"].([]any); len(l) != 1 || l[0].(map[string]any)["name"] != "ping" {
		t.Fatalf("after removing one: %v", l)
	}
	// and then all of them, the server's own included
	botDo(t, ts, "PUT", "/api/v10/applications/"+appOut+"/commands", botToken, []map[string]any{{"name": "help", "description": "Helps"}})
	if res, body := do(t, ts, "DELETE", "/api/v1/applications/"+id+"/commands", tok, nil); res.StatusCode != 204 {
		t.Fatalf("remove all: %d %v", res.StatusCode, body)
	}
	_, body = do(t, ts, "GET", "/api/v1/applications/"+id+"/commands", tok, nil)
	if l := body["_list"].([]any); len(l) != 0 {
		t.Fatalf("after removing all: %v", l)
	}
}

func TestBotsThatReadEverythingAreListed(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	quiet, _ := makeApp(t, ts, tok, "Quiet Bot")
	reader, _ := makeApp(t, ts, tok, "Reader Bot")
	addBot(t, ts, tok, quiet["id"].(string), sid)
	addBot(t, ts, tok, reader["id"].(string), sid)
	if res, body := do(t, ts, "PATCH", "/api/v1/applications/"+reader["id"].(string), tok, map[string]any{"intents": map[string]any{"message_content": true}}); res.StatusCode != 200 {
		t.Fatalf("message content: %d %v", res.StatusCode, body)
	}
	got, err := srv.Store.ReadingBotIDs(context.Background(), snowID(t, sid))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].String() != reader["id"].(string) {
		t.Fatalf("reading bots: %v", got)
	}
}

func TestRefreshTokenRenewsAnAccessTokenThatRanOut(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	app, _ := makeApp(t, ts, tok, "Lab Bot")
	id := app["id"].(string)
	clientID := botproto.OutString(snowID(t, id))
	const back = "https://example.com/back"
	if res, body := do(t, ts, "PATCH", "/api/v1/applications/"+id, tok, map[string]any{"redirect_uris": []string{back}}); res.StatusCode != 200 {
		t.Fatalf("redirect: %d %v", res.StatusCode, body)
	}
	res, made := do(t, ts, "POST", "/api/v1/applications/"+id+"/secret/reset", tok, map[string]any{"auth_key": b64(key32)})
	if res.StatusCode != 200 {
		t.Fatalf("secret: %d %v", res.StatusCode, made)
	}
	q := url.Values{"client_id": {clientID}, "response_type": {"code"}, "redirect_uri": {back}, "scope": {"identify"}}
	res, granted := do(t, ts, "POST", "/api/v1/oauth2/authorize", tok, map[string]any{"query": q.Encode()})
	if res.StatusCode != 200 {
		t.Fatalf("authorize: %d %v", res.StatusCode, granted)
	}
	loc, err := url.Parse(granted["location"].(string))
	if err != nil {
		t.Fatal(err)
	}
	// what an OAuth2 client sends to the token endpoint
	token := func(form url.Values) (int, map[string]any) {
		t.Helper()
		form.Set("client_id", clientID)
		form.Set("client_secret", made["secret"].(string))
		res, err := http.PostForm(ts.URL+"/api/v10/oauth2/token", form)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		out := map[string]any{}
		_ = json.NewDecoder(res.Body).Decode(&out)
		return res.StatusCode, out
	}
	me := func(access string) int {
		t.Helper()
		req, _ := http.NewRequest("GET", ts.URL+"/api/v10/users/@me", nil)
		req.Header.Set("Authorization", "Bearer "+access)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	st, first := token(url.Values{"grant_type": {"authorization_code"}, "code": {loc.Query().Get("code")}, "redirect_uri": {back}})
	if st != 200 || first["refresh_token"] == nil || me(first["access_token"].(string)) != 200 {
		t.Fatalf("code for a token: %d %v", st, first)
	}
	// the access token runs out, and the sweep that follows keeps the refresh token
	ctx := context.Background()
	past := time.Now().Add(-time.Hour).UnixMilli()
	if _, err := srv.Store.DB().W.Exec(`UPDATE oauth_tokens SET expires_at = ?`, past); err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.SweepInteractions(ctx, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if code := me(first["access_token"].(string)); code != 401 {
		t.Fatalf("an access token that ran out still works: %d", code)
	}
	st, second := token(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {first["refresh_token"].(string)}})
	if st != 200 || me(second["access_token"].(string)) != 200 {
		t.Fatalf("renewing: %d %v", st, second)
	}
	// a refresh token is spent once used
	if st, body := token(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {first["refresh_token"].(string)}}); st != 400 || body["error"] != "invalid_grant" {
		t.Fatalf("a spent refresh token: %d %v", st, body)
	}
	// and renews nothing past its own time
	if _, err := srv.Store.DB().W.Exec(`UPDATE oauth_tokens SET refresh_expires_at = ?`, past); err != nil {
		t.Fatal(err)
	}
	if st, body := token(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {second["refresh_token"].(string)}}); st != 400 || body["error"] != "invalid_grant" {
		t.Fatalf("a refresh token past its time: %d %v", st, body)
	}
}

// A bot that restarts starts a new session while its old one waits for a
// resume that never comes: what people do with it goes to the connected one.
func TestInteractionsGoToAConnectedSession(t *testing.T) {
	srv, _ := newTestServer(t)
	bot := &store.User{ID: ids.New()}
	waiting := &botSession{s: srv, id: "waiting", bot: bot}
	live := &botSession{s: srv, id: "live", bot: bot, sock: newBotSocket(nil, &botproto.Codec{})}
	srv.bots.add(waiting)
	srv.bots.add(live)
	// sessions are kept in a map, so every try has to find the live one
	for i := 0; i < 20; i++ {
		if !srv.deliverInteraction(bot.ID, nil, map[string]any{"n": i}) {
			t.Fatal("not delivered")
		}
	}
	if len(waiting.replay) != 0 || len(live.replay) != 20 {
		t.Fatalf("the waiting session got %d, the live one %d", len(waiting.replay), len(live.replay))
	}
	// with none connected, one waiting for its resume keeps it
	live.mu.Lock()
	live.sock = nil
	live.mu.Unlock()
	if !srv.deliverInteraction(bot.ID, nil, map[string]any{}) || len(waiting.replay)+len(live.replay) != 21 {
		t.Fatal("an interaction with nobody connected is lost")
	}
}

func snowID(t *testing.T, s string) ids.ID {
	t.Helper()
	id, err := ids.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// Libraries such as oceanic.js answer "thinking" and then send a follow-up;
// the protocol fills in the thinking message with that first follow-up
// rather than leaving it thinking beside a second message.
func TestFirstFollowupFillsInTheThinkingMessage(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, uid := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	app, botToken := makeApp(t, ts, tok, "Lab Bot")
	appID := snowID(t, app["id"].(string))
	addBot(t, ts, tok, app["id"].(string), sid)

	ask := func() (string, string) {
		token := "token-" + ids.New().String()
		guild, channel := snowID(t, sid), snowID(t, general)
		it := &store.Interaction{ID: ids.New(), ApplicationID: appID, Type: 2, TokenHash: tokenHash(token), UserID: snowID(t, uid),
			GuildID: &guild, ChannelID: &channel, Data: json.RawMessage(`{"type":2,"data":{"id":"1","name":"ping","type":1}}`),
			CreatedAt: time.Now().UnixMilli(), ExpiresAt: time.Now().Add(interactionLife).UnixMilli()}
		if err := srv.Store.CreateInteraction(context.Background(), it); err != nil {
			t.Fatal(err)
		}
		return botproto.OutString(it.ID), token
	}
	hook := "/api/v10/webhooks/" + botproto.OutString(appID) + "/"
	messages := func() []any {
		res, body := botDo(t, ts, "GET", "/api/v10/channels/"+botproto.OutString(snowID(t, general))+"/messages", botToken, nil)
		if res.StatusCode != 200 {
			t.Fatalf("messages: %d %v", res.StatusCode, body)
		}
		var out []any
		for _, m := range body["_list"].([]any) {
			if _, answer := m.(map[string]any)["interaction_metadata"]; answer {
				out = append(out, m)
			}
		}
		return out
	}

	id, token := ask()
	if res, body := botDo(t, ts, "POST", "/api/v10/interactions/"+id+"/"+token+"/callback", botToken, map[string]any{"type": 5}); res.StatusCode != 204 {
		t.Fatalf("defer: %d %v", res.StatusCode, body)
	}
	res, first := botDo(t, ts, "POST", hook+token, botToken, map[string]any{"content": "pong", "flags": 64})
	if res.StatusCode != 200 || first["content"] != "pong" {
		t.Fatalf("first follow-up: %d %v", res.StatusCode, first)
	}
	got := messages()
	if len(got) != 1 {
		t.Fatalf("a deferred answer and its first follow-up made %d messages: %v", len(got), got)
	}
	m := got[0].(map[string]any)
	if m["content"] != "pong" || int64(m["flags"].(float64))&(64|128) != 0 {
		t.Fatalf("the thinking message should now say pong, in the channel: %v", m)
	}
	// later follow-ups are messages of their own
	if res, body := botDo(t, ts, "POST", hook+token, botToken, map[string]any{"content": "again"}); res.StatusCode != 200 || body["id"] == m["id"] {
		t.Fatalf("second follow-up: %d %v", res.StatusCode, body)
	}
	if n := len(messages()); n != 2 {
		t.Fatalf("after a second follow-up there are %d messages", n)
	}
}

// The answer line has the options someone used, while the bot's own
// interaction metadata stays unchanged.
func TestAppAnswerKeepsCommandOptionsForPeople(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, uid := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	app, botToken := makeApp(t, ts, tok, "Lab Bot")
	appID := snowID(t, app["id"].(string))
	addBot(t, ts, tok, app["id"].(string), sid)

	itID := ids.New()
	token := "caption-token-" + itID.String()
	guild, channel := snowID(t, sid), snowID(t, general)
	data := dInteraction{
		ID: snow(itID), ApplicationID: snow(appID), Type: itCommand,
		AuthorizingIntegrationOwners: map[string]snow{"0": snow(guild)},
		Data: dCommandData{ID: 1, Name: "caption", Type: cmdChatInput, Options: []dOptionValue{
			{Name: "text", Type: optString, Value: "hello there"},
			{Name: "times", Type: optInteger, Value: json.Number("0")},
			{Name: "quiet", Type: optBoolean, Value: false},
			{Name: "format", Type: optSubcommand, Options: []dOptionValue{{Name: "style", Type: optString, Value: "short"}}},
			{Name: "who", Type: optUser, Value: botproto.OutString(snowID(t, uid))},
		}},
	}
	raw, err := json.Marshal(storedInteraction{dInteraction: data})
	if err != nil {
		t.Fatal(err)
	}
	it := &store.Interaction{ID: itID, ApplicationID: appID, Type: itCommand, TokenHash: tokenHash(token), UserID: snowID(t, uid), GuildID: &guild, ChannelID: &channel,
		Data: raw, CreatedAt: time.Now().UnixMilli(), ExpiresAt: time.Now().Add(interactionLife).UnixMilli()}
	if err := srv.Store.CreateInteraction(context.Background(), it); err != nil {
		t.Fatal(err)
	}
	path := "/api/v10/interactions/" + botproto.OutString(itID) + "/" + token + "/callback"
	if res, body := botDo(t, ts, "POST", path, botToken, map[string]any{"type": cbMessage, "data": map[string]any{"content": "captioned"}}); res.StatusCode != http.StatusNoContent {
		t.Fatalf("answer command: %d %v", res.StatusCode, body)
	}

	res, page := do(t, ts, "GET", "/api/v1/channels/"+general+"/messages?limit=50", tok, nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("history: %d %v", res.StatusCode, page)
	}
	for _, row := range page["_list"].([]any) {
		message := row.(map[string]any)
		hook, _ := message["webhook"].(map[string]any)
		if hook == nil || hook["content"] != "captioned" {
			continue
		}
		interaction := hook["interaction"].(map[string]any)
		options := interaction["options"].([]any)
		if len(options) != 5 || options[4].(map[string]any)["value"] != uid {
			t.Fatalf("a person is kept by their own id, for the apps to name: %v", options)
		}
		if options[0].(map[string]any)["value"] != "hello there" || options[1].(map[string]any)["value"] != float64(0) || options[2].(map[string]any)["value"] != false {
			t.Fatalf("the command's values should be kept for people: %v", interaction)
		}
		nested := options[3].(map[string]any)["options"].([]any)
		if nested[0].(map[string]any)["value"] != "short" {
			t.Fatalf("subcommand options should be kept too: %v", interaction)
		}
		botRes, botPage := botDo(t, ts, "GET", "/api/v10/channels/"+botproto.OutString(channel)+"/messages?limit=50", botToken, nil)
		if botRes.StatusCode != http.StatusOK {
			t.Fatalf("bot history: %d %v", botRes.StatusCode, botPage)
		}
		for _, botRow := range botPage["_list"].([]any) {
			botMessage := botRow.(map[string]any)
			if botMessage["content"] != "captioned" {
				continue
			}
			metadata := botMessage["interaction_metadata"].(map[string]any)
			if _, leaked := metadata["options"]; leaked {
				t.Fatalf("command options are client-only interaction metadata: %v", metadata)
			}
			return
		}
		t.Fatal("the bot did not get its command answer")
	}
	t.Fatalf("the command answer was not in history: %v", page)
}

// A link a bot sends gets a preview as a person's would, made by the server
// (the bot has no app of a person's to fetch it), for the people reading and
// for bots, as the protocol's link embed.
func TestBotLinksGetPreviews(t *testing.T) {
	srv, ts := newTestServer(t)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(`<html><head><meta property="og:title" content="A Bird"><meta property="og:image" content="https://pics.example.net/bird.jpg"></head></html>`))
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}

	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Lab")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	app, botToken := makeApp(t, ts, tok, "Lab Bot")
	addBot(t, ts, tok, app["id"].(string), sid)
	ch := botproto.OutString(snowID(t, general))
	res, sent := botDo(t, ts, "POST", "/api/v10/channels/"+ch+"/messages", botToken, map[string]any{"content": "a bird: http://pics.example.net/birds/1"})
	if res.StatusCode != 200 {
		t.Fatalf("send: %d %v", res.StatusCode, sent)
	}
	id, err := botproto.ParseID(sent["id"].(string))
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		_, m := do(t, ts, "GET", "/api/v1/channels/"+general+"/messages/"+id.String(), tok, nil)
		hook, _ := m["webhook"].(map[string]any)
		if links, _ := hook["link_embeds"].([]any); len(links) == 1 {
			if links[0].(map[string]any)["title"] != "A Bird" {
				t.Fatalf("the preview: %v", links[0])
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the bot's link got no preview: %v", m)
		}
		time.Sleep(50 * time.Millisecond)
	}
	_, got := botDo(t, ts, "GET", "/api/v10/channels/"+ch+"/messages/"+sent["id"].(string), botToken, nil)
	embeds, _ := got["embeds"].([]any)
	if len(embeds) != 1 || embeds[0].(map[string]any)["title"] != "A Bird" || embeds[0].(map[string]any)["thumbnail"] == nil {
		t.Fatalf("the bot should see the preview as a link embed with its picture: %v", got["embeds"])
	}
}

// The staff see apps in a Bots tab of their own, and their bots are not
// listed with the people.
func TestStaffListAppsApartFromPeople(t *testing.T) {
	_, ts := newTestServer(t)
	admin, _ := register(t, ts, "alice") // the first account is Nexa's admin
	tok, _ := register(t, ts, "bob")
	app, _ := makeApp(t, ts, tok, "Lab Bot")
	res, body := do(t, ts, "GET", "/api/v1/moderation/apps", admin, nil)
	if res.StatusCode != 200 {
		t.Fatalf("apps: %d %v", res.StatusCode, body)
	}
	apps, _ := body["apps"].([]any)
	if len(apps) != 1 || apps[0].(map[string]any)["id"] != app["id"] || body["total"] != float64(1) {
		t.Fatalf("the staff should see bob's app: %v", body)
	}
	if people, _ := body["people"].(map[string]any); people[apps[0].(map[string]any)["owner_id"].(string)] == nil {
		t.Fatalf("its owner comes along: %v", body["people"])
	}
	_, users := do(t, ts, "GET", "/api/v1/moderation/users", admin, nil)
	for _, u := range users["users"].([]any) {
		if u.(map[string]any)["id"] == app["id"] {
			t.Fatal("the app's bot is listed with the people")
		}
	}
	if res, _ := do(t, ts, "GET", "/api/v1/moderation/apps", tok, nil); res.StatusCode != 403 {
		t.Fatalf("someone who is not staff: %d", res.StatusCode)
	}
}

func TestBulkOverwriteCountsOnlyTheNewList(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	app, botToken := makeApp(t, ts, tok, "Swap Bot")
	appOut := botproto.OutString(snowID(t, app["id"].(string)))
	path := "/api/v10/applications/" + appOut + "/commands"
	list := func(prefix string, n int) []map[string]any {
		out := make([]map[string]any, n)
		for i := range out {
			out[i] = map[string]any{"name": fmt.Sprintf("%s%d", prefix, i), "description": "A command"}
		}
		return out
	}
	if res, body := botDo(t, ts, "PUT", path, botToken, list("old", 90)); res.StatusCode != 200 {
		t.Fatalf("first overwrite: %d %v", res.StatusCode, body)
	}
	if res, body := botDo(t, ts, "PUT", path, botToken, list("new", 90)); res.StatusCode != 200 {
		t.Fatalf("second overwrite: %d %v", res.StatusCode, body)
	}
	if res, _ := botDo(t, ts, "PUT", path, botToken, list("big", 101)); res.StatusCode != 400 {
		t.Fatalf("101 commands: %d, want 400", res.StatusCode)
	}
}

// Every app icon and cover once answered 500: SwapImage refused the columns.
func TestAppPicturesSaveAndClear(t *testing.T) {
	_, ts := newTestServer(t)
	token, _ := register(t, ts, "appowner")
	app, _ := makeApp(t, ts, token, "Pictured")
	path := "/api/v1/applications/" + app["id"].(string)
	uri := "data:image/png;base64," + base64.StdEncoding.EncodeToString(pngBytes(64, 64))
	for _, f := range []struct{ field, col string }{{"icon", "icon_id"}, {"cover_image", "cover_id"}} {
		res, body := do(t, ts, "PATCH", path, token, map[string]any{f.field: uri})
		if res.StatusCode != 200 {
			t.Fatalf("set %s: %d %v", f.field, res.StatusCode, body)
		}
		if _, got := do(t, ts, "GET", path, token, nil); got[f.col] == nil {
			t.Fatalf("%s not saved: %v", f.col, got)
		}
		if res, body = do(t, ts, "PATCH", path, token, map[string]any{f.field: nil}); res.StatusCode != 200 {
			t.Fatalf("clear %s: %d %v", f.field, res.StatusCode, body)
		}
		if _, got := do(t, ts, "GET", path, token, nil); got[f.col] != nil {
			t.Fatalf("%s not cleared: %v", f.col, got)
		}
	}
}
