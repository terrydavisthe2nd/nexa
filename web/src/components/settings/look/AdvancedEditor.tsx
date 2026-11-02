import { AlertTriangle, ChevronRight, Sparkles } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { SearchBox } from "@/components/common/SearchBox";
import { Button } from "@/components/common/Button";
import { Field } from "@/components/common/TextInput";
import { Select } from "@/components/common/Select";
import { t } from "@/i18n";
import { fixContrast, type Issue } from "@/lib/look/contrast";
import { withPalette } from "@/lib/look/tools";
import { sourceTokens } from "@/lib/look/customTheme";
import { PRESETS } from "@/lib/look/presets";
import { ALPHA_TOKENS, TOKENS, TOKEN_GROUPS, groupOf, type TokenMap, type TokenName } from "@/lib/look/tokens";
import type { CustomTheme } from "@/lib/look/types";
import { ColourField } from "./ColourField";
import { GROUP_LABEL, TOKEN_LABEL } from "./labels";
import s from "./Look.module.css";
import b from "./Builder.module.css";

/** A colour's name in the reader's language. */
export const tokenLabel = (token: TokenName): string => t(TOKEN_LABEL[token]);

/**
 * Advanced mode: every colour of the theme, grouped the way the app uses
 * them, each with its own picker, hex code and way back. A colour that does
 * not read where it is drawn says by how much, and one press mends them all.
 */
