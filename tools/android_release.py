#!/usr/bin/env python3
"""Builds the Android app's release APKs and publishes them where chatd serves them.

  python3 tools/android_release.py [--notes "What changed"] [--if-changed]

Builds android/ with Gradle (`:app:assembleRelease`, signed with the release
key configured locally in android/signing, pointed at https://nexa.fish),
checks every APK it made (package, version, minimum Android, signing
certificate, and that no test server is baked into it), then copies the
64-bit, 32-bit and universal builds into downloads/android under names that
stay the same from version to version, and writes manifest.json beside them.

`make build` runs it with --if-changed: a release is built only when something
the app is made from (android/, and the web files it copies in) changed since
the one published in that folder, so a deploy that touched only the server or
the web offers phones no update. Each release is numbered after the one
published there: versionCode one higher, versionName's last number one
higher (both given to Gradle, so nothing in the repository is edited).

chatd reads that folder on every request (internal/api/android_handlers.go),
so the app's updater offers the new build at once. The APKs and manifest are
not kept in git: each server builds and publishes its own.

  --if-changed      do nothing when the app's sources match the published build
  --skip-build      publish the APKs already in android/app/build/outputs/apk/release
  --from DIR        publish the release APKs in DIR instead (implies --skip-build)
  --to DIR          where to publish (default downloads/android)
  --server URL      the server the build talks to (default https://nexa.fish)

Over the air first: with --if-changed, when only the web content the app copies in
changed (OTA_SOURCES: translations, What's New, legal text, sounds, seasonal art)
and the code it was built from did not, no APK is built. The content is zipped
into ota-<hash>.zip with ota.json beside manifest.json, for the published APK's
versionCode only, and the app (fish.nexa.data.Ota) takes it up on its next start.
Anything else (Kotlin, resources, Gradle, twemoji) builds a new APK, which carries
its content itself and removes the pack.

  --ota             publish a content pack now (refused when the code changed)

Android installs an update only over a lower versionCode signed with the same
key, which is why the private local key must be backed up and never replaced.
"""
from __future__ import annotations

import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, time, zipfile
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
OUTPUT = ANDROID / "app/build/outputs/apk/release"
PACKAGE = "fish.nexa"
SERVER = "https://nexa.fish"
# What is offered: most phones, older 32-bit ones, and the build for anything else.
ABIS = ("arm64-v8a", "armeabi-v7a", "universal")
ORDER = {"arm64-v8a": 0, "armeabi-v7a": 1, "x86_64": 2, "universal": 3}
APK_NAME = re.compile(r"^app-(?P<abi>arm64-v8a|armeabi-v7a|x86_64|universal)-release(?P<unsigned>-unsigned)?\.apk$")
# API level to the version people know
ANDROID_VERSIONS = {21: "5.0", 22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1", 26: "8.0", 27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16", 37: "17"}
# Addresses only a build pointed at a computer on this desk would carry.
LOCAL = re.compile(rb"https?://(?:127\.0\.0\.1|10\.0\.2\.2|10\.0\.3\.2|localhost|\[::1\])(?::\d+)?")


def fail(msg: str) -> NoReturn:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def sdk_dir() -> Path | None:
    """The Android SDK, from the environment, android/local.properties or where it usually is."""
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            return Path(os.environ[env])
    props = ANDROID / "local.properties"
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.strip().startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\:", ":"))
    for guess in ("/opt/homebrew/share/android-commandlinetools", "/usr/local/share/android-commandlinetools", "~/android/sdk", "~/Library/Android/sdk", "~/Android/Sdk"):
        p = Path(guess).expanduser()
        if (p / "build-tools").is_dir():
            return p
    return None


def sdk_tool(name: str) -> str | None:
    """The newest build-tools copy of a tool."""
    sdk = sdk_dir()
    if sdk:
        found = sorted((sdk / "build-tools").glob(f"*/{name}"), key=lambda p: [int(n) if n.isdigit() else 0 for n in p.parent.name.split(".")], reverse=True)
        if found:
            return str(found[0])
    return shutil.which(name)


def java_home() -> str | None:
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    if Path("/usr/libexec/java_home").exists():
        for want in ("21", "17"):
            out = subprocess.run(["/usr/libexec/java_home", "-v", want], capture_output=True, text=True)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
    # the Linux server keeps its JDK beside the SDK (CLAUDE.md, Commands)
    if (Path.home() / "android/jdk/bin/java").exists():
        return str(Path.home() / "android/jdk")
    return None


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# What the app is made from: android/ and the web client's files its build copies in
# (CopyWebAssets in android/app/build.gradle.kts).
# Code needs an APK; content can go over the air (OTA_FILES must match fish.nexa.data.Ota's ALLOWED).
CODE_SOURCES = ("android", "web/src/assets/twemoji-sprites")
OTA_SOURCES = ("web/src/i18n/locales", "web/public/sounds", "web/src/assets/seasonal", "web/src/lib/whatsNew.ts", "docs/legal", "web/src/lib/legal.ts")
SOURCES = CODE_SOURCES + OTA_SOURCES
# (source folder or file, name in the pack, which files): as CopyWebAssets copies them
OTA_FILES = (("web/src/i18n/locales", "locales", ".json"), ("web/public/sounds", "sounds", ".m4a"),
             ("web/src/assets/seasonal", "seasonal", ".svg"), ("docs/legal", "legal", ".txt"))
