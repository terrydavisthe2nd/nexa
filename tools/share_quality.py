"""Screen share quality, measured the way the people watching see it.

Alice shares a tab with a clip of hard motion playing over it (a fast pan
across fine detail at 1080p60, like a game's camera; made with ffmpeg the
first time into tools/out/share-quality/pan.mp4). Bob watches from a browser
of his own. Every second both pages report WebRTC's numbers (what Alice's
encoder makes and why it holds back, what Bob receives and decodes), and Bob's
page times every frame it paints (requestVideoFrameCallback), so freezes are
counted as a person watching would see them: a frame that stays up more than
three times as long as the frames before it, or 150 ms longer.

  tools/.venv/bin/python tools/share_quality.py --label before [--seconds 45] [--res 1080p] [--fps 60]
      [--viewer chromium|firefox] [--sharer chromium|firefox] [--p2p] [--up-kbps N] [--loss P]

Through Cloudflare Realtime by default: REALTIME_APP_ID and REALTIME_APP_SECRET
from chatd.env reach the test server, and nothing else. --p2p calls peer to
peer instead. --up-kbps caps Alice's upload, and --loss loses that share of her
packets, through tools/netsim (both for the SFU only). A Chromium sharer
captures its own tab for real (getDisplayMedia with the tab accepted
automatically); a Firefox one shares a canvas the clip is drawn onto, which
Firefox encodes as a camera, not a screen. The desktop app's Tauri shell has
no DevTools protocol the harness can drive, so the desktop build is measured by
hand (or by its own smoke test); these numbers are the browser's. Like e2e.py
it tests bin/chatd and web/dist unless E2E_CHATD / E2E_WEB_DIST say otherwise. Uses a few minutes of real SFU traffic (a few hundred MB at 1080p60).
Results go to tools/out/share-quality/<label>.json, with a few frames of what
Bob saw beside them.
"""
import argparse, json, os, re, shutil, statistics, subprocess, sys, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools/out/share-quality"

ap = argparse.ArgumentParser()
ap.add_argument("--label", default="run")
ap.add_argument("--seconds", type=float, default=45)
ap.add_argument("--res", default="1080p", choices=["720p", "1080p", "1440p", "source"])
ap.add_argument("--fps", type=int, default=60, choices=[15, 30, 60])
ap.add_argument("--viewer", default="chromium", choices=["chromium", "firefox"])
ap.add_argument("--sharer", default="chromium", choices=["chromium", "firefox"])
ap.add_argument("--p2p", action="store_true")
ap.add_argument("--up-kbps", type=float, default=0)
ap.add_argument("--loss", type=float, default=0)
ap.add_argument("--down-loss", type=float, default=0, help="loses this share of Bob's incoming packets (through netsim)")
ap.add_argument("--clip", default="")
ap.add_argument("--source", default="canvas", choices=["canvas", "video"])
# Experiments on the sharer's page, applied to its screen share's sender: --tweak hint=detail
# (forces the track's contentHint; "none" for none), --tweak degrade=maintain-resolution,
# --tweak noscale (never sets scaleResolutionDownBy), --tweak prio (priority and
# networkPriority high), --tweak bitrate=9000000 (maxBitrate). Several may be given.
ap.add_argument("--tweak", action="append", default=[])
args = ap.parse_args()

env = {}
for line in open(ROOT / "chatd.env"):
    m = re.match(r"#?\s*([A-Z_]+)=(.*)", line.strip())
    if m and m.group(2):
        env[m.group(1)] = m.group(2)
for k in ("REALTIME_APP_ID", "REALTIME_APP_SECRET", "TURN_KEY_ID", "TURN_API_TOKEN", "TURN_URLS"):
    os.environ.pop(k, None)
if not args.p2p:
    for k in ("REALTIME_APP_ID", "REALTIME_APP_SECRET"):
        if k not in env:
            sys.exit(f"{k} is not in chatd.env (or use --p2p)")
        os.environ[k] = env[k]
shaped = not args.p2p and (args.up_kbps > 0 or args.loss > 0 or args.down_loss > 0)
sys.argv = [sys.argv[0]]
sys.path.insert(0, str(ROOT / "tools"))
from e2e import Server, Session, free_port  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