export function AdvancedEditor({ theme, tokens, issues, onChange, lastSimple, focus }: { theme: CustomTheme; tokens: TokenMap; issues: Issue[]; onChange: (t: CustomTheme) => void; lastSimple: CustomTheme["source"] | null; focus: { token: TokenName; n: number } | null }) {
  const base = useMemo(() => sourceTokens(theme.source), [theme.source]);
  const byToken = useMemo(() => new Map(issues.map((i) => [i.token, i])), [issues]);
  // Which groups are open is the person's to decide: the first one and any with
  // a problem open by themselves, and nothing closes on its own when mended.
  const [open, setOpen] = useState<Set<string>>(() => new Set([TOKEN_GROUPS[0].id, ...issues.map((i) => groupOf(i.token))]));
  useEffect(() => {
    const flagged = issues.map((i) => groupOf(i.token));
    if (flagged.some((g) => !open.has(g))) setOpen((cur) => new Set([...cur, ...flagged]));
    // only new problems open a group
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issues]);
  const [query, setQuery] = useState("");
  const [changedOnly, setChangedOnly] = useState(false);
  const list = useRef<HTMLDivElement>(null);
  const q = query.trim().toLowerCase();
  const shows = (tk: TokenName) => (!changedOnly || tk in theme.overrides) && (!q || tk.includes(q.replace(/^-+/, "")) || tokenLabel(tk).toLowerCase().includes(q));
  const filtering = q !== "" || changedOnly;
  const allOpen = TOKEN_GROUPS.every((g) => open.has(g.id));

  // A colour pointed at in the preview or the palette: shown, opened, scrolled to and lit for a moment
  useEffect(() => {
    if (!focus) return;
    setQuery("");
    setChangedOnly(false);
    setOpen((cur) => new Set([...cur, groupOf(focus.token)]));
    const frame = requestAnimationFrame(() => {
      const el = list.current?.querySelector<HTMLElement>(`[data-testid="token-${focus.token}"]`);
      if (!el) return;
      const reduce = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
      el.scrollIntoView?.({ block: "center", behavior: reduce ? "auto" : "smooth" });
      el.classList.remove(b.found!);
      void el.offsetWidth;
      el.classList.add(b.found!);
      el.querySelector<HTMLElement>("button")?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focus]);

  const set = (token: TokenName, value: string) => {
    const overrides = { ...theme.overrides };
    // set back to the source's own colour is not set by hand any more
    if (base[token] === value) delete overrides[token];
    else overrides[token] = value;
    onChange({ ...theme, overrides });
  };
  const reset = (token: TokenName) => {
    const overrides = { ...theme.overrides };
    delete overrides[token];
    onChange({ ...theme, overrides });
  };
  const fixAll = () => {
    onChange(withPalette(theme, fixContrast(tokens), tokens));
  };
  const sourceValue = theme.source.kind === "simple" ? "simple" : theme.source.id;
  // rounded down, so a 4.46 that fails is never shown as a 4.5 that should pass
  const issueText = (i: Issue) => t("settings.themeBuilder.lowContrast", { ratio: Math.floor(i.ratio * 10) / 10, min: i.min });

  return (
    <div>
      <p className={s.panelHint}>{t("settings.themeBuilder.advancedHint")}</p>
      <div className={s.startRow}>
        <Field label={t("settings.themeBuilder.builtOn")} hint={t("settings.themeBuilder.builtOnHint")}>
          <Select
            value={sourceValue}
            onChange={(e) => {
              const v = e.target.value;
              if (v === "simple") {
                if (lastSimple) onChange({ ...theme, source: lastSimple });
              } else onChange({ ...theme, source: { kind: "preset", id: v } });
            }}
            data-testid="builder-built-on"
          >
            {lastSimple ? <option value="simple">{t("settings.themeBuilder.yourColours")}</option> : null}
            {PRESETS.map((p) => (
              <option key={p.id} value={p.id}>
                {t(p.nameKey)}
              </option>
            ))}
          </Select>
        </Field>
      </div>
      {issues.length ? (
        <div className={[s.notice, s.noticeWarn].join(" ")} role="status" data-testid="builder-issues">
          <AlertTriangle size={16} aria-hidden="true" />
          <p className={s.noticeBody}>{t("settings.themeBuilder.issues", { count: issues.length })}</p>
          <Button className={s.noticeAction} variant="secondary" size="sm" onClick={fixAll} data-testid="builder-fix">
            <Sparkles size={14} aria-hidden="true" /> {t("settings.themeBuilder.fixIssues")}
          </Button>
        </div>
      ) : null}
      <div className={b.find}>
        <SearchBox value={query} onChange={setQuery} placeholder={t("settings.themeBuilder.searchColours")} aria-label={t("settings.themeBuilder.searchColours")} data-testid="builder-search" />
        <button type="button" className={b.chip} aria-pressed={changedOnly} onClick={() => setChangedOnly(!changedOnly)} data-testid="builder-changed-only">
          {t("settings.themeBuilder.changedOnly")}
        </button>
        {filtering ? null : (
          <button type="button" className={b.chip} onClick={() => setOpen(allOpen ? new Set() : new Set(TOKEN_GROUPS.map((g) => g.id)))} data-testid="builder-expand-all">
            {allOpen ? t("settings.themeBuilder.collapseAll") : t("settings.themeBuilder.expandAll")}
          </button>
        )}
      </div>
      {filtering && !TOKENS.some(shows) ? <p className={b.empty}>{changedOnly && !q ? t("settings.themeBuilder.noneChanged") : t("settings.themeBuilder.noMatches", { query: query.trim() })}</p> : null}
      <div className={s.groups} ref={list}>
        {TOKEN_GROUPS.map((g) => {
          const all = g.tokens as readonly TokenName[];
          const list = filtering ? all.filter(shows) : all;
          if (!list.length) return null;
          const changed = all.filter((tk) => tk in theme.overrides).length;
          const bad = all.filter((tk) => byToken.has(tk)).length;
          return (
            <details
              key={g.id}
              className={s.tokenGroup}
              open={filtering || open.has(g.id)}
              onToggle={(e) => {
                const isOpen = (e.currentTarget as HTMLDetailsElement).open;
                if (!filtering && isOpen !== open.has(g.id)) setOpen((cur) => { const next = new Set(cur); if (isOpen) next.add(g.id); else next.delete(g.id); return next; });
              }}
              data-group={g.id}
            >
              <summary>
                <ChevronRight size={16} aria-hidden="true" />
                {t(GROUP_LABEL[g.id])}
                <span className={s.groupCount} data-warn={bad ? "" : undefined}>
                  {bad ? t("settings.themeBuilder.groupIssues", { count: bad }) : changed ? t("settings.themeBuilder.groupChanged", { count: changed }) : null}
                </span>
              </summary>
              <div className={s.tokenList}>
                {list.map((tk) => {
                  const issue = byToken.get(tk);
                  return (
                    <ColourField
                      key={tk}
                      label={tokenLabel(tk)}
                      name={`--${tk}`}
                      value={tokens[tk]}
                      alpha={ALPHA_TOKENS.has(tk)}
                      changed={tk in theme.overrides}
                      onChange={(v) => set(tk, v)}
                      onReset={() => reset(tk)}
                      issue={issue ? issueText(issue) : null}
                      testId={`token-${tk}`}
                    />
                  );
                })}
              </div>
            </details>
          );
        })}
      </div>
    </div>
  );
}
