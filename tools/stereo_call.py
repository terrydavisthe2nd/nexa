"""What a listener really gets from each way of sending a voice: a real call on
a throwaway chatd, the speaker's fake microphone playing 440 Hz on the left and
1500 Hz on the right, and each channel the listener decodes measured apart.
The speaker changes mode in Settings during the call, as a person would. Also Hear
everything in mono, and Use Stereo Voice taken away mid-call, from an honest app
and from one patched to go on sending stereo (window.__cheat), which is how the
listeners' end is known to hold.

    stereo_call.py            peer to peer
    stereo_call.py --sfu      through Cloudflare Realtime (keys from chatd.env; a few MB of real traffic)
    stereo_call.py --speech   speech-like sound on the left only, through RNNoise in True Stereo

Like e2e.py it tests bin/chatd and web/dist unless E2E_CHATD / E2E_WEB_DIST say otherwise.
"""
import json, math, os, re, struct, sys, tempfile, time, wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
USE_SFU = "--sfu" in sys.argv
SPEECH = "--speech" in sys.argv  # speech-like sound on the left only, silence on the right: RNNoise must keep it there
if USE_SFU:
    for line in open(ROOT / "chatd.env"):
        m = re.match(r"#?\s*([A-Z_]+)=(.*)", line.strip())
        if m and m.group(2) and m.group(1) in ("REALTIME_APP_ID", "REALTIME_APP_SECRET", "TURN_KEY_ID", "TURN_API_TOKEN"):
            os.environ[m.group(1)] = m.group(2)
sys.path.insert(0, str(ROOT / "tools"))
sys.argv = [sys.argv[0]]
from e2e import Server, Session  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

OUT = ROOT / "tools/out/stereo-call"
OUT.mkdir(parents=True, exist_ok=True)
failures = []


def check(ok, what):
    print(("  ok   " if ok else "  FAIL ") + what, flush=True)
    if not ok:
        failures.append(what)


def tones(path):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(48000)
        data = bytearray()
        for i in range(48000 * 10):
            data += struct.pack("<hh", int(math.sin(2 * math.pi * 440 * i / 48000) * 13000), int(math.sin(2 * math.pi * 1500 * i / 48000) * 13000))
        w.writeframes(bytes(data))


def speech_left(path):
    """Pitch pulses through two moving formants, in syllables: enough for RNNoise to call it speech."""
    rate = 48000
    y1 = y2 = y3 = y4 = 0.0
    data = bytearray()
    for i in range(rate * 10):
        t = i / rate
        syllable = (t * 4) % 1
        env = math.sin(math.pi * min(1, syllable / 0.7)) ** 2 if syllable < 0.7 else 0
        pitch = 120 + 25 * math.sin(2 * math.pi * 0.7 * t)
        pulse = 1.0 if (i % int(rate / pitch)) == 0 else 0.0
        f1, f2 = 500 + 250 * math.sin(2 * math.pi * 2 * t), 1500 + 700 * math.cos(2 * math.pi * 1.3 * t)
        r = 0.985
        x1 = pulse + 2 * r * math.cos(2 * math.pi * f1 / rate) * y1 - r * r * y2
        y2, y1 = y1, x1
        x2 = pulse + 2 * r * math.cos(2 * math.pi * f2 / rate) * y3 - r * r * y4
        y4, y3 = y3, x2
        v = max(-1, min(1, (x1 * 0.02 + x2 * 0.02) * env))
        data += struct.pack("<hh", int(v * 26000), 0)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(data))


