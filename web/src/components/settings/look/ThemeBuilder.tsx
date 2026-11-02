import { AlertTriangle, CheckCircle2, ChevronDown, ChevronLeft, Copy, CopyPlus, Download, Info, Redo2, SplitSquareHorizontal, Trash2, Undo2 } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { t } from "@/i18n";
import { copyText } from "@/lib/copyText";
import { contrastIssues } from "@/lib/look/contrast";
import { MAX_THEME_NAME, exportTheme, resolveCustom } from "@/lib/look/customTheme";
import { deriveTheme } from "@/lib/look/derive";
import { useLookDraft } from "@/lib/look/draft";
import type { TokenName } from "@/lib/look/tokens";
import { NEUTRAL_TUNE, VISIONS, isNeutral, tunePalette, visionPalette, withPalette, type Tune, type Vision } from "@/lib/look/tools";
import type { CustomTheme } from "@/lib/look/types";
import { useSettings } from "@/stores/settings";
import { unlessUnsaved, useHoldUnsaved, useUnsaved } from "@/stores/unsaved";
import { useUI } from "@/stores/ui";
import settings from "../Settings.module.css";
import { AdvancedEditor } from "./AdvancedEditor";
import { SimpleEditor } from "./SimpleEditor";
import { PaletteStrip, ThemePreview } from "./ThemePreview";
import { TuneEditor } from "./TuneEditor";
import { useCustomThemes } from "./ThemeGallery";
import s from "./Look.module.css";
import b from "./Builder.module.css";

type Tab = "simple" | "advanced" | "tune";
const TABS: readonly Tab[] = ["simple", "advanced", "tune"];

/** A file name made from a theme's name: letters, digits and dashes. */
function fileName(name: string): string {
  const slug = name.normalize("NFKD").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "").slice(0, 40);
  return `${slug || "theme"}.nexa-theme.json`;
}

