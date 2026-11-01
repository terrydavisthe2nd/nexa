import { create } from "zustand";
import { registerPluginCommand, unregisterPluginCommand } from "@/components/composer/commands";
import { dispatchHooks } from "@/gateway/dispatch";
import { t, translator } from "@/i18n";
import type { DecryptedMessage } from "@/lib/decrypt";
import { storage } from "@/lib/storage";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useMessages } from "@/stores/messages";
import { usePresence } from "@/stores/presence";
import { useReadStates } from "@/stores/readStates";
import { useRelationships } from "@/stores/relationships";
import { useServers } from "@/stores/servers";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { codeKey, lookCache } from "../cache";
import { FetchError, fetchFailure, fetchText } from "../fetchText";
import { codeHash } from "../hash";
import { unpackText } from "../pack";
import { noteHealthy, setLookActive } from "../safeMode";
import { sanitizeMeta, sanitizePlugins, sanitizeValues } from "../sanitize";
import { loadAllMenus } from "@/lib/contextMenus/all";
import { buildApiV2, noteLocation } from "./runtimeV2";

export { noteLocation };
import type { PermissionName } from "./permissions";
import { setSheets, type Sheet } from "../styles";
import type { PluginEntry, PluginMeta } from "../types";
import { PLUGIN_API_VERSION, type NexaPlugin, type NexaPluginApi, type PluginCommandDef, type PluginEventName, type PluginEvents, type PluginMessage, type PluginSetting, type SettingValue } from "./api";

/**
 * Plugins, run in the page with the person's full access.
 *
 * A plugin is an ES module. Its code is pinned by hash when it is approved,
 * and only code with that hash is ever run for it: pasted code is checked
 * against the hash, code from a link is read from this device's copy (kept by
 * hash) or fetched and checked. When the link serves anything else, nothing
 * runs until the person has looked and said yes (acceptUpdate).
 *
 * The code runs through a blob: URL and a dynamic import(), so it is a real
 * module with its own scope, loaded without eval. What a plugin registers
 * through the API (styles, listeners, commands) is recorded against it and
 * undone when it stops, whether or not its own stop() remembers to, which is
 * what lets a plugin be turned off and on again without a reload.
 */

export type PluginStatus = "off" | "loading" | "running" | "failed" | "changed" | "unreachable" | "mismatch" | "safe";

export interface PluginState {
  status: PluginStatus;
  /** Why it failed or is not running, in the reader's language. */
  error?: string;
  meta?: PluginMeta;
  schema?: PluginSetting[];
  /** The link now serves other code than was approved; it is kept here until it is approved or dropped. */
  update?: { hash: string; size: number; code: string };
  /** Commands it asked for that another plugin already had. */
  clashes?: string[];
}

export const usePlugins = create<{ byId: Record<string, PluginState> }>()(() => ({ byId: {} }));

function setState(id: string, patch: Partial<PluginState> | null): void {
  usePlugins.setState((s) => {
    const byId = { ...s.byId };
    if (patch === null) delete byId[id];
    else byId[id] = { ...(byId[id] ?? { status: "off" }), ...patch };
    return { byId };
  });
}

/** What an entry's code comes to: the text to run, or why there is none. */
type Loaded = { ok: true; code: string } | { ok: false; status: PluginStatus; error: string; update?: PluginState["update"] };


async function loadCode(entry: PluginEntry): Promise<Loaded> {
  if (entry.source === "code") {
    let code: string;
    try {
      code = await unpackText(entry.code ?? "");
    } catch {
      return { ok: false, status: "mismatch", error: t("look.plugins.errorUnreadable") };
    }
    return codeHash(code) === entry.hash ? { ok: true, code } : { ok: false, status: "mismatch", error: t("look.plugins.errorMismatch") };
  }
  const kept = await lookCache.get(codeKey(entry.hash));
  if (kept && codeHash(kept.text) === entry.hash) return { ok: true, code: kept.text };
  let text: string;
  try {
    text = await fetchText(entry.url ?? "");
  } catch (err) {
    const e = err instanceof FetchError ? err : new FetchError("network");
    return { ok: false, status: "unreachable", error: fetchFailure(e.reason, e.status) };
  }
  const hash = codeHash(text);
  if (hash !== entry.hash) {
    // not what was approved: kept for review, never run
    await lookCache.put(codeKey(hash), { text, fetchedAt: Date.now() });
    return { ok: false, status: "changed", error: t("look.plugins.changedBody"), update: { hash, size: new TextEncoder().encode(text).length, code: text } };
  }
  await lookCache.put(codeKey(hash), { text, fetchedAt: Date.now() });
  return { ok: true, code: text };
}