OUT.mkdir(parents=True, exist_ok=True)


def make_clip() -> Path:
    """A 20 s 1080p60 pan across a 4K fractal, with a block sweeping across it: whole-frame motion over fine detail."""
    if args.clip:
        return Path(args.clip)
    clip = OUT / "pan.mp4"
    if clip.exists():
        return clip
    ff = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
    still = OUT / "still.png"
    subprocess.run([ff, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "mandelbrot=s=3840x2160:start_scale=2.5:end_scale=2.5:maxiter=300", "-frames:v", "1", str(still)], check=True)
    vf = "crop=1920:1080:x='(1920)*(0.5+0.5*sin(t*1.3))':y='(1080)*(0.5+0.5*cos(t*0.9))',drawbox=x='mod(t*700\\,1920)':y=400:w=200:h=200:color=red@0.9:t=fill"
    subprocess.run([ff, "-loglevel", "error", "-y", "-loop", "1", "-framerate", "60", "-i", str(still), "-t", "20", "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p", "-r", "60", str(clip)], check=True)
    return clip


# Every RTCPeerConnection the page makes, and its stats each second: what is
# sent of the share (the busiest outgoing video) and what comes in of it.
SAMPLER = r"""
(() => {
  const Native = window.RTCPeerConnection;
  window.__pcs = [];
  function Patched(config, ...rest) { const pc = new Native(config, ...rest); window.__pcs.push(pc); return pc; }
  Patched.prototype = Native.prototype;
  Object.setPrototypeOf(Patched, Native);
  window.RTCPeerConnection = Patched;
  window.__samples = [];
  const pick = (list, key) => list.reduce((a, b) => (!a || (b[key] || 0) > (a[key] || 0) ? b : a), null);
  setInterval(async () => {
    const outs = [], ins = [], pairs = [], sources = [], codecs = {};
    for (const pc of window.__pcs) {
      if (pc.connectionState === 'closed') continue;
      let r; try { r = await pc.getStats(); } catch { continue; }
      r.forEach((s) => {
        if (s.type === 'codec') codecs[s.id] = s.mimeType;
        if (s.type === 'outbound-rtp' && s.kind === 'video') outs.push(s);
        if (s.type === 'inbound-rtp' && s.kind === 'video') ins.push(s);
        if (s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded') pairs.push(s);
        if (s.type === 'media-source' && s.kind === 'video') sources.push(s);
      });
    }
    const o = pick(outs, 'bytesSent'), i = pick(ins, 'bytesReceived'), p = pick(pairs, 'bytesSent'), src = pick(sources, 'frames');
    window.__samples.push({
      t: Date.now(),
      out: o && { bytes: o.bytesSent, frames: o.framesEncoded, sent: o.framesSent, w: o.frameWidth, h: o.frameHeight, fps: o.framesPerSecond, target: o.targetBitrate, qp: o.qpSum, encodeTime: o.totalEncodeTime, limit: o.qualityLimitationReason, limits: o.qualityLimitationDurations, impl: o.encoderImplementation, retx: o.retransmittedBytesSent, nacks: o.nackCount, plis: o.pliCount, huge: o.hugeFramesSent, keyframes: o.keyFramesEncoded, sendDelay: o.totalPacketSendDelay, packets: o.packetsSent, codec: codecs[o.codecId], scalability: o.scalabilityMode, rid: o.rid, power: o.powerEfficientEncoder },
      src: src && { w: src.width, h: src.height, fps: src.framesPerSecond, frames: src.frames },
      inn: i && { bytes: i.bytesReceived, frames: i.framesDecoded, recv: i.framesReceived, dropped: i.framesDropped, w: i.frameWidth, h: i.frameHeight, fps: i.framesPerSecond, qp: i.qpSum, lost: i.packetsLost, packets: i.packetsReceived, nacks: i.nackCount, plis: i.pliCount, freezes: i.freezeCount, freezeDur: i.totalFreezesDuration, jb: i.jitterBufferDelay, jbOut: i.jitterBufferEmittedCount, decodeTime: i.totalDecodeTime, keyframes: i.keyFramesDecoded, impl: i.decoderImplementation, codec: codecs[i.codecId] },
      pair: p && { out: p.availableOutgoingBitrate, in: p.availableIncomingBitrate, rtt: p.currentRoundTripTime },
    });
  }, 1000);
})();
"""

