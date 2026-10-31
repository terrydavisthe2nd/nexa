"""Real calls through Cloudflare Realtime SFU on a throwaway local chatd:
three people (one on a phone viewport, relay-only) hear each other, a camera
shows and hides without pulling again, a share is pulled only while watched,
someone in another channel cannot pull from the call, and every browser holds
one connection, to Cloudflare.

Needs REALTIME_APP_ID/SECRET and TURN_KEY_ID/API_TOKEN in chatd.env (commented
lines count). Only those four are passed to the test server; nothing is printed.
Uses a few minutes of real SFU traffic (tens of MB). Like e2e.py it tests
bin/chatd and web/dist unless E2E_CHATD / E2E_WEB_DIST say otherwise. Run it
under a memory watchdog; three browsers peak around 2-3 GB.
"""
import json, os, re, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
env = {}
for line in open(ROOT / "chatd.env"):
    m = re.match(r"#?\s*([A-Z_]+)=(.*)", line.strip())
    if m and m.group(2):
        env[m.group(1)] = m.group(2)
for k in ("REALTIME_APP_ID", "REALTIME_APP_SECRET", "TURN_KEY_ID", "TURN_API_TOKEN"):
    if k not in env:
        sys.exit(f"{k} is not in chatd.env")
    os.environ[k] = env[k]
sys.path.insert(0, str(ROOT / "tools"))
sys.argv = [sys.argv[0]]
from e2e import Server, Session  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

OUT = ROOT / "tools/out/sfu-call"
OUT.mkdir(parents=True, exist_ok=True)
failures = []

PATCH = """
(() => {
  const Native = window.RTCPeerConnection;
  window.__pcs = [];
  function Patched(config, ...rest) {
    const cfg = Object.assign({}, config || {});
    if (window.__relayOnly) cfg.iceTransportPolicy = "relay";
    const pc = new Native(cfg, ...rest);
    window.__pcs.push(pc);
    return pc;
  }
  Patched.prototype = Native.prototype;
  Object.setPrototypeOf(Patched, Native);
  window.RTCPeerConnection = Patched;
  const NativeWorker = window.Worker;
  function PatchedWorker(url, opts) {
    const w = new NativeWorker(url, opts);
    if (String(url).includes("frameCrypto")) w.addEventListener("message", (e) => { if (e.data && e.data.type === "stats") window.__frameStats = e.data; });
    return w;
  }
  PatchedWorker.prototype = NativeWorker.prototype;
  window.Worker = PatchedWorker;
  navigator.mediaDevices.getDisplayMedia = async () => {
    const c = document.createElement('canvas'); c.width = 1280; c.height = 720;
    const g = c.getContext('2d'); let n = 0;
    setInterval(() => { g.fillStyle = '#223'; g.fillRect(0, 0, 1280, 720); g.fillStyle = '#5865f2'; g.fillRect((n += 8) % 1280, 200, 240, 240); g.fillStyle = '#fff'; g.font = '64px sans-serif'; g.fillText('shared screen', 420, 600); }, 66);
    const stream = c.captureStream(15);
    // the share's sound: a steady tone, as a video or game would play
    const ctx = new AudioContext();
    const osc = ctx.createOscillator(); osc.frequency.value = 440;
    const gain = ctx.createGain(); gain.gain.value = 0.3;
    const dest = ctx.createMediaStreamDestination();
    osc.connect(gain).connect(dest); osc.start();
    stream.addTrack(dest.stream.getAudioTracks()[0]);
    return stream;
  };
})();
"""