/**
 * How code becomes a module: a blob: URL handed to import(), which runs it as
 * a real ES module in its own scope with no eval. Tests replace this, since
 * Node cannot import a blob: URL.
 */
export const moduleLoader = {
  async load(code: string): Promise<Record<string, unknown>> {
    const url = URL.createObjectURL(new Blob([code], { type: "text/javascript" }));
    try {
      return (await import(/* @vite-ignore */ url)) as Record<string, unknown>;
    } finally {
      URL.revokeObjectURL(url);
    }
  },
};

/** Imports code as a module and finds the plugin in it; throws a readable error when there is none. */
export async function importPlugin(code: string): Promise<NexaPlugin> {
  let mod: Record<string, unknown>;
  try {
    mod = await moduleLoader.load(code);
  } catch (err) {
    throw new Error(t("look.plugins.errorModule", { message: err instanceof Error ? err.message : String(err) }));
  }
  const candidate = (mod["default"] && typeof mod["default"] === "object" ? mod["default"] : mod) as Partial<NexaPlugin>;
  if (typeof candidate.start !== "function") throw new Error(t("look.plugins.errorNoStart"));
  return candidate as NexaPlugin;
}

/**
 * Which API a plugin is written for: one that says nothing, or 1, gets
 * version 1 (api.ts), and 2 gets version 2 (api2.ts). Anything else will not
 * start, saying why.
 */
export function apiVersionOf(p: Partial<NexaPlugin>): 1 | 2 {
  const v = (p as { apiVersion?: unknown }).apiVersion;
  if (v === undefined || v === null || v === 1) return 1;
  if (v === 2) return 2;
  throw new Error(t("look.plugins.errorVersion", { version: String(v).slice(0, 20) }));
}

/** What a plugin says about itself, checked. */
export function metaOf(p: Partial<NexaPlugin>, fallbackName: string): PluginMeta {
  return sanitizeMeta({ name: p.name || fallbackName, description: p.description, version: p.version, author: p.author }) ?? { name: fallbackName, description: "", version: "", author: "" };
}

const SETTING_KEY = /^[A-Za-z0-9_.-]{1,64}$/;

