#!/usr/bin/env python3
"""
Game detection, checked in the desktop app itself on Linux.

Starts a throwaway chatd (E2E_CHATD and E2E_WEB_DIST name the scratch build, as
for e2e.py), seeds alice and bob, builds desktop/dist-app and runs it under Xvfb
with a home folder of its own holding a made-up Steam install. Small stand-in
programs (built here; /bin/sleep will not run under another name on some systems)
run as the games:

  - a game Steam's manifests name, and its crash handler, which must not count
  - a game with Rich Presence, which wins over detecting it, by process id
  - a program nothing knows, added by hand from the running apps, and shared
  - a program with a game's name that fills the screen, which asks first. No
    window manager can say what is in front here, so EVILION_TEST_GAME_BEHAVIOUR
    does. It needs IGDB's keys in the environment (IGDB_CLIENT_ID and
    IGDB_CLIENT_SECRET) and the name index, which takes several minutes to build
    on a new server, so that part runs only with --behaviour.

Then a game staff add from the panel reaches the app on its next start. Every
wait has a deadline. Windows and macOS cannot be run from here.

  tools/.venv/bin/python tools/desktop_games.py [--behaviour]
"""
import argparse, json, os, shutil, subprocess, sys, tempfile, time
from pathlib import Path

from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).parent))
from e2e import Server  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
PW = "correct horse battery staple"
PORT = 9334
SLEEPER = 'package main\n\nimport "time"\n\nfunc main() { time.Sleep(time.Hour) }\n'
FRAME = r"""
const net = require("node:net");
const frame = (op, obj) => { const b = Buffer.from(JSON.stringify(obj)); const h = Buffer.alloc(8); h.writeInt32LE(op, 0); h.writeInt32LE(b.length, 4); return Buffer.concat([h, b]); };
const s = net.connect(process.env.XDG_RUNTIME_DIR + "/discord-ipc-0", () => s.write(frame(0, { v: 1, client_id: "123456789012345678" })));
let sent = false;
s.on("data", () => { if (sent) return; sent = true; s.write(frame(1, { cmd: "SET_ACTIVITY", nonce: "1", args: { pid: Number(process.argv[2]), activity: { name: "Balatro", details: "Ante 8" } } })); });
setTimeout(() => process.exit(0), 600000);
"""


class Checks:
    ok = True

    def __call__(self, label, cond, detail=""):
        self.ok &= bool(cond)
        print(f"  {'PASS' if cond else 'FAIL'} {label} {detail}", flush=True)


check = Checks()