# Bob's screen: every frame painted of the share, when and at what size.
FRAMES = r"""
() => {
  window.__painted = [];
  let video = null;
  const watch = () => {
    const v = document.querySelector('[data-place=focus][data-kind=screen] video');
    if (v && v !== video) {
      video = v;
      const each = (now, meta) => { window.__painted.push([now, meta.presentedFrames, meta.width, meta.height]); if (video === v) v.requestVideoFrameCallback(each); };
      v.requestVideoFrameCallback(each);
    }
  };
  setInterval(watch, 200);
  watch();
}
"""

# Alice's getDisplayMedia: her own tab, with the clip over the whole page (Chromium);
# or a canvas the clip is drawn onto at the chosen rate (Firefox, which cannot be told to accept a capture).
# What is shared: by default a canvas over the whole page that pans across the
# 4K fractal at a constant speed, bouncing off its edges, with a block sweeping
# across and a frame counter; drawn every frame, so the source never stalls the
# way a looping video can. --source video plays the clip instead.
MOTION_CANVAS = r"""
window.__startMotion = async (host) => {
  const img = new Image(); img.src = '/__motion/still.png'; await img.decode();
  const bmp = await createImageBitmap(img);
  const c = document.createElement('canvas'); c.width = 1920; c.height = 1080;
  c.id = '__motion';
  c.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;background:#000';
  host.appendChild(c);
  const g = c.getContext('2d', { alpha: false });
  const t0 = performance.now();
  let n = 0;
  const tri = (x, span) => { const m = x % (2 * span); return m < span ? m : 2 * span - m; };
  const draw = (now) => {
    const t = (now - t0) / 1000;
    const x = tri(t * 640, 3840 - 1920), y = tri(t * 360, 2160 - 1080);
    g.drawImage(bmp, x, y, 1920, 1080, 0, 0, 1920, 1080);
    g.fillStyle = '#ff2a1a'; g.fillRect((t * 700) % 1920, 400, 200, 200);
    g.fillStyle = '#000a'; g.fillRect(24, 24, 360, 72);
    g.fillStyle = '#fff'; g.font = '600 48px sans-serif'; g.fillText('frame ' + (++n), 40, 78);
    requestAnimationFrame(draw);
  };
  requestAnimationFrame(draw);
};
"""

SHARE_CHROMIUM = r"""
(() => {
  if (window.top !== window || !navigator.mediaDevices) return;
  const real = navigator.mediaDevices.getDisplayMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getDisplayMedia = async (opts) => {
    let v = document.getElementById('__motion');
    if (!v && window.__useCanvas) {
      await window.__startMotion(document.documentElement);
      v = document.getElementById('__motion');
    } else if (!v) {
      v = document.createElement('video');
      v.id = '__motion'; v.src = '/__motion/pan.mp4'; v.muted = true; v.loop = true; v.autoplay = true; v.playsInline = true;
      v.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;object-fit:cover;z-index:2147483647;pointer-events:none;background:#000';
      document.documentElement.appendChild(v);
    }
    const o = Object.assign({}, opts || {}, { preferCurrentTab: true, selfBrowserSurface: 'include', audio: false });
    try {
      const s = await real(o);
      window.__gdm = 'ok ' + JSON.stringify(s.getVideoTracks()[0] && s.getVideoTracks()[0].getSettings());
      if (v.play) await v.play().catch(() => {});
      return s;
    } catch (e) {
      window.__gdm = 'failed: ' + String(e) + ' with ' + JSON.stringify(o);
      throw e;
    }
  };
})();
"""
SHARE_CANVAS = r"""
(() => {
  if (window.top !== window || !navigator.mediaDevices) return;
  navigator.mediaDevices.getDisplayMedia = async (opts) => {
    const want = (opts && opts.video && opts.video.frameRate && (opts.video.frameRate.max || opts.video.frameRate.ideal)) || 60;
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:-99999px;top:0;width:1920px;height:1080px;overflow:hidden';
    document.documentElement.appendChild(host);
    await window.__startMotion(host);
    return document.getElementById('__motion').captureStream(want);
  };
})();
"""