/** The settings a plugin declares, keeping only the ones the Plugins page can draw. */
export function schemaOf(p: Partial<NexaPlugin>): PluginSetting[] {
  if (!Array.isArray(p.settings)) return [];
  const out: PluginSetting[] = [];
  const seen = new Set<string>();
  for (const raw of p.settings.slice(0, 30) as unknown[]) {
    if (!raw || typeof raw !== "object") continue;
    const s = raw as Record<string, unknown>;
    const key = s["key"];
    const label = typeof s["label"] === "string" ? s["label"].slice(0, 80) : "";
    if (typeof key !== "string" || !SETTING_KEY.test(key) || seen.has(key) || !label) continue;
    const description = typeof s["description"] === "string" ? s["description"].slice(0, 300) : undefined;
    const base = { key, label, ...(description ? { description } : {}) };
    switch (s["type"]) {
      case "toggle":
        out.push({ ...base, type: "toggle", default: s["default"] === true });
        break;
      case "text":
        out.push({ ...base, type: "text", default: typeof s["default"] === "string" ? s["default"] : "", placeholder: typeof s["placeholder"] === "string" ? s["placeholder"].slice(0, 80) : undefined, maxLength: typeof s["maxLength"] === "number" ? Math.min(2000, s["maxLength"]) : 2000 });
        break;
      case "number": {
        const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
        out.push({ ...base, type: "number", default: num(s["default"]) ?? 0, min: num(s["min"]), max: num(s["max"]), step: num(s["step"]) });
        break;
      }
      case "select": {
        const options = (Array.isArray(s["options"]) ? s["options"] : [])
          .slice(0, 50)
          .filter((o): o is { value: string; label: string } => !!o && typeof (o as { value: unknown }).value === "string" && typeof (o as { label: unknown }).label === "string")
          .map((o) => ({ value: o.value.slice(0, 64), label: o.label.slice(0, 80) }));
        if (!options.length) continue;
        out.push({ ...base, type: "select", options, default: typeof s["default"] === "string" && options.some((o) => o.value === s["default"]) ? s["default"] : options[0]!.value });
        break;
      }
      case "color":
        out.push({ ...base, type: "color", default: typeof s["default"] === "string" && /^#[0-9a-f]{6}$/i.test(s["default"]) ? s["default"] : "#8000ff" });
        break;
      default:
        continue;
    }
    seen.add(key);
  }
  return out;
}

/** The values a plugin sees: what the person chose, over the defaults it declared, each of the declared type. */
export function settingValues(schema: PluginSetting[], saved: Record<string, unknown>): Record<string, SettingValue> {
  const out: Record<string, SettingValue> = {};
  for (const s of schema) {
    const v = saved[s.key];
    if (s.type === "toggle") out[s.key] = typeof v === "boolean" ? v : !!s.default;
    else if (s.type === "number") out[s.key] = typeof v === "number" && Number.isFinite(v) ? Math.min(s.max ?? Infinity, Math.max(s.min ?? -Infinity, v)) : (s.default ?? 0);
    else if (s.type === "select") out[s.key] = typeof v === "string" && s.options.some((o) => o.value === v) ? v : (s.default ?? s.options[0]!.value);
    else if (s.type === "color") out[s.key] = typeof v === "string" && /^#[0-9a-f]{6}$/i.test(v) ? v : (s.default ?? "#8000ff");
    else out[s.key] = typeof v === "string" ? v.slice(0, s.maxLength ?? 2000) : (s.default ?? "");
  }
  return out;
}

/* --- running plugins --- */

interface Running {
  id: string;
  hash: string;
  name: string;
  plugin: NexaPlugin;
  /** Which API it was handed: 1 (api.ts) or 2 (api2.ts). */
  version: 1 | 2;
  schema: PluginSetting[];
  settingsJson: string;
  /** Its stylesheets that go when it stops: all of a v1 plugin's, and a v2 plugin's added without `persist`. */
  sheets: Sheet[];
  handlers: Map<PluginEventName, Set<(payload: never) => void>>;
  commands: { name: string; run: (args: string, channelId: string) => Promise<string | null> }[];
  settingsListeners: Set<(values: Record<string, SettingValue>, old: Record<string, SettingValue>) => void>;
  values: Record<string, SettingValue>;
  /** A v2 plugin's API takes back what it registered (listeners, menu changes) through this. */
  stopV2?: () => void;
}

const running = new Map<string, Running>();
/**
 * The stylesheets v2 plugins added with `persist`: kept after the plugin
 * stops, until it clears them, takes one back, is deleted, or the page
 * reloads.
 */
const persisted = new Map<string, Sheet[]>();
/** What each plugin is granted, as the account's settings say now (v2 only; v1 asks for nothing). */
const grants = new Map<string, Set<string>>();
/** Plugins that failed to start: off until the page reloads or the person tries again. */
const sessionOff = new Set<string>();
/**
 * The code each plugin last could not be started with because it was not
 * there to run (changed at its link, unreachable, not matching its hash), so
 * a settings sync does not fetch it again and again; a change of hash or link,
 * or the person asking, tries again.
 */
const blocked = new Map<string, string>();
const attemptKey = (e: PluginEntry) => `${e.hash}|${e.url ?? ""}`;
let navigate: ((path: string) => void) | null = null;
let onMeta: ((id: string, meta: PluginMeta) => void) | null = null;
let everRan = false;

export function configurePlugins(opts: { navigate: (path: string) => void; onMeta: (id: string, meta: PluginMeta) => void }): void {
  navigate = opts.navigate;
  onMeta = opts.onMeta;
}

/** Whether any plugin ran in this page, which is when signing out reloads it. */
export const pluginsEverRan = (): boolean => everRan;

function drawSheets(): void {
  setSheets("plugins", [...[...running.values()].flatMap((r) => r.sheets), ...[...persisted.values()].flat()]);
}

const STORAGE_MAX = 512 * 1024;
const storageKey = (pluginId: string) => `ec:plugin:${useAuth.getState().me?.id ?? "anon"}:${pluginId}`;

function readStore(pluginId: string): Record<string, unknown> {
  try {
    const v = JSON.parse(storage.get(storageKey(pluginId)) ?? "{}") as unknown;
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function writeStore(pluginId: string, data: Record<string, unknown>): void {
  const raw = JSON.stringify(data);
  if (raw.length > STORAGE_MAX) throw new Error(t("look.plugins.storageFull"));
  storage.set(storageKey(pluginId), raw);
}

/** A message as plugins are given it; frozen, since every plugin is handed the same one. */
function forPlugins(m: DecryptedMessage): PluginMessage {
  const channel = useChannels.getState().byId[m.channel_id];
  const p = m.payload;
  return Object.freeze({
    id: m.id,
    channelId: m.channel_id,
    serverId: channel?.server_id ?? null,
    authorId: m.author_id,
    content: p?.t ?? "",
    decrypted: !!p,
    attachments: (p?.att ?? []).map((a) => Object.freeze({ name: a.name, type: a.type, size: a.size })),
    sticker: p?.sticker ? Object.freeze({ id: p.sticker.id, name: p.sticker.name ?? "" }) : null,
    replyToId: m.reply_to_id,
    mentions: [...m.mentions],
    createdAt: m.created_at,
    editedAt: m.edited_at,
    mine: m.author_id === useAuth.getState().me?.id,
  });
}

function emit<E extends PluginEventName>(event: E, payload: PluginEvents[E]): void {
  for (const r of running.values()) {
    for (const handler of r.handlers.get(event) ?? []) {
      try {
        (handler as (p: PluginEvents[E]) => void)(payload);
      } catch (err) {
        console.warn(`plugin ${r.name}: its ${event} listener failed`, err);
      }
    }
  }
}

// One listener each on the gateway for all plugins; nothing is decrypted for them that the app had not already.
dispatchHooks.onMessage.push((_m, _mine, decrypted) => {
  if (running.size) emit("messageCreate", forPlugins(decrypted));
});
dispatchHooks.onMessageUpdate.push((decrypted) => {
  if (running.size) emit("messageUpdate", forPlugins(decrypted));
});
dispatchHooks.onMessageDelete.push((channelId, ids, was) => {
  if (!running.size) return;
  ids.forEach((id, i) => emit("messageDelete", Object.freeze({ id, channelId, message: was[i] ? forPlugins(was[i]!) : null })));
});

const COMMAND_NAME = /^[a-z0-9_-]{1,32}$/;

function apiFor(r: Running, meta: PluginMeta): NexaPluginApi {
  const stores = unsupportedStores();
  return Object.freeze({
    apiVersion: PLUGIN_API_VERSION,
    plugin: Object.freeze({ id: r.id, name: meta.name, version: meta.version }),
    app: Object.freeze({
      name: "Nexa",
      build: typeof __BUILD_ID__ === "string" ? __BUILD_ID__ : "",
      get locale() {
        return translator().locale;
      },
    }),
    get me() {
      const m = useAuth.getState().me;
      return m ? { id: m.id, username: m.username, displayName: m.display_name } : null;
    },
    css: Object.freeze({
      add(css: string) {
        const sheet = { id: `${r.id}-${Math.random().toString(36).slice(2, 10)}`, css: String(css) };
        r.sheets.push(sheet);
        drawSheets();
        return () => {
          r.sheets = r.sheets.filter((s) => s !== sheet);
          if (running.get(r.id) === r) drawSheets();
        };
      },
      clear() {
        r.sheets = [];
        drawSheets();
      },
    }),
    toast(text: string, kind: "info" | "success" | "error" = "info") {
      useUI.getState().toast(t("look.plugins.toastFrom", { name: meta.name, text: String(text).slice(0, 300) }), kind === "success" || kind === "error" ? kind : "info");
    },
    navigate(path: string) {
      const p = String(path);
      if (!p.startsWith("/") || p.startsWith("//")) throw new Error("navigate takes a path inside the app, such as /channels/@me");
      navigate?.(p);
    },
    events: Object.freeze({
      on<E extends PluginEventName>(event: E, handler: (payload: PluginEvents[E]) => void) {
        if (typeof handler !== "function") throw new Error("events.on needs a function");
        let set = r.handlers.get(event);
        if (!set) r.handlers.set(event, (set = new Set()));
        set.add(handler as (payload: never) => void);
        return () => void set!.delete(handler as (payload: never) => void);
      },
    }),
    commands: Object.freeze({
      register(def: PluginCommandDef) {
        const name = String(def?.name ?? "").toLowerCase();
        if (!COMMAND_NAME.test(name) || typeof def.run !== "function") throw new Error("a command needs a name of letters, digits, - or _ and a run function");
        const run = async (args: string, channelId: string): Promise<string | null> => {
          try {
            const out = await def.run(args, Object.freeze({ channelId }));
            return typeof out === "string" && out.trim() ? out : null;
          } catch (err) {
            useUI.getState().toast(t("look.plugins.commandFailed", { name: meta.name, command: name, message: err instanceof Error ? err.message : String(err) }), "error");
            return null;
          }
        };
        const desc = t("look.plugins.commandFrom", { description: String(def.description ?? "").slice(0, 100), name: meta.name });
        if (!registerPluginCommand({ name, desc, run })) {
          const clashes = [...new Set([...(usePlugins.getState().byId[r.id]?.clashes ?? []), name])];
          setState(r.id, { clashes });
          throw new Error(`the command /${name} is taken`);
        }
        r.commands.push({ name, run });
        return () => {
          unregisterPluginCommand(name, run);
          r.commands = r.commands.filter((c) => c.run !== run);
        };
      },
    }),
    storage: Object.freeze({
      get<T = unknown>(key: string): T | undefined {
        return readStore(r.id)[String(key)] as T | undefined;
      },
      set(key: string, value: unknown) {
        const data = readStore(r.id);
        data[String(key)] = JSON.parse(JSON.stringify(value ?? null)) as unknown;
        writeStore(r.id, data);
      },
      remove(key: string) {
        const data = readStore(r.id);
        delete data[String(key)];
        writeStore(r.id, data);
      },
      keys() {
        return Object.keys(readStore(r.id));
      },
      clear() {
        storage.remove(storageKey(r.id));
      },
    }),
    settings: Object.freeze({
      get: (key: string) => r.values[String(key)],
      all: () => ({ ...r.values }),
      onChange(handler: (values: Record<string, SettingValue>) => void) {
        r.settingsListeners.add(handler);
        return () => void r.settingsListeners.delete(handler);
      },
    }),
    unsupported: Object.freeze({ stores }),
  });
}

/** The stores a plugin can reach past the API: v1's `unsupported.stores`, and v2's `stores`. */
function unsupportedStores(): Record<string, unknown> {
  return Object.freeze({ auth: useAuth, settings: useSettings, messages: useMessages, channels: useChannels, servers: useServers, users: useUsers, relationships: useRelationships, presence: usePresence, readStates: useReadStates, ui: useUI });
}

/** Saves one of a plugin's setting values with the account, as the Plugins page does, and tells the plugin at once. */
function saveSetting(r: Running, key: string, value: SettingValue | undefined): void {
  const now = sanitizePlugins(useSettings.getState().settings.plugins);
  const entry = now.find((p) => p.id === r.id);
  if (!entry) return;
  const next = { ...entry.settings };
  if (value === undefined) delete next[key];
  else next[key] = value;
  const settings = sanitizeValues(next);
  const old = r.values;
  r.settingsJson = JSON.stringify(settings);
  r.values = settingValues(r.schema, settings);
  useSettings.getState().patch({ plugins: now.map((p) => (p.id === r.id ? { ...p, settings } : p)) });
  for (const fn of r.settingsListeners) {
    try {
      fn({ ...r.values }, old);
    } catch (err) {
      console.warn(`plugin ${r.name}: its settings listener failed`, err);
    }
  }
}

function apiForV2(r: Running, meta: PluginMeta): Record<string, unknown> {
  const built = buildApiV2({
    id: r.id,
    meta,
    schema: r.schema,
    can: (p: PermissionName) => !!grants.get(r.id)?.has(p),
    values: () => r.values,
    saveSetting: (key, value) => saveSetting(r, key, value),
    onSettings: (fn) => {
      r.settingsListeners.add(fn);
      return () => void r.settingsListeners.delete(fn);
    },
    addSheet: (css, persist) => {
      const sheet = { id: `${r.id}-${Math.random().toString(36).slice(2, 10)}`, css };
      if (persist) persisted.set(r.id, [...(persisted.get(r.id) ?? []), sheet]);
      else r.sheets.push(sheet);
      drawSheets();
      return () => {
        r.sheets = r.sheets.filter((x) => x !== sheet);
        const kept = (persisted.get(r.id) ?? []).filter((x) => x !== sheet);
        if (kept.length) persisted.set(r.id, kept);
        else persisted.delete(r.id);
        drawSheets();
      };
    },
    clearSheets: () => {
      r.sheets = [];
      persisted.delete(r.id);
      drawSheets();
    },
    storage: {
      get: (key) => readStore(r.id)[key],
      set: (key, value) => {
        const data = readStore(r.id);
        data[key] = JSON.parse(JSON.stringify(value ?? null)) as unknown;
        writeStore(r.id, data);
      },
      remove: (key) => {
        const data = readStore(r.id);
        delete data[key];
        writeStore(r.id, data);
      },
      keys: () => Object.keys(readStore(r.id)),
      clear: () => storage.remove(storageKey(r.id)),
    },
    stores: unsupportedStores(),
    navigate: (path) => navigate?.(path),
  });
  r.stopV2 = built.stop;
  return built.api;
}

function undo(r: Running): void {
  r.stopV2?.();
  r.stopV2 = undefined;
  for (const c of r.commands) unregisterPluginCommand(c.name, c.run);
  r.commands = [];
  r.handlers.clear();
  r.settingsListeners.clear();
  r.sheets = [];
  running.delete(r.id);
  drawSheets();
}

const STARTING = "ec:look:starting";
const START_TIMEOUT_MS = 10_000;

function within<T>(work: Promise<T>, ms: number, what: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(what)), ms);
    work.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (err: unknown) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
}

async function start(entry: PluginEntry): Promise<void> {
  const fallback = entry.meta?.name ?? t("look.plugins.unnamed");
  setState(entry.id, { status: "loading", error: undefined, clashes: undefined });
  const loaded = await loadCode(entry);
  if (!loaded.ok) {
    blocked.set(entry.id, attemptKey(entry));
    setState(entry.id, { status: loaded.status, error: loaded.error, update: loaded.update });
    return;
  }
  blocked.delete(entry.id);
  // Written before and cleared after: still here on the next start means this plugin took the page down with it.
  storage.set(STARTING, entry.id);
  let r: Running | null = null;
  try {
    const plugin = await importPlugin(loaded.code);
    const meta = metaOf(plugin, fallback);
    const schema = schemaOf(plugin);
    const version = apiVersionOf(plugin);
    r = { id: entry.id, hash: entry.hash, name: meta.name, plugin, version, schema, settingsJson: JSON.stringify(entry.settings), sheets: [], handlers: new Map(), commands: [], settingsListeners: new Set(), values: settingValues(schema, entry.settings) };
    running.set(entry.id, r);
    // a plugin that may change menus sees all of them from the start, the ones of screens not opened yet too
    if (version === 2 && grants.get(entry.id)?.has("ui.write")) await loadAllMenus();
    const api = version === 2 ? apiForV2(r, meta) : apiFor(r, meta);
    await within(Promise.resolve(plugin.start(api as NexaPluginApi)), START_TIMEOUT_MS, t("look.plugins.errorSlow"));
    everRan = true;
    setLookActive(true);
    setState(entry.id, { status: "running", error: undefined, meta, schema });
    if (JSON.stringify(meta) !== JSON.stringify(entry.meta)) onMeta?.(entry.id, meta);
    // stayed up a while with its plugins: crashes before now were not theirs
    setTimeout(noteHealthy, 30_000);
  } catch (err) {
    if (r) {
      try {
        await within(Promise.resolve(r.plugin.stop?.()), 3000, "stop");
      } catch {
        /* it failed already */
      }
      undo(r);
    }
    sessionOff.add(entry.id);
    const message = err instanceof Error ? err.message : String(err);
    setState(entry.id, { status: "failed", error: message });
    useUI.getState().toast(t("look.plugins.failedToast", { name: r?.name ?? fallback, message: message.slice(0, 160) }), "error");
  } finally {
    storage.remove(STARTING);
  }
}

async function stop(id: string, status: PluginStatus = "off"): Promise<void> {
  const r = running.get(id);
  if (r) {
    try {
      await within(Promise.resolve(r.plugin.stop?.()), 3000, "stop");
    } catch (err) {
      console.warn(`plugin ${r.name}: stop failed`, err);
    }
    undo(r);
  }
  setState(id, { status });
}

let queue: Promise<void> = Promise.resolve();
let checkedHang = false;

/**
 * Makes what runs match the list: starts what is on and not running, stops
 * what is off, gone or pinned to other code now, and hands running plugins
 * their new setting values. One pass at a time, in order.
 */
export function syncPlugins(entries: PluginEntry[], allowed: boolean, why: PluginStatus = "off"): Promise<void> {
  queue = queue.then(async () => {
    if (!checkedHang) {
      checkedHang = true;
      const hung = storage.get(STARTING);
      storage.remove(STARTING);
      if (hung && entries.some((e) => e.id === hung && e.enabled)) {
        sessionOff.add(hung);
        const name = entries.find((e) => e.id === hung)?.meta?.name ?? t("look.plugins.unnamed");
        setState(hung, { status: "failed", error: t("look.plugins.errorHung") });
        useUI.getState().toast(t("look.plugins.hungToast", { name }), "error");
      }
    }
    const ids = new Set(entries.map((e) => e.id));
    // the grants as the account's settings say now: a permission taken away applies at once
    grants.clear();
    for (const e of entries) if (Array.isArray(e.permissions)) grants.set(e.id, new Set(e.permissions));
    for (const id of [...running.keys()]) if (!ids.has(id)) await stop(id);
    // a deleted plugin's persisted stylesheets go with it
    let dropped = false;
    for (const id of [...persisted.keys()]) if (!ids.has(id)) dropped = persisted.delete(id) || dropped;
    if (dropped) drawSheets();
    for (const id of Object.keys(usePlugins.getState().byId)) if (!ids.has(id)) setState(id, null);
    for (const e of entries) {
      const want = allowed && e.enabled && !sessionOff.has(e.id);
      const r = running.get(e.id);
      if (r && (!want || r.hash !== e.hash)) await stop(e.id, allowed ? "off" : why);
      if (want && (!r || r.hash !== e.hash)) {
        if (blocked.get(e.id) !== attemptKey(e)) await start(e);
      }
      else if (!want && !running.has(e.id)) {
        const cur = usePlugins.getState().byId[e.id];
        // a failure is kept on screen until it is tried again or turned off
        if (!(cur?.status === "failed" && sessionOff.has(e.id) && e.enabled)) setState(e.id, { status: allowed ? "off" : e.enabled ? why : "off" });
      }
      const now = running.get(e.id);
      if (now) {
        const json = JSON.stringify(e.settings);
        if (json !== now.settingsJson) {
          const old = now.values;
          now.settingsJson = json;
          now.values = settingValues(now.schema, e.settings);
          for (const fn of now.settingsListeners) {
            try {
              fn({ ...now.values }, old);
            } catch (err) {
              console.warn(`plugin ${now.name}: its settings listener failed`, err);
            }
          }
        }
      }
    }
    if (running.size === 0) setLookActive(false);
  });
  return queue;
}

/** Lets a plugin that failed be started again, on the next sync. */
export function retryPlugin(id: string): void {
  sessionOff.delete(id);
  blocked.delete(id);
}

export async function stopAllPlugins(): Promise<void> {
  queue = queue.then(async () => {
    for (const id of [...running.keys()]) await stop(id);
  });
  return queue;
}

/**
 * Asks the link whether it still serves the approved code. A difference is
 * kept for review and never run; "same" also clears one that was waiting.
 */
export async function checkForUpdate(entry: PluginEntry): Promise<"same" | "changed" | "failed"> {
  if (entry.source !== "url" || !entry.url) return "same";
  let text: string;
  try {
    text = await fetchText(entry.url);
  } catch {
    return "failed";
  }
  const hash = codeHash(text);
  await lookCache.put(codeKey(hash), { text, fetchedAt: Date.now() });
  if (hash === entry.hash) {
    setState(entry.id, { update: undefined });
    return "same";
  }
  setState(entry.id, { update: { hash, size: new TextEncoder().encode(text).length, code: text } });
  return "changed";
}

/** Forgets a waiting update (the person kept the old version). */
export function dismissUpdate(id: string): void {
  setState(id, { update: undefined });
}

/** Clears what this device keeps for plugins: on signing out. */
export async function forgetPlugins(): Promise<void> {
  await stopAllPlugins();
  persisted.clear();
  grants.clear();
  drawSheets();
  sessionOff.clear();
  blocked.clear();
  usePlugins.setState({ byId: {} });
}
