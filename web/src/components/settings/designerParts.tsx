import { ArrowDown, ArrowUp, ChevronDown, Pencil, Pin, Plus, Trash2 } from "lucide-react";
import { ColourInput } from "@/components/common/ColourInput";
import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { widgetName, type WidgetDesign } from "@/components/profile/Widgets";
import type { Widget } from "@/lib/widgets";
import { StyledTyping } from "@/components/chat/TypingLine";
import { ServerTagChip } from "@/components/servertag/ServerTagChip";
import { t } from "@/i18n";
import { RoleIcon } from "@/components/common/RoleIcon";
import { nameClass, nameStyle, type RoleLook } from "@/lib/roleLook";
import { formatTimestamp } from "@/lib/time";
import type { TypingStyle } from "@/lib/typingStyle";
import { useAuth } from "@/stores/auth";
import { AboutMeEditor } from "./AboutMeEditor";
import { CustomColorIcon } from "./CustomColorIcon";
import st from "./Settings.module.css";
import d from "./Designer.module.css";

/*
 * The pieces both profile designers are made of (Settings, Profiles): the
 * account's own profile, and the one for each server.
 */

export const PRESETS = ["#8000ff", "#5865f2", "#3d9e60", "#fdb833", "#da3e44", "#eb459f", "#1abc9c", "#e67e22", "#607d8b"];

/** The longest About Me the server keeps (internal/api/user_handlers.go maxAboutMe). */
export const ABOUT_MAX = 1000;

/** A colour column may be missing entirely on an older server, so treat anything but a number as unset. */
export function hexOf(v: number | null | undefined): string | null {
  return typeof v === "number" ? `#${v.toString(16).padStart(6, "0")}` : null;
}

/** About Me in the form, with what is left of its length. */
export function AboutField({ value, onChange, outLength, placeholder }: { value: string; onChange: (v: string) => void; outLength: number; placeholder?: string | undefined }) {
  return (
    <div className={d.aboutEdit} data-testid="designer-about">
      <AboutMeEditor value={value} onChange={onChange} max={ABOUT_MAX} placeholder={placeholder ?? t("settings.profile.tellPeopleAboutYourself")} label={t("settings.profile.aboutMe")} />
      <span className={d.count}>{t("composer.limit.left", { count: ABOUT_MAX - outLength })}</span>
    </div>
  );
}

/** A hex field for people who know the colour they want. */
export function HexInput({ value, fallback, label, testId, disabled, onChange }: { value: string | null; fallback: string; label: string; testId: string; disabled?: boolean; onChange: (v: string) => void }) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? value ?? "";
  const commit = (raw: string) => {
    const hex = raw.trim().replace(/^#?/, "#").toLowerCase();
    const short = /^#([0-9a-f]{3})$/.exec(hex);
    const full = /^#([0-9a-f]{6})$/.exec(hex);
    if (short) {
      const [r, g, b] = short[1]!.split("");
      onChange(`#${r}${r}${g}${g}${b}${b}`);
    } else if (full) {
      onChange(hex);
    }
    setDraft(null);
  };
  return (
    <input
      className={st.hex}
      value={shown}
      disabled={disabled}
      spellCheck={false}
      maxLength={7}
      placeholder={fallback}
      aria-label={label}
      data-testid={testId}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={(e) => commit(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          commit(e.currentTarget.value);
          e.currentTarget.blur();
        }
      }}
    />
  );
}

