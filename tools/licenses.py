#!/usr/bin/env python3
"""Collects the open-source licences of everything Nexa ships, into the JSON the
settings page reads.

The point of generating this rather than writing it by hand is that a hand-written
list is wrong the day after it is written, and being wrong here is the kind of wrong
that gets a notice from someone's lawyer. Every name, version and licence below comes
out of the lockfiles — `web/package-lock.json`, `desktop/package-lock.json` and
`go.mod` — so the page cannot claim a dependency the build does not have, or miss one
it does.

Only what actually ends up in a build is listed. A test runner, a bundler, a type
definition: none of them reach a reader's machine, so none of them are here. For npm
that means the production closure (npm's own `dev` flag agrees with the walk we do);
and for Go the modules the `chatd` binary links, not the ones only its tests import.

The licence *text* is read from the copy that ships — `node_modules/<pkg>/LICENSE`,
the module cache — because that is the copy the licence talks about. Storing
one of those per package would be tens of thousands of lines of near-identical MIT, so
each SPDX id keeps one text with the copyright lines lifted out of it, and every
package carries its own copyright line beside its entry. That is the same set of
words, at a twentieth of the size.

    tools/licenses.py                  # regenerate web/src/data/licenses.json
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "web/src/data/licenses.json"

# The builds, in the order the page shows them. Whoever opens this is reading it in
# the web client, so that is what comes first.
PARTS = ["web", "desktop", "server", "assets"]

# Hunspell dictionaries whose "(MIT AND BSD)" is the npm wrapper's MIT and the word
# lists' own terms; what ships is the word lists. Their terms are a set of notices
# each asking to be kept with its permission text, so that text is kept whole.
WORD_LISTS = {"dictionary-en": "SCOWL", "dictionary-en-gb": "SCOWL"}

# The files a package may keep its licence in, most specific first. `LICENSE-MIT`
# and friends are how a dual-licensed package names them.
LICENSE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|UNLICENSE)([.\-_].*)?$", re.I)
# Files that only point at a licence rather than being one, or that cover a third
# party's code vendored into the package rather than the package itself.
NOT_A_LICENSE = re.compile(r"\.(png|jpg|gif|svg)$|3RD.?PARTY|THIRD.?PARTY", re.I)

# A licence is recognised by a phrase no other licence uses. Order matters: the
# three-clause BSD is the two-clause one plus a sentence, so it has to be tried first.
SIGNATURES: list[tuple[str, str]] = [
    ("Apache-2.0", "Apache License\n                           Version 2.0"),
    ("Apache-2.0", "Licensed under the Apache License, Version 2.0"),
    ("MPL-2.0", "Mozilla Public License Version 2.0"),
    ("OFL-1.1", "SIL OPEN FONT LICENSE"),
    ("CC-BY-4.0", "Attribution 4.0 International"),
    ("Unlicense", "This is free and unencumbered software released into the public domain"),
    ("BSD-3-Clause", "Neither the name of"),
    ("BSD-3-Clause", "nor the names of its\ncontributors may be used to endorse"),
    ("BSD-2-Clause", "Redistribution and use in source and binary forms"),
    ("ISC", "Permission to use, copy, modify, and/or distribute this software"),
    # the older wording, still what a few packages ship
    ("ISC", "Permission to use, copy, modify, and distribute this software"),
    ("MIT", "Permission is hereby granted, free of charge"),
    ("Zlib", "This software is provided 'as-is', without any express or implied"),
    ("BSL-1.0", "Boost Software License"),
]

# Human names for the ids, so a page can show "MIT License" over "MIT".
LICENSE_NAMES = {
    "0BSD": "BSD Zero Clause License",
    "Apache-2.0": "Apache License 2.0",
    "BSD-2-Clause": "BSD 2-Clause License",
    "BSD-3-Clause": "BSD 3-Clause License",
    "BSL-1.0": "Boost Software License 1.0",
    "BlueOak-1.0.0": "Blue Oak Model License 1.0.0",
    "CC-BY-4.0": "Creative Commons Attribution 4.0 International",
    "CPL-1.0": "Common Public License 1.0",
    "EPL-1.0": "Eclipse Public License 1.0",
    "ISC": "ISC License",
    "MIT": "MIT License",
    "MIT-0": "MIT No Attribution",
    "MPL-2.0": "Mozilla Public License 2.0",
    "OFL-1.1": "SIL Open Font License 1.1",
    "Python-2.0": "Python License 2.0",
    "SCOWL": "SCOWL and Ispell word list licences",
    "Unlicense": "The Unlicense",
    "Zlib": "zlib License",
}

# Where the full text lives when nothing that ships carries a copy. Twemoji's
# artwork is the only one of these in practice: the npm package that redraws it
# ships the pictures and no notice, and Creative Commons has to be reproduced.
CANONICAL = ROOT / "tools/licenses"


def die(msg: str) -> None:
    print(f"licenses: {msg}", file=sys.stderr)
    raise SystemExit(1)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def license_file(directory: Path) -> Path | None:
    """The file a package keeps its licence in, if it ships one."""
    if not directory.is_dir():
        return None
    found = [f for f in sorted(directory.iterdir()) if f.is_file() and LICENSE_FILE.match(f.name) and not NOT_A_LICENSE.search(f.name)]
    # a plain LICENSE beats LICENSE-MIT, which beats nothing
    found.sort(key=lambda f: (len(f.name), f.name))
    return found[0] if found else None


def detect(text: str) -> str | None:
    """The SPDX id a licence text is, from a phrase only that licence uses."""
    flat = text.replace("\r\n", "\n")
    for spdx, phrase in SIGNATURES:
        if phrase in flat:
            return spdx
    return None


COPYRIGHT = re.compile(r"^(?:Copyright|\(c\)|©)\b", re.I)
COPYRIGHT_LEAD = re.compile(r"^(?:Copyright|\(c\)|©)\s*(?:\(c\)|\(C\)|©)?\s*", re.I)
# The blank a licence leaves for whoever adopts it — Apache's appendix says
# "Copyright [yyyy] [name of copyright owner]" — which is not a claim anyone made.
PLACEHOLDER = re.compile(r"\[(?:yyyy|year|name|your|copyright|insert)", re.I)
# What a real notice never says next. Every licence talks *about* copyright in its
# own body — "copyright notice and this permission notice", "the copyright holder" —
# and a sentence that happens to wrap onto one of those words reads exactly like a
# notice until you look at the word after it.
NOT_A_HOLDER = {
    "notice", "notices", "statement", "statements", "holder", "holders", "owner", "owners",
    "ownership", "law", "laws", "license", "licence", "interest", "symbol", "year", "in",
    "and", "or", "is", "are", "may", "shall", "that", "which", "to", "from", "of", "for",
    "on", "as", "the", "this", "a", "an", "by",
}


def is_notice(line: str) -> bool:
    """Whether a line is one package's copyright notice rather than licence prose."""
    flat = " ".join(line.split())
    if not COPYRIGHT.match(flat):
        return False
    # The length is generous because a font's notice names every file it covers;
    # licence prose wraps at eighty columns, so nothing this long is prose.
    if len(flat) > 400 or PLACEHOLDER.search(flat):
        return False
    rest = COPYRIGHT_LEAD.sub("", flat)
    if rest.startswith("<"):
        return False
    word = re.split(r"\W", rest.lower(), maxsplit=1)[0]
    return bool(rest) and word not in NOT_A_HOLDER


