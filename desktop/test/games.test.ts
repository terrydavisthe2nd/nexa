import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { judge, nameHash, nameVariants, foldName, NameIndex } from "../src/main/games/candidates";
import { cleanKnownGames } from "../src/main/games/known";
import { desktopProgram, installedGame, libraryFolders, parseBinaryVdf, parseDesktopEntry, parseUninstall, parseVdf, steamShortcuts } from "../src/main/games/library";
import { plistValue } from "../src/main/games/markers";
import { cleanName, emptyLibrary, entryOf, identify, norm, type Library, type Proc } from "../src/main/games/match";
import { parseCmdline, parseDrmFdinfo, parseWindowOwners, parsePs, parseStat, parseXprop, splitCommandLine } from "../src/main/games/processes";
import { GameService, pickable, type GameServiceOptions } from "../src/main/games/service";

const proc = (path: string, args?: string[], pid = 100): Proc => ({ pid, ppid: 1, path, ...(args ? { args } : {}) });

function library(): Library {
  const l = emptyLibrary();
  const cs = { id: "730", name: "Counter-Strike 2" };
  l.steam.set("counter-strike global offensive", cs);
  l.steamById.set("730", cs);
  l.roots.push({ key: "epic:fn", name: "Fortnite", source: "epic", root: norm("D:\\Epic\\Fortnite") });
  return l;
}

describe("what stores write", () => {
  it("reads Steam's library list and manifests, old and new", () => {
    const now = parseVdf(`"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"C:\\\\Program Files (x86)\\\\Steam"\n\t\t"apps" { "730" "123" }\n\t}\n\t"1" { "path" "D:\\\\Games" }\n}`);
    expect(libraryFolders(now)).toEqual(["C:\\Program Files (x86)\\Steam", "D:\\Games"]);
    expect(libraryFolders(parseVdf(`"LibraryFolders" { "TimeNextStatsReport" "1" "1" "E:\\\\Steam" }`))).toEqual(["E:\\Steam"]);
    const app = parseVdf(`// comment\n"AppState"\n{\n"appid" "730"\n"name" "Say \\"hi\\""\n"installdir" "Foo"\n}`).AppState as Record<string, string>;
    expect(app.name).toBe('Say "hi"');
  });

  it("reads what the system says about a process", () => {
    expect(parseStat("123 (a) b) c) S 7 1 1 34816 1 0 0 0 0 0 0 0 0 0 20 0 1 0 555 0 0")).toEqual({ ppid: 7, tty: true, start: 555 });
    expect(parseCmdline("java\0-jar\0a b.jar\0")).toEqual(["java", "-jar", "a b.jar"]);
    expect(parsePs("  12   1  1-02:03:04 /Applications/My Game.app/Contents/MacOS/My Game\n  13 12 00:05 /bin/zsh\n", 1_000_000_000).map((p) => [p.path, p.startedAt])).toEqual([
      ["/Applications/My Game.app/Contents/MacOS/My Game", 1_000_000_000 - 93784000],
      ["/bin/zsh", 1_000_000_000 - 5000],
    ]);
    expect(splitCommandLine('"C:\\Program Files\\x.exe" -a "b c"')).toEqual(["C:\\Program Files\\x.exe", "-a", "b c"]);
  });
});

