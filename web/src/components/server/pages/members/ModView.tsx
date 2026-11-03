import { personText } from "@/lib/personText";
import { PersonName } from "@/components/common/PersonName";
import { CalendarClock, Clock, MailWarning, ShieldAlert, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import type { AuditEntry, AutomodEvent, MemberDetail } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { isTopLayer } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { useFocusTrap } from "@/components/common/useFocusTrap";
import { usePortalRoot } from "@/hooks/usePortalRoot";
import { t, Tr } from "@/i18n";
import { formatSpan } from "@/lib/moderation";
import { memberName } from "@/lib/names";
import { P } from "@/lib/perms";
import { formatDateDivider, formatFull, formatRelative } from "@/lib/time";
import { canI, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import { outranks } from "../../memberMenu";
import s from "./Members.module.css";

/** How someone came in, in words. */
export function joinMethodLabel(method: string | undefined): string {
  switch (method) {
    case "invite":
      return t("members.method.invite");
    case "discovery":
      return t("members.method.discovery");
    case "tag":
      return t("members.method.tag");
    case "vanity":
      return t("members.method.vanity");
    case "owner":
      return t("members.method.owner");
    default:
      return t("members.method.unknown");
  }
}

/** A role's dot: its colour, or the gradient its name is drawn in. */
export function roleDot(r: { color: number; color2?: number | null; color3?: number | null }): string {
  if (!r.color) return "var(--text-muted)";
  const hex = (n: number) => `#${n.toString(16).padStart(6, "0")}`;
  if (r.color2 == null) return hex(r.color);
  return `linear-gradient(135deg, ${[r.color, r.color2, ...(r.color3 != null ? [r.color3] : [])].map(hex).join(", ")})`;
}

/** A signal's words, and what to do about it. */
export function signalLabel(sig: string): string {
  return t(`members.signal.${sig}`);
}

export function SignalIcon({ kind, size = 14 }: { kind: string; size?: number }) {
  switch (kind) {
    case "timed_out":
      return <Clock size={size} aria-hidden="true" />;
    case "quarantined":
      return <ShieldAlert size={size} aria-hidden="true" />;
    case "new_account":
      return <CalendarClock size={size} aria-hidden="true" />;
    default:
      return <MailWarning size={size} aria-hidden="true" />;
  }
}

/**
 * Mod View: one member and what a moderator needs to know about them in one
 * place: who they are here, when and how they came, what might want a look,
 * what has been done about them, and what can be done now.
 */
export function ModView({ serverId, userId, onClose, onChanged }: { serverId: string; userId: string; onClose: () => void; onChanged: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const portal = usePortalRoot();
  const server = useServers((x) => x.byId[serverId]);
  const user = useUsers((u) => u.byId[userId]);
  const users = useUsers((u) => u.byId);
  const [detail, setDetail] = useState<MemberDetail | null>(null);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [caught, setCaught] = useState<AutomodEvent[]>([]);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  useFocusTrap(ref, true);

  const load = useCallback(async () => {
    setFailed(false);
    try {
      const res = await serversApi.modView(serverId, userId);
      useUsers.getState().upsert(res.users);
      setDetail(res.member);
      setAudit(res.audit ?? []);
      setCaught(res.automod ?? []);
    } catch {
      setFailed(true);
    }
  }, [serverId, userId]);
  useEffect(() => {
    void load();
  }, [load]);
  // a change made elsewhere (a timeout from the dialog) shows here when it lands
  const live = useServers((x) => x.byId[serverId]?.members[userId]);
  useEffect(() => {
    if (live) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live?.timeout_until, live?.flags, live?.role_ids.join(",")]);
  // Escape (and a phone's back, which goes through Escape) closes Mod View when
  // it is on top: not the settings under it, nor it under a dialog it opened
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && isTopLayer(ref.current)) onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const name = detail ? memberName(detail.nickname, user) : displayName(user);
  const above = outranks(serverId, userId);
  const now = Date.now();
  const timedOut = !!detail?.timeout_until && detail.timeout_until > now;
  const held = detail ? (detail.flags & 1) !== 0 : false;
  const act = (action: "kick" | "ban" | "timeout" | "roles") => useUI.getState().openModal("memberAction", { serverId, userId, action });
  const letOut = async () => {
    setBusy(true);
    try {
      await serversApi.patchMember(serverId, userId, { quarantined: false });
      useUI.getState().toast(personText("members.mod.letOutDone", userId, name, {}, serverId), "success");
      onChanged();
      void load();
    } catch (err) {
      useUI.getState().toast(err instanceof ApiError ? err.message : t("members.mod.failed"), "error");
    } finally {
      setBusy(false);
    }
  };

  return createPortal(
    <>
      <div className={s.scrim} onClick={onClose} aria-hidden="true" />
      <div ref={ref} className={s.sheet} role="dialog" aria-modal="true" aria-label={t("members.mod.title", { name })} data-modview="" data-modal-layer="" data-testid="mod-view">
        <div className={s.sheetHead}>
          <h2 className={s.sheetTitle}>{t("members.mod.heading")}</h2>
          <button type="button" className={s.sheetClose} onClick={onClose} aria-label={t("members.mod.close")}><X size={20} /></button>
        </div>
        <div className={s.sheetBody}>
          <div className={s.person}>
            <Avatar id={userId} avatarId={detail?.avatar_id ?? user?.avatar_id ?? null} name={name} size={64} />
            <div className={s.personText}>
              <PersonName userId={userId} user={user} serverId={serverId} className={s.personName} dir="auto">{name}</PersonName>
              {user ? <span className={s.handle}>@{user.username}</span> : null}
            </div>
          </div>
          {failed ? <LoadError message={t("members.mod.loadFailed")} onRetry={() => void load()} /> : null}
          {!detail && !failed ? <Spinner label={t("chat.pins.loading")} /> : null}
          {detail ? (
            <>
              {detail.signals.length ? (
                <div className={s.block}>
                  <h3 className={s.blockHead}>{t("members.mod.signals")}</h3>
                  {detail.signals.map((sig) => (
                    <div key={sig} className={s.signalLine}>
                      <span className={s.signal} data-kind={sig}><SignalIcon kind={sig} /></span>
                      <span>{sig === "timed_out" && detail.timeout_until ? t("members.mod.timedOutUntil", { time: formatFull(detail.timeout_until) }) : signalLabel(sig)}</span>
                    </div>
                  ))}
                </div>
              ) : null}
              <dl className={s.facts}>
                <dt>{t("members.col.memberSince")}</dt>
                <dd>{formatDateDivider(detail.joined_at)}</dd>
                <dt>{t("members.col.joinedNexa")}</dt>
                <dd>{t("members.mod.accountAge", { date: formatDateDivider(detail.account_created_at), age: formatSpan(now - detail.account_created_at) })}</dd>
                {detail.join ? (
                  <>
                    <dt>{t("members.col.joinMethod")}</dt>
                    <dd>
                      {joinMethodLabel(detail.join.join_method)}
                      {detail.join.join_code ? <> <code dir="ltr">{detail.join.join_code}</code></> : null}
                      {detail.join.invited_by ? <span className={s.methodSub}>{<Tr id="members.mod.invitedBy" values={{ name: <PersonName userId={detail.join.invited_by}>{displayName(users[detail.join.invited_by])}</PersonName> }} />}</span> : null}
                    </dd>
                  </>
                ) : null}
                <dt>{t("members.mod.messages")}</dt>
                <dd>{detail.message_count}</dd>
                <dt>{t("members.mod.lastSeen")}</dt>
                <dd>{detail.last_seen ? formatRelative(detail.last_seen) : t("members.mod.never")}</dd>
              </dl>
              <div className={s.block}>
                <h3 className={s.blockHead}>{t("server.members.roles")}</h3>
                <div className={s.roles}>
                  {detail.role_ids.map((id) => {
                    const r = server?.roles[id];
                    if (!r) return null;
                    return (
                      <span key={id} className={s.role}>
                        <span className={s.dot} style={{ background: roleDot(r) }} />
                        <span dir="auto">{r.name}</span>
                      </span>
                    );
                  })}
                  {detail.role_ids.length === 0 ? <span className={s.more}>{t("members.mod.noRoles")}</span> : null}
                </div>
              </div>
              {above ? (
                <div className={s.block}>
                  <h3 className={s.blockHead}>{t("members.mod.do")}</h3>
                  <div className={s.actions}>
                    {canI(server, P.MANAGE_ROLES) ? <Button size="sm" variant="secondary" onClick={() => act("roles")}>{t("server.members.editRoles")}</Button> : null}
                    {held && canI(server, P.MODERATE_MEMBERS) ? <Button size="sm" variant="positive" loading={busy} onClick={() => void letOut()} data-testid="mod-let-out">{t("members.mod.letOut")}</Button> : null}
                    {canI(server, P.MODERATE_MEMBERS) ? <Button size="sm" variant="secondary" onClick={() => act("timeout")}>{timedOut ? t("server.members.removeTimeout") : t("server.members.timeout")}</Button> : null}
                    {canI(server, P.KICK_MEMBERS) ? <Button size="sm" variant="secondary" onClick={() => act("kick")}>{t("server.members.kick")}</Button> : null}
                    {canI(server, P.BAN_MEMBERS) ? <Button size="sm" variant="danger" onClick={() => act("ban")}>{t("server.members.ban")}</Button> : null}
                  </div>
                </div>
              ) : null}
              {caught.length ? (
                <div className={s.block}>
                  <h3 className={s.blockHead}>{t("members.mod.automod")}</h3>
                  <ul className={s.history}>
                    {caught.map((ev) => (
                      <li key={ev.id}>
                        <time dateTime={new Date(ev.created_at).toISOString()}>{formatFull(ev.created_at)}</time>
                        {t("members.mod.caughtBy", { rule: ev.rule_name })}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {audit.length ? (
                <div className={s.block}>
                  <h3 className={s.blockHead}>{t("members.mod.history")}</h3>
                  <ul className={s.history}>
                    {audit.map((e) => (
                      <li key={e.id}>
                        <time dateTime={new Date(e.created_at).toISOString()}>{formatFull(e.created_at)}</time>
                        {<Tr id="members.mod.historyLine" values={{ who: e.actor_id === "0" ? t("automod.name") : <PersonName userId={e.actor_id} serverId={serverId}>{displayName(users[e.actor_id])}</PersonName>, what: t(`server.auditLog.verb.${e.action}`), target: <PersonName user={user} serverId={serverId}>{displayName(user)}</PersonName> }} />}
                        {e.reason ? <span className={s.methodSub}>{t("server.auditLog.reason", { reason: e.reason })}</span> : null}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </>,
    portal,
  );
}
