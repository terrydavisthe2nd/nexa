import { useReportNavigation } from "@/lib/reportNavigation";
import { PersonName } from "@/components/common/PersonName";
import { Award, Ban, ChevronLeft, Copy, Flag, LockOpen, MessageSquareOff, ShieldCheck, Trash2, UserRound } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { moderation as modApi, type ModAction, type ModMembership, type ModUser, type People, type StaffCase, type ModPack } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { promptText } from "@/components/modals/PromptModal";
import { describeError } from "@/lib/errors";
import { copyAndSay } from "@/lib/copyText";
import { FLAG_DELETED, relativeTo } from "@/lib/moderation";
import { can, rankOf, useMyRank } from "@/lib/staffRank";
import { useModEvents, useModeration } from "@/stores/moderation";
import { usePresence } from "@/stores/presence";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import { CaseList, StaffCasePane } from "./CaseWorkspace";
import { AltAccountsModal, AltBadge } from "./AltAccounts";
import { ModerateModal, type ModeratePreset } from "./ModerateModal";
import { modStatus, RankPill, StatusSummary } from "./ModStatus";
import { RankModal } from "./RankModal";
import { GrantBadgesModal } from "./GrantBadgesModal";
import { isBot } from "@/lib/botCopies";
import { ProfileSheet } from "./ProfileSheet";
import m from "./Moderation.module.css";

/** What the Moderate dialog opens on: an action, and what in it is already chosen. */
export interface ModerateIntent {
  action?: ModAction;
  preset?: ModeratePreset;
}

/**
 * One person, for the staff, on one page: Mute and Ban (or lifting them) as
 * buttons, their profile as everyone sees it with a way to take down any part
 * of it, and every case about them beside it. A case opens in place. Never
 * anything they said in a conversation: the panel has no way to see it.
 */
