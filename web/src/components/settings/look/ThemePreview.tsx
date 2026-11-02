import { Bell, Hash, Megaphone, Palette, Pin, Plus, Search, Smile, Sparkles, Users, Volume2 } from "lucide-react";
import { useRef, useState, type PointerEvent } from "react";
import { t } from "@/i18n";
import { isToken, tokenStyle, TOKEN_GROUPS, ALPHA_TOKENS, type TokenMap, type TokenName } from "@/lib/look/tokens";
import { GROUP_LABEL, TOKEN_LABEL } from "./labels";
import b from "./Builder.module.css";

/** The colour a part of the preview is drawn in, read from the innermost part under the pointer. */
function tokenAt(target: EventTarget | null, root: HTMLElement | null): { el: HTMLElement; token: TokenName } | null {
  const el = target instanceof Element ? (target.closest("[data-token]") as HTMLElement | null) : null;
  if (!el || !root?.contains(el)) return null;
  const token = el.dataset["token"] ?? "";
  return isToken(token) ? { el, token } : null;
}

/**
 * A small Nexa in the theme being made: the server rail with a tooltip, the
 * channel list with a selected and a hovered row, a conversation with a
 * mention, code, a link, reactions, a spoiler and a link preview, the message
 * box, a menu, buttons and the status colours. Every part names the colour it
 * is drawn in, so pressing it opens that colour in the editor; the pointer
 * shows which one it is first. It is a picture for the eye: keyboards and
 * screen readers reach the same colours through the palette below it.
 */
