package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/voice"
)

func TestGuestSFUVideoChecksPermissionsOnPublishAndRestart(t *testing.T) {
	srv, ts := newTestServer(t)
	_, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	owner, _ := register(t, ts, "owner")
	view := createServer(t, ts, owner, "Guest video")
	sid := view["id"].(string)
	channel := channelNamed(view, "General")["id"].(string)
	epoch := createEpoch(t, ts, owner, sid)
	guest, _ := guestJoin(t, ts, guestLink(t, ts, owner, channel, epoch), "Guest")
	g := connectGW(t, ts, guest)
	g.op(t, "voice_state", map[string]any{"channel_id": channel, "sfu": true})
	if g.next("voice_state", 3*time.Second) == nil {
		t.Fatal("guest did not join")
	}
	res, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", guest, nil)
	if res.StatusCode != 200 {
		t.Fatalf("guest SFU session: %d %v", res.StatusCode, body)
	}
	session := body["session_id"].(string)
	publish := map[string]any{"session_id": session, "offer": offerSDP, "tracks": []map[string]any{{"mid": "1", "kind": "video"}}}
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", guest, publish); res.StatusCode != 200 {
		t.Fatalf("allowed video: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+channel+"/permissions/"+sid, owner,
		map[string]any{"type": "role", "allow": "0", "deny": strconv.FormatUint(uint64(perms.Stream), 10)}); res.StatusCode != 200 {
		t.Fatalf("deny video: %d %v", res.StatusCode, body)
	}
	for path, request := range map[string]any{
		"/api/v1/voice/sfu/publish": publish,
		"/api/v1/voice/sfu/restart": map[string]any{"session_id": session, "offer": offerSDP, "mid": "1", "kind": "video"},
	} {
		method := "POST"
		if strings.HasSuffix(path, "/restart") {
			method = "PUT"
		}
		res, body := do(t, ts, method, path, guest, request)
		if res.StatusCode != 409 || errCode(body) != "sfu_session_gone" {
			t.Errorf("guest bypassed video denial at %s: %d %v", path, res.StatusCode, body)
		}
	}
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", guest, nil)
	publish["session_id"] = body["session_id"]
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", guest, publish); res.StatusCode != 403 {
		t.Fatalf("fresh session bypassed Video denial: %d %v", res.StatusCode, body)
	}

}

// realtimeStandIn answers like Cloudflare Realtime and remembers what it was asked.
type realtimeStandIn struct {
	mu              sync.Mutex
	sessions        int
	mids            int
	closed          map[string]bool // sessions whose tracks were force-closed
	pulled          []string        // track names pulled
	refuseClose     bool
	refuseCloses    int
	beforeRemoteAdd func()
}

func newRealtimeStandIn(t *testing.T) (*realtimeStandIn, *httptest.Server) {
	t.Helper()
	rt := &realtimeStandIn{closed: map[string]bool{}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rt.mu.Lock()
		defer rt.mu.Unlock()
		raw, _ := io.ReadAll(r.Body)
		var body map[string]any
		_ = json.Unmarshal(raw, &body)
		w.Header().Set("Content-Type", "application/json")
		path := strings.TrimPrefix(r.URL.Path, "/apps/app/sessions/")
		switch {
		case r.URL.Path == "/apps/app/sessions/new":
			rt.sessions++
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]any{"sessionId": "sess-" + strconv.Itoa(rt.sessions)})
		case strings.HasSuffix(path, "/tracks/new"):
			tracks := []map[string]any{}
			for _, x := range body["tracks"].([]any) {
				t := x.(map[string]any)
				mid, _ := t["mid"].(string)
				if mid == "" {
					mid = strconv.Itoa(rt.mids)
					rt.mids++
				}
				if t["location"] == "remote" {
					if rt.beforeRemoteAdd != nil {
						rt.beforeRemoteAdd()
					}
					rt.pulled = append(rt.pulled, t["trackName"].(string))
				}
				tracks = append(tracks, map[string]any{"mid": mid, "trackName": t["trackName"], "sessionId": t["sessionId"]})
			}
			if _, offered := body["sessionDescription"]; offered {
				_ = json.NewEncoder(w).Encode(map[string]any{"sessionDescription": map[string]any{"type": "answer", "sdp": "v=0 answer"}, "tracks": tracks})
			} else {
				_ = json.NewEncoder(w).Encode(map[string]any{"requiresImmediateRenegotiation": true, "sessionDescription": map[string]any{"type": "offer", "sdp": "v=0 sfu offer"}, "tracks": tracks})
			}
		case strings.HasSuffix(path, "/renegotiate"):
			_ = json.NewEncoder(w).Encode(map[string]any{"sessionDescription": map[string]any{"type": "answer", "sdp": "v=0 answer"}})
		case strings.HasSuffix(path, "/tracks/close"):
			if rt.refuseClose || rt.refuseCloses > 0 {
				rt.refuseCloses--
				w.WriteHeader(http.StatusBadGateway)
				return
			}
			if body["force"] == true {
				rt.closed[strings.TrimSuffix(path, "/tracks/close")] = true
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"tracks": body["tracks"]})
		case r.Method == http.MethodGet:
			_ = json.NewEncoder(w).Encode(map[string]any{"tracks": []map[string]any{{"mid": "0"}}})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return rt, srv
}

