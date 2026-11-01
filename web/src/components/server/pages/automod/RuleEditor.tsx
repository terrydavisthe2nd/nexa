import { ChevronLeft, CircleCheck, ShieldAlert, Trash2, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { ApiError } from "@/api/client";
import { automod as automodApi, type RuleBody } from "@/api/automod";
import type { AutomodAction, AutomodConfig, AutomodKind, AutomodRule } from "@/api/types";
import { Button } from "@/components/common/Button";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { Select } from "@/components/common/Select";
import { Field, TextArea, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { automodReason } from "@/components/composer/AutomodNotice";
import { t } from "@/i18n";
import { automodPresets, tryRule } from "@/lib/automod/rules";
import { P } from "@/lib/perms";
import { formatShortDuration } from "@/lib/time";
import { useChannels } from "@/stores/channels";
import { canI, rolesByPosition, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { unlessUnsaved } from "@/stores/unsaved";
import st from "@/components/settings/Settings.module.css";
import { SaveBar } from "../../SettingsLayer";
import { kindInfo, LISTS, listLabel, patternProblem, splitLines, splitWords, TIMEOUTS } from "./kinds";
import s from "./Automod.module.css";

/** A rule as it is being written: the words of its lists as they are typed. */
interface Draft {
  name: string;
  enabled: boolean;
  config: AutomodConfig;
  actions: AutomodAction[];
  exempt_roles: string[];
  exempt_channels: string[];
  /** What is typed in the word boxes, kept as typed until saved. */
  wordsText: string;
  regexText: string;
  allowText: string;
  domainsText: string;
  extText: string;
}

function draftOf(rule: AutomodRule | null, kind: AutomodKind): Draft {
  const base = rule ?? { ...kindInfo(kind)!.defaults(), enabled: true, exempt_roles: [], exempt_channels: [] };
  const c = base.config;
  return {
    name: base.name,
    enabled: rule ? rule.enabled : true,
    config: { ...c },
    actions: base.actions.map((a) => ({ ...a })),
    exempt_roles: [...(rule?.exempt_roles ?? [])],
    exempt_channels: [...(rule?.exempt_channels ?? [])],
    wordsText: (c.words ?? []).join("\n"),
    regexText: (c.regex ?? []).join("\n"),
    allowText: (kind === "links" ? c.allow : c.allow)?.join("\n") ?? "",
    domainsText: (c.domains ?? []).join("\n"),
    extText: (c.extensions ?? []).join(", "),
  };
}

/** The config as it will be saved, from what is typed. */
function configOf(kind: AutomodKind, d: Draft): AutomodConfig {
  const c: AutomodConfig = { ...d.config };
  if (kind === "words" || kind === "profile") {
    c.words = splitWords(d.wordsText);
    c.regex = splitLines(d.regexText);
  }
  if (kind === "words" || kind === "flagged_words" || kind === "profile") c.allow = splitWords(d.allowText);
  if (kind === "links") {
    c.allow = splitWords(d.allowText);
    c.domains = splitWords(d.domainsText);
  }
  if (kind === "attachments") c.extensions = splitWords(d.extText).map((e) => e.replace(/^\./, "").toLowerCase());
  return c;
}

function bodyOf(kind: AutomodKind, d: Draft): RuleBody {
  return { name: d.name.trim() || kindInfo(kind)!.defaults().name, kind, enabled: d.enabled, config: configOf(kind, d), actions: d.actions, exempt_roles: d.exempt_roles, exempt_channels: d.exempt_channels };
}

function Check({ checked, onChange, label, desc }: { checked: boolean; onChange: (v: boolean) => void; label: string; desc?: string }) {
  return (
    <label className={s.check}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className={s.checkText}>
        <span className={s.checkLabel}>{label}</span>
        {desc ? <span className={s.checkDesc}>{desc}</span> : null}
      </span>
    </label>
  );
}

/**
 * One rule, written or rewritten: what it looks for, a box to try it on, what
 * it does about what it finds, and who it leaves alone.
 */
export function RuleEditor({ serverId, rule, kind, onDone }: { serverId: string; rule: AutomodRule | null; kind: AutomodKind; onDone: () => void }) {
  const info = kindInfo(kind)!;
  const server = useServers((x) => x.byId[serverId]);
  const channels = useChannels((c) => c.byId);
  const toast = useUI((u) => u.toast);
  const [saved] = useState(() => draftOf(rule, kind));
  const [d, setD] = useState<Draft>(saved);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [sample, setSample] = useState("");
  const [, setPresetsHere] = useState(0);
  const set = (patch: Partial<Draft>) => setD((cur) => ({ ...cur, ...patch }));
  const setConfig = (patch: Partial<AutomodConfig>) => setD((cur) => ({ ...cur, config: { ...cur.config, ...patch } }));
  const dirty = !rule || JSON.stringify(bodyOf(kind, d)) !== JSON.stringify(bodyOf(kind, saved));
  const mayTimeout = canI(server, P.MODERATE_MEMBERS);

  // the ready-made lists, for trying them out here
  useEffect(() => {
    if (kind === "flagged_words" || kind === "spam" || kind === "profile") void automodPresets().then(() => setPresetsHere((n) => n + 1));
  }, [kind]);

  const textChannels = useMemo(
    () => Object.values(channels).filter((c) => c.server_id === serverId && (c.type === "text" || c.type === "announcement")).sort((a, b) => a.position - b.position),
    [channels, serverId],
  );
  const exemptable = useMemo(
    () => Object.values(channels).filter((c) => c.server_id === serverId && (c.type === "text" || c.type === "announcement" || c.type === "forum" || c.type === "media" || c.type === "voice")).sort((a, b) => a.position - b.position),
    [channels, serverId],
  );
  const roles = rolesByPosition(server).filter((r) => r.id !== serverId);

  const action = (type: AutomodAction["type"]) => d.actions.find((a) => a.type === type);
  const setAction = (type: AutomodAction["type"], on: boolean, patch: Partial<AutomodAction> = {}) =>
    setD((cur) => {
      const rest = cur.actions.filter((a) => a.type !== type);
      if (!on) return { ...cur, actions: rest };
      const had = cur.actions.find((a) => a.type === type);
      return { ...cur, actions: [...rest, { ...(had ?? { type }), ...patch, type }] };
    });

  const patterns = splitLines(d.regexText);
  const patternIssue = patterns.map(patternProblem).find(Boolean) ?? null;
  const tryResult = useMemo(() => {
    if (!info.tryable || !sample.trim()) return null;
    return tryRule({ kind, config: configOf(kind, d) }, sample);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sample, d, kind, info.tryable]);

  const save = async () => {
    setProblem(null);
    if (patternIssue) return setProblem(patternIssue);
    if (d.actions.length === 0) return setProblem(t("automod.editor.needAction"));
    const alert = action("alert");
    if (alert && !alert.channel_id) return setProblem(t("automod.editor.needAlertChannel"));
    setBusy(true);
    try {
      const body = bodyOf(kind, d);
      if (rule) await automodApi.update(serverId, rule.id, body);
      else await automodApi.create(serverId, body);
      toast(rule ? t("automod.editor.saved") : t("automod.editor.created"), "success");
      onDone();
    } catch (err) {
      setProblem(err instanceof ApiError ? err.message : t("automod.editor.saveFailed"));
    } finally {
      setBusy(false);
    }
  };
  const remove = () => {
    if (!rule) return;
    useUI.getState().openModal("confirm", {
      title: t("automod.editor.deleteTitle"),
      body: t("automod.editor.deleteBody", { name: rule.name }),
      confirmLabel: t("automod.editor.delete"),
      danger: true,
      onConfirm: async () => {
        try {
          await automodApi.remove(serverId, rule.id);
          onDone();
        } catch (err) {
          toast(err instanceof ApiError ? err.message : t("automod.editor.deleteFailed"), "error");
        }
      },
    });
  };

  const Icon = info.icon;
  return (
    <>
      <button type="button" className={s.back} onClick={unlessUnsaved(onDone)}>
        <ChevronLeft size={18} aria-hidden="true" />
        {t("automod.title")}
      </button>
      <div className={s.editorHead}>
        <span className={s.ruleIcon} data-server={info.server || undefined}><Icon size={20} aria-hidden="true" /></span>
        <h2 className={st.title} style={{ margin: 0 }}>{rule ? t("automod.editor.editTitle") : t(`automod.kind.${kind}`)}</h2>
      </div>
      <div className={st.form}>
        <Field label={t("automod.editor.name")}>
          <TextInput value={d.name} onChange={(e) => set({ name: e.target.value })} maxLength={100} data-testid="rule-name" />
        </Field>
        <Toggle checked={d.enabled} onChange={(v) => set({ enabled: v })} label={t("automod.editor.enabled")} description={info.server ? t("automod.editor.enabledServer") : t("automod.editor.enabledApps")} />

        <section className={s.section} aria-label={t("automod.editor.looksFor")}>
          <h3 className={s.sectionHead}>{t("automod.editor.looksFor")}</h3>
          <p className={s.sectionNote}>{t(`automod.kindDesc.${kind}`)}</p>
          {kind === "flagged_words" || kind === "profile" ? (
            <div className={s.checks}>
              {LISTS.map((name) => (
                <Check key={name} checked={(d.config.lists ?? []).includes(name)} onChange={(on) => setConfig({ lists: on ? [...(d.config.lists ?? []), name] : (d.config.lists ?? []).filter((x) => x !== name) })} label={listLabel(name)} desc={t(`automod.listDesc.${name}`)} />
              ))}
            </div>
          ) : null}
          {kind === "words" || kind === "profile" ? (
            <>
              <Field label={t("automod.editor.words")} hint={t("automod.editor.wordsHint")}>
                <TextArea className={s.area} value={d.wordsText} onChange={(e) => set({ wordsText: e.target.value })} placeholder={t("automod.editor.wordsPlaceholder")} data-testid="rule-words" />
              </Field>
              <Field label={t("automod.editor.patterns")} hint={t("automod.editor.patternsHint")}>
                <TextArea className={s.area} value={d.regexText} onChange={(e) => set({ regexText: e.target.value })} placeholder={"(?:buy|cheap)\\s+followers"} spellCheck={false} dir="ltr" />
              </Field>
              {patternIssue ? <p className={s.problem}>{patternIssue}</p> : null}
            </>
          ) : null}
          {kind === "words" || kind === "flagged_words" || kind === "profile" ? (
            <>
              <Field label={t("automod.editor.allow")} hint={t("automod.editor.allowHint")}>
                <TextArea className={s.area} value={d.allowText} onChange={(e) => set({ allowText: e.target.value })} placeholder={t("automod.editor.allowPlaceholder")} />
              </Field>
              <Toggle checked={d.config.evasion !== false} onChange={(v) => setConfig({ evasion: v })} label={t("automod.editor.evasion")} description={t("automod.editor.evasionDesc")} />
            </>
          ) : null}
          {kind === "profile" ? (
            <div className={s.checks}>
              <Check checked={d.config.names !== false} onChange={(v) => setConfig({ names: v })} label={t("automod.profile.namesLong")} />
              <Check checked={!!d.config.bio} onChange={(v) => setConfig({ bio: v })} label={t("automod.profile.bioLong")} />
            </div>
          ) : null}
          {kind === "spam" ? (
            <div className={s.checks}>
              <Check checked={!!d.config.scams} onChange={(v) => setConfig({ scams: v })} label={t("automod.spam.scamsLong")} desc={t("automod.spam.scamsDesc")} />
              <Check checked={!!d.config.shady_links} onChange={(v) => setConfig({ shady_links: v })} label={t("automod.spam.shadyLong")} desc={t("automod.spam.shadyDesc")} />
              <Check checked={!!d.config.repeats} onChange={(v) => setConfig({ repeats: v })} label={t("automod.spam.repeatsLong")} desc={t("automod.spam.repeatsDesc")} />
              <Check checked={!!d.config.zalgo} onChange={(v) => setConfig({ zalgo: v })} label={t("automod.spam.zalgoLong")} desc={t("automod.spam.zalgoDesc")} />
              <Check checked={!!d.config.walls} onChange={(v) => setConfig({ walls: v })} label={t("automod.spam.wallsLong")} desc={t("automod.spam.wallsDesc")} />
            </div>
          ) : null}
          {kind === "links" ? (
            <>
              <div className={s.checks} role="radiogroup" aria-label={t("automod.links.which")}>
                <label className={s.check}>
                  <input type="radio" name="linkmode" checked={d.config.mode !== "list"} onChange={() => setConfig({ mode: "all" })} />
                  <span className={s.checkText}><span className={s.checkLabel}>{t("automod.links.all")}</span><span className={s.checkDesc}>{t("automod.links.allDesc")}</span></span>
                </label>
                <label className={s.check}>
                  <input type="radio" name="linkmode" checked={d.config.mode === "list"} onChange={() => setConfig({ mode: "list" })} />
                  <span className={s.checkText}><span className={s.checkLabel}>{t("automod.links.list")}</span><span className={s.checkDesc}>{t("automod.links.listDesc")}</span></span>
                </label>
              </div>
              {d.config.mode === "list" ? (
                <Field label={t("automod.links.blocked")} hint={t("automod.links.sitesHint")}>
                  <TextArea className={s.area} value={d.domainsText} onChange={(e) => set({ domainsText: e.target.value })} placeholder={"example.com\nbadsite.net"} dir="ltr" spellCheck={false} />
                </Field>
              ) : (
                <Field label={t("automod.links.allowed")} hint={t("automod.links.sitesHint")}>
                  <TextArea className={s.area} value={d.allowText} onChange={(e) => set({ allowText: e.target.value })} placeholder={"youtube.com\ngithub.com"} dir="ltr" spellCheck={false} />
                </Field>
              )}
            </>
          ) : null}
          {kind === "personal" ? (
            <div className={s.checks}>
              <Check checked={!!d.config.emails} onChange={(v) => setConfig({ emails: v })} label={t("automod.personal.emailsLong")} />
              <Check checked={!!d.config.phones} onChange={(v) => setConfig({ phones: v })} label={t("automod.personal.phonesLong")} />
              <Check checked={!!d.config.ips} onChange={(v) => setConfig({ ips: v })} label={t("automod.personal.ipsLong")} />
              <Check checked={!!d.config.cards} onChange={(v) => setConfig({ cards: v })} label={t("automod.personal.cardsLong")} />
            </div>
          ) : null}
          {kind === "caps" ? (
            <div className={s.inline}>
              <Field label={t("automod.caps.percent", { percent: d.config.percent ?? 70 })}>
                <input type="range" className={st.slider} min={50} max={100} step={5} value={d.config.percent ?? 70} onChange={(e) => setConfig({ percent: Number(e.target.value) })} />
              </Field>
              <Field label={t("automod.caps.length")}>
                <TextInput className={s.num} type="number" min={1} max={2000} value={d.config.min_length ?? 10} onChange={(e) => setConfig({ min_length: Number(e.target.value) || 10 })} />
              </Field>
            </div>
          ) : null}
          {kind === "emoji_spam" || kind === "mentions" ? (
            <Field label={kind === "mentions" ? t("automod.mentions.max") : t("automod.emoji.max")}>
              <TextInput className={s.num} type="number" min={1} max={kind === "mentions" ? 50 : 200} value={d.config.max ?? (kind === "mentions" ? 5 : 10)} onChange={(e) => setConfig({ max: Number(e.target.value) || 1 })} />
            </Field>
          ) : null}
          {kind === "mentions" ? <Toggle checked={!!d.config.raid} onChange={(v) => setConfig({ raid: v })} label={t("automod.mentions.raid")} description={t("automod.mentions.raidDesc")} /> : null}
          {kind === "flood" ? (
            <div className={s.inline}>
              <Field label={t("automod.flood.messages")}>
                <TextInput className={s.num} type="number" min={2} max={50} value={d.config.messages ?? 5} onChange={(e) => setConfig({ messages: Number(e.target.value) || 5 })} />
              </Field>
              <Field label={t("automod.flood.seconds")}>
                <TextInput className={s.num} type="number" min={1} max={600} value={d.config.seconds ?? 5} onChange={(e) => setConfig({ seconds: Number(e.target.value) || 5 })} />
              </Field>
            </div>
          ) : null}
          {kind === "attachments" ? (
            <Field label={t("automod.files.endings")} hint={t("automod.files.endingsHint")}>
              <TextArea className={s.area} value={d.extText} onChange={(e) => set({ extText: e.target.value })} dir="ltr" spellCheck={false} />
            </Field>
          ) : null}
        </section>

        {info.tryable ? (
          <section className={s.section} aria-label={t("automod.try.title")}>
            <h3 className={s.sectionHead}>{t("automod.try.title")}</h3>
            <p className={s.sectionNote}>{kind === "profile" ? t("automod.try.hintProfile") : t("automod.try.hint")}</p>
            <div className={s.try}>
              <TextArea value={sample} onChange={(e) => setSample(e.target.value)} placeholder={kind === "profile" ? t("automod.try.placeholderProfile") : t("automod.try.placeholder")} rows={3} data-testid="rule-try" />
              {sample.trim() ? (
                tryResult ? (
                  <div className={s.tryResult} data-hit="" role="status" data-testid="rule-try-result">
                    <ShieldAlert size={16} aria-hidden="true" />
                    <span>{t("automod.try.caught", { why: automodReason(kind, tryResult) })}</span>
                  </div>
                ) : (
                  <div className={s.tryResult} data-clear="" role="status" data-testid="rule-try-result">
                    <CircleCheck size={16} aria-hidden="true" />
                    <span>{t("automod.try.clear")}</span>
                  </div>
                )
              ) : null}
            </div>
          </section>
        ) : null}

        <section className={s.section} aria-label={t("automod.editor.does")}>
          <h3 className={s.sectionHead}>{t("automod.editor.does")}</h3>
          {kind !== "profile" ? (
            <div className={s.action}>
              <Toggle checked={!!action("block")} onChange={(v) => setAction("block", v)} label={t("automod.do.blockLong")} description={info.server ? t("automod.do.blockServerDesc") : t("automod.do.blockDesc")} />
              {action("block") ? (
                <div className={s.actionBody}>
                  <Field label={t("automod.do.message")} hint={t("automod.do.messageHint", { count: 150 - (action("block")?.message ?? "").length })}>
                    <TextInput value={action("block")?.message ?? ""} onChange={(e) => setAction("block", true, { message: e.target.value.slice(0, 150) })} maxLength={150} placeholder={t("automod.do.messagePlaceholder")} />
                  </Field>
                </div>
              ) : null}
            </div>
          ) : null}
          {kind === "profile" ? (
            <div className={s.action}>
              <Toggle checked={!!action("quarantine")} disabled={!mayTimeout} onChange={(v) => setAction("quarantine", v)} label={t("automod.do.quarantineLong")} description={mayTimeout ? t("automod.do.quarantineDesc") : t("automod.do.needModerate")} />
            </div>
          ) : null}
          <div className={s.action}>
            <Toggle checked={!!action("alert")} onChange={(v) => setAction("alert", v, { channel_id: action("alert")?.channel_id ?? textChannels[0]?.id })} label={t("automod.do.alertLong")} description={t("automod.do.alertDesc")} />
            {action("alert") ? (
              <div className={s.actionBody}>
                <Field label={t("automod.do.alertChannel")}>
                  <Select value={action("alert")?.channel_id ?? ""} onChange={(e) => setAction("alert", true, { channel_id: e.target.value })}>
                    {textChannels.map((c) => <option key={c.id} value={c.id}>#{c.name}</option>)}
                  </Select>
                </Field>
              </div>
            ) : null}
          </div>
          {kind !== "profile" ? (
            <div className={s.action}>
              <Toggle checked={!!action("timeout")} disabled={!mayTimeout} onChange={(v) => setAction("timeout", v, { seconds: action("timeout")?.seconds ?? 600, after: action("timeout")?.after ?? 1 })} label={t("automod.do.timeoutLong")} description={mayTimeout ? t("automod.do.timeoutDesc") : t("automod.do.needModerate")} />
              {action("timeout") ? (
                <div className={[s.actionBody, s.inline].join(" ")}>
                  <Field label={t("automod.do.timeoutFor")}>
                    <Select value={action("timeout")?.seconds ?? 600} onChange={(e) => setAction("timeout", true, { seconds: Number(e.target.value) })}>
                      {TIMEOUTS.map((sec) => <option key={sec} value={sec}>{formatShortDuration(sec)}</option>)}
                    </Select>
                  </Field>
                  <Field label={t("automod.do.timeoutWhen")}>
                    <Select value={action("timeout")?.after ?? 1} onChange={(e) => setAction("timeout", true, { after: Number(e.target.value) })}>
                      {[1, 2, 3, 4, 5, 10].map((n) => <option key={n} value={n}>{n === 1 ? t("automod.do.first") : t("automod.do.nth", { count: n })}</option>)}
                    </Select>
                  </Field>
                </div>
              ) : null}
            </div>
          ) : null}
        </section>

        <section className={s.section} aria-label={t("automod.editor.skips")}>
          <h3 className={s.sectionHead}>{t("automod.editor.skips")}</h3>
          <p className={s.sectionNote}>{t("automod.editor.skipsNote")}</p>
          <Field label={t("automod.editor.skipRoles")}>
            <Select value="" onChange={(e) => e.target.value && set({ exempt_roles: [...d.exempt_roles, e.target.value].slice(0, 50) })}>
              <option value="">{t("automod.editor.addRole")}</option>
              {roles.filter((r) => !d.exempt_roles.includes(r.id)).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </Select>
          </Field>
          {d.exempt_roles.length ? (
            <div className={s.picks}>
              {d.exempt_roles.map((id) => {
                const r = server?.roles[id];
                return (
                  <span key={id} className={s.pick}>
                    <span className={s.roleDot} style={{ background: r?.color ? `#${r.color.toString(16).padStart(6, "0")}` : "var(--text-muted)" }} aria-hidden="true" />
                    <span dir="auto">{r?.name ?? t("automod.editor.goneRole")}</span>
                    <button type="button" aria-label={t("automod.editor.removeSkip", { name: r?.name ?? "" })} onClick={() => set({ exempt_roles: d.exempt_roles.filter((x) => x !== id) })}><X size={14} /></button>
                  </span>
                );
              })}
            </div>
          ) : null}
          <Field label={t("automod.editor.skipChannels")}>
            <Select value="" onChange={(e) => e.target.value && set({ exempt_channels: [...d.exempt_channels, e.target.value].slice(0, 50) })}>
              <option value="">{t("automod.editor.addChannel")}</option>
              {exemptable.filter((c) => !d.exempt_channels.includes(c.id)).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </Select>
          </Field>
          {d.exempt_channels.length ? (
            <div className={s.picks}>
              {d.exempt_channels.map((id) => {
                const c = channels[id];
                return (
                  <span key={id} className={s.pick}>
                    {c ? <ChannelGlyph channel={c} size={14} /> : null}
                    <span dir="auto">{c?.name ?? t("automod.editor.goneChannel")}</span>
                    <button type="button" aria-label={t("automod.editor.removeSkip", { name: c?.name ?? "" })} onClick={() => set({ exempt_channels: d.exempt_channels.filter((x) => x !== id) })}><X size={14} /></button>
                  </span>
                );
              })}
            </div>
          ) : null}
        </section>

        {problem ? <p className={s.problem} role="alert">{problem}</p> : null}
        {rule ? (
          <div className={s.footerRow}>
            <Button variant="ghost" onClick={remove}>
              <Trash2 size={16} aria-hidden="true" /> {t("automod.editor.delete")}
            </Button>
          </div>
        ) : null}
      </div>
      <SaveBar dirty={dirty} busy={busy} onReset={() => (rule ? setD(saved) : onDone())} onSave={() => void save()} label={rule ? t("automod.editor.save") : t("automod.editor.create")} />
    </>
  );
}