/** The profile's two colours: the top one alone colours the banner; both make the whole card a gradient. */
export function ColoursPicker({ accent, accent2, setAccent, setAccent2 }: { accent: string | null; accent2: string | null; setAccent: (v: string | null) => void; setAccent2: (v: string | null) => void }) {
  return (
    <div className={d.colours}>
      <p className={d.popHint}>{t("settings.profile.topColourOnItsOwnIt")}</p>
      <div className={st.swatches}>
        <button className={st.swatch} data-on={accent === null} aria-pressed={accent === null} style={{ background: "var(--bg-active)" }} title={t("server.roles.default")} aria-label={t("server.roles.default")} onClick={() => { setAccent(null); setAccent2(null); }} />
        {PRESETS.map((c, i) => <button key={c} className={st.swatch} data-on={accent === c} aria-pressed={accent === c} style={{ background: c }} title={c} aria-label={t("server.roles.colourIndexOfCount", { index: i + 1, count: PRESETS.length })} onClick={() => setAccent(c)} />)}
        <label className={st.swatch} data-on={!!accent && !PRESETS.includes(accent)} title={t("settings.appearance.custom")}>
          <CustomColorIcon color={accent && !PRESETS.includes(accent) ? accent : null} />
          <ColourInput value={accent ?? "#8000ff"} label={t("settings.appearance.custom")} onChange={setAccent} />
        </label>
        <HexInput value={accent} fallback="#8000ff" label={t("settings.profile.primaryColourHex")} testId="accent-hex" onChange={setAccent} />
      </div>
      <p className={d.popHint}>{t("settings.profile.bottomColourPickOneTooAnd")}</p>
      <div className={st.swatches}>
        <button className={st.swatch} data-on={accent2 === null} aria-pressed={accent2 === null} style={{ background: "var(--bg-active)" }} title={t("settings.profile.none")} aria-label={t("settings.profile.none")} onClick={() => setAccent2(null)} disabled={!accent} />
        {PRESETS.map((c, i) => <button key={c} className={st.swatch} data-on={accent2 === c} aria-pressed={accent2 === c} style={{ background: c }} title={c} aria-label={t("server.roles.colourIndexOfCount", { index: i + 1, count: PRESETS.length })} onClick={() => setAccent2(c)} disabled={!accent} />)}
        <label className={st.swatch} data-on={!!accent2 && !PRESETS.includes(accent2)} style={{ opacity: accent ? 1 : 0.4 }} title={t("settings.appearance.custom")}>
          <CustomColorIcon color={accent2 && !PRESETS.includes(accent2) ? accent2 : null} />
          <ColourInput value={accent2 ?? "#eb459e"} disabled={!accent} label={t("settings.appearance.custom")} onChange={setAccent2} />
        </label>
        <HexInput value={accent2} fallback="#eb459e" label={t("settings.profile.secondColourHex")} testId="accent2-hex" disabled={!accent} onChange={setAccent2} />
      </div>
    </div>
  );
}

/**
 * A conversation as others see it: their message, then yours with your name
 * and tag, then you typing. In a server it is one of its channels, with your
 * name in your roles' colours and icon.
 */
export function ChatPreview({ name, avatarId, userId, typing, channel = "general", look }: { name: string; avatarId: string | null; userId: string; typing: TypingStyle | null; channel?: string; look?: RoleLook | undefined }) {
  const me = useAuth((a) => a.me);
  // two messages from a few minutes ago, in the reader's own way of writing times
  const when = useMemo(() => Date.now() - 4 * 60 * 1000, []);
  const friend = t("designer.chat.friend");
  return (
    <div className={d.chat} data-testid="designer-chat">
      <div className={d.chatHead} aria-hidden="true" dir="auto">#{channel}</div>
      <div className={d.chatBody}>
        <div className={d.msg}>
          <Avatar id="designer-friend" avatarId={null} name={friend} size={40} />
          <div className={d.msgMain}>
            <div className={d.msgHead}>
              <span className={d.msgName} dir="auto">{friend}</span>
              <span className={d.msgTime}>{formatTimestamp(when - 60 * 1000)}</span>
            </div>
            <p className={d.msgText}>{t("designer.chat.theirs")}</p>
          </div>
        </div>
        <div className={d.msg}>
          <Avatar id={userId} avatarId={avatarId} name={name} size={40} />
          <div className={d.msgMain}>
            <div className={d.msgHead}>
              <span className={[d.msgName, nameClass(look)].join(" ")} style={nameStyle(look)} dir="auto">{name}</span>
              {look?.icon ? <RoleIcon icon={look.icon} size={16} /> : null}
              {me?.server_tag ? <ServerTagChip tag={me.server_tag} open={false} /> : null}
              <span className={d.msgTime}>{formatTimestamp(when)}</span>
            </div>
            <p className={d.msgText}>{t("designer.chat.yours")}</p>
          </div>
        </div>
      </div>
      <div className={d.chatTyping}>
        <StyledTyping style={typing} name={name} userId={userId} />
      </div>
      <div className={d.chatComposer} aria-hidden="true">{t("typing.editor.composer")}</div>
    </div>
  );
}


/** Narrower than this, the preview goes above the form instead of beside it. */
const PREVIEW_BESIDE = 720;
/** Where this browser remembers the preview folded away on a narrow screen. Both designers share it. */
const FOLDED_KEY = "nexa.profilePreviewFolded";
function readFolded(): boolean {
  try {
    return localStorage.getItem(FOLDED_KEY) === "1";
  } catch {
    return false;
  }
}

