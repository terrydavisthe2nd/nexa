// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api as client } from "@/api/client";
import type { Channel, PublicUser, Relationship } from "@/api/types";
import { messageMenu } from "@/components/chat/messageMenu";
import { commands as commandList, runCommand } from "@/components/composer/commands";
import { runPluginKeybinds, usePluginKeybinds } from "@/lib/pluginKeybinds";
import { parseBind } from "@/lib/keybinds";
import { dispatchHooks } from "@/gateway/dispatch";
import { resetChanges } from "@/lib/contextMenus/registry";
import type { DecryptedMessage } from "@/lib/decrypt";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { lookCache } from "../cache";
import { codeHash } from "../hash";
import { packText } from "../pack";
import type { PluginEntry } from "../types";
import { PermissionError } from "./api2";
import { toUser } from "./objects";
import { forgetPlugins, moduleLoader, syncPlugins, usePlugins } from "./runtime";

/* Test plugins are object literals turned into modules here, since Node cannot import a blob: URL. */
const g = globalThis as unknown as Record<string, unknown>;
type Api = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const apiOf = (name: string) => g[name] as Api;

beforeEach(async () => {
  await lookCache.clear();
  moduleLoader.load = async (code: string) => {
    // eslint-disable-next-line @typescript-eslint/no-implied-eval
    return { default: new Function(`return (${code});`)() as unknown };
  };
  usePlugins.setState({ byId: {} });
  resetChanges();
  document.getElementById("nexa-custom-css")?.remove();
  useAuth.setState({ me: { id: "me", username: "me", display_name: "Me", flags: 0 } as never });
  useUsers.setState({ byId: { bob: { id: "bob", username: "bob", display_name: "Bob", flags: 0, created_at: 5 } as unknown as PublicUser } });
  useChannels.setState({ byId: { sc: { id: "sc", server_id: "s1", type: "text", name: "general" } as Channel, dm: { id: "dm", server_id: null, type: "dm", name: "", recipient_ids: ["me", "bob"] } as unknown as Channel } });
  useRelationships.setState({ byUser: {} });
});

afterEach(async () => {
  vi.useRealTimers();
  await forgetPlugins();
  vi.restoreAllMocks();
});

async function entry(code: string, permissions: string[] | null, id = "abcdefghij", settings: Record<string, unknown> = {}): Promise<PluginEntry> {
  return { id, source: "code", url: null, code: await packText(code), hash: codeHash(code), enabled: true, settings, meta: null, addedAt: 1, ...(permissions ? { permissions } : {}) };
}

const v2 = (name: string, body = "", extra = "") => `{
  name: ${JSON.stringify(name)}, apiVersion: 2, ${extra}
  start(api) { globalThis[${JSON.stringify(name)}] = api; ${body} },
}`;

const message = (channel: string, text: string | null): DecryptedMessage =>
  ({ id: "1", channel_id: channel, author_id: "bob", type: "default", ...(text === null ? {} : { payload: { t: text } }), mentions: [], mention_roles: [], mention_everyone: false, reactions: [], flags: 0, reply_to_id: null, created_at: 1000, edited_at: null }) as unknown as DecryptedMessage;

describe("which API a plugin gets", () => {
  it("hands version 2 to a plugin that asks for it, and version 1 to the rest", async () => {
    await syncPlugins([await entry(v2("Two"), []), await entry(`{ name: "One", start(api) { globalThis.One = api; } }`, null, "bcdefghijk")], true);
    expect(apiOf("Two")["apiVersion"]).toBe(2);
    expect(apiOf("Two")["users"]).toBeDefined();
    expect(apiOf("One")["apiVersion"]).toBe(1);
    expect(apiOf("One")["css"]).toBeDefined();
  });

  it("will not start a plugin written for a version there is not", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const e = await entry(`{ name: "Three", apiVersion: 3, start() {} }`, []);
    await syncPlugins([e], true);
    expect(usePlugins.getState().byId[e.id]).toMatchObject({ status: "failed", error: expect.stringContaining("3") });
  });
});