STATS = """
async () => {
  const pcs = [];
  for (const pc of window.__pcs || []) {
    if (pc.connectionState === "closed") continue;
    const stats = await pc.getStats();
    const byId = new Map();
    stats.forEach((s) => byId.set(s.id, s));
    const r = { state: pc.connectionState, inAudio: [], inVideo: [], outAudio: 0, outVideo: 0, pair: null };
    stats.forEach((s) => {
      if (s.type === "inbound-rtp" && s.kind === "audio") r.inAudio.push({ mid: s.mid, energy: s.totalAudioEnergy || 0, packets: s.packetsReceived || 0 });
      if (s.type === "inbound-rtp" && s.kind === "video") r.inVideo.push({ mid: s.mid, frames: s.framesDecoded || 0, width: s.frameWidth || 0 });
      if (s.type === "outbound-rtp" && s.kind === "audio") r.outAudio += s.packetsSent || 0;
      if (s.type === "outbound-rtp" && s.kind === "video") r.outVideo += s.framesEncoded || 0;
      if (s.type === "transport" && s.selectedCandidatePairId) {
        const p = byId.get(s.selectedCandidatePairId);
        const local = p && byId.get(p.localCandidateId);
        const remote = p && byId.get(p.remoteCandidateId);
        if (p) r.pair = { local: local && local.candidateType, relayProtocol: local && local.relayProtocol, remote: remote && remote.address, remoteType: remote && remote.candidateType, rtt: p.currentRoundTripTime };
      }
    });
    pcs.push(r);
  }
  return { pcs, frames: window.__frameStats || null, voices: document.querySelectorAll("audio[data-voice-peer]").length };
}
"""


def check(cond, what):
    print(("  ok   " if cond else "  FAIL ") + what, flush=True)
    if not cond:
        failures.append(what)


def api(page, method, path, body=None):
    return page.evaluate(
        """async ([method, path, body]) => {
          const res = await fetch('/api/v1' + path, { method, headers: { Authorization: 'Bearer ' + localStorage.getItem('ec.token'), 'Content-Type': 'application/json' }, body: body == null ? undefined : JSON.stringify(body) });
          let json = null; try { json = await res.json(); } catch {}
          return { status: res.status, body: json };
        }""",
        [method, path, body],
    )


def stats(sess):
    return sess.page.evaluate(STATS)


PUMP = []  # a page to wait on: the sync API only delivers events (requests seen) while it is called


def pause(seconds):
    if PUMP:
        PUMP[0].wait_for_timeout(int(seconds * 1000))
    else:
        time.sleep(seconds)