export function PersonPage() {
  const { userId = "", caseId } = useParams();
  const {nav,back,returnTo} = useReportNavigation();
  const openModal = useUI((u) => u.openModal);
  const mergePeople = useModeration((s) => s.mergePeople);
  const ownerId = useModeration((s) => s.ownerId);
  const [user, setUser] = useState<ModUser | null>(null);
  const [cases, setCases] = useState<StaffCase[] | null>(null);
  const [servers, setServers] = useState<ModMembership[]>([]);
  const [packs, setPacks] = useState<ModPack[]>([]);
  const [people, setPeople] = useState<People>({});
  const [failed, setFailed] = useState(false);
  const [moderating, setModerating] = useState<ModerateIntent | null>(null);
  const [altsOpen, setAltsOpen] = useState(false);
  const [ranking, setRanking] = useState(false);
  const [badging, setBadging] = useState(false);
  const mine = useMyRank();
  const live = usePresence((p) => p.byUser[userId]);
  const load = useCallback(async () => {
    try {
      const r = await modApi.user(userId);
      setUser(r.user);
      setCases(r.cases);
      setServers(r.servers ?? []);
      setPacks(r.packs ?? []);
      setPeople(r.people);
      mergePeople(r.people);
    } catch {
      setFailed(true);
    }
  }, [userId, mergePeople]);

  useEffect(() => {
    setUser(null);
    setCases(null);
    setFailed(false);
    void load();
  }, [load]);

  useModEvents((e) => {
    if (e.type === "case" && e.case.user_id === userId) void load();
  });

  const standing = useMemo(() => {
    const out: string[] = [];
    if (user?.ban) out.push(user.ban.expires_at ? t("moderation.state.bannedUntil", { when: relativeTo(user.ban.expires_at) }) : t("moderation.state.bannedForever"));
    if (user?.mute) out.push(user.mute.expires_at ? t("moderation.state.mutedUntil", { when: relativeTo(user.mute.expires_at) }) : t("moderation.state.muted"));
    return out;
  }, [user]);

  if (failed) {
    return (
      <div className={m.empty}>
        <p>{t("moderation.user.missing")}</p>
        <Button size="sm" variant="secondary" onClick={() => back("/channels/@admin/users")}>
          {t("moderation.backToUsers")}
        </Button>
      </div>
    );
  }
  if (!user || !cases) {
    return (
      <div className={m.loadingRow}>
        <Spinner label={t("chat.pins.loading")} />
      </div>
    );
  }
  const deleted = (user.admin_flags & FLAG_DELETED) !== 0;
  const rank = rankOf(user.id, user.admin_flags, ownerId ?? undefined);
  const st = modStatus(user, live);
  const base = `/channels/@admin/users/${userId}`;
  // a helper reads; a mod and above act
  const actable = !user.blocked && !deleted && can(mine, "restrict");
  // lifting asks why, as it does from the case, then the account is read again
  const lift = async (r: NonNullable<ModUser["mute"]>) => {
    const what = r.kind === "ban" ? t("moderation.case.liftBan") : t("moderation.case.liftMute");
    const reason = await promptText(what, { label: t("moderation.case.liftReason"), placeholder: t("moderation.case.liftPlaceholder"), maxLength: 1000, hint: t("moderation.case.liftHint") }, what);
    if (reason === null) return;
    try {
      await modApi.lift(r.case_id, reason);
      useUI.getState().toast(t("moderation.case.lifted"), "success");
      void load();
    } catch (err) {
      useUI.getState().toast(describeError(err, t("moderation.failed")), "error");
    }
  };

  // one case open: its thread in place of the page, with the way back to them
  if (caseId) {
    return (
      <div className={m.workspace} data-open="true" style={{ flex: 1 }}>
        <div className={m.pane} style={{ flex: 1 }}>
          <StaffCasePane key={caseId} caseId={caseId} showMember={false} onBack={() => nav(base)} onChanged={(c) => setCases((cur) => cur?.map((x) => (x.id === c.id ? c : x)) ?? cur)} />
        </div>
      </div>
    );
  }

  return (
    <>
      <div className={m.userHeader} data-testid="user-cases-header">
        <button type="button" className={m.iconBtn} onClick={() => back("/channels/@admin/users")} aria-label={t(returnTo ? "reports.returnToReport" : "moderation.backToUsers")}>
          <ChevronLeft size={18} className={m.flip} />
        </button>
        <div className={m.userWho}>
          <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={48} status={deleted ? null : st.status} />
          <div className={m.userNames}>
            <span className={m.userName}>
              <PersonName userId={deleted ? null : user.id} user={deleted ? null : user}>{deleted ? t("nexa.deletedUser") : user.display_name}</PersonName>
              {can(mine, "seeAltsAndCalls") ? <AltBadge user={user} onOpen={() => setAltsOpen(true)} /> : null}
              {rank ? <RankPill rank={rank} /> : null}
            </span>
            <span className={m.userSub}>
              {deleted ? t("moderation.user.deleted") : `@${user.username}`}
            </span>
            {standing.length ? <span className={m.userStanding}>{standing.join(" · ")}</span> : !deleted ? <StatusSummary st={st} compact /> : null}
          </div>
        </div>
        {/* the common actions are buttons on the page, never behind a menu */}
        <div className={m.userActions}>
          {actable ? (
            <>
              {user.mute ? (
                <Button size="sm" variant="positive" onClick={() => void lift(user.mute!)} data-testid="user-unmute">
                  <LockOpen size={15} aria-hidden="true" /> {t("moderation.case.liftMute")}
                </Button>
              ) : (
                <Button size="sm" variant="secondary" onClick={() => setModerating({ action: "mute" })} data-testid="user-mute">
                  <MessageSquareOff size={15} aria-hidden="true" /> {t("reports.act.restrict.mute")}
                </Button>
              )}
              {user.ban ? (
                <Button size="sm" variant="positive" onClick={() => void lift(user.ban!)} data-testid="user-unban">
                  <LockOpen size={15} aria-hidden="true" /> {t("moderation.case.liftBan")}
                </Button>
              ) : (
                <Button size="sm" variant="danger" onClick={() => setModerating({ action: "ban" })} data-testid="user-ban">
                  <Ban size={15} aria-hidden="true" /> {t("reports.act.restrict.ban")}
                </Button>
              )}
            </>
          ) : null}
        </div>
      </div>
      <div className={m.userTools}>
        {!deleted ? (
          <Button size="sm" variant="ghost" onClick={() => openModal("profile", { userId: user.id })}>
            <UserRound size={15} aria-hidden="true" /> {t("moderation.menu.profile")}
          </Button>
        ) : null}
        <Button size="sm" variant="ghost" onClick={() => nav(`/channels/@admin/reports?status=all&q=${user.id}`)} data-testid="user-reports">
          <Flag size={15} aria-hidden="true" /> {t("moderation.person.reports")}
        </Button>
        {/* the owner gives and takes ranks */}
        {mine === "owner" && user.blocked !== "self" && user.blocked !== "owner" && !deleted ? (
          <Button size="sm" variant="ghost" onClick={() => setRanking(true)} data-testid="user-rank">
            <ShieldCheck size={15} aria-hidden="true" /> {t("staff.rankModal.menu")}
          </Button>
        ) : null}
        {/* admins give Contributor and custom badges by hand; bots hold none */}
        {can(mine, "runNexa") && !deleted && !isBot({ flags: user.admin_flags }) ? (
          <Button size="sm" variant="ghost" onClick={() => setBadging(true)} data-testid="user-badges">
            <Award size={15} aria-hidden="true" /> {t("badges.staff.giveMenu")}
          </Button>
        ) : null}
        <Button size="sm" variant="ghost" onClick={() => copyAndSay(user.id, t("moderation.copied"))}>
          <Copy size={15} aria-hidden="true" /> {t("moderation.menu.copyId")}
        </Button>
        {actable && can(mine, "closeAccounts") ? (
          <Button size="sm" variant="ghost" className={m.dangerGhost} onClick={() => setModerating({ action: "delete" })} data-testid="user-delete">
            <Trash2 size={15} aria-hidden="true" /> {t("moderation.act.deleteTitle")}
          </Button>
        ) : null}
      </div>

      <ProfileSheet
        user={user}
        servers={servers}
        owner={user.id === ownerId}
        onModerate={(intent) => setModerating(intent)}
        onOpenServer={(id) => nav(`/channels/@admin/servers/${id}`)}
        packs={packs}
        onOpenPack={(id) => nav(`/channels/@admin/expressions/packs/${id}`)}
        cases={
          <section className={m.personCases} aria-labelledby="person-cases">
            <h3 id="person-cases" className={m.personCasesTitle}>
              {t("moderation.person.casesTab")} <span className={m.chipCount}>{cases.length}</span>
            </h3>
            {cases.length ? <CaseList cases={cases} people={people} onPick={(id) => nav(`${base}/cases/${id}`)} showTarget={false} /> : <p className={m.personCasesEmpty}>{t("moderation.user.noCases")}</p>}
          </section>
        }
      />
      {ranking ? <RankModal user={user} current={rank} onClose={() => setRanking(false)} onDone={() => void load()} /> : null}
      {badging ? <GrantBadgesModal user={user} onClose={() => setBadging(false)} /> : null}
      {altsOpen ? <AltAccountsModal user={user} onClose={() => setAltsOpen(false)} /> : null}
      {moderating ? (
        <ModerateModal
          user={user}
          servers={servers}
          initial={moderating.action}
          preset={moderating.preset}
          onClose={() => setModerating(null)}
          onDone={() => void load()}
        />
      ) : null}
    </>
  );
}