describe("permissions", () => {
  it("throws a PermissionError without the permission, and works with it", async () => {
    const e = await entry(v2("Perm"), ["users.read"]);
    await syncPlugins([e], true);
    const a = apiOf("Perm");
    expect(a["users"].get("bob")).toMatchObject({ id: "bob", displayName: "Bob", relationship: null });
    expect(() => a["users"].me).toThrow(PermissionError);
    expect(() => a["servers"].list()).toThrow(/servers.read/);
    try {
      a["storage"].get("x");
    } catch (err) {
      expect((err as Error).name).toBe("PermissionError");
    }
  });

  it("takes a permission away at once, from calls and from events", async () => {
    const e = await entry(v2("Taken", `api.events.on("messageCreated", (m) => { globalThis.heardTaken = m.content; });`), ["users.read", "events.read", "servers.messages.read"]);
    await syncPlugins([e], true);
    const hear = (m: DecryptedMessage) => dispatchHooks.onMessage.forEach((h) => h({} as never, false, m));
    hear(message("sc", "first"));
    expect(g["heardTaken"]).toBe("first");
    await syncPlugins([{ ...e, permissions: ["events.read"] }], true);
    hear(message("sc", "second"));
    expect(g["heardTaken"]).toBe("first");
    expect(() => apiOf("Taken")["users"].get("bob")).toThrow(PermissionError);
  });
});

describe("events", () => {
  it("delivers server and DM messages by their own permissions, decrypted, or with no content", async () => {
    const got: unknown[] = [];
    g["gotEvents"] = got;
    await syncPlugins([await entry(v2("Ev", `api.events.on("messageCreated", (m) => globalThis.gotEvents.push([m.channelId, m.content, m.createdAt instanceof Date]));`), ["events.read", "servers.messages.read"])], true);
    for (const m of [message("sc", "server words"), message("dm", "dm words"), message("sc", null)]) dispatchHooks.onMessage.forEach((h) => h({} as never, false, m));
    expect(got).toEqual([["sc", "server words", true], ["sc", null, true]]);
  });

  it("turns the app's events into the API's, a relationship's kind by its API name", async () => {
    const got: unknown[] = [];
    g["gotRel"] = got;
    await syncPlugins([await entry(v2("Rel", `api.events.on("relationshipChanged", (e) => globalThis.gotRel.push([e.user && e.user.id, e.relationship, e.old]));`), ["events.read", "users.relationships.read", "users.read"])], true);
    useRelationships.getState().set({ user: useUsers.getState().byId["bob"]!, type: "incoming", nickname: "", created_at: 1 } as Relationship);
    expect(got).toEqual([["bob", "incomingRequest", "none"]]);
  });

  it("fires a custom event under the plugin's name, for other plugins to hear", async () => {
    await syncPlugins(
      [
        await entry(v2("Sender"), ["events.write"]),
        await entry(v2("Hearer", `api.events.on("Sender:ping", (d) => { globalThis.pinged = d; });`), ["events.read"], "bcdefghijk"),
      ],
      true,
    );
    apiOf("Sender")["events"].run("ping", { n: 1 });
    expect(g["pinged"]).toEqual({ n: 1 });
  });

  it("calls a once listener once, and a listener that throws stops nothing", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    g["onceCount"] = 0;
    await syncPlugins([await entry(v2("Once", `api.events.once("localeChanged", () => { globalThis.onceCount++; }); api.events.on("localeChanged", () => { throw new Error("no"); });`), ["events.read"])], true);
    dispatchHooks.onEvent.forEach((h) => h({ type: "localeChanged", locale: "de" }));
    dispatchHooks.onEvent.forEach((h) => h({ type: "localeChanged", locale: "fr" }));
    expect(g["onceCount"]).toBe(1);
  });
});

describe("styles", () => {
  const sheets = () => [...document.querySelectorAll('#nexa-custom-css [data-group="plugins"] style')].map((s) => s.textContent);

  it("removes a stylesheet when the plugin stops, unless it asked to keep it, which goes when the plugin is deleted", async () => {
    const e = await entry(v2("Css", `api.styles.add(".a{}"); api.styles.add(".b{}", true);`), ["styles.write"]);
    await syncPlugins([e], true);
    expect(sheets()).toEqual([".a{}", ".b{}"]);
    await syncPlugins([{ ...e, enabled: false }], true);
    expect(sheets()).toEqual([".b{}"]);
    await syncPlugins([], true);
    expect(sheets()).toEqual([]);
  });
});