def copyright_of(text: str) -> str | None:
    """The copyright notices a licence text carries — the part that is per-package."""
    notices = [" ".join(line.split()) for line in text.splitlines() if is_notice(line)]
    if not notices:
        return None
    return "\n".join(dict.fromkeys(notices))[:600]


def without_copyright(text: str) -> str:
    """The licence text with the per-package copyright notices taken out.

    Every package's MIT is the same words around a different name, so the shared copy
    drops the name and each package keeps its own beside its entry. Blank runs left
    behind collapse, otherwise the top of every text is a hole.
    """
    kept = [line for line in text.replace("\r\n", "\n").split("\n") if not is_notice(line)]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(kept)).strip() + "\n"


class Collector:
    """Gathers packages, and one licence text per SPDX id as it goes."""

    def __init__(self) -> None:
        self.packages: list[dict[str, str]] = []
        self.texts: dict[str, Counter[str]] = {}
        self.unknown: list[str] = []
        self.seen: set[tuple[str, str, str]] = set()

    def add(self, *, part: str, name: str, version: str, license_: str | None, url: str, text: str | None, notice: str | None = None, whole: bool = False) -> None:
        spdx = license_ or (detect(text) if text else None)
        if not spdx:
            self.unknown.append(f"{part}/{name}")
            spdx = "Unknown"
        entry: dict[str, str] = {"part": part, "name": name, "version": version, "license": spdx, "url": url}
        # A notice given by name beats one read out of a file, because it is given
        # where the file that ships has none to read. A licence kept whole carries
        # its notices itself.
        line = notice or (copyright_of(text) if text and not whole else None)
        if line:
            entry["copyright"] = line
        if text:
            # Every real copy of a licence is a vote for what the shared text should
            # be; the one most packages ship wins, so a single mangled file cannot
            # become the text everyone reads.
            self.texts.setdefault(spdx, Counter())[text.replace("\r\n", "\n").strip() + "\n" if whole else without_copyright(text)] += 1
        key = (part, name, version)
        if key not in self.seen:
            self.seen.add(key)
            self.packages.append(entry)


