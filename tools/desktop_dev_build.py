#!/usr/bin/env python3
"""Builds the desktop app for every platform for a test site, and nothing live.

  python3 tools/desktop_dev_build.py [--url https://dev.nexa.fish] [--to data-dev/desktop]

An installed app ignores environment overrides, so a client for a test site has
its address fixed when it is built (NEXA_BUILD_URL). It is also a different app:
"Nexa Dev", with its own bundle id and profile, so it installs beside the real
Nexa, never over it, and shares no sign-in with it.

It is not signed with the update key, which is for releases, so a test client
does not update itself: its updater finds no signed feed and stays as it is.
Install a newer one by hand. The release's own steps are not used:
tools/desktop_release.py and tools/publish_desktop.py publish to the live folder.
"""
from __future__ import annotations

import argparse, json, os, shutil, subprocess, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from publish_desktop import ARCH, KIND, OS, PATTERN, atomic_copy, sha256  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DESKTOP = ROOT / "desktop"
LIVE = ROOT / "data"


def run(*cmd: str, cwd: Path = ROOT, env: dict | None = None) -> None:
    print("  $", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True, env=env)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="https://dev.nexa.fish")
    ap.add_argument("--to", default=str(ROOT / "data-dev/desktop"))
    ap.add_argument("--work", default=str(Path.home() / ".cache/nexa-desktop-dev"), help="where the builds are made; about 6 GB")
    args = ap.parse_args()
    to = Path(args.to).resolve()
    if to == (LIVE / "desktop").resolve() or LIVE.resolve() in to.parents or os.environ.get("DESKTOP_DIR") and to == Path(os.environ["DESKTOP_DIR"]).resolve():
        sys.exit(f"{to} is what the live site serves")
    if args.url.rstrip("/") in ("https://nexa.fish", "https://mathtest.lol"):
        sys.exit("that is the live site: a test build is never pointed at it")

    work = Path(args.work)
    shutil.rmtree(work, ignore_errors=True)
    version = json.loads((DESKTOP / "package.json").read_text())["version"]
    print(f"desktop: building {version} for {args.url}")
    run("node", "build.mjs", cwd=DESKTOP, env=dict(os.environ, NEXA_BUILD_URL=args.url))
    identity = [
        "--publish", "never",
        f"-c.directories.output={work}",
        "-c.appId=xyz.evilion.chat.dev",
        "-c.productName=Nexa Dev",
        "-c.extraMetadata.name=nexa-desktop-dev",
        "-c.extraMetadata.productName=Nexa Dev",
        f"-c.publish.url={args.url}/desktop/",
        "-c.nsis.shortcutName=Nexa Dev",
        "-c.linux.executableName=nexa-dev",
        "-c.linux.desktop.entry.StartupWMClass=nexa-dev",
    ]
    try:
        run("npx", "electron-builder", "--win", "--linux", *identity, cwd=DESKTOP)
        run("npx", "electron-builder", "--mac", "dir", "--x64", "--arm64", *identity, cwd=DESKTOP)
        run(sys.executable, "tools/mac_package.py", str(work))
    finally:
        # dist-app is the bundle a release also packs; it must not keep this address
        run("node", "build.mjs", cwd=DESKTOP)

    to.mkdir(parents=True, exist_ok=True)
    files = []
    for p in sorted(work.iterdir()):
        m = PATTERN.match(p.name) if p.is_file() else None
        if not m or m["ext"] not in KIND:
            continue
        atomic_copy(p, to / p.name)
        files.append({"os": OS[m["os"]], "arch": ARCH[m["arch"]], "kind": KIND[m["ext"]], "name": p.name, "size": p.stat().st_size, "sha256": sha256(p)})
        print(f"  {p.name} ({p.stat().st_size / 1048576:.1f} MB)")
    if not files:
        sys.exit("nothing was built")
    # the download page reads this; there is deliberately no signed feed beside it
    tmp = to / "manifest.json.tmp"
    tmp.write_text(json.dumps({"version": version, "released_at": int(time.time() * 1000), "files": files}, indent=2))
    tmp.replace(to / "manifest.json")
    print(f"built {version} for {args.url} into {to}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