describe("settings", () => {
  const SET = `settings: [{ key: "size", type: "number", label: "Size", min: 1, max: 5, default: 3 }, { key: "mode", type: "select", label: "Mode", options: [{ value: "a", label: "A" }, { value: "b", label: "B" }], default: "a" }],`;

  it("gives defaults, saves a value with the account, tells onChange, and refuses what does not fit", async () => {
    const e = await entry(v2("Prefs", `api.settings.onChange("size", (v, old) => { globalThis.sizeChange = [v, old]; });`, SET), []);
    useSettings.setState((s) => ({ settings: { ...s.settings, plugins: [e] } }));
    await syncPlugins([e], true);
    const st = apiOf("Prefs")["settings"];
    expect(st.get("size")).toBe(3);
    expect(st.get("nope")).toBeUndefined();
    st.set("size", 5);
    expect(st.get("size")).toBe(5);
    expect(g["sizeChange"]).toEqual([5, 3]);
    expect(useSettings.getState().settings.plugins[0]!.settings).toEqual({ size: 5 });
    expect(() => st.set("size", 9)).toThrow(/from 1 to 5/);
    expect(() => st.set("mode", "c")).toThrow(/one of a, b/);
    st.reset();
    expect(st.getAll()).toEqual({ size: 3, mode: "a" });
  });
});

describe("context menus", () => {
  const ids = () => messageMenu.items({ msg: { ...message("sc", "hi"), author_id: "me" } as DecryptedMessage, openReactionPicker: () => undefined }).map((i) => i.id);

  it("adds, edits and removes buttons on any menu, and takes it all back when the plugin stops", async () => {
    const e = await entry(
      v2(
        "Menu",
        `api.ui.contextMenu.add("message", { id: "quote", label: "Quote", after: "replyToMessage", onClick: (ctx) => { globalThis.quoted = ctx.message && ctx.message.content; } });
         api.ui.contextMenu.remove("deleteMessage");`,
      ),
      ["ui.write", "servers.messages.read"],
    );
    await syncPlugins([e], true);
    expect(ids()).toContain("plugin:quote");
    expect(ids()).not.toContain("delete");
    expect(ids().indexOf("plugin:quote")).toBe(ids().indexOf("reply") + 1);
    messageMenu.items({ msg: message("sc", "quote me"), openReactionPicker: () => undefined }).find((i) => i.id === "plugin:quote")!.onSelect!();
    expect(g["quoted"]).toBe("quote me");
    expect(apiOf("Menu")["ui"].contextMenu.get("deleteMessage")).toBeNull();
    await syncPlugins([{ ...e, enabled: false }], true);
    expect(ids()).not.toContain("plugin:quote");
    expect(ids()).toContain("delete");
  });
});

describe("the API's objects", () => {
  it("leaves out a user's relationship without the permission, and names it with it", () => {
    useRelationships.getState().set({ user: useUsers.getState().byId["bob"]!, type: "outgoing", nickname: "", created_at: 1 } as Relationship);
    const bob = useUsers.getState().byId["bob"]!;
    expect(toUser(bob, () => false).relationship).toBeNull();
    expect(toUser(bob, () => true).relationship).toBe("outgoingRequest");
    expect(Object.isFrozen(toUser(bob, () => true))).toBe(true);
  });
});

describe("managers", () => {
  it("fetches a message and decrypts it on this device, with no content when it cannot be read", async () => {
    vi.spyOn(client, "request").mockResolvedValue({ id: "9", channel_id: "sc", author_id: "bob", type: "default", ciphertext: null, mentions: [], mention_roles: [], mention_everyone: false, reactions: [], attachments: [], flags: 0, created_at: 1, edited_at: null, key_conv_id: null, key_epoch: null, reply_to_id: null } as never);
    await syncPlugins([await entry(v2("Msg"), ["servers.messages.read"])], true);
    expect(await apiOf("Msg")["messages"].fetch("sc", "9")).toMatchObject({ id: "9", content: null });
  });

  it("asks the server to update a member with only what changed, timeouts as times", async () => {
    const req = vi.spyOn(client, "request").mockResolvedValue({ server_id: "s1", user_id: "bob", nickname: "B", role_ids: [], joined_at: 0, timeout_until: null, flags: 0, avatar_id: null } as never);
    await syncPlugins([await entry(v2("Mem"), ["servers.members.write"])], true);
    const until = new Date(5000);
    await apiOf("Mem")["servers"].members.update("s1", 7n, { nickname: "B", timeoutUntil: until });
    expect(req).toHaveBeenCalledWith("PATCH", "/servers/s1/members/7", { nick: "B", timeout_until: 5000 });
  });

  it("asks before something with the app's own dialog, and hears no as false", async () => {
    await syncPlugins([await entry(v2("Ask"), ["ui.write"])], true);
    const answer = apiOf("Ask")["ui"].confirm({ title: "Sure?", message: "Really", confirmText: "Yes", cancelText: "No" });
    const modal = useUI.getState().modals.at(-1)!;
    expect(modal).toMatchObject({ kind: "confirm", props: { title: "Ask: Sure?", body: "Really", confirmLabel: "Yes", cancelLabel: "No" } });
    (modal.props!["onDismiss"] as () => void)();
    expect(await answer).toBe(false);
  });
});