func (rt *realtimeStandIn) wasClosed(session string) bool {
	for i := 0; i < 40; i++ {
		rt.mu.Lock()
		done := rt.closed[session]
		rt.mu.Unlock()
		if done {
			return true
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}

var offerSDP = map[string]any{"type": "offer", "sdp": "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:1\r\n"}

// nextVoiceState waits for a voice_state that matches, skipping earlier ones.
func nextVoiceState(g *gw, match func(map[string]any) bool) map[string]any {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ev := g.next("voice_state", time.Until(deadline))
		if ev == nil {
			return nil
		}
		if match(ev) {
			return ev
		}
	}
	return nil
}

func mediaCount(ev map[string]any) int {
	m, _ := ev["media"].(map[string]any)
	return len(m)
}

func TestCallsThroughTheSFU(t *testing.T) {
	srv, ts := newTestServer(t)
	rt, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)

	tokA, idA := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Calls")
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)
	connectGW(t, ts, tokC)

	if _, body := do(t, ts, "GET", "/api/v1/voice/ice", tokA, nil); body["sfu"] != true {
		t.Errorf("ice says calls go through the SFU: %v", body)
	}
	// carol is not in the call
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", tokC, nil); res.StatusCode != 409 || errCode(body) != "not_in_voice" {
		t.Errorf("a session needs a call: %d %v", res.StatusCode, body)
	}
	// a page still set up for peer-to-peer calls is turned away
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := ga.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "sfu_required" {
		t.Errorf("a join without the SFU: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	ga.next("voice_state", 3*time.Second)
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	gb.next("voice_state", 3*time.Second)
	ga.next("voice_state", 3*time.Second)

	_, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", tokA, nil)
	sessA, _ := body["session_id"].(string)
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", tokB, nil)
	sessB, _ := body["session_id"].(string)
	if sessA == "" || sessB == "" || sessA == sessB {
		t.Fatalf("sessions: %q %q", sessA, sessB)
	}

	// alice publishes her microphone and camera; bob hears which kinds she has
	res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", tokA, map[string]any{"session_id": sessA, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}, {"mid": "1", "kind": "video"}}})
	if res.StatusCode != 200 || body["answer"].(map[string]any)["type"] != "answer" {
		t.Fatalf("publish: %d %v", res.StatusCode, body)
	}
	ev := nextVoiceState(gb, func(e map[string]any) bool { return e["user_id"] == idA && mediaCount(e) > 0 })
	if ev == nil || mediaCount(ev) != 2 {
		t.Fatalf("the voice state lists what alice publishes: %v", ev)
	}
	if raw, _ := json.Marshal(ev); strings.Contains(string(raw), sessA) || strings.Contains(string(raw), "-audio-") {
		t.Errorf("session ids and track names stay on the server: %s", raw)
	}
	// someone else's session id is refused
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", tokB, map[string]any{"session_id": sessA, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}}); res.StatusCode != 409 || errCode(body) != "sfu_session_gone" {
		t.Errorf("another person's session: %d %v", res.StatusCode, body)
	}

	// bob pulls alice's tracks; what she does not publish, and carol, are refused
	res, body = do(t, ts, "POST", "/api/v1/voice/sfu/subscribe", tokB, map[string]any{"session_id": sessB, "tracks": []map[string]any{{"user_id": idA, "kind": "audio"}, {"user_id": idA, "kind": "video"}, {"user_id": idA, "kind": "screen"}, {"user_id": idC, "kind": "audio"}}})
	if res.StatusCode != 200 || body["renegotiate"] != true || body["offer"].(map[string]any)["type"] != "offer" {
		t.Fatalf("subscribe: %d %v", res.StatusCode, body)
	}
	results := map[string]string{}
	tags := map[string]any{}
	for _, x := range body["tracks"].([]any) {
		p := x.(map[string]any)
		key := p["user_id"].(string) + ":" + p["kind"].(string)
		if e, _ := p["error"].(string); e != "" {
			results[key] = e
		} else if p["mid"] != nil && p["mid"] != "" {
			results[key] = "ok"
			tags[p["kind"].(string)] = p["tag"]
		}
	}
	if results[idA+":audio"] != "ok" || results[idA+":video"] != "ok" || results[idA+":screen"] != "not_published" || results[idC+":audio"] != "not_in_call" {
		t.Errorf("subscribe results: %v", results)
	}
	// what was pulled carries the tags bob saw in alice's voice state
	if media := ev["media"].(map[string]any); tags["audio"] == nil || tags["audio"] != media["audio"] || tags["video"] != media["video"] || media["audio"] == media["video"] {
		t.Errorf("tags: pulled %v, voice state %v", tags, media)
	}
	rt.mu.Lock()
	pulled := len(rt.pulled)
	rt.mu.Unlock()
	if pulled != 2 {
		t.Errorf("only the allowed tracks reach the SFU: %d", pulled)
	}
	// nobody pulls their own tracks
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/subscribe", tokA, map[string]any{"session_id": sessA, "tracks": []map[string]any{{"user_id": idA, "kind": "audio"}}})
	if body["offer"] != nil || body["tracks"].([]any)[0].(map[string]any)["error"] != "not_in_call" {
		t.Errorf("subscribing to yourself: %v", body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/voice/sfu/renegotiate", tokB, map[string]any{"session_id": sessB, "description": map[string]any{"type": "answer", "sdp": "v=0 bob answer"}}); res.StatusCode != 200 {
		t.Errorf("renegotiate: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/voice/sfu/close", tokB, map[string]any{"session_id": sessB, "mids": []string{"0"}, "offer": offerSDP}); res.StatusCode != 200 {
		t.Errorf("close: %d %v", res.StatusCode, body)
	}

	// a mute toggle keeps alice's session and what she publishes
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "self_mute": true, "sfu": true})
	if ev := nextVoiceState(gb, func(e map[string]any) bool { return e["user_id"] == idA && e["self_mute"] == true }); ev == nil || mediaCount(ev) != 2 {
		t.Errorf("a toggle keeps the published tracks: %v", ev)
	}

	// bob starting a new session closes the old one at the SFU
	do(t, ts, "POST", "/api/v1/voice/sfu/session", tokB, nil)
	if !rt.wasClosed(sessB) {
		t.Error("a replaced session is closed")
	}
	// alice hanging up closes hers
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	if !rt.wasClosed(sessA) {
		t.Error("hanging up closes the session")
	}

	// near the end of the allowance, video is refused and then calls
	srv.Meter = realtime.NewMeter(srv.Store, 0)
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", tokB, nil)
	sessB2, _ := body["session_id"].(string)
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", tokB, map[string]any{"session_id": sessB2, "offer": offerSDP, "tracks": []map[string]any{{"mid": "1", "kind": "video"}}}); res.StatusCode != 403 || errCode(body) != "video_paused" {
		t.Errorf("video paused: %d %v", res.StatusCode, body)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	if ev := ga.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "calls_paused" {
		t.Errorf("a new call is refused at the limit: %v", ev)
	}
}

