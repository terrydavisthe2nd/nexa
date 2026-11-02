import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { ChevronRight, Copy, MessageCircle, CornerUpLeft, Info, Lightbulb, MessageSquareWarning, OctagonAlert, TriangleAlert, type LucideIcon } from "lucide-react";
import { createContext, Fragment, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { CSSProperties, KeyboardEvent, MouseEvent, ReactNode } from "react";
import { Twemoji } from "@/components/common/Twemoji";
import { copyText } from "@/lib/clipboard";
import { shownInvite } from "@/lib/inviteLink";
import { jumpTarget } from "@/lib/appLinks";
import { channelDisplayName } from "@/lib/names";
import { useAuth } from "@/stores/auth";
import type { Channel } from "@/api/types";
import { channels as channelsApi } from "@/api/channels";
import { MentionGlyph } from "@/components/common/ChannelGlyph";
import { ensureShortcodes, mightHaveShortcode, onShortcodesReady, shortcodeVersion, shortcodesReady } from "@/lib/shortcodes";
import { formatStamp, type StampStyle } from "@/lib/time";
import type { AlertKind, Align, Node, RenderCtx } from "./ast";
import { FALLBACK_SURFACE, paintColor, subscribeTheme, surfaceOf, themeVersion, type Surface } from "./colors";
import { alertTitle, parse, plainText } from "./parse";

import { t } from "@/i18n";
import { clampZalgo } from "@/lib/zalgo";
// ---------------------------------------------------------------------------
// React renderer for the markdown AST.
//
// Everything here builds real React elements; no HTML string ever reaches the
// DOM, including the syntax highlighting, which is converted from highlight.js'
// output by `parseHighlightHtml` below.
// ---------------------------------------------------------------------------

/** Renders a raw message. `parse` is memoised on the source and the mode. */
export function Markdown(props: { source: string; ctx: RenderCtx; inline?: boolean; jumbo?: boolean; files?: boolean; max?: number }): JSX.Element {
  const { source, ctx, inline, jumbo, files, max } = props;
  // `:shortcode:` names need a table that arrives on demand; once it does,
  // everything already on screen parses again and the names turn into emoji.
  const codes = useShortcodes(source);
  const nodes = useMemo(() => parse(source, { inline: inline === true, files: files === true, ...(max ? { max } : {}) }), [source, inline, files, max, codes]);
  // footnote links point within this message, whatever else is on the page
  const scope = useId();
  return (
    <NoteScope.Provider value={scope}>
      <RenderNodes nodes={nodes} ctx={ctx} jumbo={jumbo} />
    </NoteScope.Provider>
  );
}

/** Loads the shortcode table when some text might need it, and re-renders once it lands. */
function useShortcodes(source: string): number {
  const [version, setVersion] = useState(() => shortcodeVersion());
  const wants = mightHaveShortcode(source);
  useEffect(() => {
    if (!wants || shortcodesReady()) return;
    ensureShortcodes();
    return onShortcodesReady(() => setVersion(shortcodeVersion()));
  }, [wants]);
  return version;
}

/**
 * A key that follows what the node holds rather than where it sits.
 *
 * Most nodes are stateless, but a spoiler remembers being revealed and a code
 * block its highlighting. Keyed by index, an edit that removes a node hands
 * that state to whichever node moves into its place — the second spoiler in a
 * message shows itself because the first one was opened.
 */
function signature(node: Node, max: number): string {
  if (max <= 0) return "";
  switch (node.type) {
    case "text":
    case "code":
    case "codeblock":
    case "unicodeEmoji":
      return node.text.slice(0, max);
    case "emoji":
      return node.id;
    case "mention":
      return "id" in node ? `${node.kind}:${node.id}` : node.kind;
    case "link":
      return node.url.slice(0, max);
    case "timestamp":
      return `${node.unix}:${node.style}`;
    case "br":
    case "hr":
      return "";
    case "file":
      return node.id;
    case "task":
      return node.checked ? "x" : "-";
    case "footnoteRef":
      return node.label.slice(0, max);
    case "footnotes":
      return node.notes.map((note) => note.label).join(" ").slice(0, max);
    case "table":
      return signatureOf(node.head.flat(), max);
    case "details":
      return signatureOf([...node.summary, ...node.children], max);
    case "list": {
      let out = "";
      for (const item of node.items) {
        for (const child of item) {
          out += signature(child, max - out.length);
          if (out.length >= max) return out.slice(0, max);
        }
      }
      return out;
    }
    default:
      return signatureOf(node.children, max);
  }
}

function signatureOf(list: Node[], max: number): string {
  let out = "";
  for (const child of list) {
    out += signature(child, max - out.length);
    if (out.length >= max) return out.slice(0, max);
  }
  return out;
}

function nodeKey(node: Node, n: number): string {
  return `${n}:${node.type}:${signature(node, 48)}`;
}

/** Renders an already parsed list of nodes. */
export function RenderNodes(props: { nodes: Node[]; ctx: RenderCtx; jumbo?: boolean }): JSX.Element {
  useMediaAnimation();
  const { nodes, ctx, jumbo } = props;
  return (
    <>
      {nodes.map((node, i) => (
        <Fragment key={nodeKey(node, i)}>{renderNode(node, ctx, jumbo === true)}</Fragment>
      ))}
    </>
  );
}

function renderNode(node: Node, ctx: RenderCtx, jumbo: boolean): ReactNode {
  switch (node.type) {
    case "text":
      // zalgo sent from before it was refused, or by something that skipped the app, is cut back to fit its line
      return clampZalgo(node.text);
    case "br":
      return <br />;
    case "strong":
      return (
        <strong>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </strong>
      );
    case "em":
      return (
        <em>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </em>
      );
    case "u":
      return (
        <u>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </u>
      );
    case "s":
      return (
        <s>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </s>
      );
    case "spoiler":
      return (
        <Spoiler ctx={ctx}>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </Spoiler>
      );
    case "mark":
      return (
        <mark className="md-mark">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </mark>
      );
    case "sup":
      return (
        <sup className="md-sup">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </sup>
      );
    case "sub":
      return (
        <sub className="md-sub">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </sub>
      );
    case "color":
      return (
        <ColorText fg={node.fg} bg={node.bg}>
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </ColorText>
      );
    // Every block takes the direction of its own words (dir="auto"): an
    // English line in an Arabic conversation reads, and lines up, as English.
    case "paragraph":
      return (
        <div className="md-paragraph" dir="auto">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </div>
      );
    case "quote":
      return (
        <blockquote className="md-quote" dir="auto">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </blockquote>
      );
    case "heading": {
      const Tag = `h${node.level}` as "h1" | "h2" | "h3" | "h4" | "h5" | "h6";
      return (
        <Tag className={"md-h" + String(node.level)} dir="auto">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </Tag>
      );
    }
    case "subtext":
      return (
        <small className="md-subtext" dir="auto">
          <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />
        </small>
      );
    case "list": {
      const items = node.items.map((item, i) => {
        const task = item[0]?.type === "task" ? item[0] : null;
        return (
          <li key={i} className={task ? "md-list-item md-task-item" : "md-list-item"} dir="auto" data-checked={task ? task.checked : undefined}>
            <RenderNodes nodes={item} ctx={ctx} jumbo={jumbo} />
          </li>
        );
      });
      const tasks = node.items.some((item) => item[0]?.type === "task");
      // the list as a whole follows its first item, so its markers sit on that side
      const cls = tasks ? "md-list md-task-list" : "md-list";
      return node.ordered ? (
        <ol className={cls} start={node.start} dir="auto">
          {items}
        </ol>
      ) : (
        <ul className={cls} dir="auto">{items}</ul>
      );
    }
    case "task":
      // read only: a message's checklist says what its author ticked
      return <input type="checkbox" className="md-task" checked={node.checked} disabled readOnly aria-label={node.checked ? t("render.taskDone") : t("render.taskOpen")} />;
    case "hr":
      return <hr className="md-hr" />;
    case "file":
      return ctx.renderFile ? <>{ctx.renderFile(node.id, node.name, node.width, node.align)}</> : <div className="md-paragraph" dir="auto">{node.name}</div>;
    case "table":
      return <Table node={node} ctx={ctx} />;
    case "alert":
      return <Alert kind={node.kind} ctx={ctx} nodes={node.children} />;
    case "details":
      return (
        <details className="md-details" dir="auto">
          <summary className="md-summary">
            <ChevronRight className="md-summary-chevron" size={16} aria-hidden="true" />
            <span className="md-summary-text">{node.summary.length ? <RenderNodes nodes={node.summary} ctx={ctx} /> : t("render.details")}</span>
          </summary>
          <div className="md-details-body">
            <RenderNodes nodes={node.children} ctx={ctx} />
          </div>
        </details>
      );
    case "footnoteRef":
      return <FootnoteRef n={node.n} />;
    case "footnotes":
      return <Footnotes notes={node.notes} ctx={ctx} />;
    case "code":
      return <code className="md-inline-code">{node.text}</code>;
    case "codeblock":
      return <CodeBlock lang={node.lang} text={node.text} />;
    case "link": {
      // a bare link to a channel or message this account can see reads as a pill, as a channel mention does
      const jump = node.masked ? null : jumpTarget(node.url);
      if (jump) return <JumpLink url={node.url} channelId={jump.channelId} messageId={jump.messageId} ctx={ctx} fallback={<PlainLink node={node} ctx={ctx} jumbo={jumbo} />} />;
      return <PlainLink node={node} ctx={ctx} jumbo={jumbo} />;
    }
    case "mention":
      switch (node.kind) {
        case "everyone":
          return <span className="md-mention" style={STATIC}>{t("render.everyone")}</span>;
        case "here":
          return <span className="md-mention" style={STATIC}>{t("render.here")}</span>;
        case "user":
          return <UserMention id={node.id} ctx={ctx} />;
        case "channel":
          return <ChannelMention id={node.id} ctx={ctx} />;
        default:
          return <RoleMention id={node.id} ctx={ctx} />;
      }
    // In a box the pointer lands on rather than the picture (see Twemoji's
    // boxClassName), which EmojiDetails names; a copy takes the picture's alt text.
    case "emoji":
      if (ctx.plainEmoji?.(node.id)) return <>{":" + node.name + ":"}</>;
      return (
        <span
          className={jumbo ? "md-emoji-box md-emoji-box-jumbo" : "md-emoji-box"}
          data-emoji-card="custom"
          data-emoji-id={node.id}
          data-emoji-name={node.name}
          data-emoji-animated={node.animated ? "true" : undefined}
        >
          <img className={jumbo ? "md-emoji md-emoji-jumbo" : "md-emoji"} src={ctx.emojiUrl(node.id, node.animated, 48)} alt={":" + node.name + ":"} draggable={false} />
        </span>
      );
    case "unicodeEmoji":
      return (
        <Twemoji
          text={node.text}
          className={jumbo ? "md-emoji md-emoji-jumbo" : "md-emoji"}
          fallbackClassName={jumbo ? "md-emoji-unicode md-emoji-jumbo" : "md-emoji-unicode"}
          boxClassName={jumbo ? "md-emoji-box md-emoji-box-jumbo" : "md-emoji-box"}
        />
      );
    case "timestamp":
      return <Timestamp unix={node.unix} style={node.style} />;
  }
}

// ---------------------------------------------------------------------------
// tables, callouts, footnotes
// ---------------------------------------------------------------------------

const ALIGN: Record<Exclude<Align, null>, CSSProperties["textAlign"]> = { left: "start", center: "center", right: "end" };

/**
 * A table scrolls sideways inside its own box rather than widening the
 * message; while it does, the box takes focus so the keyboard can scroll it too.
 */
function Table(props: { node: Extract<Node, { type: "table" }>; ctx: RenderCtx }): JSX.Element {
  const { node, ctx } = props;
  const ref = useRef<HTMLDivElement>(null);
  const [scrolls, setScrolls] = useState(false);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const check = () => setScrolls(el.scrollWidth > el.clientWidth + 1);
    check();
    const ro = new ResizeObserver(check);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const cell = (nodes: Node[], c: number, head: boolean) => {
    const align = node.align[c];
    const style = align ? { textAlign: ALIGN[align] } : undefined;
    const content = <RenderNodes nodes={nodes} ctx={ctx} />;
    return head ? (
      <th key={c} style={style} dir="auto">{content}</th>
    ) : (
      <td key={c} style={style} dir="auto">{content}</td>
    );
  };
  return (
    <div ref={ref} className="md-table-wrap" tabIndex={scrolls ? 0 : undefined} role={scrolls ? "region" : undefined} aria-label={scrolls ? t("render.table") : undefined}>
      <table className="md-table">
        <thead>
          <tr>{node.head.map((c, i) => cell(c, i, true))}</tr>
        </thead>
        {node.rows.length ? (
          <tbody>
            {node.rows.map((row, r) => (
              <tr key={r}>{row.map((c, i) => cell(c, i, false))}</tr>
            ))}
          </tbody>
        ) : null}
      </table>
    </div>
  );
}

const ALERT_ICON: Record<AlertKind, LucideIcon> = { note: Info, tip: Lightbulb, important: MessageSquareWarning, warning: TriangleAlert, caution: OctagonAlert };

/** A callout: its kind named and marked in its colour, then what it says. */
function Alert(props: { kind: AlertKind; nodes: Node[]; ctx: RenderCtx }): JSX.Element {
  const Icon = ALERT_ICON[props.kind];
  const titleId = useId();
  return (
    <div className="md-alert" data-kind={props.kind} role="note" aria-labelledby={titleId} dir="auto">
      <p className="md-alert-title" id={titleId}>
        <Icon size={16} aria-hidden="true" />
        {alertTitle(props.kind)}
      </p>
      {props.nodes.length ? <RenderNodes nodes={props.nodes} ctx={props.ctx} /> : null}
    </div>
  );
}

/** What footnote links in one message are prefixed with, so two messages' notes never swap places. */
const NoteScope = createContext("md");

function noteIds(scope: string, n: number): { ref: string; note: string } {
  const safe = scope.replace(/[^a-zA-Z0-9_-]/g, "");
  return { ref: `${safe}-fnref-${n}`, note: `${safe}-fn-${n}` };
}

/** Scrolls to an element in the same message and puts the keyboard there, without touching the address bar. */
function jumpTo(e: MouseEvent<HTMLAnchorElement>, id: string): void {
  e.preventDefault();
  const el = document.getElementById(id);
  if (!el) return;
  const reduce = document.documentElement.dataset["reducedMotion"] === "true" || !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  el.scrollIntoView?.({ block: "nearest", behavior: reduce ? "auto" : "smooth" });
  el.focus({ preventScroll: true });
}

function FootnoteRef(props: { n: number }): JSX.Element {
  const ids = noteIds(useContext(NoteScope), props.n);
  return (
    <sup className="md-fnref">
      <a href={"#" + ids.note} id={ids.ref} className="md-fnref-link" aria-label={t("render.footnote", { n: props.n })} onClick={(e) => jumpTo(e, ids.note)}>
        {props.n}
      </a>
    </sup>
  );
}

function Footnotes(props: { notes: Extract<Node, { type: "footnotes" }>["notes"]; ctx: RenderCtx }): JSX.Element {
  const scope = useContext(NoteScope);
  return (
    <section className="md-footnotes" aria-label={t("render.footnotes")}>
      <ol className="md-footnote-list">
        {props.notes.map((note) => {
          const ids = noteIds(scope, note.n);
          return (
            <li key={note.n} id={ids.note} tabIndex={-1} value={note.n} dir="auto">
              <RenderNodes nodes={note.children} ctx={props.ctx} />
              <a href={"#" + ids.ref} className="md-fnback" aria-label={t("render.footnoteBack", { n: note.n })} onClick={(e) => jumpTo(e, ids.ref)}>
                <CornerUpLeft size={13} aria-hidden="true" />
              </a>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

// ---------------------------------------------------------------------------
// coloured text
// ---------------------------------------------------------------------------

/**
 * Words in the colour their author chose, measured against whatever they are
 * drawn on (colors.ts). It is painted for the theme's own background first and
 * measured again before the first paint, and again when the theme changes.
 */
function ColorText(props: { fg: string | null; bg: string | null; children: ReactNode }): JSX.Element {
  const ref = useRef<HTMLSpanElement>(null);
  const theme = useSyncExternalStore(subscribeTheme, themeVersion, themeVersion);
  const [surface, setSurface] = useState<Surface>(FALLBACK_SURFACE);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const next = surfaceOf(el.parentElement ?? el);
    setSurface((cur) => (sameSurface(cur, next) ? cur : next));
  }, [theme]);
  const paint = paintColor({ fg: props.fg, bg: props.bg }, surface);
  const style: CSSProperties = {};
  if (paint.color) style.color = paint.color;
  if (paint.background) style.backgroundColor = paint.background;
  return (
    <span ref={ref} className={paint.background ? "md-color md-color-bg" : "md-color"} data-fg={props.fg ?? undefined} data-bg={props.bg ?? undefined} style={style}>
      {props.children}
    </span>
  );
}

function sameSurface(a: Surface, b: Surface): boolean {
  return a.bg.every((v, i) => Math.round(v) === Math.round(b.bg[i]!)) && a.text.every((v, i) => Math.round(v) === Math.round(b.text[i]!));
}

// ---------------------------------------------------------------------------
// spoilers
// ---------------------------------------------------------------------------

/**
 * A spoiler. Hidden, it is a button named "spoiler" whose words are out of
 * the accessibility tree as well as out of sight (transparent text used to be
 * read out in full, as the button's name); shown, it is ordinary text again.
 */
function Spoiler(props: { ctx: RenderCtx; children: ReactNode }): JSX.Element {
  const [revealed, setRevealed] = useState(false);
  const open = props.ctx.revealSpoilers === true || revealed;
  const inner = <span className="md-spoiler-inner">{props.children}</span>;
  // a preview keeps a spoiler covered, and does not offer to uncover it
  if (props.ctx.inert && !open) {
    return (
      <span className="md-spoiler" data-revealed="false" role="img" aria-label={t("render.spoilerHidden")}>
        {inner}
      </span>
    );
  }
  if (open) {
    return (
      <span className="md-spoiler" data-revealed="true">
        {inner}
      </span>
    );
  }
  return (
    <span
      className="md-spoiler"
      data-revealed="false"
      role="button"
      tabIndex={0}
      aria-label={t("render.spoilerHidden")}
      aria-expanded={false}
      onClick={(e) => {
        // the first click only uncovers: a link or a mention under it waits for a second one
        e.preventDefault();
        e.stopPropagation();
        setRevealed(true);
      }}
      onKeyDown={(e) => {
        if (e.key !== "Enter" && e.key !== " ") return;
        e.preventDefault();
        setRevealed(true);
      }}
    >
      {inner}
    </span>
  );
}

// ---------------------------------------------------------------------------
// mentions
// ---------------------------------------------------------------------------

/** Enter or Space on a mention that works as a button. */
function pressed(e: KeyboardEvent): boolean {
  if (e.key !== "Enter" && e.key !== " ") return false;
  e.preventDefault();
  return true;
}

function UserMention(props: { id: string; ctx: RenderCtx }): JSX.Element {
  const user = props.ctx.users[props.id];
  const name = user ? user.display_name || user.username || t("render.unknownUser") : t("render.unknownUser");
  if (props.ctx.inert) return <span className="md-mention md-user-mention">@<PersonName showNameStyle={false} userId={props.id} serverId={props.ctx.serverId} user={user ? { id: props.id, name_style: user.name_style } : undefined}>{name}</PersonName></span>;
  return (
    <span
      className="md-mention md-user-mention"
      role="button"
      tabIndex={0}
      onClick={(e) => props.ctx.onUserClick?.(props.id, e.currentTarget)}
      onKeyDown={(e) => pressed(e) && props.ctx.onUserClick?.(props.id, e.currentTarget)}
    >
      @<PersonName showNameStyle={false} userId={props.id} serverId={props.ctx.serverId} user={user ? { id: props.id, name_style: user.name_style } : undefined}>{name}</PersonName>
    </span>
  );
}

function ChannelMention(props: { id: string; ctx: RenderCtx }): JSX.Element {
  const channel = props.ctx.channels[props.id];
  // A channel this device does not know was deleted, or is private and not
  // for this reader: which one cannot be told, so it says neither, and it
  // leads nowhere.
  if (!channel?.name) {
    return (
      <span className="md-mention" style={UNKNOWN_MENTION} data-channel-mention="unknown">
        {"#" + t("render.unknownChannel")}
      </span>
    );
  }
  const name = channel.name;
  if (props.ctx.inert) {
    return (
      <span className="md-mention">
        <MentionGlyph channel={channel} />{name}
      </span>
    );
  }
  return (
    <span className="md-mention" role="button" tabIndex={0} onClick={() => props.ctx.onChannelClick?.(props.id)} onKeyDown={(e) => pressed(e) && props.ctx.onChannelClick?.(props.id)}>
      <MentionGlyph channel={channel} />{name}
    </span>
  );
}

function PlainLink({ node, ctx, jumbo }: { node: Extract<Node, { type: "link" }>; ctx: RenderCtx; jumbo?: boolean }): JSX.Element {
  // an invite pasted as it is reads without its key, which is most of its length; the link keeps it
  const invite = node.masked ? null : shownInvite(node.url);
  const words = invite ?? <RenderNodes nodes={node.children} ctx={ctx} jumbo={jumbo} />;
  if (ctx.inert) return <span className="md-link">{words}</span>;
  // A masked link says where it really goes, after its words, unless they
  // already name that site: the tooltip that did so shows on no phone.
  const host = node.masked ? linkHost(node.url) : null;
  const shows = host && !plainText(node.children).toLowerCase().replace(/\bwww\./g, "").includes(host);
  return (
    <a
      href={node.url}
      target="_blank"
      rel="noopener noreferrer"
      className="md-link"
      title={node.masked ? node.url : undefined}
    >
      {words}
      {shows ? <span className="md-link-host" dir="ltr">{host}</span> : null}
    </a>
  );
}

// Channels a link names that this device has not loaded (an archived thread, say), asked of the
// server once each; it answers only for one this account can see. null: not one of ours.
const linkedChannels = new Map<string, Channel | null | Promise<void>>();

function useLinkedChannel(id: string | null): Channel | null {
  const [, redraw] = useState(0);
  const got = id ? linkedChannels.get(id) : undefined;
  useEffect(() => {
    if (!id) return;
    let live = true;
    let p = linkedChannels.get(id);
    if (p === undefined) {
      p = channelsApi.get(id).then((c) => void linkedChannels.set(id, c), () => void linkedChannels.set(id, null));
      linkedChannels.set(id, p);
    }
    if (p instanceof Promise) void p.then(() => live && redraw((n) => n + 1));
    return () => { live = false; };
  }, [id]);
  return got && !(got instanceof Promise) ? got : null;
}

/**
 * A link to a channel or a message in this app, drawn as a mention: the
 * channel's name (a direct message's person or group), then for a message a
 * small caret and a speech bubble. Still an anchor, so AppLinks opens it in
 * the app, jumps to the message, and a middle click opens a new tab.
 */
function JumpLink(props: { url: string; channelId: string; messageId: string | null; ctx: RenderCtx; fallback: ReactNode }): JSX.Element {
  const meId = useAuth((s) => s.me?.id);
  const known = props.ctx.channels[props.channelId] as Channel | undefined;
  const fetched = useLinkedChannel(known ? null : props.channelId);
  const channel = known ?? fetched;
  if (!channel) return <>{props.fallback}</>;
  const dm = channel.type === "dm" || channel.type === "group_dm";
  const name = dm ? channelDisplayName(channel, props.ctx.users as Parameters<typeof channelDisplayName>[1], meId) : channel.name;
  const label = props.messageId ? t("render.jumpToMessage", { name }) : t("render.jumpToChannel", { name });
  const body = (
    <>
      {dm ? (channel.type === "dm" ? "@" : "") : <MentionGlyph channel={channel} />}
      <span className="md-jump-name">{name}</span>
      {props.messageId ? (
        <>
          <ChevronRight className="md-jump-caret" size={12} strokeWidth={3} aria-hidden />
          <MessageCircle className="md-jump-bubble" size={14} fill="currentColor" aria-hidden />
        </>
      ) : null}
    </>
  );
  if (props.ctx.inert) return <span className="md-mention md-jump">{body}</span>;
  return (
    <a href={props.url} className="md-mention md-jump" aria-label={label} title={props.url}>
      {body}
    </a>
  );
}

/** Mentions that only say something: nothing happens when they are clicked, so they do not look as if it would. */
const STATIC: CSSProperties = { cursor: "default" };

/** A role or channel we know nothing about (deleted, or not ours to see) reads as a quiet placeholder, not as a live mention. */
const UNKNOWN_MENTION: CSSProperties = { ...STATIC, color: "var(--text-muted)", backgroundColor: "color-mix(in srgb, var(--text-muted) 14%, transparent)" };

function RoleMention(props: { id: string; ctx: RenderCtx }): JSX.Element {
  const role = props.ctx.roles?.[props.id];
  if (!role) {
    return (
      <span className="md-mention" style={UNKNOWN_MENTION} data-role-mention="unknown">
        {"@" + t("render.unknownRole")}
      </span>
    );
  }
  // Colour 0 is a role without a colour, which looks like any other mention.
  // A coloured one is mixed a little toward the theme's strongest text, which
  // is light on a dark theme and dark on a light one, so a pale yellow role
  // still reads on white and a deep blue one on black.
  const style: CSSProperties =
    role.color !== 0
      ? { ...STATIC, color: `color-mix(in srgb, ${hexColor(role.color)} 72%, var(--text-strong))`, backgroundColor: rgbaColor(role.color, 0.16) }
      : STATIC;
  return (
    <span className="md-mention" style={style} data-role-mention={props.id}>
      {"@" + (role.name || t("render.unknownRole"))}
    </span>
  );
}

function clampColor(color: number): number {
  return Number.isFinite(color) ? Math.max(0, Math.min(0xffffff, Math.trunc(color))) : 0;
}

function hexColor(color: number): string {
  return "#" + clampColor(color).toString(16).padStart(6, "0");
}

function rgbaColor(color: number, alpha: number): string {
  const value = clampColor(color);
  const r = (value >> 16) & 0xff;
  const g = (value >> 8) & 0xff;
  const b = value & 0xff;
  return "rgba(" + String(r) + ", " + String(g) + ", " + String(b) + ", " + String(alpha) + ")";
}

// ---------------------------------------------------------------------------
// timestamps
// ---------------------------------------------------------------------------

/** How soon a relative time ("in 5 minutes") can read differently: often while it is close, rarely once it is far. */
export function relativeTick(deltaMs: number): number {
  const away = Math.abs(deltaMs);
  return away < 60_000 ? 10_000 : away < 3_600_000 ? 30_000 : away < 86_400_000 ? 60_000 : 3_600_000;
}

/**
 * A `<t:…>` time. It is set apart from the words around it (dir="auto"
 * isolates it), so a date written in Arabic does not decide which way an
 * English sentence around it reads. The relative style ("in 2 hours") keeps
 * counting; rendered once, it went on saying "in 2 hours" all day.
 */
function Timestamp(props: { unix: number; style: StampStyle }): JSX.Element {
  const date = new Date(props.unix * 1000);
  const valid = !Number.isNaN(date.getTime());
  const relative = valid && props.style === "R";
  const [, tick] = useState(0);
  useEffect(() => {
    if (!relative) return;
    let timer: ReturnType<typeof setTimeout>;
    const next = () => {
      timer = setTimeout(() => {
        tick((n) => n + 1);
        next();
      }, relativeTick(props.unix * 1000 - Date.now()));
    };
    next();
    return () => clearTimeout(timer);
  }, [relative, props.unix]);
  if (!valid) {
    return <time className="md-timestamp">{String(props.unix)}</time>;
  }
  return (
    <time className="md-timestamp" dir="auto" dateTime={date.toISOString()} title={formatStamp(date, "F")}>
      {formatStamp(date, props.style)}
    </time>
  );
}

// ---------------------------------------------------------------------------
// code blocks + the highlight.js html -> react bridge
// ---------------------------------------------------------------------------

export type HlNode = { type: "text"; text: string } | { type: "span"; className: string; children: HlNode[] };

const RE_SPAN_OPEN = /<span class="([^"]*)">/y;

/**
 * Minimal parser for the HTML highlight.js emits. It understands exactly
 * `<span class="...">`, `</span>` and text with the five escapes hljs writes;
 * anything else is kept as literal text, so no markup can ever be injected.
 */
export function parseHighlightHtml(html: string): HlNode[] {
  const root: HlNode[] = [];
  const stack: HlNode[][] = [root];
  let buf = "";
  const top = (): HlNode[] => stack[stack.length - 1] ?? root;
  const flush = (): void => {
    if (buf === "") return;
    top().push({ type: "text", text: unescapeHtml(buf) });
    buf = "";
  };

  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt < 0) {
      buf += html.slice(i);
      break;
    }
    buf += html.slice(i, lt);
    RE_SPAN_OPEN.lastIndex = lt;
    const open = RE_SPAN_OPEN.exec(html);
    if (open) {
      flush();
      const node: HlNode = { type: "span", className: open[1] ?? "", children: [] };
      top().push(node);
      stack.push(node.children);
      i = RE_SPAN_OPEN.lastIndex;
      continue;
    }
    if (html.startsWith("</span>", lt) && stack.length > 1) {
      flush();
      stack.pop();
      i = lt + 7;
      continue;
    }
    buf += "<";
    i = lt + 1;
  }
  flush();
  return root;
}

function unescapeHtml(text: string): string {
  if (!text.includes("&")) return text;
  return text.replace(/&(?:lt|gt|amp|quot|#x27|#39);/g, (entity) => {
    switch (entity) {
      case "&lt;":
        return "<";
      case "&gt;":
        return ">";
      case "&amp;":
        return "&";
      case "&quot;":
        return '"';
      default:
        return "'";
    }
  });
}

function renderHl(nodes: HlNode[]): ReactNode {
  return nodes.map((node, i) =>
    node.type === "text" ? (
      <Fragment key={i}>{node.text}</Fragment>
    ) : (
      <span key={i} className={node.className}>
        {renderHl(node.children)}
      </span>
    ),
  );
}

/**
 * Code in a language, coloured by highlight.js once it has loaded (it loads on
 * first use); plain until then, and for a language it does not know.
 */
export function HighlightedCode({ lang, text }: { lang: string | null; text: string }): JSX.Element {
  const highlighted = useHighlighted(lang, text);
  return <>{highlighted ? renderHl(highlighted) : text}</>;
}

function useHighlighted(lang: string | null, text: string): HlNode[] | null {
  const [highlighted, setHighlighted] = useState<HlNode[] | null>(null);

  useEffect(() => {
    setHighlighted(null);
    if (lang === null) return;
    let alive = true;
    void import("highlight.js/lib/common")
      .then((mod) => {
        if (!alive) return;
        const hljs = mod.default;
        if (!hljs.getLanguage(lang)) return;
        setHighlighted(parseHighlightHtml(hljs.highlight(text, { language: lang }).value));
      })
      .catch(() => {
        /* highlighting is a nicety; plain text is a fine fallback */
      });
    return () => {
      alive = false;
    };
  }, [lang, text]);
  return highlighted;
}

function CodeBlock(props: { lang: string | null; text: string }): JSX.Element {
  const { lang, text } = props;
  return (
    <div className="md-codeblock-wrap">
      <pre className="md-codeblock">
        <code className={lang === null ? "hljs" : "hljs language-" + lang}>
          <HighlightedCode lang={lang} text={text} />
        </code>
      </pre>
      {/* just the code, without the fences and whatever else the message said */}
      <button type="button" className="md-code-copy" aria-label={t("render.copyCode")} title={t("render.copyCode")} onClick={() => void copyText(text, t("clipboard.codeCopied"))}>
        <Copy size={15} aria-hidden="true" />
      </button>
    </div>
  );
}

/** The site a link goes to, as people know it: no scheme, no "www.". */
function linkHost(url: string): string | null {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, "") || null;
  } catch {
    return null;
  }
}