/** Whether the preview stands beside the form (measured on the designer itself). */
export function usePreviewLayout() {
  const layoutRef = useRef<HTMLDivElement>(null);
  const [beside, setBeside] = useState(true);
  useLayoutEffect(() => {
    const el = layoutRef.current;
    if (!el) return;
    const measure = () => setBeside(el.clientWidth >= PREVIEW_BESIDE);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return { layoutRef, beside };
}

/**
 * Scrolls the form to a part's field, puts the caret in it and lights it for
 * a moment: what pressing a part of the preview does.
 */
export function jumpToField(root: HTMLElement | null, field: string) {
  const el = root?.querySelector<HTMLElement>(`[data-field="${field}"]`);
  if (!el) return;
  const reduce = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
  el.scrollIntoView({ block: "center", behavior: reduce ? "auto" : "smooth" });
  const target = el.querySelector<HTMLElement>("input:not([type=file]):not([type=hidden]), textarea, [data-field-focus], button");
  target?.focus({ preventScroll: true });
  el.removeAttribute("data-flash");
  void el.offsetWidth;
  el.setAttribute("data-flash", "");
  window.setTimeout(() => el.removeAttribute("data-flash"), 1600);
}

/** A group of fields under a heading. */
export function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={d.section}>
      <h3 className={d.sectionTitle}>{title}</h3>
      <div className={d.fields}>{children}</div>
    </section>
  );
}

/** One part of the profile in the form: its name, what changes it, and a line of help when it needs one. */
export function FormField({ field, setting, label, hint, aside, children }: { field: string; setting?: string | undefined; label: ReactNode; hint?: ReactNode; aside?: ReactNode; children: ReactNode }) {
  return (
    <div className={d.field} data-field={field} data-setting={setting} data-testid={`designer-field-${field}`}>
      <div className={d.fieldHead}>
        <span className={d.fieldLabel}>{label}</span>
        {aside}
      </div>
      {children}
      {hint ? <p className={d.fieldHint}>{hint}</p> : null}
    </div>
  );
}

/** A part whose tools take room (name style, typing style, server tag): its current value in a line, opened in place. */
export function FormExpander({ field, setting, label, value, open, onToggle, children }: { field: string; setting?: string | undefined; label: string; value: ReactNode; open: boolean; onToggle: (open: boolean) => void; children: ReactNode }) {
  return (
    <div className={d.field} data-field={field} data-setting={setting} data-open={open || undefined} data-testid={`designer-field-${field}`}>
      <button type="button" className={d.expander} aria-expanded={open} onClick={() => onToggle(!open)} data-field-focus="" data-testid={`designer-toggle-${field}`}>
        <span className={d.fieldLabel}>{label}</span>
        <span className={d.expanderValue} dir="auto">{value}</span>
        <ChevronDown size={18} className={d.expanderChevron} aria-hidden="true" />
      </button>
      {open ? <div className={d.expanderBody}>{children}</div> : null}
    </div>
  );
}

/** The three places the preview shows the profile, as a sunken strip of choices. */
export function ViewSwitch<V extends string>({ views, value, onChange }: { views: { id: V; label: string }[]; value: V; onChange: (v: V) => void }) {
  return (
    <div className={d.views} role="tablist" aria-label={t("designer.view.label")}>
      {views.map((v) => (
        <button key={v.id} type="button" role="tab" aria-selected={value === v.id} className={d.viewBtn} data-selected={value === v.id} onClick={() => onChange(v.id)} data-testid={`designer-view-${v.id}`}>
          {v.label}
        </button>
      ))}
    </div>
  );
}

/**
 * The designer's two halves: every part of the profile as one form, and the
 * profile as others see it beside it. The preview never scrolls by itself:
 * taller than the window, it scrolls with the page until its foot shows, then
 * stays (so the wheel always moves the page). Without room it goes above the
 * form, foldable to one line.
 */
