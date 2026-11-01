import { ShieldAlert, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button } from "@/components/common/Button";
import { t } from "@/i18n";
import { isDemo } from "@/lib/demoMode";
import { lookCache } from "@/lib/look/cache";
import { forgetLinks, loadLink, refreshLink, useCssLinks } from "@/lib/look/cssLinks";
import { useLookDraft } from "@/lib/look/draft";
import { unpackText } from "@/lib/look/pack";
import { leaveSafeMode, setLookActive, useSafeMode } from "@/lib/look/safeMode";
import { sanitizeCustomCss, sanitizePlugins } from "@/lib/look/sanitize";
import { clearSheets, forgetSheets, rememberSheets, setSheets, type Sheet } from "@/lib/look/styles";
import type { PluginEntry } from "@/lib/look/types";
import { useAuth } from "@/stores/auth";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import s from "./LookEffect.module.css";

type Runtime = typeof import("@/lib/look/plugins/runtime");
let runtime: Promise<Runtime> | null = null;
/** The plugin engine, fetched only by an account that has plugins. */
export function loadRuntime(): Promise<Runtime> {
  if (isDemo) {
    window.dispatchEvent(new Event("nexa-demo-account"));
    return Promise.reject(new Error(t("home.demo.accountTitle")));
  }
  runtime ??= import("@/lib/look/plugins/runtime").catch((err: unknown) => {
    runtime = null;
    throw err;
  });
  return runtime;
}

/** The stylesheets from links a moment after start, and the plugins' links a little later: never in the way of loading. */
const REFRESH_AFTER_MS = 5_000;
const UPDATES_AFTER_MS = 12_000;

/**
 * Keeps the page in step with the account's custom CSS and plugins: draws the
 * stylesheets, runs and stops plugins as they are turned on and off, checks
 * links for new versions in the background, and forgets all of it when the
 * account signs out. In safe mode it draws and runs nothing, and says so.
 */