describe("commands", () => {
  const OLD = `{ name: "Old", start(api) { globalThis.oldTook = (() => { try { api.commands.register({ name: "wave", run: () => "old wave" }); return "took"; } catch (e) { return "refused"; } })(); } }`;

  it("refuses the app's names and names with capitals or spaces", async () => {
    await syncPlugins([await entry(v2("Cmd"), ["commands.write"])], true);
    const reg = (n: string) => () => apiOf("Cmd")["commands"].register(n, "", () => undefined);
    expect(reg("shrug")).toThrow(/app's own/);
    expect(reg("Wave")).toThrow(/lowercase/);
    expect(reg("two words")).toThrow(/spaces/);
    expect(reg("x".repeat(33))).toThrow(/up to 32/);
  });

  it("runs with what was typed after it and who and where, sending nothing in its place", async () => {
    g["ran"] = null;
    await syncPlugins([await entry(v2("Runs", `api.commands.register("say-it", "Says it", (args, ctx) => { globalThis.ran = [args, ctx.channel && ctx.channel.id, ctx.user]; });`), ["commands.write", "servers.channels.read"])], true);
    expect(commandList().find((c) => c.name === "say-it")?.desc).toBe("Says it (from Runs)");
    const cmd = runCommand("/say-it hello there");
    expect(cmd.kind).toBe("plugin");
    if (cmd.kind === "plugin") expect(await cmd.run("sc")).toBeNull();
    expect(g["ran"]).toEqual(["hello there", "sc", null]);
  });

  it("takes over another plugin's command, which it does not get back; a v1 plugin is still refused a taken name", async () => {
    const old = await entry(OLD, null, "bcdefghijk");
    const taker = await entry(v2("Taker", `api.commands.register("wave", "", () => { globalThis.waved = "new"; });`), ["commands.write"]);
    await syncPlugins([old, taker], true);
    expect(g["oldTook"]).toBe("took");
    const cmd = runCommand("/wave");
    if (cmd.kind === "plugin") await cmd.run("sc");
    expect(g["waved"]).toBe("new");
    // the v2 plugin stops: the name is free, and the v1 one does not get it back
    await syncPlugins([old, { ...taker, enabled: false }], true);
    expect(runCommand("/wave").kind).toBe("unknown");
    // a v1 plugin started after the v2 one took a name is refused it, as before
    await syncPlugins([{ ...old, enabled: false }, { ...taker, enabled: true }], true);
    await syncPlugins([{ ...old, enabled: true }, taker], true);
    expect(g["oldTook"]).toBe("refused");
  });

  it("keeps working past a command that throws", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    await syncPlugins([await entry(v2("Throws", `api.commands.register("boom", "", () => { throw new Error("no"); });`), ["commands.write"])], true);
    const cmd = runCommand("/boom");
    if (cmd.kind === "plugin") await expect(cmd.run("sc")).resolves.toBeNull();
  });
});

