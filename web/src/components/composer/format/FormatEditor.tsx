import {
  Baseline,
  Bold,
  Check,
  ChevronDown,
  Code,
  Columns3,
  Ellipsis,
  EyeOff,
  Highlighter,
  Info,
  Italic,
  Lightbulb,
  Link,
  List,
  ListChecks,
  ListCollapse,
  ListIndentDecrease,
  ListIndentIncrease,
  ListOrdered,
  MessageSquareWarning,
  OctagonAlert,
  Redo2,
  RemoveFormatting,
  Rows3,
  SendHorizontal,
  SeparatorHorizontal,
  SquareCode,
  Strikethrough,
  Subscript,
  Superscript,
  Table,
  TextAlignCenter,
  TextAlignEnd,
  TextAlignStart,
  TextQuote,
  Trash2,
  TriangleAlert,
  Underline,
  Undo2,
  X,
  type LucideIcon,
} from "lucide-react";
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import { Modal } from "@/components/common/Modal";
import { useSpellHighlights } from "@/components/common/SpellLines";
import { Tooltip } from "@/components/common/Tooltip";
import { modKeys, useTextMenu, type TextFormat } from "@/lib/textMenu";
import { useRenderCtx } from "@/lib/markdownCtx";
import type { AlertKind, Align } from "@/markdown/ast";
import { COLOR_NAMES, colorToken, FALLBACK_SURFACE, subscribeTheme, surfaceOf, swatchColor, type Surface } from "@/markdown/colors";
import { alertTitle } from "@/markdown/parse";
import { MessageCounter } from "../MessageCounter";
import type { Flag, Wrap } from "./model";
import type { ReadOptions } from "./markdown";
import { FormatSurface, type Command, type FileHost, type SurfaceState } from "./surface";
import s from "./FormatEditor.module.css";

import { t } from "@/i18n";

interface Props {
  /** What the message box holds when the editor opens. */
  initial: string;
  /** The message box's own placeholder, "Message #general". */
  placeholder: string;
  /** Puts the markdown in the message box. */
  onInsert: (markdown: string) => void;
  /** Sends the markdown; false when it could not go (too long), and the editor stays. Without it there is no Send, only Insert (an About Me). */
  onSend?: (markdown: string) => boolean;
  /** Closes without touching the message box. */
  onClose: () => void;
  canSend?: boolean;
  /** The editor's title, "Format Your Message" unless said otherwise. */
  title?: string;
  /** The longest the text may be, a message's unless said otherwise. */
  max?: number;
}

/** The formatting a text box's right-click menu offers, as the surface's marks. */
const MENU_FLAGS: Record<TextFormat, Flag> = { bold: "b", italic: "i", underline: "u", strike: "s", spoiler: "spoiler", code: "code" };

type Tray = "style" | "color" | "callout" | "more" | "link";

const KINDS = ["p", "h1", "h2", "h3", "h4", "h5", "h6", "subtext"] as const;
const WRAPS: { wrap: Exclude<Wrap, null>; icon: LucideIcon }[] = [
  { wrap: "quote", icon: TextQuote },
  { wrap: "note", icon: Info },
  { wrap: "tip", icon: Lightbulb },
  { wrap: "important", icon: MessageSquareWarning },
  { wrap: "warning", icon: TriangleAlert },
  { wrap: "caution", icon: OctagonAlert },
];
const LANGUAGES = ["js", "ts", "tsx", "json", "html", "css", "py", "go", "rust", "java", "kotlin", "swift", "c", "cpp", "cs", "php", "ruby", "sql", "bash", "sh", "yaml", "toml", "md", "diff"];

function kindLabel(kind: (typeof KINDS)[number]): string {
  if (kind === "p") return t("composer.format.styleNormal");
  if (kind === "subtext") return t("composer.format.styleSmall");
  return t("composer.format.styleHeading", { n: Number(kind.slice(1)) });
}

function kindShort(kind: string | null): string {
  if (kind && /^h[1-6]$/.test(kind)) return "H" + kind.slice(1);
  if (kind === "subtext") return t("composer.format.styleSmallShort");
  return t("composer.format.styleNormalShort");
}

function wrapLabel(wrap: Exclude<Wrap, null>): string {
  if (wrap === "quote") return t("composer.format.quote");
  if (wrap === "details") return t("composer.format.section");
  return alertTitle(wrap as AlertKind);
}