PATCH = """
(() => {
  const Native = window.RTCPeerConnection;
  window.__pcs = [];
  function Patched(...args) {
    const pc = new Native(...args);
    window.__pcs.push(pc);
    pc.addEventListener("track", (e) => { if (e.track.kind === "audio") (window.__heard = window.__heard || []).push(e.track); });
    return pc;
  }
  Patched.prototype = Native.prototype;
  Object.setPrototypeOf(Patched, Native);
  window.RTCPeerConnection = Patched;
  // a sender that ignores what its app is told: two channels at the stereo bitrate, whatever it asks for
  const setRemote = Native.prototype.setRemoteDescription;
  Native.prototype.setRemoteDescription = function (desc, ...rest) {
    if (window.__cheat && desc && desc.sdp) desc = { type: desc.type, sdp: desc.sdp.replace(/;?(stereo|sprop-stereo|maxaveragebitrate)=\\d+/g, "").replace(/(a=fmtp:\\d+ )(minptime|usedtx|useinbandfec)/g, "$1stereo=1;sprop-stereo=1;maxaveragebitrate=128000;$2") };
    return setRemote.call(this, desc, ...rest);
  };
  const gum = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia = (c) => gum(window.__cheat && c.audio ? { audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 2 } } : c);
  const size = (b, f, sr) => { let w = 2 * Math.PI * f / sr, c = 2 * Math.cos(w), x = 0, y = 0; for (const s of b) { const t = s + c * x - y; y = x; x = t; } return Math.sqrt(Math.max(0, x * x + y * y - c * x * y)) / b.length * 2; };
  // the loudest live voice this page receives, each channel on its own
  window.__measure = async () => {
    const ctx = new AudioContext({ sampleRate: 48000 });
    await ctx.resume();
    let best = null;
    for (const track of (window.__heard || []).filter((t) => t.readyState === "live")) {
      const stream = new MediaStream([track]);
      const el = document.createElement("audio"); el.srcObject = stream; el.muted = true; el.play().catch(() => {});
      const split = ctx.createChannelSplitter(2);
      ctx.createMediaStreamSource(stream).connect(split);
      const l = ctx.createAnalyser(), r = ctx.createAnalyser();
      l.fftSize = r.fftSize = 32768;
      split.connect(l, 0); split.connect(r, 1);
      await new Promise((d) => setTimeout(d, 1500));
      const L = new Float32Array(32768), R = new Float32Array(32768);
      l.getFloatTimeDomainData(L); r.getFloatTimeDomainData(R);
      const rms = (b) => Math.sqrt(b.reduce((n, x) => n + x * x, 0) / b.length);
      const m = { rmsL: rms(L), rmsR: rms(R), L440: size(L, 440, ctx.sampleRate), L1500: size(L, 1500, ctx.sampleRate), R440: size(R, 440, ctx.sampleRate), R1500: size(R, 1500, ctx.sampleRate) };
      el.srcObject = null;
      const loud = Math.max(m.L440, m.L1500, m.R440, m.R1500);
      if (!best || loud > best.loud) best = { ...m, loud };
    }
    await ctx.close();
    return best;
  };
  // what this page's encoder is sending, in kbit/s over two seconds
  window.__sending = async () => {
    const read = async () => { for (const pc of window.__pcs) for (const s of (await pc.getStats()).values()) if (s.type === "outbound-rtp" && s.kind === "audio" && s.bytesSent > 0 && s.active !== false) return s; return null; };
    const a = await read(); await new Promise((d) => setTimeout(d, 2000)); const b = await read();
    return a && b && b.id === a.id ? Math.round((b.bytesSent - a.bytesSent) * 8 / ((b.timestamp - a.timestamp))) : null;
  };
})();
"""


def wait_for(fn, timeout=20):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(0.5)
    return last


def separated(m):
    """Left has the left tone and right the right, each at least 30 dB over the other's."""
    return m and m["L440"] > 0.02 and m["R1500"] > 0.02 and m["L1500"] < m["L440"] / 30 and m["R440"] < m["R1500"] / 30


def same_both(m):
    """Both ears hold the same thing: a mix of the two."""
    return m and m["L440"] > 0.02 and m["L1500"] > 0.02 and abs(m["L440"] - m["R440"]) < m["L440"] * 0.1 and abs(m["L1500"] - m["R1500"]) < m["L1500"] * 0.1