# A microphone made in the page, for the Chromium sharer, which runs without fake devices:
# a quiet tone, so the call has a voice to send and no real microphone is ever opened.
FAKE_MIC = r"""
(() => {
  const md = navigator.mediaDevices;
  if (!md) return;
  md.getUserMedia = async () => {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator(); osc.frequency.value = 220;
    const g = ctx.createGain(); g.gain.value = 0.02;
    const dest = ctx.createMediaStreamDestination();
    osc.connect(g).connect(dest); osc.start();
    return dest.stream;
  };
})();
"""


TWEAKS = r"""
(() => {
  const t = window.__tweaks || {};
  const screen = (track) => { try { return !!track && track.kind === 'video' && track.getSettings().displaySurface !== undefined; } catch { return false; } };
  if (t.hint !== undefined) {
    const d = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, 'contentHint');
    Object.defineProperty(MediaStreamTrack.prototype, 'contentHint', { configurable: true, get() { return d.get.call(this); }, set(v) { d.set.call(this, screen(this) ? (t.hint === 'none' ? '' : t.hint) : v); } });
  }
  if (t.codec) {
    // the screen's transceiver offers this codec first, whatever the app asks for
    const scp = RTCRtpTransceiver.prototype.setCodecPreferences;
    RTCRtpTransceiver.prototype.setCodecPreferences = function (codecs, ...rest) {
      if (screen(this.sender.track)) {
        const want = new RegExp('^video/' + t.codec + '$', 'i');
        const all = RTCRtpSender.getCapabilities('video').codecs;
        codecs = [...all.filter((c) => want.test(c.mimeType)), ...all.filter((c) => /rtx/i.test(c.mimeType))];
        window.__codecForced = codecs.map((c) => c.mimeType + ' ' + (c.sdpFmtpLine || '')).join(', ');
      }
      return scp.call(this, codecs, ...rest);
    };
  }
  const sp = RTCRtpSender.prototype.setParameters;
  RTCRtpSender.prototype.setParameters = function (params, ...rest) {
    if (screen(this.track) && params) {
      if (t.degrade) params.degradationPreference = t.degrade;
      for (const e of params.encodings || []) {
        if (t.noscale) delete e.scaleResolutionDownBy;
        if (t.prio) { e.priority = 'high'; e.networkPriority = 'high'; }
        if (t.bitrate) e.maxBitrate = t.bitrate;
        if (t.svc) e.scalabilityMode = t.svc;
      }
      window.__tweaked = (window.__tweaked || 0) + 1;
    }
    return sp.call(this, params, ...rest);
  };
  const rt = RTCRtpSender.prototype.replaceTrack;
  RTCRtpSender.prototype.replaceTrack = function (track, ...rest) {
    const r = rt.call(this, track, ...rest);
    // a track handed over after the transceiver was made gets the same treatment
    if (screen(track)) r.then(() => { try { this.setParameters(this.getParameters()); } catch {} });
    return r;
  };
})();
"""


def tweaks() -> dict:
    out = {}
    for tw in args.tweak:
        k, _, v = tw.partition("=")
        out[k] = (int(v) if v.isdigit() else v) if v else True
    return out


def launch(pw, kind, sharer, bypass_csp=False):
    if kind == "firefox":
        # silent: the call would otherwise play the other side's fake microphone aloud
        return pw.firefox.launch(firefox_user_prefs={"media.navigator.streams.fake": True, "media.navigator.permission.disabled": True, "media.autoplay.default": 0, "media.autoplay.blocking_policy": 0, "media.volume_scale": "0.0"})
    flags = ["--mute-audio", "--autoplay-policy=no-user-gesture-required", "--disable-renderer-backgrounding", "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows"]
    # Chromium's fake devices fake screen capture too (a green test card at 20 fps), and its
    # fake permission prompt turns down the tab capture it is told to accept, so the sharer
    # gets neither: a real capture of its own tab, and a microphone made in the page (FAKE_MIC)
    if sharer:
        flags.append("--auto-accept-this-tab-capture")
    else:
        flags += ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"]
    # the full Chromium in its new headless mode: the old headless shell cannot capture a tab
    return pw.chromium.launch(channel="chromium", args=flags)