def wait_until(fn, timeout, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v:
            return v
        pause(step)
    return fn()


LEVEL = """
async ([userId, attr]) => {
  // what the call actually plays for someone: the stream on its element, measured like the speaking ring,
  // and whether the call lets it through (sound is held silent until its frames are known to open)
  const el = document.querySelector(`audio[${attr || 'data-voice-peer'}='${userId}']`);
  if (!el || !el.srcObject) return { el: false };
  const held = el.getAttribute('data-held');
  const ctx = new AudioContext();
  const src = ctx.createMediaStreamSource(el.srcObject);
  const an = ctx.createAnalyser(); an.fftSize = 2048; src.connect(an);
  const buf = new Float32Array(an.fftSize);
  let peak = 0;
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 100));
    an.getFloatTimeDomainData(buf);
    let sum = 0; for (const v of buf) sum += v * v;
    peak = Math.max(peak, Math.sqrt(sum / buf.length));
  }
  src.disconnect(); await ctx.close();
  return { el: true, peak, ctx: ctx.state, held };
}
"""


def level(sess, user_id, attr="data-voice-peer"):
    return sess.page.evaluate(LEVEL, [user_id, attr])


def hears(sess, user_id, timeout=40, attr="data-voice-peer"):
    """True once what this browser plays for user_id has sound in it (the fake microphone beeps) and it is not held silent."""
    def ok():
        lv = level(sess, user_id, attr)
        return (lv.get("peak") or 0) > 0.01 and lv.get("held") != "true"
    return wait_until(ok, timeout, 0.5)


def join(sess, name, phone=False):
    p = sess.page
    voice = p.locator(f"[data-channel]:has(span:text-is('{name}')) button").first
    if phone:
        box = voice.bounding_box()
        if not box or box["x"] < 0 or box["x"] >= 390:
            p.click("button[aria-label='Open Navigation']")
            time.sleep(0.6)
    voice.click()
    p.wait_for_selector("[data-testid=voice-status]", timeout=30000)


srv = Server()
print("chatd dir:", srv.dir, flush=True)
errors = []
try:
    with sync_playwright() as pw:
        b = pw.chromium.launch(args=["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--autoplay-policy=no-user-gesture-required"])
        alice = Session(b, srv.base, "alice", OUT)
        bob = Session(b, srv.base, "bob", OUT)
        carol = Session(b, srv.base, "carol", OUT, viewport=(390, 844))
        sfu = {"alice": [], "bob": [], "carol": []}
        chatter = []
        for sess in (alice, bob, carol):
            sess.page.add_init_script(PATCH)
            sess.page.on("request", lambda r, n=sess.name: sfu[n].append((time.time(), r.method, r.url.split("/api/v1")[1], r.post_data or "")) if "/voice/sfu/" in r.url else None)
            sess.page.on("console", lambda m, n=sess.name: chatter.append(f"{n} {m.type}: {m.text[:300]}") if m.type in ("warning", "error") else None)
        carol.page.add_init_script("window.__relayOnly = true")
        PUMP.append(alice.page)
        alice.register("Alice"); bob.register("Bob"); carol.register("Carol")
        # The fake microphone is a steady tone, which noise suppression (RNNoise
        # and its speech gate) rightly takes for noise and silences, so these
        # calls run with it off. Carol is widened for a moment to reach Settings.
        for sess in (alice, bob, carol):
            size = sess.page.viewport_size
            sess.page.set_viewport_size({"width": 1366, "height": 768})
            sess.open_settings("voice")
            sess.page.locator("[data-testid=noise-off]").check(force=True)
            sess.page.wait_for_timeout(800)
            sess.page.keyboard.press("Escape")
            sess.page.wait_for_timeout(300)
            sess.page.set_viewport_size(size)

        a = alice.page
        a.goto(srv.base + "/channels/@me", wait_until="networkidle")
        a.click("button[aria-label='Add a Server']")
        a.fill("[data-testid=server-name]", "Calls")
        a.click("[role=dialog] button:has-text('Create')")
        a.wait_for_selector("textarea[aria-label='Message #general']", timeout=20000)
        server_id = a.url.split("/channels/")[1].split("/")[0]
        a.click("button[aria-label='Invite People']")
        box = a.locator("[data-testid=invite-link]"); box.wait_for(timeout=15000)
        for _ in range(50):
            link = box.input_value()
            if "#k=" in link:
                break
            time.sleep(0.1)
        a.keyboard.press("Escape")
        for sess in (bob, carol):
            sess.page.goto(link, wait_until="networkidle")
            sess.page.wait_for_selector("text=Calls", timeout=15000)
            btn = sess.page.locator("button:has-text('Accept Invite'), button:has-text('Join')").first
            if btn.count():
                btn.click()
            sess.page.wait_for_url("**/channels/*/*", timeout=20000)

        print("== the server says calls go through the SFU", flush=True)
        ice = api(a, "GET", "/voice/ice")
        urls = [u for s in ice["body"]["ice_servers"] for u in (s["urls"] if isinstance(s["urls"], list) else [s["urls"]])]
        check(ice["status"] == 200 and ice["body"].get("sfu") is True, "GET /voice/ice has sfu: true")
        check(any(u.startswith(("turn:", "turns:")) for u in urls), f"and Cloudflare TURN ({len(urls)} urls)")
        lounge = api(a, "POST", f"/servers/{server_id}/channels", {"type": "voice", "name": "Lounge"})
        check(lounge["status"] in (200, 201), f"a second voice channel: {lounge['status']}")
        alice_id = api(a, "GET", "/users/@me")["body"]["id"]
        r = api(carol.page, "POST", "/voice/sfu/session")
        check(r["status"] == 409 and "not_in_voice" in json.dumps(r["body"]), f"outside a call no session opens: {r['status']} {r['body']}")

        print("== alice and bob call in General", flush=True)
        bob.page.goto(srv.base + "/channels/" + server_id, wait_until="networkidle")
        a.goto(srv.base + "/channels/" + server_id, wait_until="networkidle")
        t0 = time.time()
        join(alice, "General")
        join(bob, "General")
        bob_id = api(bob.page, "GET", "/users/@me")["body"]["id"]
        carol_id = api(carol.page, "GET", "/users/@me")["body"]["id"]
        ok = hears(bob, alice_id, 45)
        check(ok, f"bob hears alice ({time.time() - t0:.1f}s from joining): {level(bob, alice_id)}")
        check(hears(alice, bob_id, 20), f"alice hears bob: {level(alice, bob_id)}")
        speaking = bob.page.wait_for_selector(f"[data-user='{alice_id}'][data-speaking=true]", timeout=15000)
        check(bool(speaking), "bob sees alice's speaking ring")
        sa, sb = stats(alice), stats(bob)
        for name, s in (("alice", sa), ("bob", sb)):
            print(f"  {name}: {json.dumps(s)}", flush=True)
            check(len(s["pcs"]) == 1 and s["pcs"][0]["state"] == "connected", f"{name} holds one connection, connected")
            pair = s["pcs"][0]["pair"] or {}
            remote = pair.get("remote") or ""
            check(remote and not remote.startswith(("10.", "192.168.", "127.", "172.16.", "::1", "fe80")), f"{name}'s connection goes to a public address ({remote}, {pair.get('remoteType')})")
            fs = s["frames"] or {}
            check(fs.get("sealed", 0) > 0 and fs.get("opened", 0) > 0, f"{name}'s frames are sealed and opened ({fs})")
        bob.page.wait_for_selector(f"[data-user='{alice_id}'][data-peer-state=connected]", timeout=15000)

        print("== carol, in another channel, cannot pull from the call", flush=True)
        carol.page.goto(srv.base + "/channels/" + server_id, wait_until="networkidle")
        join(carol, "Lounge", phone=True)
        session = wait_until(lambda: next((json.loads(d)["session_id"] for _, m, p, d in sfu["carol"] if p.endswith("/publish")), None), 30)
        check(bool(session), "carol opened her own session in Lounge")
        if session:
            r = api(carol.page, "POST", "/voice/sfu/subscribe", {"session_id": session, "tracks": [{"user_id": alice_id, "kind": "audio"}]})
            check(r["status"] == 200 and r["body"]["offer"] is None and r["body"]["tracks"][0].get("error") == "not_in_call", f"pulling alice from Lounge is refused: {r}")

        print("== carol joins General on her phone, relay only", flush=True)
        join(carol, "General", phone=True)
        check(hears(carol, alice_id, 45), f"carol hears alice through TURN: {level(carol, alice_id)}")
        check(hears(carol, bob_id, 20), f"and bob: {level(carol, bob_id)}")
        check(hears(bob, carol_id, 20), f"bob hears carol: {level(bob, carol_id)}")
        sc = stats(carol)
        print(f"  carol: {json.dumps(sc)}", flush=True)
        pair = (sc["pcs"][0]["pair"] if sc["pcs"] else None) or {}
        check(len(sc["pcs"]) == 1 and pair.get("local") == "relay", f"carol's one connection is relayed ({pair})")
        check(hears(alice, carol_id, 20), f"alice hears carol: {level(alice, carol_id)}")
        carol.page.wait_for_function("() => document.querySelectorAll('[data-peer-state=connected][data-kind]').length >= 3", timeout=30000)
        carol.shot("phone-call")
        alice.shot("desktop-call")

        print("== alice's camera shows, hides and shows again without pulling again", flush=True)
        subs = lambda n: [x for x in sfu[n] if x[2].endswith("/subscribe")]
        t0 = time.time()
        a.click("button[aria-label='Turn On Camera']")
        video_sel = f"[data-user='{alice_id}'][data-kind=video] video"
        shows = lambda sess: sess.page.wait_for_function(f"() => {{ const v = document.querySelector(\"{video_sel}\"); return v && v.videoWidth > 0 && v.currentTime > 0; }}", timeout=30000)
        shows(bob)
        check(True, f"bob sees alice's camera {time.time() - t0:.1f}s after she turned it on")
        shows(carol)
        bob_subs = len(subs("bob"))
        pause(1)
        a.click("button[aria-label='Turn Off Camera']")
        bob.page.wait_for_selector(video_sel, state="detached", timeout=15000)
        check(True, "turning it off goes back to her avatar")
        pause(2)
        t0 = time.time()
        a.click("button[aria-label='Turn On Camera']")
        shows(bob)
        again = time.time() - t0
        check(len(subs("bob")) == bob_subs, f"on again it shows in {again:.1f}s, with no new pull ({len(subs('bob'))} subscribes, was {bob_subs})")
        bob.shot("camera")
        a.click("button[aria-label='Turn Off Camera']")

        print("== alice's share is pulled only while watched", flush=True)
        a.click("[data-testid=screen-share]")
        a.click("[data-testid=go-live]")
        bob.page.wait_for_selector("[data-testid=watch-stream]", timeout=30000)
        carol.page.wait_for_selector("[data-testid=watch-stream]", timeout=30000)
        pause(3)
        pulled_screen = lambda n: [x for x in subs(n) if '"screen"' in x[3]]
        check(not pulled_screen("bob") and not pulled_screen("carol"), "offered, but nobody pulls it before watching")
        carol.shot("phone-offered")
        t0 = time.time()
        bob.page.click("[data-testid=watch-stream]")
        bob.page.wait_for_function("() => { const v = document.querySelector('[data-place=focus][data-kind=screen] video'); return v && v.videoWidth > 0 && v.currentTime > 0; }", timeout=30000)
        check(len(pulled_screen("bob")) == 1, f"watching pulls it: bob sees the share in {time.time() - t0:.1f}s")
        check(hears(bob, alice_id, 20, "data-voice-screen"), f"and hears its sound, opened and let through: {level(bob, alice_id, 'data-voice-screen')}")
        bob.shot("watching")
        closes = len([x for x in sfu["bob"] if x[2].endswith("/close")])
        bob.page.hover("[data-place=focus]")
        bob.page.click("[data-place=focus] [data-testid=stop-watching]")
        bob.page.wait_for_selector("[data-place=focus]", state="detached", timeout=10000)
        wait_until(lambda: len([x for x in sfu["bob"] if x[2].endswith("/close")]) > closes, 15)
        check(len([x for x in sfu["bob"] if x[2].endswith("/close")]) > closes, "stopping lets the pull go")
        check(not pulled_screen("carol"), "carol never pulled it")
        a.click("[data-testid=screen-share]")
        bob.page.wait_for_selector("[data-testid=watch-stream]", state="detached", timeout=15000)
        check(True, "the offer goes when the share stops")

        print("== alice leaves", flush=True)
        a.click("[data-testid=voice-leave]")
        bob.page.wait_for_function(f"() => !document.querySelector(\"audio[data-voice-peer='{alice_id}']\")", timeout=20000)
        check(hears(bob, carol_id, 20), "bob and carol keep talking")
        for name, sess in (("bob", bob), ("carol", carol)):
            fs = stats(sess)["frames"] or {}
            print(f"  {name} frames: {fs}", flush=True)
        errors = alice.errors + bob.errors + carol.errors
        b.close()
finally:
    srv.stop()
bypass = [line for line in chatter if "without decryption" in line]
print("tracks pulled again for skipping decryption:", bypass or "none")
print("console warnings/errors:")
for line in chatter[:40]:
    print("  ", line)
print("page errors:", errors or "none")
print("FAILURES:", failures or "none")
sys.exit(1 if failures or errors else 0)