describe("keybinds", () => {
  const press = (bind: string, repeat = false, at = Date.now()) => runPluginKeybinds(parseBind(bind), repeat, at);
  const bindFor = (pluginId: string, id: string, keys: string) => useSettings.setState((s) => ({ settings: { ...s.settings, pluginKeybinds: { ...s.settings.pluginKeybinds, [pluginId]: { ...(s.settings.pluginKeybinds[pluginId] ?? {}), [id]: keys } } } }));

  beforeEach(() => useSettings.setState((s) => ({ settings: { ...s.settings, pluginKeybinds: {} } })));

  it("registers unbound, refuses an id twice, and runs on the keys chosen, once while held", async () => {
    g["pressed"] = 0;
    const e = await entry(v2("Keys", `api.keybinds.register("hi", { name: "Say Hi", description: "Says hi" }, (ctx) => { globalThis.pressed++; globalThis.keyCtx = ctx; });`), ["keybinds.write", "keybinds.read"]);
    await syncPlugins([e], true);
    expect(usePluginKeybinds.getState().byPlugin[e.id]).toEqual({ plugin: "Keys", keybinds: { hi: { name: "Say Hi", description: "Says hi" } } });
    expect(() => apiOf("Keys")["keybinds"].register("hi", { name: "x", description: "" }, () => undefined)).toThrow(/already/);
    expect(apiOf("Keys")["keybinds"].get("hi")).toBeNull();
    expect(press("Mod+Shift+KeyH")).toBe(false);
    const changes: unknown[] = [];
    apiOf("Keys")["keybinds"].onChange("hi", (k: string | null) => changes.push(k));
    bindFor(e.id, "hi", "Mod+Shift+KeyH");
    expect(apiOf("Keys")["keybinds"].get("hi")).toMatch(/H$/);
    expect(changes).toHaveLength(1);
    expect(press("Mod+Shift+KeyH", false, 10_000)).toBe(true);
    expect(press("Mod+Shift+KeyH", true, 10_050)).toBe(true);
    expect(g["pressed"]).toBe(1);
    expect(g["keyCtx"]).toEqual({ user: null, channel: null });
  });

  it("stops answering when its plugin stops, keeps its keys for a restart, and needs its permissions", async () => {
    g["pressed2"] = 0;
    const e = await entry(v2("Keys2", `api.keybinds.register("go", { name: "Go", description: "" }, () => { globalThis.pressed2++; });`), ["keybinds.write"]);
    await syncPlugins([e], true);
    bindFor(e.id, "go", "Alt+KeyG");
    expect(() => apiOf("Keys2")["keybinds"].get("go")).toThrow(PermissionError);
    await syncPlugins([{ ...e, enabled: false }], true);
    expect(press("Alt+KeyG", false, 20_000)).toBe(false);
    expect(usePluginKeybinds.getState().byPlugin[e.id]).toBeUndefined();
    await syncPlugins([e], true);
    expect(press("Alt+KeyG", false, 30_000)).toBe(true);
    expect(g["pressed2"]).toBe(1);
  });

  it("forgets a deleted plugin's keys", () => {
    useSettings.getState().patch({ pluginKeybinds: { gone: { go: "Alt+KeyG" }, kept: { go: "Alt+KeyK" } }, plugins: [{ id: "kept" } as PluginEntry] });
    expect(useSettings.getState().settings.pluginKeybinds).toEqual({ kept: { go: "Alt+KeyK" } });
  });
});

describe("toasts", () => {
  it("passes a plugin's kind and duration through, a warning included, 4 seconds when it does not say", async () => {
    vi.useFakeTimers();
    await syncPlugins([await entry(v2("Toaster"), ["ui.write"])], true);
    useUI.setState({ toasts: [] });
    apiOf("Toaster")["ui"].toast("careful", { type: "warning", duration: 1500 });
    apiOf("Toaster")["ui"].toast("plain");
    expect(useUI.getState().toasts.map((x) => [x.text, x.kind])).toEqual([["Toaster: careful", "warning"], ["Toaster: plain", "info"]]);
    vi.advanceTimersByTime(1600);
    expect(useUI.getState().toasts.map((x) => x.text)).toEqual(["Toaster: plain"]);
    vi.advanceTimersByTime(2500);
    expect(useUI.getState().toasts).toEqual([]);
  });

  it("holds a duration to 1 to 15 seconds, and leaves the app's own toasts their timings", () => {
    vi.useFakeTimers();
    useUI.setState({ toasts: [] });
    useUI.getState().toast("short", "info", 10);
    useUI.getState().toast("long", "info", 60_000);
    useUI.getState().toast("app error", "error");
    vi.advanceTimersByTime(1100);
    expect(useUI.getState().toasts.map((x) => x.text)).toEqual(["long", "app error"]);
    vi.advanceTimersByTime(7000);
    expect(useUI.getState().toasts.map((x) => x.text)).toEqual(["long"]);
    vi.advanceTimersByTime(7000);
    expect(useUI.getState().toasts).toEqual([]);
  });
});