OTA_SINGLE = (("web/src/lib/whatsNew.ts", "whatsnew/whatsNew.ts"), ("web/src/lib/legal.ts", "legal/legal.ts"))


def source_hash(server: str, sources: tuple[str, ...] = SOURCES) -> str:
    """One hash of every file the app is built from, as it is on disk now (git's list, so build output is left out)."""
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *sources], cwd=ROOT, capture_output=True)
    if out.returncode != 0:
        fail("git could not list the app's sources")
    h = hashlib.sha256(server.encode() + b"\0")
    for name in sorted(set(out.stdout.decode().split("\0")) - {""}):
        path = ROOT / name
        if not path.is_file():
            continue  # deleted but not yet committed
        h.update(name.encode() + b"\0" + sha256(path).encode() + b"\0")
    return h.hexdigest()


def drop_ota(dest: Path) -> None:
    for f in [dest / "ota.json", *dest.glob("ota-*.zip")]:
        if f.exists():
            f.unlink()
            print(f"  removed {f.name}")


def publish_ota(dest: Path, previous: dict, ota_source: str) -> int:
    """Zips the current content for the published APK and writes ota.json."""
    code = int(previous.get("version_code") or 0)
    if not code:
        fail("no APK is published for a content pack to go with")
    try:
        old = json.loads((dest / "ota.json").read_text())
    except (OSError, ValueError):
        old = {}
    if old.get("source") == ota_source and old.get("version_code") == code and (dest / old.get("name", "-")).is_file():
        print(f"the content pack for {previous.get('version')} ({code}) is up to date; nothing to publish")
        return 0
    entries: list[tuple[str, Path]] = []
    for folder, into, ext in OTA_FILES:
        for f in sorted((ROOT / folder).iterdir()):
            if f.is_file() and f.name.endswith(ext):
                entries.append((f"{into}/{f.name}", f))
    for src, into in OTA_SINGLE:
        entries.append((into, ROOT / src))
    fd, tmp = tempfile.mkstemp(dir=dest, prefix=".ota-", suffix=".zip")
    os.close(fd)
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name, f in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))  # the same content, the same bytes
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, f.read_bytes())
    digest = sha256(Path(tmp))
    pid = digest[:16]
    name = f"ota-{pid}.zip"
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest / name)
    pack = {"id": pid, "version_code": code, "name": name, "size": (dest / name).stat().st_size, "sha256": digest,
            "source": ota_source, "released_at": int(time.time() * 1000)}
    fd, tmp = tempfile.mkstemp(dir=dest, prefix=".ota-", suffix=".json")
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(pack, indent=2) + "\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest / "ota.json")
    for stale in dest.glob("ota-*.zip"):
        if stale.name != name:
            stale.unlink()
    print(f"published content pack {pid} over the air for Nexa for Android {previous.get('version')} ({code}); phones take it up on their next start")
    return 0


def next_version(previous: dict) -> tuple[int, str]:
    """The release after the one published (or after what Gradle carries when nothing is)."""
    gradle = (ANDROID / "app/build.gradle.kts").read_text()
    base_code = int(m.group(1)) if (m := re.search(r"toIntOrNull\(\) \?: (\d+)", gradle)) else 1
    base_name = m.group(1) if (m := re.search(r'takeIf \{ it.isNotEmpty\(\) \} \?: "([^"]+)"', gradle)) else "1.0.0"
    code = max(int(previous.get("version_code") or 0), base_code) + 1
    name = str(previous.get("version") or base_name)
    parts = name.split(".")
    if parts[-1].isdigit():
        parts[-1] = str(int(parts[-1]) + 1)
    else:
        parts.append("1")
    return code, ".".join(parts)