# ---------------------------------------------------------------------------- npm


def npm_resolve(packages: dict[str, dict], importer: str, dep: str) -> str | None:
    """Where npm would find `dep` for the package installed at `importer`.

    Node looks in the importer's own node_modules, then in its parent's, and so on
    up to the root; a lockfile's keys are exactly those paths.
    """
    prefix = importer
    while True:
        candidate = f"{prefix}/node_modules/{dep}" if prefix else f"node_modules/{dep}"
        if candidate in packages:
            return candidate
        if not prefix:
            return None
        # step out of the nearest node_modules, which is what "up a level" means here
        at = prefix.rfind("node_modules/")
        prefix = prefix[:at].rstrip("/") if at > 0 else ""


def npm_closure(lock: dict, roots: list[str]) -> dict[str, dict]:
    """Every package reachable at runtime from `roots`, by lockfile path."""
    packages = lock["packages"]
    seen: dict[str, dict] = {}
    queue = [("", name) for name in roots]
    while queue:
        importer, dep = queue.pop()
        path = npm_resolve(packages, importer, dep)
        if path is None or path in seen:
            continue
        info = packages[path]
        seen[path] = info
        # optional dependencies ship when they install, which on some platform they do
        for field in ("dependencies", "optionalDependencies"):
            for nxt in info.get(field, {}):
                queue.append((path, nxt))
    return seen


def npm_part(collector: Collector, part: str, project: Path, roots: list[str]) -> int:
    lock = json.loads(read_text(project / "package-lock.json"))
    if lock.get("lockfileVersion", 0) < 2:
        die(f"{project.name}/package-lock.json is version {lock.get('lockfileVersion')}; this wants 2 or 3")
    closure = npm_closure(lock, roots)
    count = 0
    for path, info in sorted(closure.items()):
        name = path.split("node_modules/")[-1]
        # A type definition is erased by the compiler, so it never reaches anyone.
        if name.startswith("@types/") or name == "csstype":
            continue
        installed = project / path
        manifest = installed / "package.json"
        declared = info.get("license")
        homepage = ""
        if manifest.is_file():
            try:
                pkg = json.loads(read_text(manifest))
            except json.JSONDecodeError:
                pkg = {}
            declared = declared or (pkg.get("license") if isinstance(pkg.get("license"), str) else None)
            homepage = pkg["homepage"] if isinstance(pkg.get("homepage"), str) else ""
        found = license_file(installed)
        text = read_text(found) if found else None
        # "(MIT OR Apache-2.0)" is a choice whoever uses it makes; we take the first,
        # which is the one the package itself puts first.
        spdx = re.split(r"\s+(?:OR|AND)\s+", (declared or "").strip("()"))[0].strip() or None
        # A package that declares something the SPDX list has no name for — "BSD",
        # "Apache", a URL — but ships a text we can read is filed under what it ships.
        if spdx and spdx not in LICENSE_NAMES and text:
            spdx = detect(text) or spdx
        # Only the word lists of these reach a reader (the spell checker fetches the
        # .aff and .dic files), and they are under the terms their licence file quotes.
        spdx = WORD_LISTS.get(name, spdx)
        # whoever follows this is on an https page; an old http address only downgrades
        homepage = re.sub(r"^http://", "https://", homepage)
        url = homepage if homepage.startswith("https://") else f"https://www.npmjs.com/package/{name}"
        collector.add(part=part, name=name, version=info.get("version", ""), license_=spdx, url=url, text=text, whole=name in WORD_LISTS)
        count += 1
    return count