function download(name: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

const same = (a: CustomTheme, b: CustomTheme) => JSON.stringify(a) === JSON.stringify(b);

/**
 * The draft with its undo and redo. Changes that come quickly one after
 * another (a picker being dragged, a slider, typing) are one step, so Undo
 * takes back a whole gesture rather than one frame of it.
 */
function useHistory(initial: CustomTheme) {
  const [h, setH] = useState({ past: [] as CustomTheme[], now: initial, future: [] as CustomTheme[] });
  const lastAt = useRef(0);
  const set = useCallback((next: CustomTheme) => {
    setH((cur) => {
      if (same(next, cur.now)) return cur;
      const at = Date.now();
      const merge = at - lastAt.current < 600 && cur.past.length > 0;
      lastAt.current = at;
      return { past: merge ? cur.past : [...cur.past, cur.now].slice(-100), now: next, future: [] };
    });
  }, []);
  const undo = useCallback(() => {
    lastAt.current = 0;
    setH((cur) => (cur.past.length ? { past: cur.past.slice(0, -1), now: cur.past[cur.past.length - 1]!, future: [cur.now, ...cur.future] } : cur));
  }, []);
  const redo = useCallback(() => {
    lastAt.current = 0;
    setH((cur) => (cur.future.length ? { past: [...cur.past, cur.now], now: cur.future[0]!, future: cur.future.slice(1) } : cur));
  }, []);
  /** In place, as no step of its own (Save tidying the name). */
  const replace = useCallback((next: CustomTheme) => setH((cur) => ({ ...cur, now: next })), []);
  return { draft: h.now, set, undo, redo, replace, canUndo: h.past.length > 0, canRedo: h.future.length > 0 };
}

/** Whether a key press belongs to a text box, which has an undo of its own. */
const typing = (el: EventTarget | null) => el instanceof HTMLElement && (el.isContentEditable || el instanceof HTMLTextAreaElement || (el instanceof HTMLInputElement && !["range", "checkbox", "radio", "button"].includes(el.type)));

/**
 * Making or changing a custom theme. The whole app wears the theme while it
 * is open, so every page behind this one is a preview too; beside the tools
 * (above them on a narrow screen) a small Nexa shows the parts the settings
 * never do, and pressing any part of it opens that colour. Nothing is kept
 * until Save; leaving with changes shakes the save bar, as every settings
 * page does.
 */
export function ThemeBuilder({ initial, isNew, onClose, onDuplicate }: { initial: CustomTheme; isNew: boolean; onClose: () => void; onDuplicate: (theme: CustomTheme) => void }) {
  const patch = useSettings((x) => x.patch);
  const theme = useSettings((x) => x.settings.theme);
  const accent = useSettings((x) => x.settings.accent);
  const pair = useSettings((x) => x.settings.systemThemes);
  const customs = useCustomThemes();
  const toast = useUI((u) => u.toast);
  const { draft, set: setDraft, undo, redo, replace, canUndo, canRedo } = useHistory(initial);
  const [saved, setSaved] = useState<CustomTheme>(initial);
  const [fresh, setFresh] = useState(isNew);
  const [tab, setTabState] = useState<Tab>(initial.source.kind === "simple" && Object.keys(initial.overrides).length === 0 ? "simple" : "advanced");
  const [held, setHeld] = useState(false);
  const [vision, setVision] = useState<Vision>("typical");
  const [focus, setFocus] = useState<{ token: TokenName; n: number } | null>(null);
  const [folded, setFolded] = useState(false);
  // Tune works from the theme as it was when a slider first moved, so going
  // back to zero is exactly where it started
  const [tune, setTune] = useState<Tune>(NEUTRAL_TUNE);
  const [tuneStatus, setTuneStatus] = useState(false);
  const tuneFrom = useRef<{ theme: CustomTheme; tokens: ReturnType<typeof resolveCustom>["tokens"] } | null>(null);
  // the simple colours last used, so Advanced can go back to them after trying a preset
  const lastSimple = useRef<CustomTheme["source"] | null>(initial.source.kind === "simple" ? initial.source : null);
  if (draft.source.kind === "simple") lastSimple.current = draft.source;
  const ids = useId();

  // Compare shows the theme as it was saved (or as it started, for a new one) while it is held
  const shownTheme = held ? saved : draft;
  const resolved = useMemo(() => resolveCustom(draft), [draft]);
  const shown = useMemo(() => (held ? resolveCustom(saved) : resolved), [held, saved, resolved]);
  const simpleColours = draft.source.kind === "simple" ? draft.source : null;
  const derived = useMemo(() => (simpleColours ? deriveTheme(simpleColours) : null), [simpleColours]);
  const issues = useMemo(() => contrastIssues(resolved.tokens), [resolved.tokens]);
  const issueSet = useMemo(() => new Set<string>(issues.map((i) => i.token)), [issues]);
  const changedSet = useMemo(() => new Set(Object.keys(draft.overrides)), [draft.overrides]);
  const previewTokens = useMemo(() => visionPalette(shown.tokens, vision), [shown.tokens, vision]);
  const dirty = fresh || !same(draft, saved);

  // The app wears the draft, a frame at a time while a picker is dragged.
  const setDraftTheme = useLookDraft((d) => d.setTheme);
  useEffect(() => {
    const frame = requestAnimationFrame(() => setDraftTheme({ tokens: shown.tokens, scheme: shown.scheme, id: shownTheme.id }));
    return () => cancelAnimationFrame(frame);
  }, [shown, shownTheme.id, setDraftTheme]);
  useEffect(() => () => setDraftTheme(null), [setDraftTheme]);

  useHoldUnsaved(dirty);
  const nudge = useUnsaved((u) => u.nudge);
  const [calm] = useState(nudge);

  const endTune = () => {
    tuneFrom.current = null;
    setTune(NEUTRAL_TUNE);
  };
  const setTab = (next: Tab) => {
    if (next !== "tune") endTune();
    setTabState(next);
  };
  const doUndo = () => {
    endTune();
    undo();
  };
  const doRedo = () => {
    endTune();
    redo();
  };

  // Ctrl+Z and Ctrl+Shift+Z (or Ctrl+Y) anywhere in the builder but a text box
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || typing(e.target)) return;
      const k = e.key.toLowerCase();
      if (k === "z" && !e.shiftKey) doUndo();
      else if ((k === "z" && e.shiftKey) || k === "y") doRedo();
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const applyTune = (next: Tune, status: boolean) => {
    if (!tuneFrom.current) tuneFrom.current = { theme: draft, tokens: resolved.tokens };
    const from = tuneFrom.current;
    setTune(next);
    setTuneStatus(status);
    setDraft(isNeutral(next) ? from.theme : withPalette(from.theme, tunePalette(from.tokens, next, status), from.tokens));
  };
  const resetTune = () => {
    if (tuneFrom.current) setDraft(tuneFrom.current.theme);
    endTune();
  };

  const pick = (token: TokenName) => {
    setTab("advanced");
    setFocus((f) => ({ token, n: (f?.n ?? 0) + 1 }));
  };

  const save = () => {
    const next = { ...draft, name: draft.name.trim().slice(0, MAX_THEME_NAME) || t("settings.themeBuilder.untitled") };
    const list = customs.some((c) => c.id === next.id) ? customs.map((c) => (c.id === next.id ? next : c)) : [...customs, next];
    // saving is choosing: the theme on screen stays on screen
    patch({ customThemes: list, theme: next.id });
    replace(next);
    setSaved(next);
    setFresh(false);
    toast(t("settings.themeBuilder.saved"), "success");
  };

  const remove = () =>
    useUI.getState().openModal("confirm", {
      title: t("settings.themeBuilder.deleteTitle", { name: saved.name || t("settings.themeBuilder.untitled") }),
      body: t("settings.themeBuilder.deleteBody"),
      confirmLabel: t("settings.themeBuilder.delete"),
      danger: true,
      onConfirm: () => {
        const gone = saved.id;
        patch({
          customThemes: customs.filter((c) => c.id !== gone),
          ...(theme === gone ? { theme: "dark" } : {}),
          ...(pair.dark === gone || pair.light === gone ? { systemThemes: { dark: pair.dark === gone ? "dark" : pair.dark, light: pair.light === gone ? "light" : pair.light } } : {}),
        });
        // nothing is left to be unsaved about
        setSaved(draft);
        setFresh(false);
        setTimeout(onClose, 0);
      },
    });

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const i = TABS.indexOf(tab);
    const next = e.key === "Home" ? TABS[0] : e.key === "End" ? TABS[TABS.length - 1] : e.key === "ArrowRight" ? TABS[(i + 1) % TABS.length] : e.key === "ArrowLeft" ? TABS[(i + TABS.length - 1) % TABS.length] : null;
    if (!next) return;
    e.preventDefault();
    choose(next);
    document.getElementById(`${ids}-tab-${next}`)?.focus();
  };

  const choose = (k: Tab) => {
    if (k !== "simple" || draft.source.kind === "simple") return setTab(k);
    // Simple works from a few colours; a theme built on a preset brings its own over
    const tk = resolved.tokens;
    setDraft({ ...draft, source: lastSimple.current?.kind === "simple" ? lastSimple.current : { kind: "simple", background: tk["bg-raised"], accent: tk.brand, text: null, scheme: resolved.scheme } });
    setTab("simple");
  };

  // Compare is held with the pointer or with Space or Enter, and lets go however it ends
  const hold = (on: boolean) => setHeld(on);

  return (
    <div data-wide-page="" data-testid="theme-builder">
      <div className={s.backRow}>
        <button type="button" className={s.backBtn} onClick={unlessUnsaved(onClose)} aria-label={t("server.settingsLayer.backTo", { name: t("settings.appearance.appearance") })} data-testid="builder-back">
          <ChevronLeft size={18} className={settings.flip} aria-hidden="true" />
          {t("settings.appearance.appearance")}
        </button>
      </div>
      <h2 className="visually-hidden">{fresh ? t("settings.themeBuilder.titleNew") : t("settings.themeBuilder.titleEdit")}</h2>
      <div className={b.head}>
        <div className={b.nameWrap}>
          <input
            className={b.name}
            value={draft.name}
            maxLength={MAX_THEME_NAME}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            placeholder={t("settings.themeBuilder.untitled")}
            aria-label={t("settings.themeBuilder.name")}
            title={t("settings.themeBuilder.rename")}
            dir="auto"
            spellCheck={false}
            data-testid="builder-name"
          />
          <p className={b.kind} aria-live="polite">
            {held ? (fresh ? t("settings.themeBuilder.comparingNew") : t("settings.themeBuilder.comparing")) : fresh ? t("settings.themeBuilder.titleNew") : t("settings.themeBuilder.titleEdit")}
          </p>
        </div>
        <div className={b.headTools}>
          <button type="button" className={b.tool} onClick={doUndo} disabled={!canUndo} aria-label={t("settings.themeBuilder.undo")} title={`${t("settings.themeBuilder.undo")} (Ctrl+Z)`} data-testid="builder-undo">
            <Undo2 size={18} aria-hidden="true" />
          </button>
          <button type="button" className={b.tool} onClick={doRedo} disabled={!canRedo} aria-label={t("settings.themeBuilder.redo")} title={`${t("settings.themeBuilder.redo")} (Ctrl+Shift+Z)`} data-testid="builder-redo">
            <Redo2 size={18} aria-hidden="true" />
          </button>
          <span className={b.toolSep} aria-hidden="true" />
          <button
            type="button"
            className={b.tool}
            data-held={held || undefined}
            disabled={!held && same(draft, saved)}
            aria-pressed={held}
            title={fresh ? t("settings.themeBuilder.compareNewHint") : t("settings.themeBuilder.compareHint")}
            onPointerDown={(e) => {
              e.currentTarget.setPointerCapture?.(e.pointerId);
              hold(true);
            }}
            onPointerUp={() => hold(false)}
            onPointerCancel={() => hold(false)}
            onLostPointerCapture={() => hold(false)}
            onKeyDown={(e) => {
              if ((e.key === " " || e.key === "Enter") && !e.repeat) {
                e.preventDefault();
                hold(true);
              }
            }}
            onKeyUp={(e) => {
              if (e.key === " " || e.key === "Enter") hold(false);
            }}
            onBlur={() => hold(false)}
            onContextMenu={(e) => e.preventDefault()}
            data-testid="builder-compare"
          >
            <SplitSquareHorizontal size={18} aria-hidden="true" />
            {t("settings.themeBuilder.compare")}
          </button>
        </div>
      </div>
      {accent ? (
        <div className={s.notice}>
          <Info size={16} aria-hidden="true" />
          <p className={s.noticeBody}>{t("settings.themeBuilder.accentOverride")}</p>
          <Button className={s.noticeAction} variant="secondary" size="sm" onClick={() => patch({ accent: null })}>
            {t("settings.themeBuilder.useThemeAccent")}
          </Button>
        </div>
      ) : null}
      <div className={b.work}>
        <div className={b.grid}>
          <aside className={b.stage} aria-label={t("settings.themeBuilder.preview")}>
            <div className={b.stageBar}>
              <h3>{t("settings.themeBuilder.preview")}</h3>
              <Button variant="ghost" size="sm" onClick={() => setFolded(!folded)} aria-expanded={!folded} data-testid="builder-fold">
                {folded ? t("designer.preview.show") : t("designer.preview.hide")}
                <ChevronDown size={16} aria-hidden="true" style={{ transform: folded ? undefined : "rotate(180deg)" }} />
              </Button>
            </div>
            <div className={b.stageBody} data-folded={folded || undefined}>
              <ThemePreview tokens={previewTokens} onPick={pick} />
              <div className={b.stageRow}>
                <Select value={vision} onChange={(e) => setVision(e.target.value as Vision)} aria-label={t("settings.themeBuilder.previewAs")} data-testid="builder-vision">
                  {VISIONS.map((v) => (
                    <option key={v} value={v}>
                      {t(`settings.themeBuilder.vision.${v}`)}
                    </option>
                  ))}
                </Select>
              </div>
              {issues.length ? (
                <div className={b.read} data-bad="" role="status" data-testid="builder-readability">
                  <AlertTriangle size={16} aria-hidden="true" />
                  <p>{t("settings.themeBuilder.issues", { count: issues.length })}</p>
                  <Button variant="secondary" size="sm" onClick={() => pick(issues[0]!.token)}>
                    {t("settings.themeBuilder.review")}
                  </Button>
                </div>
              ) : (
                <div className={b.read} data-ok="" role="status" data-testid="builder-readability">
                  <CheckCircle2 size={16} aria-hidden="true" />
                  <p>{t("settings.themeBuilder.allReadable")}</p>
                </div>
              )}
              <PaletteStrip tokens={resolved.tokens} changed={changedSet} issues={issueSet} onPick={pick} />
            </div>
          </aside>
          <div className={b.panel}>
            <div className={s.tabs} role="tablist" aria-label={t("settings.themeBuilder.modeLabel")}>
              {TABS.map((k) => (
                <button
                  key={k}
                  type="button"
                  role="tab"
                  id={`${ids}-tab-${k}`}
                  className={s.tab}
                  aria-selected={tab === k}
                  aria-controls={`${ids}-panel`}
                  tabIndex={tab === k ? 0 : -1}
                  onClick={() => choose(k)}
                  onKeyDown={onTabKey}
                  data-testid={`builder-tab-${k}`}
                >
                  {t(`settings.themeBuilder.${k}`)}
                </button>
              ))}
            </div>
            <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${tab}`}>
              {tab === "simple" && simpleColours && derived ? (
                <SimpleEditor
                  value={simpleColours}
                  derived={derived}
                  onChange={(v) => setDraft({ ...draft, source: { kind: "simple", ...v } })}
                  handSet={Object.keys(draft.overrides).length}
                  onClearHandSet={() => setDraft({ ...draft, overrides: {} })}
                />
              ) : tab === "tune" ? (
                <TuneEditor tune={tune} status={tuneStatus} onChange={applyTune} onReset={resetTune} />
              ) : (
                <AdvancedEditor theme={draft} tokens={resolved.tokens} issues={issues} onChange={setDraft} lastSimple={lastSimple.current} focus={focus} />
              )}
            </div>
          </div>
        </div>
      </div>
      <div className={s.builderFoot}>
        <Button variant="secondary" size="sm" onClick={() => download(fileName(draft.name), exportTheme(draft))} data-testid="builder-export">
          <Download size={15} aria-hidden="true" /> {t("settings.themeBuilder.export")}
        </Button>
        <Button variant="secondary" size="sm" onClick={() => void copyText(exportTheme(draft)).then((ok) => toast(ok ? t("settings.themeBuilder.copied") : t("common.copyFailed"), ok ? "success" : "error"))} data-testid="builder-copy">
          <Copy size={15} aria-hidden="true" /> {t("settings.themeBuilder.copy")}
        </Button>
        {fresh ? null : (
          <Button variant="secondary" size="sm" onClick={() => onDuplicate(draft)} data-testid="builder-duplicate">
            <CopyPlus size={15} aria-hidden="true" /> {t("settings.themeBuilder.duplicate")}
          </Button>
        )}
        {fresh ? null : (
          <Button className={s.push} variant="ghost" size="sm" onClick={remove} data-testid="builder-delete">
            <Trash2 size={15} aria-hidden="true" /> {t("settings.themeBuilder.delete")}
          </Button>
        )}
      </div>
      {dirty ? (
        <div key={nudge} className={[settings.saveBar, nudge !== calm ? settings.shake : ""].join(" ")} role="status" data-testid="save-bar">
          <span className={settings.saveText}>{fresh ? t("settings.themeBuilder.unsavedNew") : t("settings.themeBuilder.unsaved")}</span>
          <span className={[settings.saveBtns, s.touchBar].join(" ")}>
            <Button
              variant="ghost"
              onClick={
                fresh
                  ? onClose
                  : () => {
                      endTune();
                      setDraft(saved);
                    }
              }
              data-testid="builder-discard"
            >
              {fresh ? t("settings.themeBuilder.discard") : t("settings.themeBuilder.reset")}
            </Button>
            <Button variant="positive" onClick={save} data-testid="save">
              {t("settings.themeBuilder.save")}
            </Button>
          </span>
        </div>
      ) : null}
    </div>
  );
}
