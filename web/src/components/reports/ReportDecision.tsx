import { Check } from "lucide-react";
import { useState } from "react";
import { reports, type ReportDetail } from "@/api/reports";
import { moderation, type PackField, type ResetField, type ServerField } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { TextArea } from "@/components/common/TextInput";
import { describeError } from "@/lib/errors";
import { formatSpan, SPAN } from "@/lib/moderation";
import { atLeast, MOD_MAX_MS, useMyRank } from "@/lib/staffRank";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import s from "./Reports.module.css";

const closing = (status: string) => status === "actioned" || status === "dismissed";

/** Something a report's Take Action can take down, by kind of content. */
interface Removal {
  id: string;
  label: string;
  /** The label already says what happens ("Remove this message"), rather than naming a part. */
  says?: boolean;
}

const PROFILE_FIELDS: { id: string; key: string }[] = [
  { id: "avatar", key: "moderation.field.avatar" },
  { id: "banner", key: "moderation.field.banner" },
  { id: "display_name", key: "moderation.field.displayName" },
  { id: "username", key: "moderation.field.username" },
  { id: "about_me", key: "moderation.field.aboutMe" },
  { id: "pronouns", key: "moderation.field.pronouns" },
  { id: "status", key: "moderation.field.status" },
  { id: "widgets", key: "moderation.field.widgets" },
  { id: "name_style", key: "moderation.field.nameStyle" },
  { id: "typing_style", key: "moderation.field.typingStyle" },
  { id: "server_profiles", key: "moderation.field.serverProfiles" },
];
const SERVER_FIELDS: { id: ServerField; key: string }[] = [
  { id: "icon", key: "moderation.field.serverIcon" },
  { id: "banner", key: "moderation.field.serverBanner" },
  { id: "name", key: "moderation.field.serverName" },
  { id: "description", key: "moderation.field.serverDescription" },
  { id: "tag", key: "moderation.field.serverTag" },
  { id: "discovery", key: "moderation.field.serverDiscovery" },
];

/** What can be taken down for this report, and what starts picked: the part people reported. */
function removalsFor(d: ReportDetail): { options: Removal[]; picked: string[]; single?: boolean } {
  const v = d.report;
  switch (v.target_kind) {
    case "message":
      return { options: [{ id: "message", label: t("reports.act.removeMessage"), says: true }], picked: ["message"] };
    case "user": {
      const part = v.part === "server_profile" ? "server_profiles" : v.part;
      return { options: PROFILE_FIELDS.map((f) => ({ id: f.id, label: t(f.key) })), picked: PROFILE_FIELDS.some((f) => f.id === part) ? [part] : [] };
    }
    case "server":
      return { options: SERVER_FIELDS.map((f) => ({ id: f.id, label: t(f.key) })), picked: SERVER_FIELDS.some((f) => f.id === v.part) ? [v.part] : [] };
    case "pack":
      return { options: [{ id: "delete", label: t("reports.act.removePack"), says: true }, { id: "private", label: t("reports.act.packPrivate"), says: true }], picked: [], single: true };
    case "sticker":
      return v.snapshot.pack_id ? { options: [{ id: "sticker", label: t("reports.act.removeSticker"), says: true }], picked: ["sticker"] } : { options: [], picked: [] };
    case "emoji":
      return v.server_id !== "0" ? { options: [{ id: "emoji", label: t("reports.act.removeEmoji"), says: true }], picked: ["emoji"] } : { options: [], picked: [] };
    default:
      return { options: [], picked: [] };
  }
}

type Restrict = "none" | "mute" | "ban";
const MUTE_SPANS = [SPAN.HOUR, SPAN.DAY, 3 * SPAN.DAY, SPAN.WEEK, 30 * SPAN.DAY];
const BAN_SPANS = [SPAN.DAY, 3 * SPAN.DAY, SPAN.WEEK, 30 * SPAN.DAY, 0];

/**
 * Take Action, opened in place under the reported content: what to take down
 * (the reported part already picked), what to do to the account, the reason
 * they will read, and what the reporter is told. One press does all of it,
 * opens the cases, and closes this report and the others about the same item.
 */