srv = Server()
errors = []
try:
    wav = Path(tempfile.mkdtemp(prefix="stereo-call-")) / "lr.wav"
    (speech_left if SPEECH else tones)(wav)
    with sync_playwright() as pw:
        b = pw.chromium.launch(args=["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", f"--use-file-for-fake-audio-capture={wav}", "--autoplay-policy=no-user-gesture-required"])
        alice = Session(b, srv.base, "alice", OUT)
        bob = Session(b, srv.base, "bob", OUT)
        for s in (alice, bob):
            s.ctx.add_init_script(PATCH)
            s.register()
        a = alice.page
        a.goto(srv.base + "/channels/@me", wait_until="networkidle")
        a.click("button[aria-label='Add a Server']")
        a.fill("[data-testid=server-name]", "Calls")
        a.click("[role=dialog] button:has-text('Create')")
        a.wait_for_selector("textarea[aria-label='Message #general']", timeout=20000)
        server_id = a.url.split("/channels/")[1].split("/")[0]
        a.click("button[aria-label='Invite People']")
        box = a.locator("[data-testid=invite-link]")
        box.wait_for(timeout=15000)
        for _ in range(50):
            link = box.input_value()
            if "#k=" in link:
                break
            time.sleep(0.1)
        a.keyboard.press("Escape")
        bob.page.goto(link, wait_until="networkidle")
        btn = bob.page.locator("button:has-text('Accept Invite'), button:has-text('Join')").first
        if btn.count():
            btn.click()
        bob.page.wait_for_url("**/channels/*/*", timeout=20000)

        def switch(sess, label, on):
            box = sess.page.locator(f"label:has-text('{label}') input")
            box.check(force=True) if on else box.uncheck(force=True)

        def choose(sess, mode, noise="off"):
            sess.open_settings("voice")
            if mode == "stereo":
                switch(sess, "Stereo voice", True)
            elif mode == "dual":
                switch(sess, "Dual mono", True)
            else:
                switch(sess, "Stereo voice", False)
                switch(sess, "Dual mono", False)
            sess.page.locator(f"[data-testid=noise-{noise}]").check(force=True)
            sess.page.wait_for_timeout(800)
            sess.shot(f"settings-{mode}")
            sess.page.keyboard.press("Escape")
            sess.page.wait_for_timeout(300)

        def measure_until(want, what, timeout=25, speaker=None, listener=None):
            speaker, listener = speaker or alice, listener or bob
            got = wait_for(lambda: (lambda m: m if want(m) else None)(listener.page.evaluate("window.__measure()")), timeout)
            kbps = speaker.page.evaluate("window.__sending()")
            shown = {k: round(v, 4) for k, v in (got or listener.page.evaluate("window.__measure()") or {}).items() if k in ("L440", "L1500", "R440", "R1500")}
            check(bool(got), f"{what}: {json.dumps(shown)}, sending {kbps} kbit/s")
            return kbps

        for s in (alice, bob):
            s.page.goto(srv.base + "/channels/" + server_id, wait_until="networkidle")
            try:
                s.dismiss_whats_new(3000)
            except Exception:
                pass
        # a steady tone is what automatic sensitivity learns to take for background noise
        for s in (alice, bob):
            s.open_settings("voice")
            s.page.locator("label:has-text('Automatically determine input sensitivity') input").uncheck(force=True)
            s.page.locator("[data-testid=noise-off]").check(force=True)
            s.page.wait_for_timeout(500)
            s.page.keyboard.press("Escape")
            s.page.wait_for_timeout(300)
        choose(alice, "stereo", "enhanced") if SPEECH else choose(alice, "mono")
        for s in (alice, bob):
            s.page.locator("[data-channel]:has(span:text-is('General')) button").first.click()
            s.page.wait_for_selector("[data-testid=voice-status]", timeout=30000)
        print(f"== {'SFU' if USE_SFU else 'peer to peer'}: alice speaks, bob listens", flush=True)
        if SPEECH:
            m = wait_for(lambda: (lambda m: m if m and m["rmsL"] > 0.003 and m["rmsR"] < m["rmsL"] / 100 else None)(bob.page.evaluate("window.__measure()")), 40)
            check(bool(m), f"True Stereo through RNNoise: sound only on the left: {m and {k: round(v, 5) for k, v in m.items()}}")
            b.close()
            raise SystemExit(1 if failures else 0)
        mono = measure_until(same_both, "Mono: the same mix in both ears")
        check(mono is not None and mono < 80, f"Mono stays at a voice's bitrate ({mono} kbit/s)")
        choose(alice, "stereo")
        stereo = measure_until(separated, "True Stereo, changed mid-call: left and right apart")
        check(stereo is not None and 90 < stereo < 150, f"True Stereo sends about 128 kbit/s ({stereo})")
        choose(alice, "dual")
        dual = measure_until(same_both, "Dual Mono, changed mid-call: the same mix in both ears")
        check(dual is not None and dual > 60, f"Dual Mono sends more than Mono ({dual} kbit/s)")
        # settings changed together: the newest must win
        choose(alice, "stereo", "enhanced")
        choose(alice, "mono")
        measure_until(same_both, "and back to Mono")

        print("== Hear everything in mono", flush=True)
        choose(alice, "stereo")
        measure_until(separated, "alice in stereo, bob hears it")
        bob.open_settings("voice")
        switch(bob, "Hear everything in mono", True)
        bob.page.wait_for_timeout(500)
        bob.page.keyboard.press("Escape")
        k = measure_until(same_both, "bob turns it on: the same mix in both ears, just for him")
        check(k is not None and k > 90, f"alice is still sending stereo ({k} kbit/s)")
        bob.open_settings("voice")
        switch(bob, "Hear everything in mono", False)
        bob.page.wait_for_timeout(500)
        bob.page.keyboard.press("Escape")
        measure_until(separated, "bob turns it off: left and right apart again")

        print("== Use Stereo Voice, bob speaking and alice listening", flush=True)
        choose(alice, "mono")
        choose(bob, "stereo")
        voice_id = a.evaluate("() => [...document.querySelectorAll('[data-channel]')].find((x) => x.querySelector('button') && x.textContent.includes('General')).getAttribute('data-channel')")
        def deny(bits):
            r = a.evaluate("""async ([ch, sid, deny]) => {
              const tok = localStorage.getItem('ec.token');
              const res = await fetch(`/api/v1/channels/${ch}/permissions/${sid}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tok }, body: JSON.stringify({ type: 'role', deny }) });
              return res.status; }""", [voice_id, server_id, bits])
            check(r < 300, f"the owner sets the channel's @everyone deny to {bits}: {r}")
        STEREO_BIT = str(1 << 61)
        measure_until(separated, "bob in stereo, allowed: alice hears left and right", speaker=bob, listener=alice)
        deny(STEREO_BIT)
        k = measure_until(same_both, "taken away mid-call: alice hears one mix", speaker=bob, listener=alice)
        check(k is not None and k < 80, f"bob's honest app drops to a voice's bitrate ({k} kbit/s)")
        deny("0")
        measure_until(separated, "given back: left and right again", speaker=bob, listener=alice)
        bob.page.evaluate("window.__cheat = true")
        deny(STEREO_BIT)
        k = measure_until(same_both, "a patched app that lost it: alice still hears one mix", speaker=bob, listener=alice)
        check(k is not None and k > 90, f"and it really is still sending two channels ({k} kbit/s)")
        bob.page.evaluate("window.__cheat = false")
        deny("0")
        errors = [e for s in (alice, bob) for e in s.errors]
        check(not errors, f"no page errors: {errors[:5]}")
        b.close()
finally:
    srv.stop()
print("FAILED: " + "; ".join(failures) if failures else "stereo call: all good", flush=True)
sys.exit(1 if failures else 0)
