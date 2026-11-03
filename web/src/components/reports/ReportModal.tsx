import { ArrowLeft, Check, CircleCheck, ImagePlus, Link2, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { MessageItem } from "@/components/chat/MessageItem";
import type { DecryptedMessage } from "@/lib/decrypt";
import { Markdown } from "@/markdown";
import { useRenderCtx } from "@/lib/markdownCtx";
import { ReportTargetCard } from "./ReportSnapshot";
import { ReasonGlyph } from "./ReasonGlyph";
import { REPORT_KINDS, REPORT_REASONS, reports, type ReportKind, type ReportTarget, type ReportedMessage } from "@/api/reports";
import type { BugFile } from "@/api/bugs";
import { users as usersApi } from "@/api/users";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Select } from "@/components/common/Select";
import { Field, TextArea, TextInput } from "@/components/common/TextInput";
import { describeError } from "@/lib/errors";
import { appLink } from "@/lib/appLinks";
import { reasonTone } from "@/lib/reportReasons";
import { fullReportedMessage, type ReportContext } from "@/lib/reporting";
import { openBlockDialog } from "@/lib/friendship";
import { messages } from "@/api/messages";
import { decryptForStore } from "@/lib/decrypt";
import { useAuth } from "@/stores/auth";
import { useRelationships } from "@/stores/relationships";
import { useMyReports } from "@/stores/myReports";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import { t } from "@/i18n";
import s from "./Reports.module.css";

type Step = "start" | "reason" | "details" | "sent";

/** Screenshots go with "something else" reports only, where nothing else is captured. */
const MAX_FILES = 5;
const MAX_TOTAL = 100 << 20;

/**
 * Report Content: what is being reported, why (one press on a reason), what
 * happened, then a receipt that says where the answer will come. Opened from an
 * item's menu it starts at the reasons; from Settings it starts by asking what
 * to report (a link, an ID, or something with neither).
 */