describe("what else a computer writes about its games", () => {
  it("reads Steam's non-Steam shortcuts", () => {
    const b = (...parts: (string | number)[]) => Buffer.concat(parts.map((x) => (typeof x === "number" ? Buffer.from([x]) : Buffer.from(`${x}\0`))));
    const file = Buffer.concat([b(0x00, "shortcuts", 0x00, "0", 0x01, "appname", "Foo Game", 0x01, "exe", '"C:\\Games\\Foo\\foo.exe"', 0x02, "hidden"), Buffer.from([1, 0, 0, 0]), Buffer.from([0x08, 0x08, 0x08])]);
    expect(steamShortcuts(parseBinaryVdf(file))).toEqual([{ name: "Foo Game", exe: "C:\\Games\\Foo\\foo.exe" }]);
  });

  it("finds what launchers registered with Windows, and leaves the rest", () => {
    const text = [
      "HKEY_LOCAL_MACHINE\\SOFTWARE\\Uninstall\\Diablo IV",
      "    DisplayName    REG_SZ    Diablo IV",
      "    InstallLocation    REG_SZ    D:\\Games\\Diablo IV",
      '    UninstallString    REG_SZ    "C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe" --uid=fenris',
      "    Publisher    REG_SZ    Blizzard Entertainment",
      "HKEY_LOCAL_MACHINE\\SOFTWARE\\Uninstall\\Battle.net",
      "    DisplayName    REG_SZ    Battle.net",
      "    InstallLocation    REG_SZ    C:\\Program Files (x86)\\Battle.net",
      "    Publisher    REG_SZ    Blizzard Entertainment",
      "HKEY_LOCAL_MACHINE\\SOFTWARE\\Uninstall\\7-Zip",
      "    DisplayName    REG_SZ    7-Zip 24.08",
      "    InstallLocation    REG_SZ    C:\\Program Files\\7-Zip",
      "    Publisher    REG_SZ    Igor Pavlov",
    ].join("\r\n");
    expect(parseUninstall(text).map(installedGame).filter(Boolean)).toMatchObject([{ name: "Diablo IV", root: "d:/games/diablo iv" }]);
  });

  it("reads what a Linux desktop and a Mac app say they are", () => {
    const e = parseDesktopEntry("[Desktop Entry]\nName=SuperTuxKart\nExec=supertuxkart %f\nCategories=Game;ArcadeGame;\n\n[Desktop Action x]\nName=Other\n");
    expect(e).toMatchObject({ name: "SuperTuxKart", categories: ["Game", "ArcadeGame"] });
    expect(desktopProgram("/usr/bin/flatpak run --branch=stable --command=supertuxkart net.supertuxkart.SuperTuxKart")).toBe("supertuxkart");
    expect(desktopProgram("env GDK_BACKEND=x11 /opt/foo/foo-bin %U")).toBe("foo-bin");
    expect(desktopProgram("steam steam://rungameid/570")).toBeNull();
    expect(desktopProgram("lutris lutris:rungameid/3")).toBeNull();
    expect(plistValue("<plist><dict><key>LSApplicationCategoryType</key>\n<string>public.app-category.action-games</string></dict></plist>", "LSApplicationCategoryType")).toBe("public.app-category.action-games");
    expect(parseDrmFdinfo("pos: 0\ndrm-client-id:\t12\ndrm-engine-gfx:\t100 ns\ndrm-engine-render:\t50 ns\n")).toEqual({ client: "12", engineNs: 150 });
    expect(parseXprop('_NET_WM_PID(CARDINAL) = 42\n_NET_WM_NAME(UTF8_STRING) = "Foo \\"Bar\\""\n_NET_WM_STATE(ATOM) = _NET_WM_STATE_FULLSCREEN\n')).toEqual({ pid: 42, title: 'Foo "Bar"', fullscreen: true });
  });

  it("takes only a well-formed list from the server", () => {
    expect(cleanKnownGames({ games: [{ name: "Good", exes: ["Good.EXE"], game_id: "12" }, { name: "No exes", exes: [] }, { exes: ["x"] }, "junk"] })).toEqual([{ name: "Good", exes: ["good.exe"], game_id: "12" }]);
    expect(cleanKnownGames({ games: "nope" })).toBeNull();
  });
});