describe("what version 2 gained later", () => {
  it("hands over a message's server, files, sticker, forward and pin", async () => {
    const e = await entry(v2("Gain", `api.events.on("messageCreated", (m) => { globalThis.GainSeen = m; });`), ["events.read", "servers.messages.read"]);
    await syncPlugins([e], true);
    const m = { ...message("sc", "hi"), flags: 1, payload: { t: "hi", att: [{ blob_id: "9", name: "a.png", type: "image/png", size: 10, w: 4, h: 3, key: "k", spoiler: true }], sticker: { id: "st", name: "Wave" }, fwd: { at: 5000, comment: "look" } } } as unknown as DecryptedMessage;
    for (const h of dispatchHooks.onMessage) h(m as never, false, m);
    const seen = g["GainSeen"] as Api;
    expect(seen).toMatchObject({ serverId: "s1", pinned: true, sticker: { id: "st", name: "Wave" }, forwarded: { comment: "look" }, fromApp: false });
    expect(seen["attachments"][0]).toEqual({ name: "a.png", type: "image/png", size: 10, width: 4, height: 3, spoiler: true, duration: null, voice: false });
    expect(seen["attachments"][0]).not.toHaveProperty("key");
  });

  it("formats mentions and timestamps without any permission", async () => {
    await syncPlugins([await entry(v2("Utils"), [])], true);
    const u = apiOf("Utils")["utils"];
    expect(u.mention.user("5")).toBe("<@5>");
    expect(u.mention.role(6n)).toBe("<@&6>");
    expect(u.mention.channel("7")).toBe("<#7>");
    expect(u.timestamp(new Date(90_000), "R")).toBe("<t:90:R>");
    expect(u.escapeMarkdown("*hi* _there_")).toBe("\\*hi\\* \\_there\\_");
    expect(u.hasPermission(6n, 2n)).toBe(true);
    expect(u.hasPermission(4n, 2n)).toBe(false);
  });

  it("puts text in the message box with composer.write only", async () => {
    await syncPlugins([await entry(v2("Box"), ["composer.write"]), await entry(v2("NoBox"), [], "bcdefghijk")], true);
    const got: string[] = [];
    const on = (ev: Event) => got.push((ev as CustomEvent<string>).detail);
    window.addEventListener("ec:insert-text", on);
    apiOf("Box")["composer"].insert("hello ");
    // a tick later, once a command's own text has left the box
    expect(got).toEqual([]);
    await new Promise((r) => setTimeout(r, 0));
    window.removeEventListener("ec:insert-text", on);
    expect(got).toEqual(["hello "]);
    expect(() => apiOf("NoBox")["composer"].insert("x")).toThrow(/composer.write/);
  });

  it("goes to a channel and hears where the app went, with navigation", async () => {
    const went: string[] = [];
    const { configurePlugins, noteLocation } = await import("./runtime");
    configurePlugins({ navigate: (p) => went.push(p), onMeta: () => undefined });
    await syncPlugins([await entry(v2("Nav", `api.events.on("locationChanged", (l) => { (globalThis.NavSeen ??= []).push(l); });`), ["navigation", "events.read"])], true);
    const nav = apiOf("Nav")["navigation"];
    nav.openChannel("sc", "42");
    nav.openChannel("dm");
    expect(went).toEqual(["/channels/s1/sc/42", "/channels/@me/dm"]);
    expect(() => nav.go("https://example.com")).toThrow();
    noteLocation("/channels/123/456");
    expect(g["NavSeen"]).toEqual([{ path: "/channels/123/456", serverId: "123", channelId: "456" }]);
  });

  it("says who is online from what this device knows", async () => {
    const { usePresence } = await import("@/stores/presence");
    usePresence.setState({ byUser: { bob: { user_id: "bob", status: "invisible", status_text: "", status_emoji: "", status_expires_at: null } } });
    await syncPlugins([await entry(v2("Pres"), ["users.read"])], true);
    expect(apiOf("Pres")["users"].presence("bob")).toEqual({ userId: "bob", status: "offline", activities: [] });
  });
});
