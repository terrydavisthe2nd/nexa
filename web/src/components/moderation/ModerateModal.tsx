import { personText } from "@/lib/personText";
import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { ArrowLeft, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { hasServerProfile, moderation as modApi, type ActionRequest, type ModAction, type ModMembership, type ModUser, type PurgeCounts, type ResetField, type StaffCase } from "@/api/moderation";
import { ApiError } from "@/api/client";
import { bannerUrl } from "@/api/users";
import { statusEmojiText } from "@/components/activity/StatusEmoji";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Select } from "@/components/common/Select";
import { Spinner } from "@/components/common/Spinner";
import { TextArea, TextInput } from "@/components/common/TextInput";
import { profileTheme } from "@/lib/avatarColor";
import { describeError } from "@/lib/errors";
import { actionIcon, fieldLabel, formatSpan, fullDate, relativeTo, severity, SPAN } from "@/lib/moderation";
import { readTypingStyle, typingStyleText } from "@/lib/typingStyle";
import { nameStyleText, readNameStyle } from "@/lib/nameStyle";
import { readWidgets, widgetsText } from "@/lib/widgets";
import { widgetTitle } from "@/components/profile/Widgets";
import { formatBytes } from "@/lib/time";
import { usePresence } from "@/stores/presence";
import { useUI } from "@/stores/ui";
import { t, Tr } from "@/i18n";
import { modStatus } from "./ModStatus";
import { can, MOD_MAX_MS, useMyRank, type StaffRank } from "@/lib/staffRank";
import m from "./Moderation.module.css";

const MAX_REASON = 1000;
const MUTE_PRESETS = [SPAN.HOUR, 6 * SPAN.HOUR, 12 * SPAN.HOUR, SPAN.DAY, 3 * SPAN.DAY, SPAN.WEEK, 2 * SPAN.WEEK, 30 * SPAN.DAY];
const BAN_PRESETS = [SPAN.DAY, 3 * SPAN.DAY, SPAN.WEEK, 30 * SPAN.DAY, 90 * SPAN.DAY, 365 * SPAN.DAY];
const UNITS = [SPAN.MINUTE, SPAN.HOUR, SPAN.DAY, SPAN.WEEK];
const MAX_MUTE = 365 * SPAN.DAY;
const MAX_BAN = 5 * 365 * SPAN.DAY;

const ACTIONS: ModAction[] = ["avatar_remove", "field_reset", "mute", "ban", "delete"];

/** The actions a rank may take: a mod takes profile parts down, mutes and bans; an admin deletes too. */
function actionsFor(rank: StaffRank | null): ModAction[] {
  if (can(rank, "closeAccounts")) return ACTIONS;
  if (can(rank, "restrict")) return ACTIONS.filter((a) => a !== "delete");
  return [];
}
/** The fields a reset empties rather than writing MODERATOR_REMOVED_ in. */
const CLEARED: ResetField[] = ["status", "nicknames", "server_profiles", "typing_style", "name_style", "widgets", "server_tag"];

function unitLabel(u: number): string {
  switch (u) {
    case SPAN.MINUTE:
      return t("moderation.unit.minutes");
    case SPAN.HOUR:
      return t("moderation.unit.hours");
    case SPAN.DAY:
      return t("moderation.unit.days");
    default:
      return t("moderation.unit.weeks");
  }
}

function actionTitle(a: ModAction): string {
  switch (a) {
    case "avatar_remove":
      return t("moderation.act.avatarTitle");
    case "field_reset":
      return t("moderation.act.fieldsTitle");
    case "mute":
      return t("moderation.act.muteTitle");
    case "ban":
      return t("moderation.act.banTitle");
    default:
      return t("moderation.act.deleteTitle");
  }
}