describe("games found by how they behave", () => {
  /** An index of these names, as the server writes one. */
  function index(rated: string[], other: string[] = []): NameIndex {
    const sorted = (names: string[]) => names.map(nameHash).sort(Buffer.compare);
    const a = sorted(rated);
    const b = sorted(other);
    const head = Buffer.alloc(13);
    head.write("NXGI", 0, "latin1");
    head[4] = 1;
    head.writeUInt32BE(a.length, 5);
    head.writeUInt32BE(b.length, 9);
    return NameIndex.parse(Buffer.concat([head, ...a, ...b]))!;
  }
  const full = { fullscreen: true, gpu: false, osGame: false };
  const both = { fullscreen: true, gpu: true, osGame: false };

  it("hashes names as the server does", () => {
    const vectors = JSON.parse(readFileSync("../internal/games/testdata/fold_vectors.json", "utf8")) as { Name: string; Fold: string; Hash: string }[];
    for (const v of vectors) {
      expect(foldName(v.Name), v.Name).toBe(v.Fold);
      expect(nameHash(v.Name).toString("hex"), v.Name).toBe(v.Hash);
    }
  });

  it("reads a game's name out of what windows and files say", () => {
    const has = (raw: string, want: string) => expect(nameVariants(raw).map(foldName), raw).toContain(foldName(want));
    has("Hollow Knight v1.5.78.11833", "Hollow Knight");
    has("Cyberpunk 2077 (C) 2020 by CD Projekt RED", "Cyberpunk 2077");
    has("DOOM Eternal (Vulkan)", "DOOM Eternal");
    has("Skyrim Special Edition - 144 FPS", "Skyrim");
    has("Terraria: Shut Up and Dig Gaiden!", "Terraria");
    has("Portal 2 - Direct3D 11", "Portal 2");
    has("The Witcher 3: Wild Hunt - Game of the Year Edition", "The Witcher 3: Wild Hunt");
    has("Foo Game™ | Steam", "Foo Game");
    expect(nameVariants("60 FPS")).toEqual([]);
  });

  it("asks only when behaviour and a game's name agree", () => {
    const ix = index(["Hollow Knight", "Balatro", "Notes"], ["Some Obscure Game", "Scrivener"]);
    const p = (path: string) => proc(path);
    expect(judge(p("C:\\Games\\HK\\hollow_knight.exe"), { title: "Hollow Knight" }, full, ix)).toMatchObject({ name: "Hollow Knight", tier: "rated" });
    // one rated word on its own needs a second place saying it
    expect(judge(p("C:\\Stuff\\x\\run_it.exe"), { title: "Balatro" }, full, ix)).toBeNull();
    expect(judge(p("C:\\Games\\Balatro\\Balatro.exe"), { title: "Balatro" }, full, ix)).toMatchObject({ name: "Balatro" });
    // an obscure game's name asks for both kinds of behaviour
    expect(judge(p("C:\\x\\y\\z.exe"), { title: "Some Obscure Game" }, full, ix)).toBeNull();
    expect(judge(p("C:\\x\\y\\z.exe"), { title: "Some Obscure Game" }, both, ix)).toMatchObject({ tier: "other" });
    // somebody's game called the same as an app says nothing
    expect(judge(p("C:\\Apps\\Scrivener\\Scrivener.exe"), { title: "Scrivener" }, full, ix)).toBeNull();
    expect(judge(p("C:\\Apps\\Notes\\notes.exe"), { title: "Notes" }, both, ix)).toBeNull();
    // the same name, with nothing behaving like a game, or in a program that never is one
    expect(judge(p("C:\\Games\\HK\\hollow_knight.exe"), { title: "Hollow Knight" }, { fullscreen: false, gpu: false, osGame: true }, ix)).toBeNull();
    expect(judge(p("C:\\Program Files\\VideoLAN\\vlc.exe"), { title: "Hollow Knight" }, both, ix)).toBeNull();
    expect(judge(p("C:\\Chrome\\chrome.exe"), { title: "Hollow Knight - YouTube" }, both, ix)).toBeNull();
    expect(NameIndex.parse(Buffer.from("not an index"))).toBeNull();
  });
});