export default function ReportModal({ context = {}, onClose }: { context?: ReportContext; onClose: () => void }) {
  const renderCtx = useRenderCtx();
  const [step, setStep] = useState<Step>(context.kind ? "reason" : "start");
  const [kind, setKind] = useState<ReportKind | null>(context.kind ?? null);
  const [id, setId] = useState(context.id ?? "");
  const [target, setTarget] = useState<ReportTarget | null>(null);
  const [reason, setReason] = useState<string | null>(null);
  const [description, setDescription] = useState("");
  const [message, setMessage] = useState<ReportedMessage | null>(null);
  const [preview, setPreview] = useState<DecryptedMessage | null>(null);
  const [files, setFiles] = useState<(BugFile & { local: string })[]>([]);
  const [uploading, setUploading] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const requestId = useRef(crypto.randomUUID());
  const sendLock = useRef(false);
  const me = useAuth((a) => a.me?.id);

  useEffect(() => {
    setTarget(null);
    setMessage(null);
    setPreview(null);
    setError("");
    if (!kind || (kind !== "other" && !/^\d{1,20}$/.test(id))) return;
    const controller = new AbortController();
    setLoading(true);
    reports
      .target(kind, id || "0", context.serverId, controller.signal)
      .then(async (value) => {
        if (kind === "message") {
          const opened = await decryptForStore(await messages.get(String(value.snapshot.channel_id), value.id));
          if (controller.signal.aborted) return;
          const full = fullReportedMessage(opened);
          if (!full) throw new Error(t("reports.messageUnavailable"));
          setMessage(full);
          setPreview(opened);
        }
        if (!controller.signal.aborted) setTarget(value);
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setError(describeError(err, t("reports.failed")));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [kind, id, context.serverId, retry]);

  const reasons = target?.reasons ?? REPORT_REASONS.filter((r) => r !== "sexual");
  const evidence = kind === "message" ? "" : (context.evidence ?? []).map((item) => item.text).join("\n\n");
  const valid = !!target && !!reason && reasons.includes(reason) && (kind !== "message" || !!message) && description.trim().length >= 3 && evidence.length <= 16000 && uploading === 0;

  // who the report is about, for the receipt's Block button
  const subjectId = target && (kind === "user" || kind === "message") && target.subject_id !== "0" && target.subject_id !== me ? target.subject_id : null;
  const subject = useUsers((u) => (subjectId ? u.byId[subjectId] : undefined));
  const blocked = useRelationships((r) => (subjectId ? r.byUser[subjectId]?.type === "blocked" : false));
  const label = useMemo(() => targetName(target, preview), [target, preview]);

  const send = async () => {
    if (!valid || sendLock.current || !target || !kind || !reason) return;
    sendLock.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await reports.send({
        request_id: requestId.current,
        ...(kind === "message" && message ? { message } : {}),
        target_kind: kind,
        target_id: target.id,
        ...(context.serverId ? { server_id: context.serverId } : {}),
        part: kind === "other" ? "custom" : "whole",
        custom_part: kind === "other" ? description.trim().slice(0, 120) : "",
        reason,
        description,
        excerpt: evidence,
        files: files.map((f) => ({ id: f.id, name: f.name })),
      });
      useMyReports.getState().onUpdate(result.report);
      setStep("sent");
    } catch (err) {
      setError(describeError(err, t("reports.failed")));
    } finally {
      sendLock.current = false;
      setBusy(false);
    }
  };

  const addFiles = async (picked: FileList | null) => {
    if (!picked) return;
    const room = MAX_FILES - files.length;
    const list = [...picked].slice(0, room);
    let total = files.reduce((n, f) => n + f.size, 0);
    for (const file of list) {
      total += file.size;
      if (total > MAX_TOTAL) {
        setError(t("reports.screenshotsTooBig"));
        break;
      }
      setUploading((n) => n + 1);
      try {
        const up = await reports.upload(file);
        setFiles((cur) => [...cur, { ...up, local: URL.createObjectURL(file) }]);
      } catch (err) {
        setError(describeError(err, t("reports.failed")));
      } finally {
        setUploading((n) => n - 1);
      }
    }
  };
  useEffect(() => () => files.forEach((f) => URL.revokeObjectURL(f.local)), []); // eslint-disable-line react-hooks/exhaustive-deps

  const close = () => {
    if (!busy) onClose();
  };
  const footer =
    step === "details" ? (
      <Button className={s.sendButton} onClick={() => void send()} disabled={!valid} loading={busy} data-testid="report-send">
        {t("reports.send")}
      </Button>
    ) : step === "sent" ? (
      <div className={s.sentActions}>
        <Button
          variant="secondary"
          className={s.sendButton}
          onClick={() => {
            onClose();
            useUI.getState().openModal("settings", { page: "reports" });
          }}
        >
          {t("reports.yours.view")}
        </Button>
        <Button className={s.sendButton} onClick={onClose}>
          {t("reports.done")}
        </Button>
      </div>
    ) : undefined;

  return (
    <Modal open onClose={close} title={step === "sent" ? undefined : t("reports.report")} ariaLabel={t("reports.report")} width={560} closeOnOverlay={!busy} closeButton={!busy} swipe={!busy} footer={footer}>
      <div className={s.form} data-testid="content-report-form" data-step={step}>
        {step === "start" && (
          <StartStep
            onPick={(k, value) => {
              setKind(k);
              setId(value);
              setReason(null);
              setStep("reason");
            }}
          />
        )}

        {(step === "reason" || step === "details") && (
          <>
            {!context.kind && (
              <button type="button" className={s.stepBack} onClick={() => (step === "details" ? setStep("reason") : setStep("start"))} disabled={busy}>
                <ArrowLeft size={18} aria-hidden="true" />
                {t("reports.stepBack")}
              </button>
            )}
            {kind !== "other" && kind !== "message" && target && <ReportTargetCard kind={kind ?? "other"} snapshot={target.snapshot} />}
            {kind === "message" && preview && step === "reason" && <MessagePreview msg={preview} compact />}
            {target?.already_reported && (
              <p className={s.callout} role="note" data-testid="already-reported">
                {t("reports.alreadyReported")}
              </p>
            )}
          </>
        )}

        {step === "reason" && (
          <section className={s.reasonStep} aria-labelledby="report-reason-title">
            <h3 id="report-reason-title" className={s.stepTitle}>
              {t("reports.whatsWrong")}
            </h3>
            <ul className={s.reasons} role="list">
              {reasons.map((r) => (
                <li key={r}>
                  <button
                    type="button"
                    className={s.reasonRow}
                    aria-pressed={reason === r}
                    disabled={!target}
                    data-testid={`report-reason-${r}`}
                    onClick={() => {
                      setReason(r);
                      setStep("details");
                    }}
                  >
                    <ReasonGlyph reason={r} />
                    <span className={s.reasonText}>
                      <span className={s.reasonTitle}>{t(`reports.reason.${r}`)}</span>
                      <span className={s.reasonHint}>{t(`reports.reasonHint.${r}`)}</span>
                    </span>
                    {reason === r && <Check size={18} className={s.reasonCheck} aria-hidden="true" />}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        {step === "details" && reason && (
          <>
            <div className={s.chosenReason}>
              <ReasonGlyph reason={reason} />
              <span className={s.reasonTitle}>{t(`reports.reason.${reason}`)}</span>
              <Button variant="ghost" size="sm" onClick={() => setStep("reason")} disabled={busy}>
                {t("reports.change")}
              </Button>
            </div>
            {reasonTone(reason) === "urgent" && (
              <p className={s.urgent} role="note" data-testid="report-urgent">
                {t(reason === "self_harm" ? "reports.urgent.selfHarm" : "reports.urgent.danger")}
              </p>
            )}
            {kind === "message" && preview && (
              <section className={s.shared} aria-label={t("reports.sharedWithStaff")}>
                <h3 className={s.smallHeading}>{t("reports.sharedWithStaff")}</h3>
                <MessagePreview msg={preview} />
              </section>
            )}
            {kind !== "message" && !!context.evidence?.length && (
              <section className={s.shared} aria-label={t("reports.sharedWithStaff")}>
                <h3 className={s.smallHeading}>{t("reports.sharedWithStaff")}</h3>
                {context.evidence.map((item, i) => (
                  <Markdown key={i} source={item.text} ctx={renderCtx} />
                ))}
              </section>
            )}
            <Field label={t("reports.description")} hint={t("reports.descriptionHint")} required>
              <TextArea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={4000} rows={5} disabled={busy} data-testid="report-description" autoFocus />
            </Field>
            {kind === "other" && (
              <div className={s.screenshots}>
                <span className={s.smallHeading}>{t("reports.screenshots")}</span>
                <span className={s.notice}>{t("reports.screenshotsHint")}</span>
                <div className={s.fileRow}>
                  {files.map((f) => (
                    <figure key={f.id} className={s.fileThumb}>
                      {f.mime.startsWith("video/") ? <video src={f.local} muted /> : <img src={f.local} alt={f.name} />}
                      <button
                        type="button"
                        className={s.fileRemove}
                        aria-label={t("reports.removeFile", { name: f.name })}
                        onClick={() => {
                          URL.revokeObjectURL(f.local);
                          setFiles((cur) => cur.filter((x) => x.id !== f.id));
                        }}
                        disabled={busy}
                      >
                        <X size={14} aria-hidden="true" />
                      </button>
                    </figure>
                  ))}
                  {files.length < MAX_FILES && (
                    <label className={s.fileAdd} data-busy={uploading > 0 || undefined}>
                      <ImagePlus size={20} aria-hidden="true" />
                      <span>{uploading > 0 ? t("reports.uploading") : t("reports.addScreenshot")}</span>
                      <input
                        type="file"
                        accept="image/png,image/jpeg,image/gif,image/webp,video/mp4,video/webm"
                        multiple
                        disabled={busy || uploading > 0}
                        onChange={(e) => {
                          void addFiles(e.target.files);
                          e.target.value = "";
                        }}
                      />
                    </label>
                  )}
                </div>
              </div>
            )}
            <p className={s.notice}>{t(kind === "message" ? "reports.fullMessageNotice" : "reports.privacy")}</p>
          </>
        )}

        {step === "sent" && (
          <div className={s.sent} data-testid="report-sent">
            <CircleCheck size={44} className={s.sentMark} aria-hidden="true" />
            <h2 className={s.sentTitle}>{t("reports.sent")}</h2>
            <p>{t("reports.sentBody")}</p>
            <p className={s.notice}>{t("reports.sentPrivate")}</p>
            {subjectId && !blocked && (
              <div className={s.blockOffer}>
                <p className={s.notice}>{t("reports.blockHint")}</p>
                <Button variant="danger" onClick={() => openBlockDialog(subjectId)}>
                  {t("reports.blockPerson", { name: subject ? displayName(subject) : label })}
                </Button>
              </div>
            )}
          </div>
        )}

        {loading && step !== "start" && <p role="status" className={s.notice}>{t("reports.loading")}</p>}
        {evidence.length > 16000 && (
          <p className={s.error} role="alert">
            {t("reports.evidenceTooLong")}
          </p>
        )}
        {error && (
          <div className={s.error} role="alert">
            <span>{error}</span>
            {!target && kind && (
              <Button variant="link" onClick={() => setRetry((n) => n + 1)}>
                {t("reports.retry")}
              </Button>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}

/** The chat row as the reader saw it, inert. */
function MessagePreview({ msg, compact = false }: { msg: DecryptedMessage; compact?: boolean }) {
  return (
    <div className={s.chatPreview} data-compact={compact || undefined} ref={(el) => el?.setAttribute("inert", "")}>
      <MessageItem msg={msg} groupStart preview />
    </div>
  );
}

/** The name the receipt and Block button use for what was reported. */
function targetName(target: ReportTarget | null, preview: DecryptedMessage | null): string {
  const snap = target?.snapshot ?? {};
  for (const key of ["display_name", "username", "name"]) if (typeof snap[key] === "string" && snap[key]) return snap[key] as string;
  if (preview) {
    const author = useUsers.getState().byId[preview.author_id];
    if (author) return displayName(author);
  }
  return "";
}

/**
 * From Settings: what to report. A link to it is the easy way (a message,
 * channel, profile or pack); something with neither a link nor an ID can still
 * be described; and an ID works for the rest.
 */
function StartStep({ onPick }: { onPick: (kind: ReportKind, id: string) => void }) {
  const [link, setLink] = useState("");
  const [linkError, setLinkError] = useState("");
  const [checking, setChecking] = useState(false);
  const [kind, setKind] = useState<ReportKind>("message");
  const [id, setId] = useState("");

  const useLink = async () => {
    setLinkError("");
    const found = appLink(link.trim());
    const path = found?.kind === "route" ? found.path.split(/[?#]/)[0]! : "";
    const message = /^\/channels\/(?:@me|\d{1,20})\/(\d{1,20})\/(\d{1,20})\/?$/.exec(path);
    const channel = /^\/channels\/(?:@me|\d{1,20})\/(\d{1,20})\/?$/.exec(path);
    const pack = /^\/stickers\/pack\/(\d{1,20})\/?$/.exec(path);
    if (message) return onPick("message", message[2]!);
    if (channel) return onPick("channel", channel[1]!);
    if (pack) return onPick("pack", pack[1]!);
    if (found?.kind === "profile") {
      setChecking(true);
      try {
        const user = await usersApi.shared(found.username);
        return onPick("user", user.id);
      } catch (err) {
        setLinkError(describeError(err, t("reports.start.badLink")));
      } finally {
        setChecking(false);
      }
      return;
    }
    setLinkError(t("reports.start.badLink"));
  };

  return (
    <div className={s.start}>
      <p className={s.lead}>{t("reports.start.intro")}</p>
      <form
        className={s.linkForm}
        onSubmit={(e) => {
          e.preventDefault();
          void useLink();
        }}
      >
        <Field label={t("reports.start.link")} error={linkError || undefined}>
          <TextInput value={link} onChange={(e) => setLink(e.target.value)} placeholder={t("reports.start.linkPlaceholder")} maxLength={500} inputMode="url" data-testid="report-link" />
        </Field>
        <Button type="submit" variant="secondary" disabled={!link.trim()} loading={checking}>
          <Link2 size={16} aria-hidden="true" />
          {t("reports.start.continue")}
        </Button>
      </form>
      <button type="button" className={s.elseRow} onClick={() => onPick("other", "")} data-testid="report-something-else">
        <span className={s.reasonTitle}>{t("reports.start.somethingElse")}</span>
        <span className={s.reasonHint}>{t("reports.start.somethingElseHint")}</span>
      </button>
      <details className={s.byId}>
        <summary>{t("reports.start.byId")}</summary>
        <div className={s.byIdBody}>
          <Field label={t("reports.target")}>
            <Select value={kind} onChange={(e) => setKind(e.target.value as ReportKind)}>
              {REPORT_KINDS.filter((k) => k !== "other").map((k) => (
                <option key={k} value={k}>
                  {t(`reports.kind.${k}`)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("reports.targetId")}>
            <TextInput value={id} onChange={(e) => setId(e.target.value.trim())} maxLength={20} inputMode="numeric" />
          </Field>
          <Button variant="secondary" disabled={!/^\d{1,20}$/.test(id)} onClick={() => onPick(kind, id)}>
            {t("reports.start.continue")}
          </Button>
        </div>
      </details>
    </div>
  );
}