function actionBlurb(a: ModAction): string {
  switch (a) {
    case "avatar_remove":
      return t("moderation.act.avatarDesc");
    case "field_reset":
      return t("moderation.act.fieldsDesc");
    case "mute":
      return t("moderation.act.muteDesc");
    case "ban":
      return t("moderation.act.banDesc");
    default:
      return t("moderation.act.deleteDesc");
  }
}

/** A choice of how long, from the usual lengths or typed in. */
function DurationPicker({ presets, value, onChange, max, permanent }: { presets: number[]; value: number | "permanent"; onChange: (v: number | "permanent") => void; max: number; permanent?: boolean }) {
  const [custom, setCustom] = useState(() => typeof value === "number" && !presets.includes(value));
  const [amount, setAmount] = useState("2");
  const [unit, setUnit] = useState(SPAN.DAY);
  const pickCustom = (n: string, u: number) => {
    setAmount(n);
    setUnit(u);
    const v = Math.round(Number(n)) * u;
    onChange(Number.isFinite(v) && v > 0 ? Math.min(v, max) : 0);
  };
  return (
    <div className={m.section}>
      <span className={m.sectionLabel}>{t("moderation.form.duration")}</span>
      <div className={m.chips} role="radiogroup" aria-label={t("moderation.form.duration")}>
        {presets.map((p) => (
          <button key={p} type="button" role="radio" aria-checked={!custom && value === p} className={m.chip} data-selected={!custom && value === p} onClick={() => { setCustom(false); onChange(p); }}>
            {formatSpan(p)}
          </button>
        ))}
        <button type="button" role="radio" aria-checked={custom} className={m.chip} data-selected={custom} onClick={() => { setCustom(true); pickCustom(amount, unit); }}>
          {t("moderation.form.custom")}
        </button>
        {permanent ? (
          <button type="button" role="radio" aria-checked={!custom && value === "permanent"} className={m.chip} data-tone="danger" data-selected={!custom && value === "permanent"} onClick={() => { setCustom(false); onChange("permanent"); }}>
            {t("moderation.form.permanent")}
          </button>
        ) : null}
      </div>
      {custom ? (
        <div className={m.custom}>
          <TextInput type="number" min={1} inputMode="numeric" value={amount} onChange={(e) => pickCustom(e.target.value, unit)} aria-label={t("moderation.form.amount")} data-testid="duration-amount" />
          <Select value={unit} onChange={(e) => pickCustom(amount, Number(e.target.value))} aria-label={t("moderation.form.unit")}>
            {UNITS.map((u) => (
              <option key={u} value={u}>
                {unitLabel(u)}
              </option>
            ))}
          </Select>
        </div>
      ) : null}
      <span className={m.ends}>
        {value === "permanent" ? t("moderation.form.endsNever") : value > 0 ? t("moderation.form.endsAt", { when: fullDate(Date.now() + value) }) : t("moderation.form.pickLength")}
      </span>
    </div>
  );
}

/** What a dialog opened from one field of a profile starts with chosen. */
export interface ModeratePreset {
  fields?: ResetField[];
  images?: { avatar: boolean; banner: boolean };
}

interface ModerateProps {
  user: ModUser;
  onClose: () => void;
  onDone: (c: StaffCase) => void;
  initial?: ModAction | undefined;
  preset?: ModeratePreset | undefined;
  /** The servers they own or have a server profile in, where it is known: which nicknames a reset would clear. */
  servers?: ModMembership[] | undefined;
  /**
   * Everyone the action is for, when it is more than one account (user is
   * then the first of them). Each gets a case of their own, as if acted on
   * one at a time.
   */
  bulk?: ModUser[] | undefined;
}

/**
 * Taking an action against an account: pick it, set it up, say why. The
 * reason goes to the member, word for word, with the moderator's name — so
 * the dialog says so beside the box it is typed in.
 */
