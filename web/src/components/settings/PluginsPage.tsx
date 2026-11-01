import { AlertTriangle, CheckCircle2, Code2, Download, Link2, Loader2, Pencil, Plus, Puzzle, RefreshCw, Settings2, ShieldCheck, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/common/Button";
import { Toggle } from "@/components/common/Toggle";
import { t } from "@/i18n";
import { codeKey, lookCache } from "@/lib/look/cache";
import { shortHash } from "@/lib/look/hash";
import { byteLength, unpackText } from "@/lib/look/pack";
import { checkForUpdate, dismissUpdate, retryPlugin, syncPlugins, usePlugins, type PluginState } from "@/lib/look/plugins/runtime";
import { useSafeMode } from "@/lib/look/safeMode";
import { MAX_PLUGINS, linkName, sanitizePlugins, sanitizeValues } from "@/lib/look/sanitize";
import type { PluginEntry } from "@/lib/look/types";
import { storage } from "@/lib/storage";
import { useAuth } from "@/stores/auth";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { AddPluginDialog } from "./look/AddPluginDialog";
import { Caution } from "./look/Caution";
import { GuideLink } from "./look/GuideLink";
import { PLUGIN_GUIDE_URL } from "@/lib/legal";
import { PluginSettingsForm } from "./look/PluginSettingsForm";
import { PluginUpdateDialog } from "./look/PluginUpdateDialog";
import { PermissionLedger } from "./look/PermissionLedger";
import { readDeclaration } from "@/lib/look/plugins/declaration";
import { inOrder } from "@/lib/look/plugins/permissions";
import look from "./look/Look.module.css";
import s from "./Settings.module.css";

function nameOf(p: PluginEntry, state: PluginState | undefined): string {
  return state?.meta?.name || p.meta?.name || (p.url ? linkName(p.url) : t("look.plugins.pastedName"));
}

function StateLine({ entry, state, safe, onRetry, onReview }: { entry: PluginEntry; state: PluginState | undefined; safe: boolean; onRetry: () => void; onReview: () => void }) {
  const status = state?.status ?? (entry.enabled ? "loading" : "off");
  if (!entry.enabled && !state?.update) return null;
  if (safe && entry.enabled && status !== "running")
    return (
      <div className={look.state} data-kind="error">
        <AlertTriangle size={15} aria-hidden="true" />
        <div className={look.stateBody}>{t("look.plugins.offInSafeMode")}</div>
      </div>
    );
  if (status === "loading")
    return (
      <div className={look.state}>
        <Loader2 size={15} aria-hidden="true" />
        <div className={look.stateBody}>{t("look.plugins.starting")}</div>
      </div>
    );
  if (status === "running")
    return (
      <div className={look.state} data-kind={state?.update ? "update" : "ok"} data-testid="plugin-state">
        {state?.update ? <Download size={15} aria-hidden="true" /> : <CheckCircle2 size={15} aria-hidden="true" />}
        <div className={look.stateBody}>
          {state?.update ? t("look.plugins.runningUpdate") : t("look.plugins.running")}
          {state?.clashes?.length ? ` ${t("look.plugins.clashes", { commands: state.clashes.map((c) => `/${c}`).join(", ") })}` : ""}
          {state?.update ? (
            <div>
              <Button size="sm" variant="secondary" onClick={onReview} data-testid="plugin-review">
                {t("look.plugins.review")}
              </Button>
            </div>
          ) : null}
        </div>
      </div>
    );
  if (status === "changed")
    return (
      <div className={look.state} data-kind="update" data-testid="plugin-state">
        <Download size={15} aria-hidden="true" />
        <div className={look.stateBody}>
          <strong>{t("look.plugins.updateAvailable")}</strong> {t("look.plugins.changedBody")}
          <div>
            <Button size="sm" variant="secondary" onClick={onReview} data-testid="plugin-review">
              {t("look.plugins.review")}
            </Button>
          </div>
        </div>
      </div>
    );
  if (status === "failed" || status === "unreachable" || status === "mismatch")
    return (
      <div className={look.state} data-kind="error" data-testid="plugin-state">
        <AlertTriangle size={15} aria-hidden="true" />
        <div className={look.stateBody}>
          {status === "failed" ? t("look.plugins.failed", { message: state?.error ?? "" }) : status === "unreachable" ? t("look.plugins.unreachable", { message: state?.error ?? "" }) : state?.error}
          <div>
            <Button size="sm" variant="secondary" onClick={onRetry} data-testid="plugin-retry">
              {t("look.plugins.tryAgain")}
            </Button>
          </div>
        </div>
      </div>
    );
  return null;
}

/**
 * Plugins: what is installed, whether each is running and why not, updates
 * waiting for a yes, and each plugin's own settings. The list and every
 * setting follow the account; each device keeps the code by its hash.
 */
export default function PluginsPage() {
  const raw = useSettings((x) => x.settings.plugins);
  const patch = useSettings((x) => x.patch);
  const list = useMemo(() => sanitizePlugins(raw), [raw]);
  const states = usePlugins((p) => p.byId);
  const mode = useSafeMode((m) => m.mode);
  const toast = useUI((u) => u.toast);
  const [adding, setAdding] = useState<"url" | "code" | null>(null);
  const [editing, setEditing] = useState<{ entry: PluginEntry; code: string } | null>(null);
  const [reviewing, setReviewing] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [checking, setChecking] = useState<string | null>(null);
  const [pasted, setPasted] = useState(0);

  const setList = (next: PluginEntry[]) => patch({ plugins: next });
  const [permsOpen, setPermsOpen] = useState<string | null>(null);
  const update = (id: string, change: Partial<PluginEntry>) => setList(list.map((p) => (p.id === id ? { ...p, ...change } : p)));

  const pastedTotal = async (except?: string) => {
    let total = 0;
    for (const p of list) if (p.source === "code" && p.id !== except) total += byteLength(await unpackText(p.code ?? "").catch(() => ""));
    return total;
  };

  const remove = (p: PluginEntry) =>
    useUI.getState().openModal("confirm", {
      title: t("look.plugins.removeTitle", { name: nameOf(p, states[p.id]) }),
      body: t("look.plugins.removeBody"),
      confirmLabel: t("look.plugins.remove"),
      danger: true,
      onConfirm: () => {
        setList(list.filter((x) => x.id !== p.id));
        // what this device kept for it goes too: its code and its own storage
        if (!list.some((x) => x.id !== p.id && x.hash === p.hash)) void lookCache.delete(codeKey(p.hash));
        storage.remove(`ec:plugin:${useAuth.getState().me?.id ?? "anon"}:${p.id}`);
      },
    });

  const check = async (p: PluginEntry) => {
    setChecking(p.id);
    try {
      const r = await checkForUpdate(p);
      if (r === "same") toast(t("look.plugins.upToDate", { name: nameOf(p, states[p.id]) }), "success");
      else if (r === "failed") toast(t("look.plugins.checkFailed"), "error");
      else setReviewing(p.id);
    } finally {
      setChecking(null);
    }
  };

  const retry = (p: PluginEntry) => {
    retryPlugin(p.id);
    void syncPlugins(list, mode === null, "safe");
  };

  const reviewEntry = list.find((p) => p.id === reviewing);
  const reviewUpdate = reviewEntry ? states[reviewEntry.id]?.update : undefined;

  return (
    <>
      <h2 data-setting="plugins" className={s.title}>{t("look.plugins.title")}</h2>
      <Caution level="danger" testId="plugins-caution">
        <p>
          <strong>{t("look.plugins.cautionLead")}</strong> {t("look.plugins.cautionBody")}
        </p>
        <p>{t("look.safe.howTo")}</p>
      </Caution>
      <GuideLink title={t("look.plugins.guideTitle")} body={t("look.plugins.guideBody")} href={PLUGIN_GUIDE_URL} testId="plugins-guide" />
      {mode ? (
        <div className={look.notice} role="status">
          <p className={look.noticeBody}>{mode === "manual" ? t("look.plugins.safeManual") : t("look.plugins.safeCrashes")}</p>
        </div>
      ) : null}
      <div className={look.sectionHead}>
        <h3 className={s.h5}>{t("look.plugins.installed")}</h3>
        {list.length < MAX_PLUGINS ? (
          <div className={look.addRow}>
            <Button size="sm" variant="secondary" onClick={() => void pastedTotal().then((n) => { setPasted(n); setAdding("url"); })} data-testid="plugin-open-url">
              <Link2 size={15} aria-hidden="true" /> {t("look.plugins.addFromLink")}
            </Button>
            <Button size="sm" variant="secondary" onClick={() => void pastedTotal().then((n) => { setPasted(n); setAdding("code"); })} data-testid="plugin-open-code">
              <Plus size={15} aria-hidden="true" /> {t("look.plugins.addFromPaste")}
            </Button>
          </div>
        ) : null}
      </div>
      {list.length === 0 ? (
        <div className={look.empty}>{t("look.plugins.empty")}</div>
      ) : (
        <div className={look.list}>
          {list.map((p) => {
            const state = states[p.id];
            const name = nameOf(p, state);
            const meta = state?.meta ?? p.meta;
            const schema = state?.status === "running" ? (state.schema ?? []) : [];
            return (
              <div key={p.id} className={look.item} data-testid="plugin-item" data-plugin={p.id}>
                <div className={look.itemTop}>
                  <Puzzle size={20} aria-hidden="true" style={{ flex: "none", marginTop: 2, color: "var(--icon)" }} />
                  <div className={look.itemMain}>
                    <div className={look.itemTitle}>
                      <span dir="auto">{name}</span>
                      {meta?.version ? <span className={look.itemVersion} dir="ltr">{meta.version}</span> : null}
                    </div>
                    <div className={look.itemSub}>
                      {meta?.author ? <span dir="auto">{t("look.plugins.by", { author: meta.author })} </span> : null}
                      {p.source === "url" ? t("look.plugins.fromSite", { site: (() => { try { return new URL(p.url ?? "").hostname; } catch { return ""; } })() }) : t("look.plugins.pasted")}
                    </div>
                    {meta?.description ? <div className={look.itemDesc} dir="auto">{meta.description}</div> : null}
                    <div className={look.itemUrl} title={t("look.plugins.fingerprintHint")}>
                      {t("look.plugins.fingerprint", { hash: shortHash(p.hash) })}
                    </div>
                    {!Array.isArray(p.permissions) && meta ? (
                      <div className={look.itemDesc} data-testid="plugin-full-access">
                        <span className={look.fullAccess}>{t("look.plugins.fullAccess")}:</span> {t("look.plugins.v1Warning")}
                      </div>
                    ) : null}
                  </div>
                  <div className={look.itemSwitch}>
                    <Toggle
                      checked={p.enabled}
                      onChange={(on) => {
                        if (on) retryPlugin(p.id);
                        update(p.id, { enabled: on });
                      }}
                      ariaLabel={t("look.plugins.turnOn", { name })}
                    />
                  </div>
                </div>
                <StateLine entry={p} state={state} safe={mode !== null} onRetry={() => retry(p)} onReview={() => setReviewing(p.id)} />
                <div className={look.itemActions}>
                  {schema.length ? (
                    <Button size="sm" variant="secondary" onClick={() => setOpen(open === p.id ? null : p.id)} aria-expanded={open === p.id} data-testid="plugin-settings-toggle">
                      <Settings2 size={14} aria-hidden="true" /> {t("look.plugins.settings")}
                    </Button>
                  ) : null}
                  {Array.isArray(p.permissions) ? (
                    <Button size="sm" variant="secondary" onClick={() => setPermsOpen(permsOpen === p.id ? null : p.id)} aria-expanded={permsOpen === p.id} data-testid="plugin-permissions-toggle">
                      <ShieldCheck size={14} aria-hidden="true" /> {t("look.plugins.permissions")}
                    </Button>
                  ) : null}
                  {p.source === "url" ? (
                    <Button size="sm" variant="secondary" onClick={() => void check(p)} loading={checking === p.id} data-testid="plugin-check">
                      <RefreshCw size={14} aria-hidden="true" /> {t("look.plugins.checkUpdate")}
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() =>
                        void Promise.all([unpackText(p.code ?? ""), pastedTotal(p.id)]).then(([code, others]) => {
                          setPasted(others);
                          setEditing({ entry: p, code });
                        }, () => toast(t("look.plugins.errorUnreadable"), "error"))
                      }
                      data-testid="plugin-edit"
                    >
                      <Pencil size={14} aria-hidden="true" /> {t("look.plugins.editCode")}
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => remove(p)} data-testid="plugin-remove">
                    <Trash2 size={14} aria-hidden="true" /> {t("look.plugins.remove")}
                  </Button>
                </div>
                {permsOpen === p.id && Array.isArray(p.permissions) ? <PluginPermissions entry={p} name={name} onChange={(permissions) => update(p.id, { permissions })} /> : null}
                {open === p.id && schema.length ? <PluginSettingsForm schema={schema} saved={p.settings} onChange={(values) => update(p.id, { settings: sanitizeValues(values) })} /> : null}
              </div>
            );
          })}
        </div>
      )}
      <p className={s.hint} style={{ marginTop: 16, display: "flex", gap: 6, alignItems: "flex-start" }}>
        <Code2 size={15} aria-hidden="true" style={{ flex: "none", marginTop: 2 }} />
        <span>{t("look.plugins.makeYourOwn")}</span>
      </p>
      {adding || editing ? (
        <AddPluginDialog
          initial={adding ?? "code"}
          editing={editing ?? undefined}
          pastedElsewhere={pasted}
          taken={list.filter((p) => p.url).map((p) => p.url!)}
          onClose={() => {
            setAdding(null);
            setEditing(null);
          }}
          onSave={(entry) => {
            retryPlugin(entry.id);
            setList(editing ? list.map((p) => (p.id === entry.id ? entry : p)) : [...list, entry]);
            toast(editing ? t("look.plugins.codeSaved", { name: entry.meta?.name ?? "" }) : t("look.plugins.added", { name: entry.meta?.name ?? "" }), "success");
            setAdding(null);
            setEditing(null);
          }}
        />
      ) : null}
      {reviewEntry && reviewUpdate ? (
        <PluginUpdateDialog
          entry={reviewEntry}
          name={nameOf(reviewEntry, states[reviewEntry.id])}
          update={reviewUpdate}
          onClose={() => setReviewing(null)}
          onKeep={() => {
            dismissUpdate(reviewEntry.id);
            setReviewing(null);
            toast(t("look.plugins.keptOld"), "info");
          }}
          onAccept={(permissions) => {
            retryPlugin(reviewEntry.id);
            dismissUpdate(reviewEntry.id);
            // the new hash is the approval, on every device of the account, and with it what the
            // dialog showed the new code asking for (read from its text, without running it)
            update(reviewEntry.id, { hash: reviewUpdate.hash, enabled: true, ...(permissions ? { permissions } : {}) });
            setReviewing(null);
            toast(t("look.plugins.updated", { name: nameOf(reviewEntry, states[reviewEntry.id]) }), "success");
          }}
        />
      ) : null}
    </>
  );
}

/**
 * A v2 plugin's permissions, each with a switch: what it was granted, and
 * what its code asks for that it was not (read from the code as this device
 * keeps it, without running it). Switching one off takes it away at once,
 * from the running plugin too; one it asks for can be given here.
 */
function PluginPermissions({ entry, name, onChange }: { entry: PluginEntry; name: string; onChange: (permissions: string[]) => void }) {
  const [asked, setAsked] = useState<string[] | null>(null);
  useEffect(() => {
    let live = true;
    const text = entry.source === "code" ? unpackText(entry.code ?? "") : lookCache.get(codeKey(entry.hash)).then((f) => f?.text ?? "");
    void text.then(
      (code) => live && setAsked(readDeclaration(code).permissions ?? []),
      () => live && setAsked([]),
    );
    return () => {
      live = false;
    };
  }, [entry.source, entry.code, entry.hash]);
  const granted = new Set(entry.permissions ?? []);
  const shown = inOrder([...granted, ...(asked ?? [])]);
  return (
    <div className={look.permsPanel} data-testid="plugin-permissions">
      {shown.length ? (
        <>
          <p className={look.ledgerNote}>{t("look.plugins.permsEditLead")}</p>
          <PermissionLedger
            permissions={shown}
            switches={{ granted, plugin: name, onChange: (perm, on) => onChange(inOrder(on ? [...granted, perm] : [...granted].filter((x) => x !== perm))) }}
          />
        </>
      ) : asked !== null ? (
        <p className={look.ledgerNote}>{t("look.plugins.permsNone")}</p>
      ) : null}
    </div>
  );
}