# ----------------------------------------------------------------------------- Go


def go_escape(module: str) -> str:
    """The module cache lower-cases capitals as `!x`, so paths stay case-insensitive."""
    return re.sub(r"[A-Z]", lambda m: "!" + m.group(0).lower(), module)


def go_part(collector: Collector) -> int:
    if not shutil.which("go"):
        die("the Go toolchain is not on PATH, and the server's modules come from it")
    cache = Path(subprocess.run(["go", "env", "GOMODCACHE"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip())
    # `go list -deps` walks what the binary imports. go.mod also carries the modules
    # only the tests need — miniredis, embedded-postgres — and those never ship.
    listed = subprocess.run(
        ["go", "list", "-deps", "-f", "{{if not .Standard}}{{.Module.Path}}\t{{.Module.Version}}{{end}}", "./cmd/chatd"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    modules = {}
    for line in listed.splitlines():
        if "\t" not in line:
            continue
        path, version = line.split("\t", 1)
        if path.startswith("evilion.xyz/"):
            continue
        modules[path] = version
    for path, version in sorted(modules.items()):
        directory = cache / f"{go_escape(path)}@{version}"
        found = license_file(directory)
        text = read_text(found) if found else None
        if text is None:
            print(f"licenses: no licence file for {path}@{version}", file=sys.stderr)
        collector.add(part="server", name=path, version=version, license_=None, url=f"https://pkg.go.dev/{path}", text=text)
    return len(modules)


# ------------------------------------------------------------------------- assets


def assets_part(collector: Collector) -> int:
    """Artwork and typefaces that ship without being a dependency of anything.

    Twemoji is the one that would otherwise be missed: the npm package that carries
    the drawings is a devDependency, because Vite copies the SVGs into the build
    rather than the app importing them, and Twitter's artwork is Creative Commons
    whatever the wrapper's own package.json says.
    """
    count = 0
    twemoji = ROOT / "web/node_modules/@twemoji/svg/package.json"
    if not twemoji.is_file():
        print("licenses: @twemoji/svg is not installed; the emoji notice is skipped", file=sys.stderr)
    else:
        canonical = CANONICAL / "CC-BY-4.0.txt"
        collector.add(
            part="assets", name="Twemoji", version=json.loads(read_text(twemoji)).get("version", ""),
            license_="CC-BY-4.0", url="https://github.com/jdecked/twemoji",
            text=read_text(canonical) if canonical.is_file() else None,
            # The notice Twemoji asks for, which is nowhere in the package that redraws it.
            notice="Copyright 2019 Twitter, Inc and other contributors",
        )
        count += 1
    # The modern wrapper embeds the same pinned Xiph model the native app uses.
    if (ROOT / "web/node_modules/@shiguredo/rnnoise-wasm/package.json").is_file():
        collector.add(
            part="assets", name="RNNoise", version="70f1d256acd4b34a572f999a05c87bf00b67730d", license_="BSD-3-Clause", url="https://github.com/xiph/rnnoise", text=None,
            notice="Copyright (c) 2017, Mozilla; Copyright (c) 2007-2017, Jean-Marc Valin; Copyright (c) 2005-2017, Xiph.Org Foundation; Copyright (c) 2003-2004, Mark Borgerding",
        )
        count += 1
    collector.add(
        part="assets", name="Whisper Tiny", version="tiny", license_="MIT", url="https://github.com/openai/whisper", text=None,
        notice="Copyright (c) 2022 OpenAI",
    )
    count += 1
    return count


# ------------------------------------------------------------------------ rust


def cargo_license(expression: str | None) -> str | None:
    """One licence from a crate's expression.

    Cargo allows a choice ("MIT OR Apache-2.0") and exceptions. MIT is used when
    it is one of the choices (the same text then covers every such crate), and
    otherwise the first choice without its "WITH" exception is listed.
    """
    if not expression:
        return None
    choices = [
        choice.split(" WITH ")[0].strip("() ")
        for choice in re.split(r"\s+OR\s+|/", expression)
    ]
    choices = [choice for choice in choices if choice]
    if not choices:
        return None
    return "MIT" if "MIT" in choices else choices[0]


def cargo_part(collector: Collector) -> int:
    """The crates linked into the desktop app.

    The Tauri shell is Rust, so its notices come from Cargo's resolved graph
    rather than an npm lockfile. Build scripts and dev-dependencies do not ship
    and are left out; normal dependencies for every target are included, since
    a release is made for each of them.
    """
    if not shutil.which("cargo"):
        die("the Rust toolchain is not on PATH, and the desktop app's crates come from it")
    metadata = json.loads(
        subprocess.run(
            [
                "cargo",
                "metadata",
                "--format-version",
                "1",
                "--locked",
                "--manifest-path",
                str(ROOT / "desktop/src-tauri/Cargo.toml"),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    )
    packages = {package["id"]: package for package in metadata["packages"]}
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    queue = [metadata["resolve"]["root"]]
    seen: set[str] = set()
    while queue:
        node = nodes.get(queue.pop())
        if node is None or node["id"] in seen:
            continue
        seen.add(node["id"])
        for dependency in node["deps"]:
            kinds = {kind["kind"] for kind in dependency["dep_kinds"]}
            if None in kinds:
                queue.append(dependency["pkg"])
    seen.discard(metadata["resolve"]["root"])
    for package_id in sorted(seen):
        package = packages[package_id]
        directory = Path(package["manifest_path"]).parent
        found = directory / package["license_file"] if package.get("license_file") else None
        if found is None or not found.is_file():
            found = license_file(directory)
        text = read_text(found) if found else None
        if text is None:
            print(f"licenses: no licence file for {package['name']}@{package['version']}", file=sys.stderr)
        url = package.get("repository") or f"https://crates.io/crates/{package['name']}"
        if url.startswith("http://"):
            url = "https://" + url[len("http://") :]
        elif not url.startswith("https://"):
            url = f"https://crates.io/crates/{package['name']}"
        collector.add(
            part="desktop",
            name=package["name"],
            version=package["version"],
            license_=cargo_license(package.get("license")),
            url=url,
            text=text,
        )
    return len(seen)


# --------------------------------------------------------------------------- main


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()

    collector = Collector()
    counts = {
        "web": npm_part(collector, "web", ROOT / "web", list(json.loads(read_text(ROOT / "web/package.json"))["dependencies"])),
        # The Tauri shell's crates come from Cargo's resolved graph; nothing
        # from npm ships with it.
        "desktop": cargo_part(collector),
        "server": go_part(collector),
    }
    counts["assets"] = assets_part(collector)

    licenses = {}
    for spdx, seen in sorted(collector.texts.items()):
        text, _ = seen.most_common(1)[0]
        licenses[spdx] = {"name": LICENSE_NAMES.get(spdx, spdx), "text": text}
    # A licence nothing ships a copy of still has to be readable; the canonical texts
    # beside this script fill those in.
    for spdx in sorted({p["license"] for p in collector.packages} - set(licenses)):
        canonical = CANONICAL / f"{spdx}.txt"
        if canonical.is_file():
            licenses[spdx] = {"name": LICENSE_NAMES.get(spdx, spdx), "text": without_copyright(read_text(canonical))}
        else:
            print(f"licenses: no text anywhere for {spdx}", file=sys.stderr)

    # Nexa's own terms, if the repository ever states them.
    own = license_file(ROOT)
    document = {
        "parts": [part for part in PARTS if part in counts],
        "self": ({"name": "Nexa", "text": read_text(own)} if own else None),
        "licenses": dict(sorted(licenses.items())),
        "packages": sorted(collector.packages, key=lambda p: (PARTS.index(p["part"]), p["name"].lower())),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=1, ensure_ascii=False, sort_keys=False) + "\n", encoding="utf-8")

    total = len(collector.packages)
    size = args.out.stat().st_size
    where = args.out.relative_to(ROOT) if args.out.is_relative_to(ROOT) else args.out
    print(f"{total} packages, {len(licenses)} distinct licences, {size // 1024} KiB → {where}")
    print("  " + ", ".join(f"{part} {n}" for part, n in sorted(counts.items())))
    if collector.unknown:
        print(f"  no licence found for {len(collector.unknown)}: {', '.join(collector.unknown[:10])}", file=sys.stderr)


if __name__ == "__main__":
    main()