export function ModerateModal({ user, onClose, onDone, initial, preset, servers, bulk }: ModerateProps) {
  useMediaAnimation();
  const many = bulk && bulk.length > 1 ? bulk : null;
  const toast = useUI((u) => u.toast);
  const live = usePresence((p) => p.byUser[user.id]);
  const presence = modStatus(user, live);
  const mine = useMyRank();
  const isMod = !can(mine, "restrictAnyLength");
  const offered = actionsFor(mine);
  // a mod mutes and bans for 30 days at most, and never for good
  const maxMute = isMod ? MOD_MAX_MS : MAX_MUTE;
  const maxBan = isMod ? MOD_MAX_MS : MAX_BAN;
  const [action, setAction] = useState<ModAction | null>(initial && offered.includes(initial) ? initial : null);
  const [reason, setReason] = useState("");
  const [images, setImages] = useState(preset?.images ?? { avatar: !!user.avatar_id || !!servers?.some((x) => x.avatar_id), banner: !user.avatar_id && (!!user.banner_id || !!servers?.some((x) => x.banner_id)) });
  const [fields, setFields] = useState<ResetField[]>(preset?.fields ?? []);
  const [muteFor, setMuteFor] = useState<number | "permanent">(SPAN.DAY);
  const [banFor, setBanFor] = useState<number | "permanent">(SPAN.WEEK);
  const [confirm, setConfirm] = useState("");
  // usernames are lower case, and a phone keyboard capitalises the first letter
  // for many accounts, the number of them is what is typed
  const confirmWord = many ? String(many.length) : user.username;
  const confirmed = confirm.trim().toLowerCase() === confirmWord;
  const [preview, setPreview] = useState<PurgeCounts | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (action !== "delete" || preview || many) return;
    let alive = true;
    modApi
      .purgePreview(user.id)
      .then((p) => alive && setPreview(p))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [action, preview, user.id]);

  const nicknamed = servers?.filter((x) => x.nickname);
  const profiled = servers?.filter(hasServerProfile);
  // a picture of theirs in one of their servers counts as theirs to take down
  const serverAvatar = !!servers?.some((x) => x.avatar_id);
  const serverBanner = !!servers?.some((x) => x.banner_id);
  const hasAvatar = many ? true : !!user.avatar_id || serverAvatar;
  const hasBanner = many ? true : !!user.banner_id || serverBanner;
  const values: Record<ResetField, string> = {
    username: `@${user.username}`,
    display_name: user.display_name,
    about_me: user.about_me,
    pronouns: user.pronouns,
    status: [statusEmojiText(user.status_emoji), user.status_text].filter(Boolean).join(" "),
    // without the list, the server says whether there are any
    nicknames: nicknamed ? nicknamed.map((x) => `${x.name}: ${x.nickname}`).join(", ") : t("moderation.form.everyServer"),
    server_profiles: profiled ? profiled.map((x) => x.name).join(", ") : t("moderation.form.everyServer"),
    typing_style: typingStyleText(readTypingStyle(user.typing_style)),
    name_style: nameStyleText(readNameStyle(user.name_style)),
    widgets: widgetsText(readWidgets(user.widgets), widgetTitle).map((w) => `${w.title}: ${w.text}`).join(" / "),
    server_tag: user.server_tag?.text ?? "",
  };
  const trimmed = reason.trim();
  const reasonOk = [...trimmed].length >= 3 && [...reason].length <= MAX_REASON;

  const ready = useMemo(() => {
    if (!action || !reasonOk) return false;
    switch (action) {
      case "avatar_remove":
        return images.avatar || images.banner;
      case "field_reset":
        return fields.length > 0;
      case "mute":
        return typeof muteFor === "number" && muteFor >= SPAN.MINUTE && muteFor <= maxMute;
      case "ban":
        return (banFor === "permanent" && !isMod) || (typeof banFor === "number" && banFor >= SPAN.MINUTE && banFor <= maxBan);
      default:
        return confirmed;
    }
  }, [action, reasonOk, images, fields, muteFor, banFor, confirmed, maxMute, maxBan, isMod]);

  const submitLabel = (): string => {
    switch (action) {
      case "avatar_remove":
        return images.avatar && images.banner ? t("moderation.submit.removeBoth") : images.banner ? t("moderation.submit.removeBanner") : t("moderation.submit.removeAvatar");
      case "field_reset":
        return t("moderation.submit.resetFields", { count: fields.length || 1 });
      case "mute":
        return typeof muteFor === "number" && muteFor > 0 ? t("moderation.submit.muteFor", { span: formatSpan(muteFor) }) : t("moderation.act.muteTitle");
      case "ban":
        return banFor === "permanent" ? t("moderation.submit.banForever") : banFor > 0 ? t("moderation.submit.banFor", { span: formatSpan(banFor) }) : t("moderation.act.banTitle");
      case "delete":
        return t("moderation.submit.delete");
      default:
        return t("moderation.submit.choose");
    }
  };

  const submit = async () => {
    if (!action || !ready || busy) return;
    const body: ActionRequest = { action, reason: trimmed };
    if (action === "avatar_remove") body.images = images;
    if (action === "field_reset") body.fields = fields;
    if (action === "mute" && typeof muteFor === "number") body.duration_ms = muteFor;
    if (action === "ban") {
      if (banFor === "permanent") body.permanent = true;
      else body.duration_ms = banFor;
    }
    setBusy(true);
    setError(null);
    if (many) {
      // one at a time, so each member gets their own case and message
      let done = 0;
      const failed: string[] = [];
      for (const u of many) {
        try {
          const r = await actOnOne(u, action === "delete" ? { ...body, confirm: u.username } : body);
          if (r) {
            done++;
            onDone(r.case);
          }
        } catch (err) {
          failed.push(`${u.display_name}: ${describeError(err, t("moderation.failed"))}`);
        }
      }
      setBusy(false);
      if (!failed.length) {
        toast(t("moderation.bulk.done", { count: done }), "success");
        onClose();
      } else {
        setError([t("moderation.bulk.partly", { done, count: many.length }), ...failed].join("\n"));
      }
      return;
    }
    if (action === "delete") body.confirm = user.username;
    try {
      const r = await modApi.act(user.id, body);
      toast(personText("moderation.done", user.id, user.display_name, { number: r.case.number }), "success");
      onDone(r.case);
      onClose();
    } catch (err) {
      setError(describeError(err, t("moderation.failed")));
    } finally {
      setBusy(false);
    }
  };

  const toggleField = (f: ResetField) => setFields((cur) => (cur.includes(f) ? cur.filter((x) => x !== f) : [...cur, f]));
  const quick = [t("moderation.quick.hateful"), t("moderation.quick.impersonation"), t("moderation.quick.spam"), t("moderation.quick.harassment"), t("moderation.quick.explicit"), t("moderation.quick.evasion")];
  const tone = action ? severity(action) : "info";

  return (
    <Modal open onClose={onClose} bare width={620} ariaLabel={many ? t("moderation.bulk.title", { count: many.length }) : t("moderation.moderateNamed", { name: user.display_name })}>
      <div className={m.modal} data-testid="moderate-modal">
        <div className={m.modalTop}>
          {action && !initial ? (
            <button type="button" className={m.modalBack} onClick={() => { setAction(null); setError(null); }} aria-label={t("moderation.back")}>
              <ArrowLeft size={20} className={m.flip} />
            </button>
          ) : null}
          {many ? (
            <AvatarStack users={many} />
          ) : (
            <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={40} status={presence.status} />
          )}
          <div style={{ minWidth: 0 }}>
            <h2 className={m.modalTitle}>{action ? actionTitle(action) : many ? t("moderation.bulk.title", { count: many.length }) : <Tr id="moderation.moderateNamed" values={{ name: <PersonName user={user}>{user.display_name}</PersonName> }} />}</h2>
            {many ? (
              <div className={m.modalTarget}>
                <span className={m.bulkNames}>{many.map((u) => `@${u.username}`).join(", ")}</span>
              </div>
            ) : (
            <div className={m.modalTarget}>
              <span>@{user.username}</span>
              {user.mute ? <span className={m.tag} data-tone="warn">{t("moderation.state.muted")}</span> : null}
              {user.ban ? <span className={m.tag} data-tone="danger">{t("moderation.state.banned")}</span> : null}
            </div>
            )}
          </div>
          <button type="button" className={m.modalClose} onClick={onClose} aria-label={t("chat.attachments.close")}>
            <X size={20} />
          </button>
        </div>

        <div className={m.modalScroll}>
          {!action ? (
            <div className={m.section}>
              <span className={m.sectionLabel}>{t("moderation.form.chooseAction")}</span>
              <div className={m.actionList}>
                {offered.map((a) => {
                  const Icon = actionIcon(a);
                  const nothing = a === "avatar_remove" && !hasAvatar && !hasBanner;
                  let desc = actionBlurb(a);
                  if (nothing) desc = t("moderation.act.avatarNone");
                  if (many) desc = actionBlurb(a);
                  if (a === "mute" && user.mute && !many) desc = user.mute.expires_at ? t("moderation.act.alreadyMuted", { when: relativeTo(user.mute.expires_at) }) : t("moderation.act.alreadyMutedForever");
                  if (a === "ban" && user.ban && !many) desc = user.ban.expires_at ? t("moderation.act.alreadyBanned", { when: relativeTo(user.ban.expires_at) }) : t("moderation.act.alreadyBannedForever");
                  return (
                    <button key={a} type="button" className={m.actionTile} data-tone={severity(a)} disabled={nothing} onClick={() => setAction(a)} data-testid={`action-${a}`}>
                      <span className={m.actionIcon} aria-hidden="true">
                        <Icon size={20} />
                      </span>
                      <span className={m.actionText}>
                        <span className={m.actionName}>{actionTitle(a)}</span>
                        <span className={m.actionDesc}>{desc}</span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : null}

          {action === "avatar_remove" && many ? (
            <div className={m.section}>
              <span className={m.sectionLabel}>{t("moderation.form.whatToRemove")}</span>
              <div className={m.choiceRow}>
                {(["avatar", "banner"] as const).map((k) => (
                  <label key={k} className={m.pick} data-checked={images[k]}>
                    <input type="checkbox" checked={images[k]} onChange={(e) => setImages((v) => ({ ...v, [k]: e.target.checked }))} />
                    <span className={m.pickText}>
                      <span className={m.pickName}>{t(k === "avatar" ? "moderation.field.avatar" : "moderation.field.banner")}</span>
                      <span className={m.pickValue}>{t("moderation.bulk.whereSet")}</span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
          ) : null}

          {action === "avatar_remove" && !many ? (
            <div className={m.section}>
              <span className={m.sectionLabel}>{t("moderation.form.whatToRemove")}</span>
              <div className={m.choiceRow}>
                <label className={m.pick} data-checked={images.avatar} data-disabled={!hasAvatar}>
                  <input type="checkbox" checked={images.avatar} disabled={!hasAvatar} onChange={(e) => setImages((v) => ({ ...v, avatar: e.target.checked }))} />
                  <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={32} />
                  <span className={m.pickText}>
                    <span className={m.pickName}>{t("moderation.field.avatar")}</span>
                    <span className={m.pickValue}>{user.avatar_id ? t("moderation.form.avatarAlso") : serverAvatar ? t("moderation.form.inServersOnly") : t("moderation.form.none")}</span>
                  </span>
                </label>
                <label className={m.pick} data-checked={images.banner} data-disabled={!hasBanner}>
                  <input type="checkbox" checked={images.banner} disabled={!hasBanner} onChange={(e) => setImages((v) => ({ ...v, banner: e.target.checked }))} />
                  <span className={m.bannerPreview} style={user.banner_id ? { backgroundImage: `url(${bannerUrl(user.banner_id, 160)})` } : { background: profileTheme(user.accent_color, user.accent_color_2, user.id) }} aria-hidden="true" />
                  <span className={m.pickText}>
                    <span className={m.pickName}>{t("moderation.field.banner")}</span>
                    <span className={m.pickValue}>{user.banner_id ? t("moderation.form.bannerAlso") : serverBanner ? t("moderation.form.inServersOnly") : t("moderation.form.none")}</span>
                  </span>
                </label>
              </div>
            </div>
          ) : null}

          {action === "field_reset" ? (
            <div className={m.section}>
              <span className={m.sectionLabel}>{t("moderation.form.whatToReset")}</span>
              <div className={m.choiceRow}>
                {(["username", "display_name", "about_me", "pronouns", "status", "nicknames", "server_profiles", "typing_style", "name_style", "widgets", "server_tag"] as ResetField[]).map((f) => {
                  const empty = many ? false : f === "nicknames" ? !!nicknamed && nicknamed.length === 0 : f === "server_profiles" ? !!profiled && profiled.length === 0 : f !== "username" && f !== "display_name" && !values[f];
                  return (
                    <label key={f} className={m.pick} data-checked={fields.includes(f)} data-disabled={empty} data-testid={`field-${f}`}>
                      <input type="checkbox" checked={fields.includes(f)} disabled={empty} onChange={() => toggleField(f)} />
                      <span className={m.pickText}>
                        <span className={m.pickName}>{fieldLabel(f)}</span>
                        <span className={m.pickValue} title={many ? undefined : values[f]}>{many ? t("moderation.bulk.whereSet") : empty ? t("moderation.form.empty") : values[f]}</span>
                      </span>
                    </label>
                  );
                })}
              </div>
              <p className={m.note}>{fields.includes("username") ? t("moderation.form.resetUsernameNote") : fields.length && fields.every((f) => CLEARED.includes(f)) ? t("moderation.form.clearNote") : t("moderation.form.resetNote")}</p>
            </div>
          ) : null}

          {action === "mute" ? (
            <>
              <DurationPicker presets={MUTE_PRESETS.filter((x) => x <= maxMute)} value={muteFor} onChange={setMuteFor} max={maxMute} />
              <p className={m.note}>{t("moderation.form.muteNote")}{isMod ? ` ${t("staff.modWeek")}` : ""}</p>
            </>
          ) : null}

          {action === "ban" ? (
            <>
              <DurationPicker presets={BAN_PRESETS.filter((x) => x <= maxBan)} value={banFor} onChange={setBanFor} max={maxBan} permanent={!isMod} />
              <p className={m.note}>{t("moderation.form.banNote")}{isMod ? ` ${t("staff.modWeek")}` : ""}</p>
            </>
          ) : null}

          {action === "delete" ? (
            <div className={m.section}>
              <div className={m.danger}>
                <strong>{many ? t("moderation.bulk.deleteWarning", { count: many.length }) : t("moderation.delete.warning")}</strong>
                {many ? null : preview ? (
                  <ul>
                    <li>{t("moderation.delete.messages", { count: preview.messages })}</li>
                    <li>{t("moderation.delete.files", { count: preview.attachments, size: formatBytes(preview.attachment_bytes) })}</li>
                    <li>{t("moderation.delete.servers", { count: preview.owned_servers })}</li>
                    <li>{t("moderation.delete.groups", { count: preview.owned_groups })}</li>
                    <li>{t("moderation.delete.packsAndReactions", { packs: preview.packs, reactions: preview.reactions })}</li>
                    <li>{t("moderation.delete.leaves", { servers: preview.servers - preview.owned_servers, groups: preview.groups - preview.owned_groups })}</li>
                  </ul>
                ) : (
                  <div style={{ padding: 8 }}>
                    <Spinner size={20} label={t("chat.pins.loading")} />
                  </div>
                )}
                <p style={{ margin: "8px 0 0" }}>{t("moderation.delete.kept")}</p>
              </div>
              <label className={m.section}>
                {/* not in capitals like the other labels: the username in it is typed exactly as shown */}
                <span className={m.confirmLabel}>{many ? t("moderation.bulk.confirmLabel", { count: many.length }) : t("moderation.delete.confirmLabel", { username: user.username })}</span>
                <TextInput value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder={confirmWord} autoComplete="off" autoCapitalize="none" autoCorrect="off" spellCheck={false} data-testid="delete-confirm" />
              </label>
            </div>
          ) : null}

          {action ? (
            <div className={m.section}>
              <label className={m.section}>
                <span className={m.sectionLabel}>{t("moderation.form.reason")}</span>
                <TextArea className={m.reasonBox} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("moderation.form.reasonPlaceholder")} maxLength={MAX_REASON + 50} data-testid="moderate-reason" />
              </label>
              <div className={m.quick}>
                {quick.map((q) => (
                  <button key={q} type="button" onClick={() => setReason((r) => (r.trim() ? `${r.trim()} ${q}` : q))}>
                    {q}
                  </button>
                ))}
              </div>
              <div className={m.reasonMeta}>
                <span>{t("moderation.form.reasonVisible")}</span>
                {/* Submit waited for three characters and never said so. */}
                {[...reason.trim()].length < 3 ? <span data-short="">{t("moderation.form.reasonTooShort", { min: 3 })}</span> : <span>{[...reason].length}/{MAX_REASON}</span>}
              </div>
            </div>
          ) : null}
          {error ? (
            <p className={m.danger} role="alert" style={{ whiteSpace: "pre-line" }}>
              {error}
            </p>
          ) : null}
        </div>

        <div className={m.modalFoot}>
          <span className={m.summary}>{action ? t("moderation.form.notifies") : t("moderation.form.chooseHint")}</span>
          <span className={m.grow} />
          <Button variant="ghost" onClick={onClose}>
            {t("moderation.cancel")}
          </Button>
          {action ? (
            <Button variant={tone === "danger" ? "danger" : "primary"} disabled={!ready} loading={busy} onClick={() => void submit()} data-testid="moderate-submit">
              {submitLabel()}
            </Button>
          ) : null}
        </div>
      </div>
    </Modal>
  );
}

/**
 * One account's part of an action for many. Pictures they do not have are
 * left out rather than failing: null when there was nothing of theirs to take.
 */
async function actOnOne(u: ModUser, body: ActionRequest): Promise<{ case: StaffCase } | null> {
  try {
    return await modApi.act(u.id, body);
  } catch (err) {
    const missing = err instanceof ApiError ? err.code : "";
    if (body.images && (missing === "no_avatar" || missing === "no_banner")) {
      const rest = { avatar: missing === "no_banner" && body.images.avatar, banner: missing === "no_avatar" && body.images.banner };
      return rest.avatar || rest.banner ? actOnOne(u, { ...body, images: rest }) : null;
    }
    throw err;
  }
}

/** The first few of the accounts a dialog is for, overlapping. */
export function AvatarStack({ users, size = 32 }: { users: ModUser[]; size?: number }) {
  const shown = users.slice(0, 4);
  return (
    <span className={m.avatarStack} aria-hidden="true">
      {shown.map((u) => (
        <span key={u.id} className={m.avatarStackItem}>
          <Avatar id={u.id} avatarId={u.avatar_id} name={u.display_name} size={size} />
        </span>
      ))}
      {users.length > shown.length ? <span className={m.avatarStackMore} style={{ width: size, height: size }}>+{users.length - shown.length}</span> : null}
    </span>
  );
}