def join_voice(sess, name):
    p = sess.page
    p.locator(f"[data-channel]:has(span:text-is('{name}')) button").first.click()
    p.wait_for_selector("[data-testid=voice-status]", timeout=45000)


def summarize(samples_a, samples_b, painted, seconds):
    def deltas(samples, side, key):
        out = []
        for x, y in zip(samples, samples[1:]):
            a, b = (x.get(side) or {}), (y.get(side) or {})
            if a.get(key) is None or b.get(key) is None:
                continue
            dt = (y["t"] - x["t"]) / 1000
            out.append((b[key] - a[key]) / dt if dt > 0 else 0)
        return out

    def pct(xs, q):
        if not xs:
            return None
        xs = sorted(xs)
        return xs[min(len(xs) - 1, max(0, int(round(q * (len(xs) - 1)))))]

    res = {}
    # what Bob painted: frames a second, and freezes as WebRTC defines them
    if len(painted) > 2:
        times = [p[0] for p in painted]
        span = (times[-1] - times[0]) / 1000
        gaps = [b - a for a, b in zip(times, times[1:])]
        freezes, frozen, recent = 0, 0.0, []
        for g in gaps:
            avg = statistics.mean(recent) if recent else g
            if recent and g > max(3 * avg, avg + 150):
                freezes += 1
                frozen += g
            recent = (recent + [g])[-30:]
        per_sec = {}
        for t in times:
            per_sec[int((t - times[0]) // 1000)] = per_sec.get(int((t - times[0]) // 1000), 0) + 1
        whole = [n for s, n in per_sec.items() if s < int(span)]
        sizes = {}
        for p in painted:
            sizes[f"{p[2]}x{p[3]}"] = sizes.get(f"{p[2]}x{p[3]}", 0) + 1
        res["seen"] = {
            "fps": round(len(painted) / span, 1) if span > 0 else 0,
            "fps_p10": pct(whole, 0.1),
            "fps_min": min(whole) if whole else None,
            "freezes": freezes,
            "frozen_ms": round(frozen),
            "frozen_share": round(frozen / (span * 1000), 3) if span > 0 else 0,
            "longest_gap_ms": round(max(gaps)),
            "sizes": dict(sorted(sizes.items(), key=lambda kv: -kv[1])[:3]),
        }
    rx = deltas(samples_b, "inn", "bytes")
    dec = deltas(samples_b, "inn", "frames")
    qp_in = []
    for x, y in zip(samples_b, samples_b[1:]):
        a, b = (x.get("inn") or {}), (y.get("inn") or {})
        if a.get("qp") is not None and b.get("qp") is not None and (b.get("frames") or 0) > (a.get("frames") or 0):
            qp_in.append((b["qp"] - a["qp"]) / (b["frames"] - a["frames"]))
    last_b = (samples_b[-1].get("inn") or {}) if samples_b else {}
    first_b = next((s.get("inn") for s in samples_b if s.get("inn")), {}) or {}
    res["received"] = {
        "kbps": round(statistics.mean(rx) * 8 / 1000) if rx else None,
        "kbps_p10": round(pct(rx, 0.1) * 8 / 1000) if rx else None,
        "decoded_fps": round(statistics.mean(dec), 1) if dec else None,
        "qp": round(statistics.mean(qp_in), 1) if qp_in else None,
        "lost": (last_b.get("lost") or 0) - (first_b.get("lost") or 0),
        "nacks": (last_b.get("nacks") or 0) - (first_b.get("nacks") or 0),
        "plis": (last_b.get("plis") or 0) - (first_b.get("plis") or 0),
        "freezes_webrtc": (last_b.get("freezes") or 0) - (first_b.get("freezes") or 0),
        "dropped": (last_b.get("dropped") or 0) - (first_b.get("dropped") or 0),
        "keyframes": (last_b.get("keyframes") or 0) - (first_b.get("keyframes") or 0),
        "decoder": last_b.get("impl"),
        "codec": last_b.get("codec"),
    }
    jb = []
    for x, y in zip(samples_b, samples_b[1:]):
        a, b = (x.get("inn") or {}), (y.get("inn") or {})
        if a.get("jb") is not None and (b.get("jbOut") or 0) > (a.get("jbOut") or 0):
            jb.append((b["jb"] - a["jb"]) / (b["jbOut"] - a["jbOut"]) * 1000)
    res["received"]["jitter_buffer_ms"] = round(statistics.mean(jb)) if jb else None
    tx = deltas(samples_a, "out", "bytes")
    enc = deltas(samples_a, "out", "frames")
    targets = [s["out"]["target"] for s in samples_a if s.get("out") and s["out"].get("target")]
    avail = [s["pair"]["out"] for s in samples_a if s.get("pair") and s["pair"].get("out")]
    qp_out = []
    for x, y in zip(samples_a, samples_a[1:]):
        a, b = (x.get("out") or {}), (y.get("out") or {})
        if a.get("qp") is not None and b.get("qp") is not None and (b.get("frames") or 0) > (a.get("frames") or 0):
            qp_out.append((b["qp"] - a["qp"]) / (b["frames"] - a["frames"]))
    last_a = (samples_a[-1].get("out") or {}) if samples_a else {}
    first_a = next((s.get("out") for s in samples_a if s.get("out")), {}) or {}
    limits = {}
    for k, v in (last_a.get("limits") or {}).items():
        limits[k] = round(v - (first_a.get("limits") or {}).get(k, 0), 1)
    srcfps = [s["src"]["fps"] for s in samples_a if s.get("src") and s["src"].get("fps")]
    sizes = {}
    for s in samples_a:
        o = s.get("out") or {}
        if o.get("w"):
            sizes[f"{o['w']}x{o['h']}"] = sizes.get(f"{o['w']}x{o['h']}", 0) + 1
    res["sent"] = {
        "kbps": round(statistics.mean(tx) * 8 / 1000) if tx else None,
        "kbps_p10": round(pct(tx, 0.1) * 8 / 1000) if tx else None,
        "target_kbps": round(statistics.mean(targets) / 1000) if targets else None,
        "available_kbps": round(statistics.mean(avail) / 1000) if avail else None,
        "available_min_kbps": round(min(avail) / 1000) if avail else None,
        "encoded_fps": round(statistics.mean(enc), 1) if enc else None,
        "capture_fps": round(statistics.mean(srcfps), 1) if srcfps else None,
        "qp": round(statistics.mean(qp_out), 1) if qp_out else None,
        "limited_s": limits,
        "sizes": dict(sorted(sizes.items(), key=lambda kv: -kv[1])[:3]),
        "encoder": last_a.get("impl"),
        "codec": last_a.get("codec"),
        "keyframes": (last_a.get("keyframes") or 0) - (first_a.get("keyframes") or 0),
        "retransmitted_kb": round(((last_a.get("retx") or 0) - (first_a.get("retx") or 0)) / 1000),
        "plis": (last_a.get("plis") or 0) - (first_a.get("plis") or 0),
        "nacks": (last_a.get("nacks") or 0) - (first_a.get("nacks") or 0),
    }
    return res


def main():
    clip = make_clip()
    srv = Server()
    netsim = None
    launched = []
    sessions = []
    print("chatd", srv.base, "through", "peer to peer" if args.p2p else "Cloudflare Realtime", flush=True)
    result = {"label": args.label, "res": args.res, "fps": args.fps, "viewer": args.viewer, "sharer": args.sharer, "p2p": args.p2p, "up_kbps": args.up_kbps, "loss": args.loss, "down_loss": args.down_loss}
    shots = OUT / args.label
    shutil.rmtree(shots, ignore_errors=True)
    shots.mkdir(parents=True)
    try:
        with sync_playwright() as pw:
            ctrl = None
            if shaped:
                tmp = Path(tempfile.mkdtemp())
                bin_ = tmp / "netsim"
                subprocess.run(["go", "build", "-o", str(bin_), "./tools/netsim"], cwd=ROOT, check=True)
                port = free_port()
                ctrl = f"http://127.0.0.1:{port}"
                netsim = subprocess.Popen([str(bin_), "-ctrl", f"127.0.0.1:{port}"], stdout=open(tmp / "netsim.log", "w"), stderr=subprocess.STDOUT)
                time.sleep(0.5)
            ba = launch(pw, args.sharer, True, bypass_csp=bool(ctrl))
            launched.append(ba)
            bb = launch(pw, args.viewer, False)
            launched.append(bb)
            result["sharer_version"] = ba.version
            print("sharing from", args.sharer, ba.version, "to", args.viewer, bb.version, flush=True)
            for br, kind in ((ba, args.sharer), (bb, args.viewer)):
                if kind == "firefox":
                    # Firefox takes no camera or microphone grants; its prefs let media through instead
                    made = br.new_context
                    br.new_context = (lambda m: lambda **kw: m(**{k: v for k, v in kw.items() if k != "permissions"}))(made)
            if ctrl:
                # the page asks netsim for its legs, which the app's own Content-Security-Policy would refuse
                for br in (ba, bb):
                    made = br.new_context
                    br.new_context = (lambda m: lambda **kw: m(**{**kw, "bypass_csp": True}))(made)
            alice = Session(ba, srv.base, "alice", shots, viewport=(1920, 1080))
            bob = Session(bb, srv.base, "bob", shots, viewport=(1366, 768))
            sessions += [alice, bob]
            alice.ctx.route("**/__motion/pan.mp4", lambda route: route.fulfill(path=str(clip), headers={"Content-Type": "video/mp4"}))
            alice.ctx.route("**/__motion/still.png", lambda route: route.fulfill(path=str(OUT / "still.png"), headers={"Content-Type": "image/png"}))
            alice.ctx.add_init_script(MOTION_CANVAS)
            if args.source == "canvas":
                alice.ctx.add_init_script("window.__useCanvas = true;")
            if ctrl:
                # each one's media reaches Cloudflare only through netsim's legs, shaped as asked
                for sess in (alice, bob):
                    sess.ctx.add_init_script("window.__netsim = " + json.dumps({"ctrl": ctrl, "client": sess.name}) + ";")
                    sess.ctx.add_init_script(NETSIM_PATCH)
            alice.ctx.add_init_script(SAMPLER)
            if args.tweak:
                alice.ctx.add_init_script("window.__tweaks = " + json.dumps(tweaks()) + ";")
                alice.ctx.add_init_script(TWEAKS)
                result["tweaks"] = tweaks()
            if args.sharer != "firefox":
                alice.ctx.add_init_script(FAKE_MIC)
            alice.ctx.add_init_script(SHARE_CANVAS if args.sharer == "firefox" else SHARE_CHROMIUM)
            bob.ctx.add_init_script(SAMPLER)
            alice.register("Alice")
            bob.register("Bob")
            a = alice.page
            a.goto(srv.base + "/channels/@me", wait_until="networkidle")
            a.click("button[aria-label='Add a Server']")
            a.fill("[data-testid=server-name]", "Share")
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
            b = bob.page
            b.goto(link, wait_until="networkidle")
            btn = b.locator("button:has-text('Accept Invite'), button:has-text('Join')").first
            btn.wait_for(timeout=15000)
            btn.click()
            b.wait_for_url("**/channels/*/*", timeout=20000)
            if ctrl:
                import urllib.request
                def shape(client, direction, profile):
                    req = urllib.request.Request(ctrl + "/profile", method="PUT", data=json.dumps({"client": client, "dir": direction, "profile": profile}).encode(), headers={"Content-Type": "application/json"})
                    urllib.request.urlopen(req, timeout=5).read()
                shape("alice", "up", {"rate_kbps": args.up_kbps, "loss": args.loss, "queue_ms": 300})
                shape("bob", "down", {"loss": args.down_loss})
            for sess in (alice, bob):
                sess.page.goto(srv.base + "/channels/" + server_id, wait_until="networkidle")
                join_voice(sess, "General")
            a.wait_for_timeout(3000)
            a.click("[data-testid=screen-share]")
            a.click(f"[data-testid=share-res-{args.res}]")
            a.click(f"[data-testid=share-fps-{args.fps}]")
            logs = []
            a.on("console", lambda m: logs.append(f"{m.type}: {m.text[:300]}"))
            a.click("[data-testid=go-live]")
            try:
                b.wait_for_selector("[data-testid=watch-stream]", timeout=45000)
            except Exception:
                a.screenshot(path=str(shots / "sharer-stuck.png"))
                print("the share did not start:", a.evaluate("() => window.__gdm || 'getDisplayMedia was never called'"))
                print("alice's console:", *logs[-30:], sep="\n  ")
                raise
            b.click("[data-testid=watch-stream]")
            b.wait_for_function("() => { const v = document.querySelector('[data-place=focus][data-kind=screen] video'); return v && v.videoWidth > 0 && v.currentTime > 0; }", timeout=60000)
            print("bob sees the share; settling 8 s, then measuring", int(args.seconds), "s", flush=True)
            b.wait_for_timeout(8000)
            b.evaluate(FRAMES)
            start_a = len(a.evaluate("() => window.__samples"))
            start_b = len(b.evaluate("() => window.__samples"))
            t0 = time.time()
            n = 0
            while time.time() - t0 < args.seconds:
                b.wait_for_timeout(1000)
                if int(time.time() - t0) // 15 >= n:
                    b.locator("[data-place=focus][data-kind=screen]").screenshot(path=str(shots / f"seen-{n}.png"))
                    n += 1
            sdp = """() => (window.__pcs || []).filter((pc) => pc.connectionState !== 'closed').map((pc) => ({ local: pc.localDescription && pc.localDescription.sdp, remote: pc.remoteDescription && pc.remoteDescription.sdp }))"""
            result["sdp"] = {"alice": a.evaluate(sdp), "bob": b.evaluate(sdp)}
            samples_a = a.evaluate("() => window.__samples")[start_a:]
            samples_b = b.evaluate("() => window.__samples")[start_b:]
            painted = b.evaluate("() => window.__painted")
            result.update(summarize(samples_a, samples_b, painted, args.seconds))
            result["samples"] = {"alice": samples_a, "bob": samples_b}
            errors = alice.errors + bob.errors
            if errors:
                result["page_errors"] = errors[:20]
    except Exception:
        # what each page said last, for a run that broke off
        for sess in sessions:
            print(f"{sess.name}'s last errors and voice log:", *(sess.errors[-12:] + sess.trace[-25:]), sep="\n  ")
        raise
    finally:
        # every browser here is one Playwright started and closes with it
        for br in launched:
            try:
                br.close()
            except Exception:
                pass
        srv.stop()
        if netsim:
            netsim.terminate()
    path = OUT / f"{args.label}.json"
    path.write_text(json.dumps(result, indent=1))
    short = {k: v for k, v in result.items() if k != "samples"}
    print(json.dumps(short, indent=1))
    print("wrote", path)


# The same patch call_quality.py uses: every candidate of the far end swapped for a leg of netsim that leads to it.
NETSIM_PATCH = r"""
(() => {
  const cfg = window.__netsim;
  if (!cfg) return;
  const Native = window.RTCPeerConnection;
  const legs = new Map();
  const legFor = (addr) => {
    if (!legs.has(addr)) legs.set(addr, fetch(cfg.ctrl + '/legs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ client: cfg.client, upstream: addr }) }).then((r) => r.json()).then((j) => j.port));
    return legs.get(addr);
  };
  const rewrite = async (sdp) => {
    const out = [];
    for (const line of sdp.split('\r\n')) {
      if (!line.startsWith('a=candidate:')) { out.push(line); continue; }
      const m = /^a=candidate:(\S+) (\d+) udp (\d+) ([0-9.]+) (\d+) typ (\S+)(.*)$/i.exec(line);
      if (!m) continue;
      const port = await legFor(`${m[4]}:${m[5]}`);
      out.push(`a=candidate:${m[1]} ${m[2]} udp ${m[3]} 127.0.0.1 ${port} typ ${m[6]}${m[7]}`);
    }
    return out.join('\r\n');
  };
  const srd = Native.prototype.setRemoteDescription;
  Native.prototype.setRemoteDescription = async function (desc) {
    if (desc && desc.sdp && desc.type !== 'rollback') desc = { type: desc.type, sdp: await rewrite(desc.sdp) };
    return srd.call(this, desc);
  };
  const aic = Native.prototype.addIceCandidate;
  Native.prototype.addIceCandidate = async function (c) { if (c && c.candidate) return; return aic.call(this, c); };
})();
"""

if __name__ == "__main__":
    main()