export function DesignerLayout({ layout, form, preview, summary }: { layout: ReturnType<typeof usePreviewLayout>; form: ReactNode; preview: ReactNode; summary: ReactNode }) {
  const { layoutRef, beside } = layout;
  const previewRef = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(0);
  const [folded, setFoldedState] = useState(readFolded);
  const setFolded = (v: boolean) => {
    setFoldedState(v);
    try {
      localStorage.setItem(FOLDED_KEY, v ? "1" : "0");
    } catch {
      // only this browser's convenience
    }
  };
  // on a narrow screen the bar stays at the top: with the preview scrolled away it brings it back, otherwise it folds it
  const [seen, setSeen] = useState(true);
  useLayoutEffect(() => {
    const el = previewRef.current;
    if (beside || folded || !el || typeof IntersectionObserver === "undefined") {
      setSeen(true);
      return;
    }
    const io = new IntersectionObserver(([e]) => setSeen(!!e && e.intersectionRatio > 0.25), { threshold: [0, 0.25, 0.5] });
    io.observe(el);
    return () => io.disconnect();
  }, [beside, folded]);
  const pressBar = () => {
    const el = previewRef.current;
    if (!folded && !seen && el) {
      const reduce = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
      layoutRef.current?.scrollIntoView({ block: "start", behavior: reduce ? "auto" : "smooth" });
      return;
    }
    setFolded(!folded);
  };
  // a preview taller than its scroller sticks by its foot instead of its head
  useLayoutEffect(() => {
    const el = previewRef.current;
    if (!el || !beside) return;
    let scroller: HTMLElement | null = el.parentElement;
    while (scroller && !/(auto|scroll)/.test(getComputedStyle(scroller).overflowY)) scroller = scroller.parentElement;
    const measure = () => {
      const room = scroller?.clientHeight ?? window.innerHeight;
      setStick(Math.min(16, room - el.offsetHeight - 24));
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    if (scroller) ro.observe(scroller);
    return () => ro.disconnect();
  }, [beside]);
  return (
    <div ref={layoutRef} className={d.layout} data-beside={beside || undefined}>
      {beside ? null : (
        <div className={d.previewBar}>
          <button type="button" className={d.previewToggle} aria-expanded={!folded && seen} onClick={pressBar} data-away={(!folded && !seen) || undefined} data-testid="designer-preview-toggle">
            <span className={d.previewSummary}>{summary}</span>
            <span className={d.previewToggleText}>{folded || !seen ? t("designer.preview.show") : t("designer.preview.hide")}</span>
            <ChevronDown size={18} className={d.expanderChevron} aria-hidden="true" />
          </button>
        </div>
      )}
      <div ref={previewRef} className={d.preview} hidden={!beside && folded} style={beside ? { top: stick } : undefined} data-testid="designer-preview">
        {preview}
      </div>
      <div className={d.form} data-testid="designer-form">{form}</div>
    </div>
  );
}

/**
 * The widgets in the form, in the order the profile shows them: the first is
 * the one the card shows, and each can be moved, changed or taken off.
 */
export function WidgetList({ widgets, tools, disabled }: { widgets: Widget[]; tools: WidgetDesign; disabled?: boolean }) {
  return (
    <div className={d.widgetList}>
      {widgets.length ? (
        <ol className={d.widgetRows}>
          {widgets.map((w, i) => (
            <li key={w.id} className={d.widgetRow} data-testid={`designer-widget-${w.type}`}>
              <button type="button" className={d.widgetPin} aria-pressed={i === 0} onClick={() => tools.feature(w.id)} title={i === 0 ? t("designer.widget.onCard") : t("designer.widget.showOnCard")} aria-label={i === 0 ? t("designer.widget.onCard") : t("designer.widget.showOnCard")}>
                <Pin size={15} aria-hidden="true" />
              </button>
              <button type="button" className={d.widgetName} onClick={() => tools.edit(w.id)} dir="auto">{widgetName(w)}</button>
              <span className={d.widgetTools}>
                <button type="button" className={d.iconBtn} onClick={() => tools.move(w.id, -1)} disabled={i === 0} aria-label={t("reorder.moveUp")} title={t("reorder.moveUp")}><ArrowUp size={16} aria-hidden="true" /></button>
                <button type="button" className={d.iconBtn} onClick={() => tools.move(w.id, 1)} disabled={i === widgets.length - 1} aria-label={t("reorder.moveDown")} title={t("reorder.moveDown")}><ArrowDown size={16} aria-hidden="true" /></button>
                <button type="button" className={d.iconBtn} onClick={() => tools.edit(w.id)} aria-label={t("designer.widget.editShort")} title={t("designer.widget.editShort")}><Pencil size={15} aria-hidden="true" /></button>
                <button type="button" className={d.iconBtn} data-danger="" onClick={() => tools.remove(w.id)} aria-label={t("widgets.remove")} title={t("widgets.remove")}><Trash2 size={15} aria-hidden="true" /></button>
              </span>
            </li>
          ))}
        </ol>
      ) : null}
      <Button size="sm" variant="secondary" onClick={tools.add} disabled={disabled} data-testid="designer-add-widget">
        <Plus size={15} aria-hidden="true" /> {t("widgets.addOne")}
      </Button>
    </div>
  );
}