export function ActionPanel({ detail, others, onCancel, onDone, onChanged }: { detail: ReportDetail; others: number; onCancel: () => void; onDone: () => void; onChanged: () => void }) {
  const v = detail.report;
  const rank = useMyRank();
  const me = useAuth((a) => a.me?.id ?? "0");
  const admin = atLeast(rank, "admin");
  const removals = removalsFor(detail);
  const [picked, setPicked] = useState<Set<string>>(() => new Set(admin ? removals.picked : []));
  const subject = detail.subject;
  const canRestrict = !!subject && !subject.blocked;
  const [restrict, setRestrict] = useState<Restrict>("none");
  const spans = (restrict === "ban" ? BAN_SPANS : MUTE_SPANS).filter((ms) => admin || (ms > 0 && ms <= MOD_MAX_MS));
  const [span, setSpan] = useState(SPAN.DAY);
  const [reason, setReason] = useState(() => t(`reports.reason.${v.reason}`));
  const [telling, setTelling] = useState(false);
  const [told, setTold] = useState("");
  const [alsoRelated, setAlsoRelated] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const flip = (id: string) =>
    setPicked((cur) => {
      const next = new Set(removals.single ? [] : cur);
      if (cur.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const pickRestrict = (r: Restrict) => {
    setRestrict(r);
    const list = (r === "ban" ? BAN_SPANS : MUTE_SPANS).filter((ms) => admin || (ms > 0 && ms <= MOD_MAX_MS));
    if (!list.includes(span)) setSpan(list.includes(SPAN.DAY) ? SPAN.DAY : list[0]!);
  };
  const name = subject ? subject.display_name || subject.username : "";
  const doing = picked.size > 0 || restrict !== "none";
  const reasonOk = reason.trim().length >= 3;

  // what one press will do, in plain words, before it is pressed
  const will: string[] = [];
  for (const id of picked) {
    const o = removals.options.find((x) => x.id === id);
    if (o) will.push(o.says ? o.label : t("reports.act.willRemove", { what: o.label }));
  }
  if (restrict !== "none") will.push(restrict === "mute" ? t("reports.act.willMute", { name, span: formatSpan(span) }) : span ? t("reports.act.willBan", { name, span: formatSpan(span) }) : t("reports.act.willBanForever", { name }));
  will.push(t("reports.act.willClose", { count: 1 + (alsoRelated || picked.has("message") ? others : 0) }));

  const submit = async () => {
    if (busy || !reasonOk) return;
    setBusy(true);
    setError("");
    const why = reason.trim();
    const cases: number[] = [];
    let steps = 0;
    try {
      let closed = false;
      if (picked.has("message")) {
        await reports.removeMessage(v.id, v.version, why, told.trim());
        closed = true;
        steps++;
      }
      if (v.target_kind === "user") {
        const images = { avatar: picked.has("avatar"), banner: picked.has("banner") };
        if (images.avatar || images.banner) {
          cases.push((await moderation.act(v.subject_id, { action: "avatar_remove", reason: why, images })).case.number);
          steps++;
        }
        const fields = [...picked].filter((f) => f !== "avatar" && f !== "banner") as ResetField[];
        if (fields.length) {
          cases.push((await moderation.act(v.subject_id, { action: "field_reset", reason: why, fields })).case.number);
          steps++;
        }
      }
      if (v.target_kind === "server" && picked.size) {
        cases.push((await moderation.actOnServer(v.target_id, { fields: [...picked] as ServerField[], reason: why })).case.number);
        steps++;
      }
      if (v.target_kind === "pack" && picked.size) {
        const body = picked.has("delete") ? { delete: true, reason: why } : { fields: ["public"] as PackField[], reason: why };
        cases.push((await moderation.actOnPack(v.target_id, body)).case.number);
        steps++;
      }
      if (picked.has("sticker")) {
        cases.push((await moderation.actOnPack(String(v.snapshot.pack_id), { stickers: [v.target_id], reason: why })).case.number);
        steps++;
      }
      if (picked.has("emoji")) {
        cases.push((await moderation.actOnEmojis(v.server_id, { emojis: [v.target_id], block: false, reason: why })).case.number);
        steps++;
      }
      if (restrict !== "none") {
        const r = await moderation.act(v.subject_id, { action: restrict, reason: why, ...(span ? { duration_ms: span } : { permanent: true }) });
        cases.push(r.case.number);
        steps++;
      }
      if (!closed) {
        // the actions above leave the report as it was; it closes with what was done in its note
        const fresh = await reports.detail(v.id);
        if (!closing(fresh.report.status)) {
          const recorded = cases.length ? `\n${cases.map((n) => t("reports.caseRecorded", { number: n })).join("\n")}` : "";
          await reports.review(v.id, {
            version: fresh.report.version,
            status: "actioned",
            note: `${why}${recorded}`,
            assigned_to: fresh.report.assigned_to === "0" ? me : fresh.report.assigned_to,
            reporter_message: told.trim(),
            resolve_related: others > 0 && alsoRelated,
          });
        }
      }
      useUI.getState().toast(t("reports.act.done"), "success");
      onDone();
    } catch (err) {
      const said = describeError(err, t("reports.failed"));
      setError(steps ? t("reports.act.partly", { error: said }) : said);
      if (steps) onChanged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={s.decide} data-kind="act" data-testid="report-act-panel">
      <h3 className={s.decideTitle}>{t("reports.act.takeAction")}</h3>

      <fieldset className={s.decideGroup} disabled={busy}>
        <legend>{t("reports.act.takeDown")}</legend>
        {removals.options.length === 0 ? (
          <p className={s.muted}>{t("reports.act.nothingToRemove")}</p>
        ) : !admin ? (
          <p className={s.muted}>{t("reports.act.adminOnly")}</p>
        ) : (
          <div className={s.pickRow}>
            {removals.options.map((o) => (
              <button key={o.id} type="button" className={s.pick} aria-pressed={picked.has(o.id)} onClick={() => flip(o.id)} data-testid={`act-remove-${o.id}`}>
                {picked.has(o.id) ? <Check size={14} strokeWidth={3} aria-hidden="true" /> : null}
                {o.label}
              </button>
            ))}
          </div>
        )}
      </fieldset>

      {canRestrict ? (
        <fieldset className={s.decideGroup} disabled={busy}>
          <legend>{t("reports.act.account", { name })}</legend>
          <div className={s.segment} role="radiogroup" aria-label={t("reports.act.account", { name })} data-glide="">
            {(["none", "mute", "ban"] as Restrict[]).map((r) => (
              <button key={r} type="button" role="radio" aria-checked={restrict === r} className={s.segmentItem} onClick={() => pickRestrict(r)} data-testid={`act-restrict-${r}`}>
                {t(`reports.act.restrict.${r}`)}
              </button>
            ))}
          </div>
          {restrict !== "none" ? (
            <div className={s.pickRow} role="radiogroup" aria-label={t("reports.act.howLong")}>
              {spans.map((ms) => (
                <button key={ms} type="button" role="radio" aria-checked={span === ms} className={s.pick} onClick={() => setSpan(ms)}>
                  {ms ? formatSpan(ms) : t("reports.act.permanent")}
                </button>
              ))}
            </div>
          ) : null}
          {subject?.ban || subject?.mute ? <p className={s.muted}>{subject.ban ? t("reports.act.alreadyBanned") : t("reports.act.alreadyMuted")}</p> : null}
        </fieldset>
      ) : null}

      <label className={s.decideField}>
        <span className={s.decideLabel}>{t("moderation.form.reason")}</span>
        <TextArea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={1000} disabled={busy} data-testid="act-reason" />
        <span className={s.muted}>{doing ? t("moderation.form.reasonVisible") : t("reports.act.reasonNoteOnly")}</span>
      </label>

      {telling ? (
        <label className={s.decideField}>
          <span className={s.decideLabel}>{t("reports.decision.told")}</span>
          <TextArea value={told} onChange={(e) => setTold(e.target.value)} rows={2} maxLength={1000} disabled={busy} placeholder={t("reports.outcome.actioned")} data-testid="report-told" autoFocus />
          <span className={s.muted}>{t("reports.decision.toldHint")}</span>
        </label>
      ) : (
        <button type="button" className={s.linkish} onClick={() => setTelling(true)}>
          {t("reports.act.tellReporter")}
        </button>
      )}

      {others > 0 && !picked.has("message") ? (
        <label className={s.check}>
          <input type="checkbox" checked={alsoRelated} onChange={(e) => setAlsoRelated(e.target.checked)} disabled={busy} />
          <span>{t("reports.decision.alsoRelated", { count: others })}</span>
        </label>
      ) : null}

      <ul className={s.willList} aria-label={t("reports.act.whatHappens")}>
        {will.map((w) => (
          <li key={w}>{w}</li>
        ))}
      </ul>

      {error ? (
        <p role="alert" className={s.error}>
          {error}
        </p>
      ) : null}
      <div className={s.decideFoot}>
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          {t("reports.decision.cancel")}
        </Button>
        <Button variant={doing ? "danger" : "primary"} loading={busy} disabled={!reasonOk} onClick={() => void submit()} data-testid="act-confirm">
          {doing ? t("reports.act.takeAction") : t("reports.act.closeActioned")}
        </Button>
      </div>
    </div>
  );
}

const DISMISS_REASONS = ["notAgainstRules", "notEnough", "handled", "duplicate"] as const;

/** Dismiss in a tap or two: a common reason (which is the note), and anything to add. */
export function DismissPanel({ detail, others, onCancel, onDone }: { detail: ReportDetail; others: number; onCancel: () => void; onDone: () => void }) {
  const v = detail.report;
  const me = useAuth((a) => a.me?.id ?? "0");
  const [why, setWhy] = useState<string>("notAgainstRules");
  const [extra, setExtra] = useState("");
  const [telling, setTelling] = useState(false);
  const [told, setTold] = useState("");
  const [alsoRelated, setAlsoRelated] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const note = [why ? t(`reports.dismiss.${why}`) : "", extra.trim()].filter(Boolean).join("\n");

  const submit = async () => {
    if (busy || note.length < 3) return;
    setBusy(true);
    setError("");
    try {
      await reports.review(v.id, {
        version: v.version,
        status: "dismissed",
        note,
        assigned_to: v.assigned_to === "0" ? me : v.assigned_to,
        reporter_message: told.trim(),
        resolve_related: others > 0 && alsoRelated,
      });
      useUI.getState().toast(t("reports.dismiss.done"), "success");
      onDone();
    } catch (err) {
      setError(describeError(err, t("reports.failed")));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={s.decide} data-kind="dismiss" data-testid="report-dismiss-panel">
      <h3 className={s.decideTitle}>{t("reports.act.dismiss")}</h3>
      <div className={s.pickRow} role="radiogroup" aria-label={t("reports.dismiss.why")}>
        {DISMISS_REASONS.map((r) => (
          <button key={r} type="button" role="radio" aria-checked={why === r} className={s.pick} onClick={() => setWhy(why === r ? "" : r)} disabled={busy} data-testid={`dismiss-${r}`}>
            {t(`reports.dismiss.${r}`)}
          </button>
        ))}
      </div>
      <label className={s.decideField}>
        <span className={s.decideLabel}>{t("reports.staffNote")}</span>
        <TextArea value={extra} onChange={(e) => setExtra(e.target.value)} rows={2} maxLength={1800} disabled={busy} placeholder={t("reports.dismiss.addHint")} data-testid="dismiss-note" />
      </label>
      {telling ? (
        <label className={s.decideField}>
          <span className={s.decideLabel}>{t("reports.decision.told")}</span>
          <TextArea value={told} onChange={(e) => setTold(e.target.value)} rows={2} maxLength={1000} disabled={busy} placeholder={t("reports.outcome.dismissed")} autoFocus />
          <span className={s.muted}>{t("reports.decision.toldHint")}</span>
        </label>
      ) : (
        <button type="button" className={s.linkish} onClick={() => setTelling(true)}>
          {t("reports.act.tellReporter")}
        </button>
      )}
      {others > 0 ? (
        <label className={s.check}>
          <input type="checkbox" checked={alsoRelated} onChange={(e) => setAlsoRelated(e.target.checked)} disabled={busy} />
          <span>{t("reports.decision.alsoRelated", { count: others })}</span>
        </label>
      ) : null}
      {error ? (
        <p role="alert" className={s.error}>
          {error}
        </p>
      ) : null}
      <div className={s.decideFoot}>
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          {t("reports.decision.cancel")}
        </Button>
        <Button variant="primary" loading={busy} disabled={note.length < 3} onClick={() => void submit()} data-testid="dismiss-confirm">
          {t("reports.dismiss.confirm")}
        </Button>
      </div>
    </div>
  );
}