export function ThemePreview({ tokens, onPick }: { tokens: TokenMap; onPick: (token: TokenName) => void }) {
  const root = useRef<HTMLDivElement>(null);
  const lit = useRef<HTMLElement | null>(null);
  const [hot, setHot] = useState<TokenName | null>(null);
  const light = (el: HTMLElement | null, token: TokenName | null) => {
    if (lit.current && lit.current !== el) delete lit.current.dataset["hot"];
    if (el) el.dataset["hot"] = "";
    lit.current = el;
    setHot(token);
  };
  const over = (e: PointerEvent) => {
    if (e.pointerType !== "mouse") return;
    const at = tokenAt(e.target, root.current);
    light(at?.el ?? null, at?.token ?? null);
  };
  const T = (k: string) => t(`settings.themeBuilder.${k}`);
  return (
    <figure className={b.previewFig}>
      <div
        ref={root}
        className={b.app}
        style={tokenStyle(tokens)}
        aria-hidden="true"
        onPointerOver={over}
        onPointerLeave={() => light(null, null)}
        onClick={(e) => {
          const at = tokenAt(e.target, root.current);
          if (at) onPick(at.token);
        }}
        data-testid="builder-preview"
      >
        <div className={b.rail} data-token="bg-base">
          <span className={b.railHome} data-token="brand">
            <Smile size={14} data-token="on-brand" />
          </span>
          <span className={b.railSep} data-token="border" />
          <span className={b.railItem} data-on="" data-token="bg-surface">
            <i className={b.railPill} data-token="text-strong" />
            <b data-token="text">W</b>
          </span>
          <span className={b.railItem} data-token="bg-surface">
            <b data-token="text">K</b>
            <i className={b.railBadge} data-token="danger">
              <span data-token="on-danger">3</span>
            </i>
          </span>
          <span className={b.railItem} data-token="bg-surface">
            <Plus size={13} data-token="positive" />
          </span>
          <span className={b.railTip} data-token="tooltip-bg">
            <span data-token="tooltip-text">{T("sampleServer")}</span>
          </span>
        </div>
        <div className={b.side} data-token="bg-base">
          <div className={b.sideHead} data-token="text-strong">
            {T("sampleServer")}
          </div>
          <div className={b.sideSearch} data-token="bg-input">
            <Search size={10} data-token="icon" />
            <span data-token="text-placeholder">{T("sampleSearch")}</span>
          </div>
          <div className={b.chan} data-token="text-channel">
            <Megaphone size={11} data-token="icon" /> {T("sampleAnnouncements")}
          </div>
          <div className={b.chan} data-sel="" data-token="bg-selected">
            <Hash size={11} data-token="icon" />
            <span data-token="text-strong">{T("sampleChannel")}</span>
          </div>
          <div className={b.chan} data-hov="" data-token="bg-hover">
            <Hash size={11} data-token="icon" />
            <span data-token="text">{T("sampleRandom")}</span>
            <i className={b.mentionCount} data-token="danger">
              <span data-token="on-danger">2</span>
            </i>
          </div>
          <div className={b.chan} data-token="text-channel">
            <Volume2 size={11} data-token="icon" /> {T("sampleVoice")}
          </div>
          <div className={b.me} data-token="bg-surface">
            <span className={b.av} data-token="brand">
              <span data-token="on-brand">R</span>
              <i className={b.dot} data-token="status-online" />
            </span>
            <span className={b.meText}>
              <span data-token="text-strong">Robin</span>
              <span data-token="text-muted">{t("shell.user.online")}</span>
            </span>
          </div>
        </div>
        <div className={b.chat} data-token="bg-raised">
          <div className={b.chatHead} data-token="border-subtle">
            <Hash size={12} data-token="icon" />
            <span data-token="text-strong">{T("sampleChannel")}</span>
            <span className={b.topic} data-token="text-muted">
              {T("sampleTopic")}
            </span>
            <span className={b.headIcons}>
              <Bell size={11} data-token="icon" />
              <Pin size={11} data-token="icon" />
              <Users size={11} data-token="icon" />
            </span>
          </div>
          <div className={b.msgs}>
            <div className={b.msg} data-token="bg-message-hover">
              <span className={b.av} data-token="brand">
                <span data-token="on-brand">R</span>
              </span>
              <div className={b.msgBody}>
                <span className={b.who} data-token="text-strong">
                  Robin <small data-token="text-muted">{t("settings.appearance.todayAt420Pm")}</small>
                </span>
                <span data-token="text">
                  {T("sampleHello")}{" "}
                  <span className={b.mention} data-token="mention-bg">
                    <span data-token="mention-fg">@Sam</span>
                  </span>{" "}
                  <code className={b.code} data-token="code-bg">
                    <span data-token="text">theme.css</span>
                  </code>{" "}
                  <span className={b.link} data-token="link">
                    nexa.fish
                  </span>
                </span>
                <span className={b.reacts}>
                  <span className={b.react} data-mine="" data-token="mention-bg">
                    <Palette size={10} data-token="text-brand" />
                    <span data-token="text-brand">4</span>
                  </span>
                  <span className={b.react} data-token="bg-surface">
                    <Sparkles size={10} data-token="text-subtle" />
                    <span data-token="text-subtle">2</span>
                  </span>
                </span>
              </div>
            </div>
            <div className={b.msg} data-mention="" data-token="brand">
              <i className={b.bar} data-token="focus-ring" />
              <span className={b.av} data-token="positive">
                <span data-token="on-positive">S</span>
              </span>
              <div className={b.msgBody}>
                <span className={b.who} data-token="text-strong">
                  Sam <small data-token="text-muted">{t("settings.appearance.todayAt420Pm")}</small>
                </span>
                <span data-token="text">{T("sampleReply")}</span>
                <pre className={b.block} dir="ltr" data-token="code-bg">
                  <span data-token="code-comment">{"// "}{T("sampleComment")}</span>
                  {"\n"}
                  <span data-token="code-keyword">const</span> <span data-token="code-variable">theme</span> = <span data-token="code-title">make</span>(<span data-token="code-string">"dusk"</span>, <span data-token="code-number">4.5</span>);{"\n"}
                  <span data-token="code-keyword">type</span> <span data-token="code-type">Look</span> = <span data-token="code-type">Theme</span>;
                </pre>
              </div>
            </div>
            <div className={b.msg}>
              <span className={b.av} data-token="warning">
                <span data-token="on-warning">K</span>
              </span>
              <div className={b.msgBody}>
                <span className={b.who} data-token="text-strong">
                  Kit <small data-token="text-muted">{t("settings.appearance.todayAt420Pm")}</small>
                </span>
                <span data-token="text">
                  {T("sampleSpoilerLead")}{" "}
                  <span className={b.spoiler} data-token="spoiler">
                    <span>{T("sampleSpoiler")}</span>
                  </span>{" "}
                  <span className={b.spoilerOpen} data-token="spoiler-revealed">
                    <span data-token="text">{T("sampleSpoiler")}</span>
                  </span>
                </span>
                <span className={b.embed} data-token="embed-bg">
                  <span data-token="text-brand">nexa.fish</span>
                  <b data-token="link">{T("sampleEmbedTitle")}</b>
                  <span data-token="text-subtle">{T("sampleEmbedBody")}</span>
                </span>
              </div>
            </div>
          </div>
          <i className={b.thumb} data-token="scroll-thumb" />
          <div className={b.composer} data-token="bg-composer">
            <Plus size={12} data-token="icon" />
            <span data-token="text-placeholder">{T("samplePlaceholder")}</span>
          </div>
        </div>
      </div>
      <div className={b.appFoot} style={tokenStyle(tokens)} aria-hidden="true" onPointerOver={over} onPointerLeave={() => light(null, null)} onClick={(e) => { const at = tokenAt(e.target, e.currentTarget); if (at) onPick(at.token); }}>
        <div className={b.menu} data-token="bg-floating">
          <span className={b.menuItem} data-token="text">
            {t("sidebar.dmMenu.markAsRead")}
          </span>
          <span className={b.menuItem} data-hov="" data-token="bg-hover">
            <span data-token="text">{t("textMenu.copyLink")}</span>
          </span>
          <span className={b.menuSep} data-token="border-subtle" />
          <span className={b.menuItem} data-token="text-danger">
            {t("chat.messageMenu.deleteMessage")}
          </span>
        </div>
        <div className={b.footRight}>
          <div className={b.btns}>
            <span className={b.btn} data-kind="brand" data-token="brand">
              <span data-token="on-brand">{T("sampleSave")}</span>
            </span>
            <span className={b.btn} data-kind="plain" data-token="border">
              <span data-token="text">{T("sampleCancel")}</span>
            </span>
            <span className={b.btn} data-kind="positive" data-token="positive">
              <span data-token="on-positive">{T("sampleAccept")}</span>
            </span>
            <span className={b.btn} data-kind="danger" data-token="danger">
              <span data-token="on-danger">{T("sampleDelete")}</span>
            </span>
          </div>
          <div className={b.states}>
            <span data-token="status-online">
              <i /> {t("shell.user.online")}
            </span>
            <span data-token="status-idle">
              <i /> {t("shell.user.idle")}
            </span>
            <span data-token="status-dnd">
              <i /> {T("sampleBusy")}
            </span>
            <span data-token="status-offline">
              <i /> {t("chat.memberList.offline")}
            </span>
            <span className={b.warnText} data-token="text-warning">
              {T("sampleWarning")}
            </span>
            <span className={b.errText} data-token="text-danger">
              {T("sampleError")}
            </span>
          </div>
        </div>
      </div>
      <figcaption className={b.caption} aria-live="off">
        {hot ? (
          <>
            <span className={b.captionName}>{t(TOKEN_LABEL[hot])}</span> <bdi dir="ltr" className={b.captionVar}>--{hot}</bdi>
          </>
        ) : (
          T("previewPoint")
        )}
      </figcaption>
    </figure>
  );
}