export function LookEffect() {
  const status = useAuth((a) => a.status);
  const loaded = useSettings((x) => x.loaded);
  const rawCss = useSettings((x) => x.settings.customCss);
  const rawPlugins = useSettings((x) => x.settings.plugins);
  const draftCss = useLookDraft((d) => d.css);
  const mode = useSafeMode((m) => m.mode);
  const texts = useCssLinks((l) => l.texts);
  // held in a ref: the function changes with every page, and plugins are not re-synced for that
  const navigate = useNavigate();
  const navRef = useRef(navigate);
  navRef.current = navigate;
  const { pathname } = useLocation();
  // plugins with the navigation permission hear where the app went
  useEffect(() => {
    if (runtime) void runtime.then((r) => r.noteLocation(pathname));
  }, [pathname]);
  const signedIn = status === "ready" && loaded;
  const css = useMemo(() => sanitizeCustomCss(rawCss), [rawCss]);
  const pluginsJson = JSON.stringify(sanitizePlugins(rawPlugins));
  // compared as text: the same list arriving again from the server is no change
  const plugins = useMemo(() => JSON.parse(pluginsJson) as PluginEntry[], [pluginsJson]);
  const [snippet, setSnippet] = useState<string | null>(null);
  const refreshed = useRef(new Set<string>());
  const checked = useRef(false);

  // the saved snippet, unpacked
  useEffect(() => {
    let live = true;
    unpackText(css.snippet).then(
      (text) => live && setSnippet(text),
      () => live && setSnippet(""),
    );
    return () => {
      live = false;
    };
  }, [css.snippet]);

  // each link's stylesheet: this device's copy first, the site behind it after
  useEffect(() => {
    if (!signedIn || mode === "manual") return;
    const timers: ReturnType<typeof setTimeout>[] = [];
    for (const link of css.links) {
      void loadLink(link.url).then(({ stale }) => {
        if (!stale || refreshed.current.has(link.url)) return;
        refreshed.current.add(link.url);
        timers.push(setTimeout(() => void refreshLink(link.url), REFRESH_AFTER_MS));
      });
    }
    return () => timers.forEach(clearTimeout);
  }, [signedIn, mode, css.links]);

  // draw them
  useEffect(() => {
    if (mode === "manual") {
      clearSheets();
      return;
    }
    // before the account's settings arrive, the sheets remembered for the first frame stay
    if (!signedIn) return;
    const links: Sheet[] = css.links.filter((l) => l.enabled && texts[l.url] !== undefined).map((l) => ({ id: l.id, css: texts[l.url]! }));
    const own = draftCss ?? (css.snippetOn ? snippet : "");
    // still unpacking: what is drawn stays until it is ready
    if (own === null) return;
    const mine: Sheet[] = own.trim() ? [{ id: "snippet", css: own }] : [];
    setSheets("links", links);
    setSheets("snippet", mine);
    // a draft is not anybody's choice yet
    if (draftCss === null) rememberSheets({ links, snippet: mine });
    setLookActive(links.length > 0 || mine.length > 0 || plugins.some((p) => p.enabled));
  }, [signedIn, mode, css, texts, draftCss, snippet, plugins]);

  // plugins: the engine is fetched only when the account has any
  useEffect(() => {
    if (!signedIn || plugins.length === 0) {
      if (runtime) void runtime.then((r) => r.syncPlugins([], true));
      return;
    }
    let live = true;
    void loadRuntime().then((r) => {
      if (!live) return;
      r.configurePlugins({
        navigate: (path) => navRef.current(path),
        onMeta: (id, meta) => {
          const now = sanitizePlugins(useSettings.getState().settings.plugins);
          if (now.some((p) => p.id === id)) useSettings.getState().patch({ plugins: now.map((p) => (p.id === id ? { ...p, meta } : p)) });
        },
      });
      void r.syncPlugins(plugins, mode === null, "safe");
    }, (err: unknown) => console.warn("plugins: could not load the plugin engine", err));
    return () => {
      live = false;
    };
  }, [signedIn, plugins, mode]);

  // once a session, a while after start: have the plugins' links moved on from the approved code?
  useEffect(() => {
    if (!signedIn || mode !== null || checked.current) return;
    const fromLinks = plugins.filter((p) => p.source === "url");
    if (!fromLinks.length) return;
    checked.current = true;
    const timer = setTimeout(() => {
      void loadRuntime()
        .then(async (r) => {
          for (const p of fromLinks) {
            if ((await r.checkForUpdate(p)) === "changed") useUI.getState().toast(t("look.plugins.updateToast", { name: p.meta?.name ?? t("look.plugins.unnamed") }), "info");
          }
        })
        .catch(() => undefined);
    }, UPDATES_AFTER_MS);
    return () => clearTimeout(timer);
  }, [signedIn, mode, plugins]);

  // Signing out takes the account's look with it. A plugin may have left
  // anything behind in the page, so if one ran, the page starts over.
  useEffect(() => {
    if (status !== "anonymous") return;
    clearSheets();
    forgetSheets();
    forgetLinks();
    setLookActive(false);
    void lookCache.clear();
    if (runtime) {
      void runtime.then(async (r) => {
        const ran = r.pluginsEverRan();
        await r.forgetPlugins();
        if (ran) window.location.reload();
      });
    }
  }, [status]);

  return status === "ready" && mode ? <SafeModeBanner mode={mode} /> : null;
}

function SafeModeBanner({ mode }: { mode: "manual" | "crashes" }) {
  const [hidden, setHidden] = useState(false);
  if (hidden) return null;
  return (
    <div className={s.banner}>
      <div className={s.bannerCard} role="status" data-testid="safe-mode-banner">
        <ShieldAlert size={18} aria-hidden="true" />
        <span className={s.bannerText}>{mode === "manual" ? t("look.safe.banner") : t("look.safe.bannerCrashes")}</span>
        <Button size="sm" variant="secondary" onClick={leaveSafeMode} data-testid="safe-mode-leave">
          {mode === "manual" ? t("look.safe.leave") : t("look.safe.turnBackOn")}
        </Button>
        <button type="button" className={s.close} onClick={() => setHidden(true)} aria-label={t("look.safe.hide")} title={t("look.safe.hide")}>
          <X size={16} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