def build(server: str, code: int, name: str) -> None:
    props = ANDROID / "signing/release.properties"
    if not props.is_file():
        fail("Release signing is not configured. Restore android/signing/release.properties and its original keystore, or follow android/signing/README.md for a new distribution.")
    store = next((l.split("=", 1)[1].strip() for l in props.read_text().splitlines() if l.strip().startswith("storeFile=")), "")
    if not store or not (ANDROID / store).is_file():
        fail(f"android/signing/release.properties names the keystore {store or '(nothing)'}, which is not in android/.")
    env = dict(os.environ)
    if jh := java_home():
        env["JAVA_HOME"] = jh
    if (sdk := sdk_dir()) and not (ANDROID / "local.properties").is_file():
        env.setdefault("ANDROID_HOME", str(sdk))
    # last time's APKs must not be mistaken for this build's
    if OUTPUT.is_dir():
        for old in OUTPUT.glob("*.apk"):
            old.unlink()
    print(f"building Nexa for Android {name} ({code}) against {server} (this takes a few minutes)")
    run = subprocess.run(["./gradlew", ":app:assembleRelease", f"-PserverUrl={server}", f"-PnexaVersionCode={code}", f"-PnexaVersionName={name}"], cwd=ANDROID, env=env)
    if run.returncode != 0:
        fail("the Gradle build failed; nothing was published")


def badging(apk: Path) -> dict:
    """Package, version, version code and minimum API level, as aapt2 reads them from the APK itself."""
    aapt = sdk_tool("aapt2")
    if not aapt:
        fail("aapt2 was not found: install the Android SDK build-tools (or set ANDROID_HOME)")
    out = subprocess.run([aapt, "dump", "badging", str(apk)], capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"aapt2 could not read {apk.name}: {out.stderr.strip()[:300]}")
    info: dict = {}
    if m := re.search(r"package: name='([^']+)'", out.stdout):
        info["package"] = m.group(1)
    if m := re.search(r"versionCode='(\d+)'", out.stdout):
        info["version_code"] = int(m.group(1))
    if m := re.search(r"versionName='([^']+)'", out.stdout):
        info["version"] = m.group(1)
    if m := re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", out.stdout):
        info["min_sdk"] = int(m.group(1))
    return info


def signer(apk: Path) -> tuple[str, str]:
    """The signing certificate's SHA-256 (as app links write it) and its name, once the signature checks out."""
    tool = sdk_tool("apksigner")
    if not tool:
        fail("apksigner was not found: install the Android SDK build-tools (or set ANDROID_HOME)")
    env = dict(os.environ)
    if jh := java_home():
        env["JAVA_HOME"] = jh
        env["PATH"] = f"{jh}/bin:{env.get('PATH', '')}"
    out = subprocess.run([tool, "verify", "--print-certs", str(apk)], capture_output=True, text=True, env=env)
    if out.returncode != 0:
        fail(f"{apk.name} is not properly signed: {(out.stderr or out.stdout).strip()[:300]}")
    digest = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", out.stdout)
    dn = re.search(r"certificate DN: (.+)", out.stdout)
    if not digest:
        fail(f"could not read {apk.name}'s signing certificate")
    raw = digest.group(1).upper()
    return ":".join(raw[i : i + 2] for i in range(0, 64, 2)), (dn.group(1).strip() if dn else "")