/**
 * Every colour of the theme as a small swatch, in the editor's groups. It is
 * how a keyboard or a screen reader goes straight to one colour, and an
 * overview of the whole palette at a glance.
 */
export function PaletteStrip({ tokens, changed, issues, onPick }: { tokens: TokenMap; changed: ReadonlySet<string>; issues: ReadonlySet<string>; onPick: (token: TokenName) => void }) {
  return (
    <div className={b.palette} role="group" aria-label={t("settings.themeBuilder.palette")} data-testid="builder-palette">
      {TOKEN_GROUPS.map((g) => (
        <div key={g.id} className={b.paletteRow} role="group" aria-label={t(GROUP_LABEL[g.id])}>
          {(g.tokens as readonly TokenName[]).map((tk) => (
            <button
              key={tk}
              type="button"
              className={b.swatch}
              data-alpha={ALPHA_TOKENS.has(tk) || undefined}
              data-changed={changed.has(tk) || undefined}
              data-issue={issues.has(tk) || undefined}
              onClick={() => onPick(tk)}
              title={`${t(TOKEN_LABEL[tk])} (--${tk})`}
              aria-label={t("settings.themeBuilder.editColour", { name: t(TOKEN_LABEL[tk]) })}
              data-swatch={tk}
            >
              <span style={{ background: tokens[tk] }} />
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}