describe("which game a process is", () => {
  const find = (p: Proc, l = library()) => identify(p, l, []);

  it("names a Steam game from its manifest, wherever the library is", () => {
    expect(find(proc("D:\\SteamLibrary\\steamapps\\common\\Counter-Strike Global Offensive\\game\\bin\\win64\\cs2.exe"))).toMatchObject({ key: "steam:730", name: "Counter-Strike 2", store: "steam", storeId: "730" });
    expect(find(proc("/mnt/x/steamapps/common/Hades II/Hades2.x86_64"))).toMatchObject({ name: "Hades II", source: "steam" });
  });

  it("sees through Proton and Wine to the game", () => {
    expect(find(proc("/home/a/.local/share/Steam/steamapps/common/Proton 9.0/dist/bin/wine64-preloader", ["Z:\\home\\a\\.local\\share\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\cs2.exe"]))).toMatchObject({ key: "steam:730" });
    expect(find(proc("/usr/bin/wine64-preloader", ["C:\\Games\\FF\\ffxiv_dx11.exe"]))).toMatchObject({ name: "Final Fantasy XIV Online" });
    expect(find(proc("/usr/bin/wine64-preloader", ["C:\\windows\\system32\\services.exe"]))).toBeNull();
    expect(find(proc("/x/Steam/ubuntu12_32/reaper", ["SteamLaunch", "AppId=730", "--", "x"]))).toMatchObject({ key: "steam:730" });
  });

  it("leaves out what sits beside a game without being it", () => {
    for (const exe of ["UnityCrashHandler64.exe", "EasyAntiCheat_EOS_Setup.exe", "launcher.exe", "vc_redist.x64.exe", "Foo_BE.exe".replace("Foo_BE", "BEService"), "CrashReportClient.exe"]) {
      expect(find(proc(`C:\\S\\steamapps\\common\\Foo\\${exe}`)), exe).toBeNull();
    }
    // but a game may simply be called this
    expect(find(proc("C:\\S\\steamapps\\common\\Crash Bandicoot\\CrashBandicoot.exe"))).not.toBeNull();
    expect(find(proc("C:\\S\\steamapps\\common\\Proton 9.0\\x.exe"))).toBeNull();
    expect(find(proc("C:\\S\\steamapps\\common\\Wallpaper Engine\\wallpaper32.exe"))).toBeNull();
    expect(find(proc("C:\\S\\steamapps\\common\\Foo Dedicated Server\\foo.exe"))).toBeNull();
    expect(find(proc("C:\\Program Files\\Riot Games\\Riot Client\\RiotClientServices.exe"))).toBeNull();
  });

  it("takes stores' folders and a game's own file name, not a shared one", () => {
    expect(find(proc("D:\\Epic\\Fortnite\\FortniteGame\\Binaries\\Win64\\Foo.exe"))).toMatchObject({ key: "epic:fn", name: "Fortnite" });
    expect(find(proc("C:\\Riot Games\\VALORANT\\live\\VALORANT.exe"))).toMatchObject({ name: "VALORANT" });
    expect(find(proc("C:\\XboxGames\\Starfield\\Content\\Starfield.exe"))).toMatchObject({ name: "Starfield", source: "xbox" });
    expect(find(proc("C:\\Battle.net Games\\World of Warcraft\\_retail_\\Wow.exe"))).toMatchObject({ name: "World of Warcraft", source: "known" });
    expect(find(proc("C:\\Tools\\launcher.exe"))).toBeNull();
    expect(find(proc("/usr/bin/java", ["-Xmx4G", "-cp", "a.jar", "net.minecraft.client.main.Main"]))).toMatchObject({ name: "Minecraft" });
    expect(find(proc("/usr/bin/java", ["-jar", "/opt/ide.jar"]))).toBeNull();
  });

  it("puts a game someone added first, and tells Java programs apart", () => {
    const p = proc("/usr/bin/java", ["-Xmx1G", "-cp", "x", "com.foo.Game"]);
    expect(entryOf(p)).toBe("java:com.foo.Game");
    const custom = [{ id: "a", name: "Mine", match: norm(entryOf(p)) }];
    expect(identify(p, library(), custom)).toMatchObject({ key: "custom:a", name: "Mine", source: "custom" });
    expect(identify(proc("/usr/bin/java", ["com.other.App"]), library(), custom)).toBeNull();
  });

  it("takes exact programs and the system's own word first", () => {
    const l = library();
    l.programs.set(norm("/home/a/foo/foo"), { key: "shortcut:foo", name: "Foo Game", source: "steam", root: "" });
    l.files.set("supertuxkart", { key: "system:stk", name: "SuperTuxKart Deluxe", source: "system", root: "" });
    expect(identify(proc("/home/a/foo/foo"), l, [])).toMatchObject({ name: "Foo Game" });
    expect(identify(proc("/usr/bin/wine64-preloader", ["Z:\\home\\a\\foo\\foo"]), l, [])).toMatchObject({ name: "Foo Game" });
    expect(identify(proc("/usr/games/supertuxkart"), l, [])).toMatchObject({ name: "SuperTuxKart Deluxe", source: "system" });
  });

  it("makes a usable name of a file", () => {
    expect(cleanName("hollow_knight.exe", true)).toBe("Hollow Knight");
    expect(cleanName("Foo-Win64-Shipping.exe", true)).toBe("Foo");
    expect(cleanName("My Game-1.2.3-linux", true)).toBe("My Game");
    expect(cleanName("Balatro™")).toBe("Balatro");
  });
});

describe("choosing from what is running", () => {
  it("offers windows, not the system's background", () => {
    expect(pickable(proc("C:\\Games\\Foo\\foo.exe"), true, "win32")).toBe(true);
    expect(pickable(proc("C:\\Games\\Foo\\foo.exe"), false, "win32")).toBe(false);
    expect(pickable(proc("C:\\Windows\\explorer.exe"), true, "win32")).toBe(false);
    expect(pickable(proc("/Applications/Foo.app/Contents/MacOS/Foo"), false, "darwin")).toBe(true);
    expect(pickable(proc("/Applications/Foo.app/Contents/Frameworks/Foo Helper.app/Contents/MacOS/Foo Helper"), false, "darwin")).toBe(false);
    expect(pickable(proc("/usr/lib/systemd/systemd"), false, "linux")).toBe(false);
    expect(pickable({ ...proc("/home/a/vim"), tty: true }, false, "linux")).toBe(false);
    expect(pickable(proc("/home/a/games/foo"), false, "linux")).toBe(true);
  });
});