/** A web address as typed, with https:// added when it has no scheme; null for anything that is not one. */
export function cleanUrl(raw: string): string | null {
  const v = raw.trim();
  if (!v) return null;
  const withScheme = /^[a-z][a-z0-9+.-]*:/i.test(v) ? v : "https://" + v;
  if (!/^https?:\/\//i.test(withScheme)) return null;
  try {
    const u = new URL(withScheme);
    return u.hostname.includes(".") || u.hostname === "localhost" ? withScheme : null;
  } catch {
    return null;
  }
}

function useNarrow(): boolean {
  const query = "(max-width: 767px)";
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.(query).matches);
  useEffect(() => {
    const mq = window.matchMedia?.(query);
    if (!mq) return;
    const on = () => setNarrow(mq.matches);
    mq.addEventListener?.("change", on);
    return () => mq.removeEventListener?.("change", on);
  }, []);
  return narrow;
}

/** What a page gets from useFormatBench to lay out as it likes: the tools, the tray under them, and where the writing goes. */
export interface Bench {
  surfaceRef: React.RefObject<HTMLDivElement>;
  /** The surface itself, once it is made. */
  ctl: React.MutableRefObject<FormatSurface | null>;
  state: SurfaceState | null;
  /** The markdown as of the last change. */
  markdown: string;
  /** Whether what was loaded had to be shown simpler than it was written. */
  simplified: boolean;
  toolbar: ReactNode;
  tray: ReactNode | null;
  run: (cmd: Command) => void;
  /** The markdown right now, not a frame behind. */
  current: () => string;
}

interface BenchOptions {
  /** What the surface holds when it is made. */
  initial: string;
  placeholder: string;
  /** Ctrl/Cmd+Enter. */
  onSubmit: () => void;
  /** Escape, with no tray open to close first. */
  onEscape: () => void;
  /** Every change, once a frame at most. */
  onMarkdown?: (markdown: string) => void;
  /** A note's editor: files as blocks, and a note's length. */
  read?: ReadOptions;
  onFiles?: (shown: FileHost[]) => void;
  onPasteFiles?: (files: File[]) => void;
  /** The surface was drawn again. */
  onDraw?: () => void;
  /** A mention of someone was clicked (it is selected whole). */
  onMention?: (userId: string, el: HTMLElement) => void;
  /** Tools the page adds after the toolbar's own. */
  tools?: ReactNode;
  /** Tools the page puts first, where they are always in sight (a note's Add Picture). */
  lead?: ReactNode;
  /** Put the caret in the surface as soon as it is made. */
  focus?: boolean;
}

/**
 * The formatting tools and the surface they work on, for a page to place:
 * the chat's formatting editor puts them in a sheet, a note in its page.
 */