def check_server(apk: Path, server: str) -> None:
    """Refuses a build that talks to a test server rather than the one it is published for."""
    wanted = server.rstrip("/").encode()
    seen_server = False
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            data = z.read(name)
            seen_server = seen_server or wanted in data
            for hit in LOCAL.findall(data):
                if hit != wanted:
                    fail(f"{apk.name} carries {hit.decode()}: it was built against a test server. Build it again with this script.")
    if not seen_server:
        print(f"note: {apk.name} does not name {server}; check that it was built for it", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description="Build and publish the Android app's release APKs into the repository.")
    ap.add_argument("--notes", default="", help="what changed, which the app can show when it offers the update")
    ap.add_argument("--if-changed", action="store_true", help="do nothing when the app's sources match the published build (what make build runs)")
    ap.add_argument("--ota", action="store_true", help="publish the web content as an over-the-air pack for the published APK (refused when the code changed)")
    ap.add_argument("--skip-build", action="store_true", help="publish the APKs already built in android/app/build/outputs/apk/release")
    ap.add_argument("--from", dest="src", help="publish the release APKs in this folder (implies --skip-build)")
    ap.add_argument("--to", default=str(ROOT / "downloads/android"), help="where to publish (default downloads/android)")
    ap.add_argument("--server", default=SERVER, help=f"the server the build talks to (default {SERVER})")
    ap.add_argument("--package", default=PACKAGE, help=f"the application id every build must carry (default {PACKAGE})")
    args = ap.parse_args()

    dest = Path(args.to)
    previous: dict = {}
    if (dest / "manifest.json").is_file():
        try:
            previous = json.loads((dest / "manifest.json").read_text())
        except ValueError:
            previous = {}
    source = source_hash(args.server)
    published = previous.get("files") and all((dest / f.get("name", "")).is_file() for f in previous["files"])
    if args.if_changed and published and previous.get("source") == source:
        print(f"Nexa for Android {previous.get('version')} ({previous.get('version_code')}) is up to date; nothing to build")
        return 0
    code_source = source_hash(args.server, CODE_SOURCES)
    ota_source = source_hash(args.server, OTA_SOURCES)
    # OTA whenever we can: the code is what the published APK was built from, only content moved
    code_same = bool(published) and previous.get("code_source") == code_source
    if args.ota and not code_same:
        fail("the app's code changed since the published APK (or it predates code hashes): this needs a new APK")
    if (args.ota or args.if_changed) and code_same:
        dest.mkdir(parents=True, exist_ok=True)
        return publish_ota(dest, previous, ota_source)

    src = Path(args.src) if args.src else OUTPUT
    if not args.src and not args.skip_build:
        build(args.server, *next_version(previous))

    found: dict[str, Path] = {}
    for apk in sorted(src.glob("*.apk")) if src.is_dir() else []:
        m = APK_NAME.match(apk.name)
        if not m:
            continue
        if m["unsigned"]:
            fail(f"{apk.name} is unsigned: android/signing/release.properties was not picked up")
        found[m["abi"]] = apk
    missing = [abi for abi in ABIS if abi not in found]
    if missing:
        fail(f"no release APK for {', '.join(missing)} in {src}")

    builds = []
    for abi in ABIS:
        apk = found[abi]
        info = badging(apk)
        if info.get("package") != args.package:
            fail(f"{apk.name} is {info.get('package') or 'an unknown package'}, not {args.package}")
        cert, dn = signer(apk)
        if "CN=Android Debug" in dn:
            fail(f"{apk.name} is signed with the debug key ({dn}); a phone could never update it with a real release")
        check_server(apk, args.server)
        builds.append((abi, apk, info, cert))

    versions = {(b[2].get("version"), b[2].get("version_code")) for b in builds}
    certs = {b[3] for b in builds}
    if len(versions) != 1 or len(certs) != 1:
        fail(f"the APKs disagree: versions {sorted(map(str, versions))}, certificates {len(certs)}")
    (version, version_code), cert = versions.pop(), certs.pop()
    if not version or not version_code:
        fail("could not read the version from the APKs")
    min_sdk = builds[0][2].get("min_sdk", 0)

    # The private local key must stay the same across releases so installed apps can update.
    old_cert = previous.get("signing_sha256")
    if old_cert and old_cert != cert:
        print(f"note: this build is signed with a new key ({cert});\n  phones with the build signed by {old_cert} cannot update to it and must uninstall and install again")
    old_code = int(previous.get("version_code") or 0)
    if previous.get("files") and version_code <= old_code:
        same = {f.get("abi"): f.get("sha256") for f in previous.get("files", [])} == {abi: sha256(apk) for abi, apk, _, _ in builds}
        if same:
            print(f"{version} ({version_code}) is already published in {dest}; nothing to do")
            return 0
        fail(f"versionCode {version_code} is not above the published {old_code}: a phone would refuse the update")

    dest.mkdir(parents=True, exist_ok=True)
    files = []
    for abi, apk, _, _ in sorted(builds, key=lambda b: ORDER[b[0]]):
        name = f"nexa-android-{abi}.apk"
        # copied beside its final name first, so the folder never holds half a file
        fd, tmp = tempfile.mkstemp(dir=dest, prefix=".publish-", suffix=".apk")
        os.close(fd)
        shutil.copyfile(apk, tmp)
        os.chmod(tmp, 0o644)
        os.replace(tmp, dest / name)
        files.append({"abi": abi, "name": name, "size": (dest / name).stat().st_size, "sha256": sha256(dest / name)})
        print(f"  {abi:12} {name}  {files[-1]['size'] / (1 << 20):.1f} MB")
    # the new APK carries its own content: a pack made for the old one goes
    drop_ota(dest)
    keep = {f["name"] for f in files}
    for stale in dest.glob("*.apk"):
        if stale.name not in keep:
            stale.unlink()
            print(f"  removed {stale.name}")

    manifest = {
        "version": version,
        "version_code": version_code,
        "released_at": int(time.time() * 1000),
        "min_sdk": min_sdk,
        "min_android": ANDROID_VERSIONS.get(min_sdk, f"API {min_sdk}"),
        "package": args.package,
        "signing_sha256": cert,
        "files": files,
    }
    if not args.src and not args.skip_build:
        # what it was built from, so --if-changed knows when the next one is due
        manifest["source"] = source
        manifest["code_source"] = code_source
    if args.notes:
        manifest["notes"] = args.notes
    fd, tmp = tempfile.mkstemp(dir=dest, prefix=".manifest-", suffix=".json")
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(manifest, indent=2) + "\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest / "manifest.json")

    try:
        shown = dest.resolve().relative_to(ROOT)
    except ValueError:
        shown = dest
    print(f"\npublished Nexa for Android {version} ({version_code}) to {shown}; chatd offers it at once")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