describe("the service", () => {
  function service(procs: { list: Proc[] }, over: Partial<GameServiceOptions> = {}) {
    let t = 1000;
    const saved: unknown[] = [];
    const s = new GameService({
      enabled: true,
      custom: [],
      hidden: [],
      ownPids: () => new Set([1]),
      claims: () => [],
      save: (p) => saved.push(p),
      lister: { list: async () => procs.list, windows: async () => new Map() },
      readLibrary: async () => library(),
      readMarkers: async () => null,
      platform: "linux",
      now: () => (t += 1000),
      ...over,
    });
    return { s, saved };
  }
  const game: Proc = { pid: 50, ppid: 40, path: "/g/steamapps/common/Counter-Strike Global Offensive/cs2", startedAt: 5000 };

  it("gives way to a program reporting the same game, by process and by name", async () => {
    const claims: { pid?: number; name?: string; game: boolean }[] = [];
    const { s } = service({ list: [{ pid: 40, ppid: 1, path: "/bin/bash" }, game] }, { claims: () => claims });
    await s.scan();
    expect(s.activities()).toMatchObject([{ name: "Counter-Strike 2", detected: true, store: "steam", store_id: "730", timestamps: { start: 5000 } }]);
    claims.push({ pid: 50, game: true });
    expect(s.activities()).toEqual([]);
    expect(s.state().running[0]?.reporting).toBe(true);
    claims[0] = { pid: 40, game: false }; // its parent, as a launcher that reports for it
    expect(s.activities()).toEqual([]);
    claims[0] = { name: "counter-strike 2", game: true };
    expect(s.activities()).toEqual([]);
    claims[0] = { pid: 999, name: "Spotify", game: false };
    expect(s.activities()).toHaveLength(1);
    s.stop();
  });

  it("keeps hidden games to itself, and sends nothing while off", async () => {
    const { s, saved } = service({ list: [game] });
    await s.scan();
    s.setHidden("steam:730", "Counter-Strike 2", true);
    expect(s.activities()).toEqual([]);
    expect(saved).toHaveLength(1);
    expect(s.state().running[0]?.hidden).toBe(true);
    s.setHidden("steam:730", "Counter-Strike 2", false);
    s.setEnabled(false);
    expect(s.activities()).toEqual([]);
    expect(s.state().running).toEqual([]);
    s.stop();
  });

  it("holds on through one missed look, and lets go after the second", async () => {
    const procs = { list: [game] };
    const { s } = service(procs);
    await s.scan();
    procs.list = [];
    await s.scan();
    expect(s.state().running).toHaveLength(1);
    await s.scan();
    expect(s.state().running).toHaveLength(0);
    s.stop();
  });

  it("asks about a game nothing knew, and only sends on a yes", async () => {
    const hash = nameHash("Nightfall Hollow");
    const head = Buffer.alloc(13);
    head.write("NXGI", 0, "latin1");
    head[4] = 1;
    head.writeUInt32BE(1, 5);
    const lists = { known: { games: [] }, index: NameIndex.parse(Buffer.concat([head, hash])), load: async () => undefined, refresh: async () => false };
    const game: Proc = { pid: 70, ppid: 1, path: "/home/me/Nightfall Hollow/nf", startedAt: 9 };
    const { s, saved } = service(
      { list: [game] },
      { lists, isWebApp: async () => false, lister: { list: async () => [game], windows: async () => new Map(), foreground: async () => ({ pid: 70, title: "Nightfall Hollow", fullscreen: true }), gpuBusy: async () => new Set() } },
    );
    await s.scan();
    expect(s.state().suggestions).toEqual([]); // one look is not enough
    for (let i = 0; i < 6; i++) await s.scan();
    const [asked] = s.state().suggestions;
    expect(asked).toMatchObject({ name: "Nightfall Hollow", exe: "nf" });
    expect(s.activities()).toEqual([]);
    s.answer(asked!.id, false);
    expect(s.state().suggestions).toEqual([]);
    expect(JSON.stringify(saved)).toContain("nightfall hollow/nf");
    for (let i = 0; i < 13; i++) await s.scan();
    expect(s.state().suggestions).toEqual([]); // no means never asking again
    s.stop();
  });

  it("shares a file name and never a path", async () => {
    const app: Proc = { pid: 60, ppid: 1, path: "/home/me/secret/My Own Game/mygame", startedAt: 1 };
    const { s } = service({ list: [app] });
    const [row] = await s.runningApps();
    s.add(row!.id, "My Own Game", null);
    const id = s.state().custom[0]!.id;
    expect(s.shareInfo(id)).toEqual({ process: "mygame", os: "linux", name: "My Own Game" });
    s.markShared(id);
    expect(s.state().custom[0]?.shared).toBe(true);
    s.stop();
  });

  it("counts a game only while it has a window, as Roblox in the menu bar has none", async () => {
    const roblox: Proc = { pid: 80, ppid: 1, path: "/Applications/Roblox.app/Contents/MacOS/RobloxPlayer", startedAt: 1 };
    let owners: Set<number> | null = new Set([999]);
    const lister = { list: async () => [roblox, { pid: 81, ppid: 80, path: "/tmp/render" }, { pid: 70, ppid: 1, path: "/Steam/steam" }], windows: async () => new Map(), gameWindows: async () => owners };
    const { s } = service({ list: [] }, { lister });
    await s.scan();
    expect(s.activities()).toEqual([]); // in the tray
    owners = new Set([80]);
    await s.scan();
    expect(s.activities()).toMatchObject([{ name: "Roblox" }]); // in a game
    owners = new Set([999]);
    await s.scan();
    await s.scan();
    expect(s.activities()).toEqual([]); // back in the tray, after the one look of grace
    owners = new Set([81]);
    await s.scan();
    expect(s.activities()).toHaveLength(1); // a window in something it started counts
    owners = null;
    await s.scan();
    expect(s.activities()).toHaveLength(1); // the system not saying must never hide a game
    s.stop();

    // Steam started the game and owns a window: that window is not the game's
    const game: Proc = { pid: 50, ppid: 70, path: "/g/steamapps/common/Counter-Strike Global Offensive/cs2" };
    const steam = service({ list: [] }, { lister: { list: async () => [{ pid: 70, ppid: 1, path: "/Steam/steam" }, game], windows: async () => new Map(), gameWindows: async () => new Set([70]) } });
    await steam.s.scan();
    expect(steam.s.activities()).toEqual([]);
    steam.s.stop();
    expect(parseWindowOwners("[1,2]")).toEqual(new Set([1, 2]));
    expect(parseWindowOwners("execution error")).toBeNull();
  });

  it("lets a game someone added count with no window", async () => {
    const app: Proc = { pid: 60, ppid: 1, path: "/home/me/Tool/mytool" };
    const { s } = service({ list: [app] }, { lister: { list: async () => [app], windows: async () => new Map([[60, "Tool"]]), gameWindows: async () => new Set<number>() } });
    const [row] = await s.runningApps();
    s.add(row!.id, "My Tool", null);
    await s.scan();
    expect(s.activities()).toMatchObject([{ name: "My Tool" }]);
    s.stop();
  });

  it("gives the page no paths, and adds only what it listed", async () => {
    const app: Proc = { pid: 60, ppid: 1, path: "/home/me/secret/My Own Game/mygame", startedAt: 1 };
    const { s, saved } = service({ list: [app, { pid: 2, ppid: 1, path: "/usr/lib/systemd/systemd" }] });
    const [row, ...rest] = await s.runningApps();
    expect(rest).toEqual([]);
    expect(JSON.stringify(row)).not.toContain("secret");
    expect(row).toMatchObject({ name: "My Own Game", exe: "mygame", added: false });
    s.add("not-listed", "Nope", null);
    expect(s.state().custom).toEqual([]);
    s.add(row!.id, "My Game", "1234");
    expect(JSON.stringify(s.state())).not.toContain("secret");
    expect(s.state().custom).toMatchObject([{ name: "My Game", exe: "mygame", gameId: "1234" }]);
    expect(JSON.stringify(saved)).toContain("/home/me/secret/my own game/mygame");
    await s.scan();
    expect(s.activities()).toMatchObject([{ name: "My Game", game_id: "1234", detected: true }]);
    s.remove(s.state().custom[0]!.id);
    expect(s.activities()).toEqual([]);
    s.stop();
  });
});