export function useFormatBench(o: BenchOptions): Bench {
  const ctx = useRenderCtx();
  const surfaceRef = useRef<HTMLDivElement>(null);
  const ctl = useRef<FormatSurface | null>(null);
  const [state, setState] = useState<SurfaceState | null>(null);
  const [markdown, setMarkdown] = useState(o.initial);
  const [tray, setTray] = useState<Tray | null>(null);
  const [simplified, setSimplified] = useState(false);
  const frame = useRef(0);
  // While a selection is being dragged out with the pointer, what the caret
  // is in does not change the tray: dragging from a list into the text under
  // it took the list's tray away, which moved the text under the pointer,
  // which moved the selection back into the list, and the tools flickered in
  // and out for as long as the button was held. It settles when it is let go.
  const [selecting, setSelecting] = useState(false);
  const settled = useRef<"list" | "code" | "table" | "link" | null>(null);

  // the controller's callbacks read these, so they never go stale
  const latest = useRef(o);
  latest.current = o;
  const trayNow = useRef(tray);
  trayNow.current = tray;

  useLayoutEffect(() => {
    const el = surfaceRef.current;
    if (!el) return;
    const c = new FormatSurface(el, {
      labels: {
        placeholder: o.placeholder,
        summary: t("composer.format.summaryPlaceholder"),
        code: t("composer.format.codeLabel"),
        alert: alertTitle,
      },
      names: ctx,
      onChange: () => {
        if (frame.current) return;
        frame.current = requestAnimationFrame(() => {
          frame.current = 0;
          const md = c.markdown();
          setMarkdown(md);
          latest.current.onMarkdown?.(md);
        });
      },
      onState: setState,
      onSubmit: () => latest.current.onSubmit(),
      onLinkKey: () => setTray("link"),
      onEscape: () => (trayNow.current ? setTray(null) : latest.current.onEscape()),
      ...(o.read ? { read: o.read } : {}),
      onFiles: (shown) => latest.current.onFiles?.(shown),
      onPasteFiles: (files) => latest.current.onPasteFiles?.(files),
      onDraw: () => latest.current.onDraw?.(),
      // only a page that shows people (a note) takes clicks on mentions; elsewhere they are plain text to the caret
      ...(o.onMention ? { onMention: (userId: string, el: HTMLElement) => latest.current.onMention?.(userId, el) } : {}),
    });
    ctl.current = c;
    setSimplified(c.load(o.initial));
    setMarkdown(c.markdown());
    const down = (e: PointerEvent) => {
      if (e.button === 0 && e.pointerType !== "touch") setSelecting(true);
    };
    const up = () => setSelecting(false);
    el.addEventListener("pointerdown", down);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    window.addEventListener("blur", up);
    if (o.focus !== false) c.focus();
    return () => {
      if (frame.current) cancelAnimationFrame(frame.current);
      el.removeEventListener("pointerdown", down);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
      window.removeEventListener("blur", up);
      c.destroy();
      ctl.current = null;
    };
    // the surface is made once, with what it was given when it opened
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // names that arrive after the surface was made (a profile fetched later) show on its mentions
  useEffect(() => {
    ctl.current?.setNames(ctx);
  }, [ctx]);

  const run = useCallback((cmd: Command) => ctl.current?.command(cmd), []);
  const current = () => ctl.current?.markdown() ?? markdown;
  const toggleTray = (next: Tray) => setTray((cur) => (cur === next ? null : next));

  // Nexa's own spell checker marks the surface, and its right-click menu
  // offers spellings, this editor's own undo and the marks of the toolbar
  const ownSpelling = useSpellHighlights(surfaceRef);
  useEffect(() => {
    const el = surfaceRef.current;
    if (!el) return;
    el.spellcheck = !ownSpelling;
    if (ownSpelling) el.setAttribute("data-spell", "");
    else el.removeAttribute("data-spell");
  }, [ownSpelling]);
  const stateRef = useRef(state);
  stateRef.current = state;
  useTextMenu(surfaceRef, {
    format: (f) => run({ t: "mark", flag: MENU_FLAGS[f] }),
    formatKeys: { bold: modKeys("B"), italic: modKeys("I"), underline: modKeys("U"), strike: modKeys("X", true), code: modKeys("E") },
    undo: () => run({ t: "undo" }),
    redo: () => run({ t: "redo" }),
    canUndo: () => !!stateRef.current?.canUndo,
    canRedo: () => !!stateRef.current?.canRedo,
    insert: (text) => run({ t: "text", text }),
  });

  const marks = state?.marks ?? {};
  const inCode = state?.kind === "code";
  const inTable = !!state?.table;
  const listTask = state?.list?.task ?? false;
  const kind = state?.kind ?? "p";

  // what the tray under (or on a phone, above) the toolbar shows: what was asked for, or what the caret is in
  // (as it was when a pointer started selecting, until it is let go)
  const caretIn: "list" | "code" | "table" | "link" | null = inTable ? "table" : inCode ? "code" : state?.link ? "link" : state?.kind === "li" ? "list" : null;
  if (!selecting) settled.current = caretIn;
  const shown: Tray | "list" | "code" | "table" | null = tray ?? (selecting ? settled.current : caretIn);

  const flag = (f: Flag, icon: LucideIcon, label: string, keys?: string) => (
    <ToolButton key={f} icon={icon} label={label} keys={keys} pressed={!!marks[f]} disabled={inCode} onPress={() => run({ t: "mark", flag: f })} />
  );
  const mod = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform) ? "⌘" : "Ctrl+";

  const toolbar = (
    <Toolbar label={t("composer.format.toolbar")}>
      {o.lead ? (
        <>
          {o.lead}
          <Divider />
        </>
      ) : null}
      <ToolButton icon={Undo2} mirror label={t("composer.format.undo")} keys={`${mod}Z`} disabled={!state?.canUndo} onPress={() => run({ t: "undo" })} />
      <ToolButton icon={Redo2} mirror label={t("composer.format.redo")} keys={`${mod}Y`} disabled={!state?.canRedo} onPress={() => run({ t: "redo" })} />
      <Divider />
      <ToolButton label={t("composer.format.style")} text={kindShort(kind === "li" || kind === "note" ? "p" : kind)} wide expanded={tray === "style"} disabled={inCode || inTable} onPress={() => toggleTray("style")} />
      <Divider />
      {flag("b", Bold, t("composer.format.bold"), `${mod}B`)}
      {flag("i", Italic, t("composer.format.italic"), `${mod}I`)}
      {flag("u", Underline, t("composer.format.underline"), `${mod}U`)}
      {flag("s", Strikethrough, t("composer.format.strike"), `${mod}Shift+X`)}
      {flag("spoiler", EyeOff, t("composer.format.spoiler"))}
      {flag("code", Code, t("composer.format.inlineCode"))}
      <Divider />
      <ToolButton icon={Link} label={t("composer.format.link")} keys={`${mod}K`} pressed={!!state?.link} expanded={tray === "link"} disabled={inCode} onPress={() => toggleTray("link")} />
      <ToolButton icon={Baseline} label={t("composer.format.textColour")} swatch={marks.color?.fg ?? null} expanded={tray === "color"} disabled={inCode} onPress={() => toggleTray("color")} />
      <Divider />
      <ToolButton icon={List} label={t("composer.format.bullets")} pressed={kind === "li" && !state?.list?.ordered && !listTask} disabled={inCode || inTable} onPress={() => run({ t: "list", type: "bullet" })} />
      <ToolButton icon={ListOrdered} label={t("composer.format.numbers")} pressed={kind === "li" && !!state?.list?.ordered && !listTask} disabled={inCode || inTable} onPress={() => run({ t: "list", type: "ordered" })} />
      <ToolButton icon={ListChecks} label={t("composer.format.checklist")} pressed={listTask} disabled={inCode || inTable} onPress={() => run({ t: "list", type: "task" })} />
      <Divider />
      <ToolButton icon={TextQuote} label={t("composer.format.callouts")} pressed={!!state?.wrap && state.wrap !== "details"} expanded={tray === "callout"} onPress={() => toggleTray("callout")} />
      <ToolButton icon={SquareCode} label={t("composer.format.codeBlock")} pressed={inCode} disabled={inTable} onPress={() => run({ t: "code" })} />
      <ToolButton icon={Table} label={t("composer.format.table")} disabled={inTable} onPress={() => run({ t: "table", rows: 3, cols: 2 })} />
      <ToolButton icon={Ellipsis} label={t("composer.format.more")} expanded={tray === "more"} onPress={() => toggleTray("more")} />
      <Divider />
      <ToolButton icon={RemoveFormatting} label={t("composer.format.clear")} keys={`${mod}\\`} onPress={() => run({ t: "clear" })} />
      {o.tools ? (
        <>
          <Divider />
          {o.tools}
        </>
      ) : null}
    </Toolbar>
  );

  const trayBody = (() => {
    switch (shown) {
      case "style":
        return (
          <div className={s.chips} role="group" aria-label={t("composer.format.style")}>
            {KINDS.map((k) => (
              <Chip key={k} pressed={kind === k} onPress={() => { run({ t: "kind", kind: k }); setTray(null); }} className={s[`style_${k}`]}>
                {kindLabel(k)}
              </Chip>
            ))}
          </div>
        );
      case "color":
        return <ColourTray marks={state?.marks ?? {}} surfaceEl={surfaceRef.current} run={run} />;
      case "callout":
        return (
          <div className={s.chips} role="group" aria-label={t("composer.format.callouts")}>
            {WRAPS.map(({ wrap, icon: Icon }) => (
              <Chip key={wrap} pressed={state?.wrap === wrap} onPress={() => { run({ t: "wrap", wrap }); setTray(null); }} className={s.calloutChip} data-kind={wrap}>
                <Icon size={16} aria-hidden="true" />
                {wrapLabel(wrap)}
              </Chip>
            ))}
          </div>
        );
      case "more":
        return (
          <div className={s.chips} role="group" aria-label={t("composer.format.more")}>
            <Chip pressed={!!marks.sup} disabled={inCode} onPress={() => run({ t: "mark", flag: "sup" })}><Superscript size={16} aria-hidden="true" />{t("composer.format.superscript")}</Chip>
            <Chip pressed={!!marks.sub} disabled={inCode} onPress={() => run({ t: "mark", flag: "sub" })}><Subscript size={16} aria-hidden="true" />{t("composer.format.subscript")}</Chip>
            <Chip pressed={!!marks.mark} disabled={inCode} onPress={() => run({ t: "mark", flag: "mark" })}><Highlighter size={16} aria-hidden="true" />{t("composer.format.highlight")}</Chip>
            <Chip pressed={state?.wrap === "details"} onPress={() => { run({ t: "wrap", wrap: "details" }); setTray(null); }}><ListCollapse size={16} aria-hidden="true" />{t("composer.format.section")}</Chip>
            <Chip onPress={() => { run({ t: "hr" }); setTray(null); }}><SeparatorHorizontal size={16} aria-hidden="true" />{t("composer.format.divider")}</Chip>
          </div>
        );
      case "link":
        return <LinkTray key={state?.link?.url ?? "new"} state={state} run={run} done={() => setTray(null)} asked={tray === "link"} />;
      case "list":
        return (
          <div className={s.chips} role="group" aria-label={t("composer.format.listTools")}>
            <Chip className={s.mirror} onPress={() => run({ t: "indent", delta: -1 })} disabled={(state?.list?.indent ?? 0) === 0}><ListIndentDecrease size={16} aria-hidden="true" />{t("composer.format.outdent")}</Chip>
            <Chip className={s.mirror} onPress={() => run({ t: "indent", delta: 1 })}><ListIndentIncrease size={16} aria-hidden="true" />{t("composer.format.indent")}</Chip>
            {listTask ? (
              <Chip pressed={!!state?.list?.checked} onPress={() => run({ t: "check" })}><Check size={16} aria-hidden="true" />{t("composer.format.done")}</Chip>
            ) : null}
          </div>
        );
      case "code":
        return <CodeTray key={state?.code?.lang ?? ""} lang={state?.code?.lang ?? ""} run={run} />;
      case "table":
        return state?.table ? <TableTray table={state.table} run={run} /> : null;
      default:
        return null;
    }
  })();

  // Escape in a tray closes the tray, not the editor
  const trayNode = trayBody ? (
    <div
      className={s.tray}
      data-testid="format-tray"
      onKeyDown={(e) => {
        if (e.key !== "Escape" || !tray) return;
        e.stopPropagation();
        setTray(null);
        ctl.current?.focus();
      }}
    >
      {trayBody}
    </div>
  ) : null;

  return { surfaceRef, ctl, state, markdown, simplified, toolbar, tray: trayNode, run, current };
}

/**
 * The formatting editor: a toolbar over a writing surface drawn like a
 * message, for formatting without knowing markdown. It opens with what the
 * message box holds and hands back markdown, to put in the box or to send.
 * On a phone it is a sheet as tall as the space above the keyboard, with the
 * toolbar at its foot, where the thumb is.
 */
export function FormatEditor({ initial, placeholder, onInsert, onSend, onClose, canSend = true, title, max }: Props) {
  const narrow = useNarrow();
  const [confirming, setConfirming] = useState(false);
  const titleId = useId();
  // the bench's callbacks read these, so they never go stale
  const latest = useRef({ send: () => {}, close: () => {} });
  const bench = useFormatBench({ initial, placeholder, onSubmit: () => latest.current.send(), onEscape: () => latest.current.close() });
  const { surfaceRef, markdown, simplified, toolbar, current } = bench;
  const trayBody = bench.tray;
  const trayOf = (body: ReactNode) => body;
  const dirty = () => current().trim() !== initial.trim();

  const insert = () => onInsert(current());
  const send = () => {
    // with nothing to send to, Ctrl+Enter puts the words in the box
    if (!onSend) return insert();
    if (!canSend) return;
    const md = current();
    if (!md.trim()) return;
    if (onSend(md)) return;
  };
  const requestClose = () => {
    if (dirty()) setConfirming(true);
    else onClose();
  };
  latest.current = { send, close: requestClose };

  const counter = <MessageCounter text={markdown} className={s.counter} max={max} />;
  const sendButton = !onSend ? null : (
    <button type="button" className={s.send} onMouseDown={(e) => e.preventDefault()} onClick={send} disabled={!canSend || !markdown.trim()} data-testid="format-send">
      <SendHorizontal size={18} aria-hidden="true" />
      <span>{t("composer.format.send")}</span>
    </button>
  );
  const insertButton = (
    <button type="button" className={s.insert} onMouseDown={(e) => e.preventDefault()} onClick={insert} data-testid="format-insert">
      {t("composer.format.insert")}
    </button>
  );

  return (
    <Modal open onClose={requestClose} bare ariaLabel={title ?? t("composer.format.title")} width={780} className={s.sheet} closeOnOverlay swipe={false}>
      <div className={s.frame} data-narrow={narrow || undefined} data-testid="format-editor">
        <header className={s.head}>
          <button type="button" className={s.close} onClick={requestClose} aria-label={t("composer.format.close")} data-testid="format-close">
            <X size={20} aria-hidden="true" />
          </button>
          <h2 className={s.title} id={titleId}>{title ?? t("composer.format.title")}</h2>
          {narrow ? (
            <div className={s.headActions}>
              {counter}
              {insertButton}
              {sendButton}
            </div>
          ) : null}
        </header>
        {confirming ? (
          <div className={s.confirm} role="alertdialog" aria-label={t("composer.format.discardTitle")}>
            <span>{t("composer.format.discardTitle")}</span>
            <span className={s.confirmActions}>
              <button type="button" className={s.ghost} onClick={() => setConfirming(false)} autoFocus>{t("composer.format.keepEditing")}</button>
              <button type="button" className={s.danger} onClick={onClose} data-testid="format-discard">{t("composer.format.discard")}</button>
            </span>
          </div>
        ) : null}
        {simplified ? <p className={s.simplified}>{t("composer.format.simplified")}</p> : null}
        {narrow ? null : toolbar}
        {!narrow && trayBody ? trayOf(trayBody) : null}
        <div className={s.page}>
          <div ref={surfaceRef} className={s.surface} aria-label={placeholder} data-testid="format-surface" />
        </div>
        {narrow && trayBody ? trayOf(trayBody) : null}
        {narrow ? toolbar : null}
        {narrow ? null : (
          <footer className={s.foot}>
            <span className={s.footNote}>{counter}</span>
            <button type="button" className={s.ghost} onClick={requestClose}>{t("chat.editMessage.cancel")}</button>
            {insertButton}
            {sendButton}
          </footer>
        )}
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// the toolbar
// ---------------------------------------------------------------------------

/**
 * A row of tools that scrolls sideways when it does not fit. Arrow keys move
 * between them, as in any toolbar; only one of them is a Tab stop.
 */
function Toolbar({ label, children }: { label: string; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const current = useRef(0);
  // one tool is the toolbar's Tab stop: the last one used, or the first
  useLayoutEffect(() => {
    const items = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("button") ?? []);
    const enabled = items.filter((b) => !b.disabled);
    const pick = enabled[Math.min(current.current, enabled.length - 1)] ?? null;
    for (const b of items) b.tabIndex = b === pick ? 0 : -1;
  });
  const onFocus = (e: React.FocusEvent<HTMLDivElement>) => {
    const target = e.target as EventTarget as HTMLElement;
    const items = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
    const at = items.findIndex((b) => b === target);
    if (at < 0) return;
    current.current = at;
    for (const b of items) b.tabIndex = b === target ? 0 : -1;
  };
  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const keys = ["ArrowLeft", "ArrowRight", "Home", "End"];
    if (!keys.includes(e.key)) return;
    const items = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    if (at < 0) return;
    e.preventDefault();
    const rtl = getComputedStyle(ref.current!).direction === "rtl";
    const step = (e.key === "ArrowRight") !== rtl ? 1 : -1;
    const next = e.key === "Home" ? 0 : e.key === "End" ? items.length - 1 : (at + step + items.length) % items.length;
    items[next]?.focus();
    items[next]?.scrollIntoView({ block: "nearest", inline: "nearest" });
  };
  return (
    <div ref={ref} className={s.toolbar} role="toolbar" aria-label={label} onKeyDown={onKeyDown} onFocus={onFocus} data-testid="format-toolbar">
      {children}
    </div>
  );
}

export function Divider() {
  return <span className={s.divider} aria-hidden="true" />;
}

interface ToolProps {
  icon?: LucideIcon;
  text?: string;
  label: string;
  keys?: string;
  pressed?: boolean;
  expanded?: boolean;
  disabled?: boolean;
  wide?: boolean;
  swatch?: string | null;
  /** An arrow along the line of text, which turns round in a right-to-left language. */
  mirror?: boolean;
  onPress: () => void;
}

/**
 * A tool never takes focus from the writing surface when pressed with a
 * mouse or a finger, so the selection it acts on is still there, and a
 * phone's keyboard stays up.
 */
export function ToolButton({ icon: Icon, text, label, keys, pressed, expanded, disabled, wide, swatch, mirror, onPress }: ToolProps) {
  const button = (
    <button
      type="button"
      className={[s.tool, wide ? s.toolWide : "", mirror ? s.mirror : ""].join(" ")}
      aria-label={label}
      aria-pressed={pressed === undefined ? undefined : pressed}
      aria-expanded={expanded === undefined ? undefined : expanded}
      aria-keyshortcuts={keys ? keys.replace(/⌘/g, "Meta+") : undefined}
      disabled={disabled}
      tabIndex={-1}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onPress}
      data-tool={label}
    >
      {Icon ? <Icon size={18} aria-hidden="true" /> : null}
      {text ? <span className={s.toolText}>{text}</span> : null}
      {wide ? <ChevronDown size={14} aria-hidden="true" /> : null}
      {swatch !== undefined ? <SwatchBar token={swatch} /> : null}
    </button>
  );
  return <Tooltip text={keys ? `${label} (${keys})` : label}>{button}</Tooltip>;
}

function SwatchBar({ token }: { token: string | null }) {
  const surface = useThemeSurface(null);
  return <span className={s.swatchBar} style={{ background: token ? swatchColor(token, surface) : "currentColor" }} aria-hidden="true" />;
}

function Chip({ pressed, disabled, onPress, className, children, ...rest }: { pressed?: boolean; disabled?: boolean; onPress: () => void; className?: string; children: ReactNode } & Record<`data-${string}`, string>) {
  return (
    <button type="button" className={[s.chip, className ?? ""].join(" ")} aria-pressed={pressed === undefined ? undefined : pressed} disabled={disabled} onMouseDown={(e) => e.preventDefault()} onClick={onPress} {...rest}>
      {children}
    </button>
  );
}

/** The surface colours are measured on, following theme changes. */
function useThemeSurface(el: HTMLElement | null): Surface {
  const [surface, setSurface] = useState<Surface>(FALLBACK_SURFACE);
  useEffect(() => {
    const measure = () => setSurface(el && el.isConnected ? surfaceOf(el) : surfaceOf(document.body));
    measure();
    return subscribeTheme(measure);
  }, [el]);
  return surface;
}

// ---------------------------------------------------------------------------
// trays
// ---------------------------------------------------------------------------

function ColourTray({ marks, surfaceEl, run }: { marks: SurfaceState["marks"]; surfaceEl: HTMLElement | null; run: (c: Command) => void }) {
  const [part, setPart] = useState<"fg" | "bg">("fg");
  const surface = useThemeSurface(surfaceEl);
  const current = part === "fg" ? (marks.color?.fg ?? null) : (marks.color?.bg ?? null);
  const [hex, setHex] = useState("");
  const [bad, setBad] = useState(false);
  const apply = (value: string | null) => run({ t: "color", part, value });
  const applyHex = () => {
    const token = colorToken(hex.startsWith("#") ? hex : "#" + hex);
    if (!token || !token.startsWith("#")) {
      setBad(true);
      return;
    }
    setBad(false);
    apply(token);
  };
  return (
    <div className={s.colourTray}>
      <div className={s.segment} role="radiogroup" data-glide="" aria-label={t("composer.format.colourFor")}>
        {(["fg", "bg"] as const).map((p) => (
          <button key={p} type="button" role="radio" aria-checked={part === p} className={s.segmentItem} onMouseDown={(e) => e.preventDefault()} onClick={() => setPart(p)}>
            {p === "fg" ? t("composer.format.colourText") : t("composer.format.highlight")}
          </button>
        ))}
      </div>
      <div className={s.swatches} role="group" aria-label={part === "fg" ? t("composer.format.textColour") : t("composer.format.highlight")}>
        <button type="button" className={s.swatch} aria-pressed={current === null} aria-label={t("composer.format.colourNone")} title={t("composer.format.colourNone")} onMouseDown={(e) => e.preventDefault()} onClick={() => apply(null)}>
          <span className={s.swatchNone} aria-hidden="true" />
        </button>
        {COLOR_NAMES.map((name) => {
          const colour = swatchColor(name, surface);
          const label = t(`composer.format.colour.${name}`);
          return (
            <button key={name} type="button" className={s.swatch} aria-pressed={current === name} aria-label={label} title={label} onMouseDown={(e) => e.preventDefault()} onClick={() => apply(name)} data-colour={name}>
              <span className={s.swatchDot} style={part === "fg" ? { background: colour } : { background: colour, opacity: 0.55 }} aria-hidden="true" />
            </button>
          );
        })}
      </div>
      <form noValidate
        className={s.hexForm}
        onSubmit={(e) => {
          e.preventDefault();
          applyHex();
        }}
      >
        <input className={s.hexInput} value={hex} onChange={(e) => { setHex(e.target.value); setBad(false); }} placeholder="#ff8800" aria-label={t("composer.format.colourHex")} aria-invalid={bad || undefined} maxLength={7} spellCheck={false} autoCapitalize="none" enterKeyHint="done" />
        <button type="submit" className={s.chip}>{t("composer.format.apply")}</button>
        {bad ? <span className={s.error} role="alert">{t("composer.format.colourHexInvalid")}</span> : null}
      </form>
    </div>
  );
}

function LinkTray({ state, run, done, asked }: { state: SurfaceState | null; run: (c: Command) => void; done: () => void; asked: boolean }) {
  const existing = state?.link ?? null;
  const [url, setUrl] = useState(existing?.url ?? "");
  const [words, setWords] = useState("");
  const [bad, setBad] = useState(false);
  const needsWords = !existing && !!state?.collapsed;
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    // Opened on purpose, the address field takes the keyboard (on a phone only when tapped);
    // shown because the caret is in a link, it leaves the keyboard where it is.
    if (asked && window.matchMedia?.("(pointer: fine)").matches) input.current?.focus();
    // only as the tray first shows
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const apply = () => {
    const clean = cleanUrl(url);
    if (!clean) {
      setBad(true);
      return;
    }
    run({ t: "link", url: clean, text: words });
    done();
  };
  return (
    <form noValidate
      className={s.linkTray}
      onSubmit={(e) => {
        e.preventDefault();
        apply();
      }}
    >
      {needsWords ? <input className={s.field} value={words} onChange={(e) => setWords(e.target.value)} placeholder={t("composer.format.linkWords")} aria-label={t("composer.format.linkWords")} dir="auto" /> : null}
      <input ref={input} className={s.field} value={url} onChange={(e) => { setUrl(e.target.value); setBad(false); }} placeholder="https://" aria-label={t("composer.format.linkAddress")} aria-invalid={bad || undefined} type="url" inputMode="url" autoCapitalize="none" spellCheck={false} dir="ltr" enterKeyHint="done" data-testid="format-link-url" />
      <button type="submit" className={s.chip} data-testid="format-link-apply">{t("composer.format.apply")}</button>
      {existing ? (
        <button type="button" className={s.chip} onMouseDown={(e) => e.preventDefault()} onClick={() => { run({ t: "link", url: null }); done(); }}>
          {t("composer.format.linkRemove")}
        </button>
      ) : null}
      {bad ? <span className={s.error} role="alert">{t("composer.format.linkInvalid")}</span> : null}
    </form>
  );
}

function CodeTray({ lang, run }: { lang: string; run: (c: Command) => void }) {
  const [value, setValue] = useState(lang);
  const list = useId();
  return (
    <form noValidate
      className={s.linkTray}
      onSubmit={(e) => {
        e.preventDefault();
        run({ t: "lang", lang: value });
      }}
    >
      <input className={s.field} value={value} onChange={(e) => setValue(e.target.value)} onBlur={() => value !== lang && run({ t: "lang", lang: value })} placeholder={t("composer.format.languageHint")} aria-label={t("composer.format.language")} list={list} autoCapitalize="none" spellCheck={false} dir="ltr" enterKeyHint="done" data-testid="format-code-lang" />
      <datalist id={list}>
        {LANGUAGES.map((l) => (
          <option key={l} value={l} />
        ))}
      </datalist>
      <button type="button" className={s.chip} onMouseDown={(e) => e.preventDefault()} onClick={() => run({ t: "code" })}>
        {t("composer.format.codeToText")}
      </button>
    </form>
  );
}

function TableTray({ table, run }: { table: NonNullable<SurfaceState["table"]>; run: (c: Command) => void }) {
  const op = (o: Extract<Command, { t: "tableOp" }>["op"]) => run({ t: "tableOp", op: o });
  const align = (a: Align) => op({ kind: "align", col: table.col, align: a });
  return (
    <div className={s.chips} role="group" aria-label={t("composer.format.tableTools")}>
      <Chip onPress={() => op({ kind: "addRow", at: Math.max(1, table.row) })}><Rows3 size={16} aria-hidden="true" />{t("composer.format.rowAbove")}</Chip>
      <Chip onPress={() => op({ kind: "addRow", at: table.row + 1 })}><Rows3 size={16} aria-hidden="true" />{t("composer.format.rowBelow")}</Chip>
      <Chip onPress={() => op({ kind: "addCol", at: table.col })}><Columns3 size={16} aria-hidden="true" />{t("composer.format.columnBefore")}</Chip>
      <Chip onPress={() => op({ kind: "addCol", at: table.col + 1 })}><Columns3 size={16} aria-hidden="true" />{t("composer.format.columnAfter")}</Chip>
      <Chip pressed={table.align === "left"} onPress={() => align(table.align === "left" ? null : "left")}><TextAlignStart size={16} aria-hidden="true" />{t("composer.format.alignStart")}</Chip>
      <Chip pressed={table.align === "center"} onPress={() => align(table.align === "center" ? null : "center")}><TextAlignCenter size={16} aria-hidden="true" />{t("composer.format.alignCenter")}</Chip>
      <Chip pressed={table.align === "right"} onPress={() => align(table.align === "right" ? null : "right")}><TextAlignEnd size={16} aria-hidden="true" />{t("composer.format.alignEnd")}</Chip>
      <Chip disabled={table.row === 0 || table.rows <= 1} onPress={() => op({ kind: "delRow", at: table.row })}><Trash2 size={16} aria-hidden="true" />{t("composer.format.deleteRow")}</Chip>
      <Chip onPress={() => op({ kind: "delCol", at: table.col })}><Trash2 size={16} aria-hidden="true" />{t("composer.format.deleteColumn")}</Chip>
      <Chip onPress={() => op({ kind: "delete" })}><Trash2 size={16} aria-hidden="true" />{t("composer.format.deleteTable")}</Chip>
    </div>
  );
}

export default FormatEditor;