func TestSFUOffWithoutKeys(t *testing.T) {
	_, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	view := createServer(t, ts, tok, "Calls")
	voiceCh := channelNamed(view, "General")["id"].(string)
	g := connectGW(t, ts, tok)
	// a page that still thinks calls go through the SFU is sent to join again
	g.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	if ev := g.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "sfu_off" {
		t.Errorf("an SFU join without keys: %v", ev)
	}
	g.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := g.next("voice_state", 3*time.Second); ev == nil {
		t.Error("a peer-to-peer join goes ahead")
	}
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", tok, nil); res.StatusCode != 404 || errCode(body) != "sfu_off" {
		t.Errorf("sfu off: %d %v", res.StatusCode, body)
	}
	if _, body := do(t, ts, "GET", "/api/v1/voice/ice", tok, nil); body["sfu"] != false {
		t.Errorf("ice without the SFU: %v", body)
	}
}

func TestSFUIceRestart(t *testing.T) {
	srv, ts := newTestServer(t)
	_, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	tokA, _ := register(t, ts, "alice")
	view := createServer(t, ts, tokA, "Calls")
	voiceCh := channelNamed(view, "General")["id"].(string)
	ga := connectGW(t, ts, tokA)
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	ga.next("voice_state", 3*time.Second)
	_, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", tokA, nil)
	sess, _ := body["session_id"].(string)
	restart := func(session, mid, kind string) (int, map[string]any) {
		res, body := do(t, ts, "PUT", "/api/v1/voice/sfu/restart", tokA, map[string]any{"session_id": session, "offer": offerSDP, "mid": mid, "kind": kind})
		return res.StatusCode, body
	}
	// nothing published yet: nothing to carry the restart
	if code, body := restart(sess, "0", "audio"); code != 409 || errCode(body) != "not_published" {
		t.Errorf("restart before publishing: %d %v", code, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", tokA, map[string]any{"session_id": sess, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}}); res.StatusCode != 200 {
		t.Fatalf("publish: %d %v", res.StatusCode, body)
	}
	if code, body := restart(sess, "0", "audio"); code != 200 || body["answer"] == nil {
		t.Errorf("restart: %d %v", code, body)
	}
	if code, _ := restart(sess, "1", "audio"); code != 409 {
		t.Errorf("restart republished a different mid: %d", code)
	}

	if code, body := restart("another", "0", "audio"); code != 409 || errCode(body) != "sfu_session_gone" {
		t.Errorf("restart of another session: %d %v", code, body)
	}
	if code, _ := restart(sess, "", "audio"); code != 400 {
		t.Errorf("restart without a mid: %d", code)
	}
}

func TestCallSurvivesAGatewayReconnect(t *testing.T) {
	serial(t)
	srv, ts := newTestServer(t)
	rt, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	prev := voiceGrace
	// long enough for a reconnect under -race, which alone can take half a second
	voiceGrace = 1500 * time.Millisecond
	t.Cleanup(func() { voiceGrace = prev })

	tokA, idA := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Calls")
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	gb := connectGW(t, ts, tokB)
	ga := connectGW(t, ts, tokA)
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	ga.next("voice_state", 3*time.Second)
	gb.next("voice_state", 3*time.Second)
	_, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", tokA, nil)
	sess := body["session_id"].(string)
	do(t, ts, "POST", "/api/v1/voice/sfu/publish", tokA, map[string]any{"session_id": sess, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}})
	gb.next("voice_state", 3*time.Second)

	// alice's gateway drops; her call waits for her, media and all
	ga.close()
	if ev := gb.next("voice_state", 300*time.Millisecond); ev != nil && ev["channel_id"] == nil {
		t.Fatalf("alice left at once: %v", ev)
	}
	ga2 := connectGW(t, ts, tokA)
	ga2.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true, "resume": true, "sfu_session": sess, "self_mute": true})
	if ev := ga2.next("voice_resumed", 3*time.Second); ev == nil || ev["kept"] != true {
		t.Fatalf("resume not kept: %v", ev)
	}
	if ev := gb.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idA || ev["channel_id"] != voiceCh || ev["self_mute"] != true || ev["media"] == nil {
		t.Fatalf("bob's view after the resume: %v", ev)
	}
	// well past the grace that began when the gateway dropped
	time.Sleep(voiceGrace)
	if st, ok := srv.Voice.Get(mustParseID(t, idA)); !ok || st.SFUSession != sess {
		t.Fatalf("the grace running out ended a resumed call: %+v %v", st, ok)
	}
	if rt.wasClosed(sess) {
		t.Error("the resumed session was closed at the SFU")
	}

	// a resume with the wrong session starts over, and says so first
	ga2.close()
	ga3 := connectGW(t, ts, tokA)
	ga3.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true, "resume": true, "sfu_session": "not-it"})
	if ev := ga3.next("voice_resumed", 3*time.Second); ev == nil || ev["kept"] != false {
		t.Fatalf("a wrong session was kept: %v", ev)
	}
	// the join itself follows the message: give it a moment
	var st voice.State
	for end := time.Now().Add(2 * time.Second); time.Now().Before(end); time.Sleep(10 * time.Millisecond) {
		if st, _ = srv.Voice.Get(mustParseID(t, idA)); st.SFUSession == "" {
			break
		}
	}
	if st.SFUSession != "" {
		t.Errorf("the start-over kept the old session: %+v", st)
	}

	// nobody coming back: the call ends once the grace is over
	ga3.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "sfu": true})
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", tokA, nil)
	sess2 := body["session_id"].(string)
	ga3.close()
	deadline := time.Now().Add(voiceGrace + 3*time.Second)
	for {
		ev := gb.next("voice_state", time.Until(deadline))
		if ev == nil {
			t.Fatal("the call never ended after the grace")
		}
		if ev["user_id"] == idA && ev["channel_id"] == nil {
			break
		}
	}
	if !rt.wasClosed(sess2) {
		t.Error("the abandoned session was not closed at the SFU")
	}
}
