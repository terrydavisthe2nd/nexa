import { Toggle } from "@/components/common/Toggle";
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Shield } from "lucide-react";
import { admin as adminApi, type ClientErrorReport } from "@/api/admin";
import { ApiError } from "@/api/client";
import type { AdminSettings, AdminStats, LegalReport, StorageMeter } from "@/api/types";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { Field, TextInput } from "@/components/common/TextInput";
import { formatBytes, formatFull, formatTime } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { useNexa } from "@/stores/nexa";
import { useUI } from "@/stores/ui";
import { copyText } from "@/lib/copyText";
import st from "./Settings.module.css";
import s from "@/components/server/ServerSettings.module.css";

import { t as tr_, translator } from "@/i18n";
import { LoadError } from "@/components/common/LoadError";
/** User settings → Nexa Admin (admins only): stats, registration policy, users. */
export default function AdminPage() {
  const toast = useUI((u) => u.toast);
  const meId = useAuth((a) => a.me?.id);
  const isOwner = useNexa((n) => !!meId && n.state?.staff?.owner_id === meId);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [settings, setSettings] = useState<AdminSettings | null>(null);
  const [key, setKey] = useState("");
  const nav = useNavigate();

  // a failure says so, with a way to try again, instead of spinning forever
  const [failed, setFailed] = useState(false);
  const loadAll = useCallback(() => {
    setFailed(false);
    adminApi.stats().then(setStats).catch(() => setFailed(true));
    adminApi.settings().then(setSettings).catch(() => setFailed(true));
  }, []);
  useEffect(() => loadAll(), [loadAll]);

  // "" hands the choice back to the config file
  const setMode = async (mode: AdminSettings["registration"] | "") => {
    const before = settings;
    if (mode) setSettings((cur) => (cur ? { ...cur, registration: mode } : cur)); // optimistic: the radio moves at once
    try {
      setSettings(await adminApi.patchSettings({ registration: mode }));
    } catch (err) {
      setSettings(before);
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.couldNotChangeThePolicy"), "error");
    }
  };
  const setSignupLimit = async (on: boolean) => {
    const before = settings;
    setSettings((cur) => (cur ? { ...cur, signup_ip_limit: on } : cur)); // optimistic: the switch moves at once
    try {
      setSettings(await adminApi.patchSettings({ signup_ip_limit: on }));
    } catch (err) {
      setSettings(before);
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.couldNotChangeTheSignupLimit"), "error");
    }
  };
  const saveKey = async () => {
    try {
      setSettings(await adminApi.patchSettings({ registration_key: key }));
      setKey("");
      toast(tr_("settings.admin.registrationKeySaved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.couldNotSaveTheKey"), "error");
    }
  };
  const clearKey = async () => {
    try {
      setSettings(await adminApi.patchSettings({ registration_key: "" }));
      toast(tr_("settings.admin.registrationKeyCleared"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.couldNotClearTheKey"), "error");
    }
  };
  return (
    <>
      <h2 className={st.title}>{tr_("settings.admin.nexaAdmin")}</h2>
      <p className={st.hint}>{tr_("settings.admin.theseSettingsApplyToEveryone")}</p>
      <h3 className={st.h5}>{tr_("server.channelSettings.overview")}</h3>
      {stats ? (
        // on the page's own background the box was invisible, and each value sat 600px from its name
        <div className={st.rows} style={{ margin: 0, background: "var(--bg-surface)" }}>
          <div className={st.row}><span className={st.rowLabel}>{tr_("settings.admin.users")}</span><span className={st.rowValue}>{tr_("settings.admin.usersOnline", { users: stats.users, online: stats.online })}</span></div>
          <div className={st.row}><span className={st.rowLabel}>{tr_("rail.rail.servers")}</span><span className={st.rowValue}>{stats.servers}</span></div>
          <div className={st.row}><span className={st.rowLabel}>{tr_("modals.quickSwitcher.messages")}</span><span className={st.rowValue}>{stats.messages}</span></div>
          <div className={st.row}><span className={st.rowLabel}>{tr_("settings.admin.storedFiles")}</span><span className={st.rowValue}>{stats.blobs} · {formatBytes(stats.blob_bytes)}</span></div>
          <div className={st.row}><span className={st.rowLabel}>{tr_("settings.admin.version")}</span><span className={st.rowValue}>{stats.version}</span></div>
          {stats.realtime && stats.realtime.limit_bytes > 0 ? <CallUsage usage={stats.realtime} /> : null}
        </div>
      ) : failed ? <LoadError message={tr_("moderation.loadFailed")} onRetry={loadAll} /> : <Spinner />}
      {settings?.storage_remote ? (
        <FileStorage
          meter={stats?.storage}
          limitCents={settings.storage_budget_cents}
          onSaved={(next) => {
            setSettings(next);
            // whether uploads are paused follows the new limit at once
            adminApi.stats().then(setStats).catch(() => undefined);
          }}
        />
      ) : null}
      <h3 className={st.h5}>{tr_("settings.admin.registration")}</h3>
      {settings ? (
        <>
          <div role="radiogroup" aria-label={tr_("settings.admin.registration")}>
            {(["open", "invite", "closed"] as const).map((m) => (
              <label key={m} className={st.radio} data-on={settings.registration === m}>
                <input type="radio" name="registration" checked={settings.registration === m} onChange={() => void setMode(m)} />
                <span className={st.radioText}>
                  <span className={st.radioLabel}>{m === "open" ? tr_("settings.admin.open") : m === "invite" ? tr_("settings.admin.inviteOnly") : tr_("settings.admin.closed")}</span>
                  <span className={st.radioDesc}>{m === "open" ? tr_("settings.admin.regOpen") : m === "invite" ? tr_("settings.admin.regInvite") : tr_("settings.admin.regClosed")}</span>
                </span>
              </label>
            ))}
          </div>
          {/* Where the choice comes from, under the choices it is about: it sat
              over the key field and read as that field's help, and the way
              back to the config file's value was not offered at all. */}
          <div className={st.configNote}>
            <span className={st.hint} style={{ margin: 0 }}>
              {settings.overrides?.["registration"] ? tr_("settings.admin.overridesConfig", { value: settings.config_registration }) : tr_("settings.admin.followsConfig", { value: settings.config_registration })}
            </span>
            {settings.overrides?.["registration"] ? <Button size="sm" variant="secondary" onClick={() => void setMode("")}>{tr_("settings.admin.useConfigFile")}</Button> : null}
          </div>
          <Field label={settings.registration_key_set ? tr_("settings.admin.registrationKeySet") : tr_("settings.admin.registrationKeyNotSet")} hint={tr_("settings.admin.shareThisWithPeopleYouWant")}>
            <div style={{ display: "flex", gap: 8 }}>
              <TextInput value={key} onChange={(e) => setKey(e.target.value)} placeholder={tr_("settings.admin.newKey")} maxLength={128} />
              <Button size="sm" onClick={() => void saveKey()}>{tr_("chat.editMessage.save")}</Button>
              {settings.registration_key_set ? <Button size="sm" variant="ghost" onClick={() => void clearKey()}>{tr_("modals.quickSwitcher.clear")}</Button> : null}
            </div>
          </Field>
          <Toggle checked={settings.signup_ip_limit} onChange={(v) => void setSignupLimit(v)} label={tr_("settings.admin.signupIpLimit")} description={tr_("settings.admin.signupIpLimitHint")} />
          <p className={st.hint} style={{ marginTop: 12 }}>{tr_("settings.admin.configLimits", { uploads: formatBytes(settings.max_upload_bytes), avatars: formatBytes(settings.max_avatar_bytes), emoji: formatBytes(settings.max_emoji_bytes), stickers: formatBytes(settings.max_sticker_bytes), gif: settings.gif_search ? tr_("settings.admin.on") : tr_("settings.admin.off"), turn: settings.turn ? tr_("settings.admin.configured") : tr_("settings.admin.notConfigured") })}</p>
        </>
      ) : failed ? <LoadError message={tr_("moderation.loadFailed")} onRetry={loadAll} /> : <Spinner />}
      <h3 className={st.h5}>{tr_("settings.admin.users")}</h3>
      <div className={st.panelLink} data-testid="admin-panel-link">
        <span className={st.panelLinkIcon} aria-hidden="true"><Shield size={22} /></span>
        <span className={st.panelLinkText}>
          <span className={st.panelLinkTitle}>{tr_("moderation.title")}</span>
          <span className={st.hint} style={{ margin: 0 }}>{tr_("settings.admin.panelMoved")}</span>
        </span>
        <Button
          size="sm"
          onClick={() => {
            useUI.getState().closeAllModals();
            nav("/channels/@admin");
          }}
        >
          {tr_("settings.admin.openPanel")}
        </Button>
      </div>
      {/* the server compiles these for the owner of Nexa alone */}
      {isOwner ? <LegalResponse /> : null}
      <CrashLog />
    </>
  );
}

/**
 * Answering legal process about one account.
 *
 * Every platform of any size can tell an officer what it holds; this does the
 * same for whoever runs Nexa. The button reads the rows that already
 * exist and writes a document out of them — it collects nothing new, and the
 * document says in plain terms which categories are end-to-end encrypted and
 * therefore cannot be produced at all, so the same request does not come back.
 *
 * The document itself is English legal prose written by the server. It is not
 * translated: it may be attached to a filing, and wording that changed with the
 * operator's interface language would be worth less there.
 */
function LegalResponse() {
  const toast = useUI((u) => u.toast);
  const [who, setWho] = useState("");
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<LegalReport | null>(null);
  const [copied, setCopied] = useState(false);

  const compile = async () => {
    const asked = who.trim();
    if (!asked) return;
    setBusy(true);
    try {
      setReport(await adminApi.legalReport(asked));
    } catch (err) {
      setReport(null);
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.legal.couldNotCompileTheReport"), "error");
    } finally {
      setBusy(false);
    }
  };

  const download = () => {
    if (!report) return;
    const url = URL.createObjectURL(new Blob([report.document], { type: "text/plain;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = report.filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const copy = async () => {
    if (!report) return;
    if (await copyText(report.document)) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else {
      toast(tr_("settings.admin.legal.couldNotReachTheClipboardSelect"), "error");
    }
  };

  return (
    <>
      <h3 className={st.h5}>{tr_("settings.admin.legal.lawEnforcementResponse")}</h3>
      <p className={st.hint}>{tr_("settings.admin.legal.compileWhatNexaHolds")}</p>
      <div className={st.legalReportRow}>
        <TextInput
          value={who}
          onChange={(e) => setWho(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && void compile()}
          placeholder={tr_("settings.admin.legal.usernameOrAccountId")}
          data-testid="legal-report-account"
        />
        <Button size="sm" loading={busy} disabled={!who.trim()} onClick={() => void compile()} data-testid="legal-report-compile">
          {tr_("settings.admin.legal.compileReport")}
        </Button>
      </div>
      {report ? (
        <>
          <p className={st.hint} style={{ marginTop: 8 }}>
            {tr_("settings.admin.legal.compiledForOn", { username: report.username, when: formatFull(report.generated_at) })}
          </p>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
            <Button size="sm" variant="secondary" onClick={download}>{tr_("settings.admin.legal.download")}</Button>
            <Button size="sm" variant="secondary" onClick={() => void copy()}>{copied ? tr_("settings.admin.legal.copied") : tr_("settings.admin.legal.copy")}</Button>
            <Button size="sm" variant="ghost" onClick={() => setReport(null)}>{tr_("modals.quickSwitcher.clear")}</Button>
          </div>
          <pre
            data-testid="legal-report-document"
            style={{ whiteSpace: "pre-wrap", wordBreak: "break-word", fontSize: 11, lineHeight: 1.5, color: "var(--text-muted)", background: "var(--bg-raised)", borderRadius: "var(--radius-sm)", padding: 12, margin: "8px 0 0", maxHeight: 420, overflow: "auto" }}
          >
            {report.document}
          </pre>
        </>
      ) : null}
    </>
  );
}

/** What has been breaking, for whoever runs Nexa. */
function CrashLog() {
  const [list, setList] = useState<ClientErrorReport[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const toast = useUI((u) => u.toast);
  // A failed request is not an empty log: it used to say "nothing has crashed".
  const load = () => {
    setFailed(false);
    return adminApi.errors(100).then(setList, () => setFailed(true));
  };
  useEffect(() => {
    void load();
  }, []);
  const clear = async () => {
    try {
      await adminApi.clearErrors();
      setList([]);
      toast(tr_("settings.admin.crashLogCleared"), "success");
    } catch {
      toast(tr_("settings.admin.couldNotClearTheLog"), "error");
    }
  };
  return (
    <>
      <h3 className={st.h5}>{tr_("settings.admin.crashes")}</h3>
      {/* This was English in every language, and ended "The newest 0 are kept" on an empty log. */}
      <p className={st.hint}>{tr_("settings.admin.crashesHint")}</p>
      <div style={{ display: "flex", gap: 8, margin: "12px 0 8px" }}>
        <Button size="sm" variant="secondary" onClick={() => void load()}>{tr_("settings.admin.refresh")}</Button>
        {list?.length ? <Button size="sm" variant="danger" onClick={() => void clear()}>{tr_("modals.quickSwitcher.clear")}</Button> : null}
      </div>
      {failed ? <LoadError message={tr_("moderation.loadFailed")} onRetry={() => void load()} /> : list === null ? <Spinner /> : null}
      {list?.length === 0 ? <p className={st.hint}>{tr_("settings.admin.nothingHasCrashedGood")}</p> : null}
      {list?.map((e) => (
        <div key={e.id} className={s.memberRow} style={{ alignItems: "flex-start" }}>
          <div className={s.memberText}>
            {/* a button, so the stack opens from the keyboard too: the row was a div with an onClick */}
            <button type="button" className={st.crashToggle} aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)}>
              <span className={s.memberName} style={{ color: "var(--danger)" }}>{e.message}</span>
              <span className={s.memberSub}>{e.kind} · {e.path || "/"} · {tr_("settings.admin.buildVersion", { version: e.version || tr_("settings.admin.unknown") })} · {formatFull(e.created_at)}</span>
            </button>
            {open === e.id ? (
              <pre style={{ whiteSpace: "pre-wrap", wordBreak: "break-word", fontSize: 11, lineHeight: 1.5, color: "var(--text-muted)", margin: "8px 0 0", maxHeight: 260, overflow: "auto" }}>
                {e.stack || tr_("settings.admin.noStack")}{e.component ? `\n\ncomponents:${e.component}` : ""}{`\n\n${e.user_agent}`}
              </pre>
            ) : null}
          </div>
        </div>
      ))}
    </>
  );
}

/**
 * This month's call traffic against the allowance. The server starts pausing
 * video at 90% and calls at 99%, and until this row the operator's first sign
 * of it was video quietly refusing to start.
 */
/** A limit typed in dollars ("50", "12.5", "7,25"), in cents, or null when it is not one. */
function parseDollars(raw: string): number | null {
  const m = /^(\d{1,6})(?:[.,](\d{0,2}))?$/.exec(raw.trim());
  if (!m) return null;
  const cents = Number(m[1]) * 100 + Number((m[2] ?? "").padEnd(2, "0"));
  return cents <= 10_000_000 ? cents : null;
}

function dollars(cents: number): string {
  try {
    return new Intl.NumberFormat(translator().locale, { style: "currency", currency: "USD" }).format(cents / 100);
  } catch {
    return `$${(cents / 100).toFixed(2)}`;
  }
}

/**
 * File Storage: what keeping attachments in Cloudflare R2 costs this month,
 * against the limit the server holds new files to (storage_budget.go), and
 * where that limit is set. Shown only while attachments go to a bucket.
 */
function FileStorage({ meter, limitCents, onSaved }: { meter: StorageMeter | null | undefined; limitCents: number | null; onSaved: (next: AdminSettings) => void }) {
  const toast = useUI((u) => u.toast);
  const shown = limitCents == null ? "" : (limitCents / 100).toFixed(2);
  const [draft, setDraft] = useState(shown);
  const [busy, setBusy] = useState(false);
  useEffect(() => setDraft(shown), [shown]);
  const cents = parseDollars(draft);
  const save = async (next: number | null) => {
    setBusy(true);
    try {
      onSaved(await adminApi.patchSettings({ storage_budget: next }));
      toast(tr_(next == null ? "settings.admin.limitRemoved" : "settings.admin.limitSaved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("settings.admin.couldNotChangeTheLimit"), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <h3 className={st.h5}>{tr_("settings.admin.fileStorage")}</h3>
      <p className={st.hint} style={{ margin: "0 0 12px" }}>{tr_("settings.admin.fileStorageHint")}</p>
      {meter ? (
        <div className={st.rows} style={{ margin: 0, background: "var(--bg-surface)" }} data-testid="admin-storage">
          <div className={st.row}>
            <span className={st.rowLabel}>
              {tr_("settings.admin.storageThisMonth")}
              {meter.held ? <span className={st.deviceSub} style={{ display: "block", fontWeight: 400, color: "var(--text-warning)" }}>{tr_("settings.admin.storagePaused")}</span> : null}
            </span>
            <span className={st.rowValue} style={{ fontVariantNumeric: "tabular-nums", ...(meter.held ? { color: "var(--text-warning)" } : {}) }}>
              {meter.limit_cents == null ? tr_("settings.admin.storageCostNoLimit", { cost: dollars(meter.cost_cents) }) : tr_("settings.admin.storageCost", { cost: dollars(meter.cost_cents), limit: dollars(meter.limit_cents) })}
            </span>
          </div>
          {meter.limit_cents != null ? (
            <span className={st.costBar} data-held={meter.held || undefined} aria-hidden="true">
              <i style={{ inlineSize: `${Math.min(100, (meter.cost_cents / Math.max(1, meter.limit_cents)) * 100)}%` }} />
            </span>
          ) : null}
          <div className={st.row}><span className={st.rowLabel}>{tr_("settings.admin.storageInBucket")}</span><span className={st.rowValue}>{formatBytes(meter.stored_bytes)}</span></div>
          <div className={st.row}><span className={st.rowLabel}>{tr_("settings.admin.storageWaiting")}</span><span className={st.rowValue}>{formatBytes(meter.waiting_bytes)}</span></div>
        </div>
      ) : <Spinner />}
      {meter ? <p className={st.hint} style={{ margin: "8px 0 0", fontSize: 13 }}>{meter.measured_at ? tr_("settings.admin.storageMeasured", { time: formatTime(meter.measured_at) }) : meter.measuring ? tr_("settings.admin.storageMeasuring") : tr_("settings.admin.storageEstimated")}</p> : null}
      <div style={{ marginTop: 16 }}>
        <Field label={tr_("settings.admin.monthlyLimit")} hint={tr_("settings.admin.monthlyLimitHint")}>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            <TextInput
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && cents != null && cents !== limitCents) void save(cents);
              }}
              placeholder={tr_("settings.admin.noLimit")}
              inputMode="decimal"
              maxLength={9}
              style={{ flex: "1 1 120px", minWidth: 0 }}
              data-testid="storage-limit"
            />
            <Button size="sm" disabled={busy || cents == null || cents === limitCents} onClick={() => cents != null && void save(cents)}>{tr_("chat.editMessage.save")}</Button>
            {limitCents != null ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void save(null)}>{tr_("settings.admin.removeLimit")}</Button> : null}
          </div>
        </Field>
      </div>
    </>
  );
}

function CallUsage({ usage }: { usage: NonNullable<AdminStats["realtime"]> }) {
  // gigabytes as the allowance is set in the config, in thousands rather than 1024s
  const gb = (n: number) => {
    try {
      return new Intl.NumberFormat(translator().locale, { style: "unit", unit: "gigabyte", maximumFractionDigits: n < 10e9 ? 1 : 0 }).format(n / 1e9);
    } catch {
      return `${Math.round(n / 1e9)} GB`;
    }
  };
  const warn = usage.calls_paused ? tr_("settings.admin.callsPaused") : usage.video_paused ? tr_("settings.admin.callsVideoPaused") : null;
  return (
    <div className={st.row} data-testid="admin-call-usage">
      <span className={st.rowLabel}>
        {tr_("settings.admin.callsThisMonth")}
        {warn ? <span className={st.deviceSub} style={{ display: "block", textTransform: "none", fontWeight: 400, color: "var(--text-warning)" }}>{warn}</span> : null}
      </span>
      <span className={st.rowValue} style={warn ? { color: "var(--text-warning)" } : undefined}>{tr_("settings.admin.callUsage", { used: gb(usage.used_bytes), limit: gb(usage.limit_bytes) })}</span>
    </div>
  );
}