def wait_for(fn, seconds=60, every=1.0):
    end = time.time() + seconds
    while time.time() < end:
        got = fn()
        if got:
            return got
        time.sleep(every)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--behaviour", action="store_true")
    args = ap.parse_args()
    electron = ROOT / "desktop/node_modules/electron/dist/electron"
    if not electron.exists():
        sys.exit("Electron's binary is missing: run `node node_modules/electron/install.js` in desktop/")
    subprocess.run(["node", "build.mjs"], cwd=ROOT / "desktop", check=True, capture_output=True)
    # a socket's path has a short limit, so the runtime folder sits at the top of /tmp
    run = Path(tempfile.mkdtemp(prefix="nxg", dir="/tmp"))
    work = Path(tempfile.mkdtemp())
    home = work / "home"
    steam = home / ".local/share/Steam/steamapps"
    (steam / "common").mkdir(parents=True)
    (steam / "libraryfolders.vdf").write_text(f'"libraryfolders"\n{{\n\t"0"\n\t{{\n\t\t"path"\t\t"{steam.parent}"\n\t}}\n}}\n')
    for app, name in ((1145350, "Hades II"), (2379780, "Balatro")):
        (steam / f"appmanifest_{app}.acf").write_text(f'"AppState"\n{{\n"appid" "{app}"\n"name" "{name}"\n"installdir" "{name}"\n}}\n')
    src = work / "sleeper"
    src.mkdir()
    (src / "main.go").write_text(SLEEPER)
    subprocess.run(["go", "build", "-o", "sleeper", "main.go"], cwd=src, check=True)
    programs = {
        "hades": steam / "common/Hades II/Hades2.x86_64",
        "crash": steam / "common/Hades II/UnityCrashHandler64",
        "balatro": steam / "common/Balatro/Balatro.x86_64",
        "indie": home / "games/Cool Indie Game/coolindie",
        "unknown": home / "apps/Copy/hk_game",
    }
    procs, running = [], {}
    for key, path in programs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(src / "sleeper", path)
        running[key] = subprocess.Popen([str(path)])
        procs.append(running[key])
    behaviour = work / "behaviour.json"
    behaviour.write_text(json.dumps({"foreground": {"pid": running["unknown"].pid, "title": "Hollow Knight v1.5.78", "fullscreen": True}}))
    profile = work / "profile"
    profile.mkdir()
    (profile / "settings.json").write_text(json.dumps({"launchAtLogin": False, "rpcRelay": False}))
    srv = Server()
    app = None
    try:
        subprocess.run([sys.executable, str(ROOT / "tools/seed.py"), srv.base, "--no-formatting"], check=True, capture_output=True, timeout=300)
        env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / ".local/share"), XDG_CONFIG_HOME=str(home / ".config"), XDG_RUNTIME_DIR=str(run), APP_MAIN=str(ROOT / "desktop/dist-app/main.js"), EVILION_URL=srv.base, EVILION_USER_DATA=str(profile), EVILION_NO_LOGIN_ITEM="1", EVILION_TEST_GAME_BEHAVIOUR=str(behaviour))

        def start():
            return subprocess.Popen(["xvfb-run", "-a", "-s", "-screen 0 1400x900x24", str(electron), str(ROOT / "tools/desktop_games"), f"--remote-debugging-port={PORT}", "--no-sandbox"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        app = start()
        with sync_playwright() as pw:
            def page_of():
                def find():
                    try:
                        br = pw.chromium.connect_over_cdp(f"http://127.0.0.1:{PORT}")
                    except Exception:
                        return None
                    return next((p for c in br.contexts for p in c.pages if srv.base in p.url), None)
                p = wait_for(find, 60)
                if not p:
                    sys.exit("the app did not open")
                p.set_default_timeout(30000)
                return p

            page = page_of()
            page.fill("input[autocomplete=username]", "alice")
            page.fill("input[type=password]", PW)
            page.click("button[type=submit]")
            page.wait_for_url("**/channels/@me", timeout=60000)
            for sel, act in (("[data-testid=terms-check]", "check"), ("[data-testid=terms-agree]", "click"), ("[data-testid=whats-new-done]", "click")):
                try:
                    page.wait_for_selector(sel, timeout=5000)
                    getattr(page, act)(sel)
                except Exception:
                    pass
            state = lambda: page.evaluate("window.evilionDesktop.games.getState()")
            acts = lambda: page.evaluate("window.evilionDesktop.getActivities()")
            names = lambda: [a["name"] for a in acts()]
            wait_for(lambda: len(state()["running"]) >= 2, 40)
            check("games named by Steam's manifests", {"Hades II", "Balatro"} <= set(names()), names())
            check("a game's crash handler is no game of its own", names().count("Hades II") == 1)
            hades = next(a for a in acts() if a["name"] == "Hades II")
            check("carries the store's id", hades.get("store_id") == "1145350", hades)

            client = subprocess.Popen(["node", "-e", FRAME, str(running["balatro"].pid)], env=env)
            procs.append(client)
            own = wait_for(lambda: next((a for a in acts() if a["name"] == "Balatro" and a.get("details")), None), 30)
            check("a program reporting the game wins", own and not own.get("detected"), own)
            check("and the game shows once", names().count("Balatro") == 1, names())

            apps = page.evaluate("window.evilionDesktop.games.runningApps()")
            check("the running apps carry no paths", str(work) not in json.dumps(apps))
            row = next((a for a in apps if a["exe"] == "coolindie"), None)
            check("a program nothing knows is offered", row and row["name"] == "Cool Indie Game", row)
            page.evaluate("id => window.evilionDesktop.games.add(id, 'Celeste', null)", row["id"])
            check("adding one shows it", wait_for(lambda: "Celeste" in names(), 10), names())
            added = next(g for g in state()["custom"] if g["name"] == "Celeste")
            info = page.evaluate("id => window.evilionDesktop.games.shareInfo(id)", added["id"])
            check("sharing sends a file name, never a path", info and info["process"] == "coolindie" and str(work) not in json.dumps(info), info)

            if args.behaviour:
                wait_for(lambda: page.evaluate("fetch('/api/v1/games/name-index').then(r => r.status)") == 200, 900, 10)
                app.terminate()
                app.wait(timeout=20)
                app = start()
                page = page_of()
                asked = wait_for(lambda: state()["suggestions"], 120)
                check("something filling the screen with a game's name asks", asked and asked[0]["name"] == "Hollow Knight", asked)
                check("and sends nothing before a yes", "Hollow Knight" not in names(), names())
                page.evaluate("id => window.evilionDesktop.games.answer(id, true)", asked[0]["id"])
                check("a yes shows it", wait_for(lambda: "Hollow Knight" in names(), 10), names())
    finally:
        for p in procs:
            p.terminate()
        if app:
            app.terminate()
        srv.stop()
        shutil.rmtree(run, ignore_errors=True)
        shutil.rmtree(work, ignore_errors=True)
    print("ALL PASS" if check.ok else "FAILED", flush=True)
    sys.exit(0 if check.ok else 1)


if __name__ == "__main__":
    main()
