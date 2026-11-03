import { useReportNavigation } from "@/lib/reportNavigation";
import { PersonName } from "@/components/common/PersonName";
import { Award, ChevronLeft, FolderOpen, Gavel, IdCard, ShieldCheck, UserRound } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from "react";
import { useLocation, useParams } from "react-router-dom";
import { moderation as modApi, type ModAction, type ModMembership, type ModUser, type People, type StaffCase, type ModPack } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { FLAG_DELETED, relativeTo } from "@/lib/moderation";
import { atLeast, rankOf, useMyRank } from "@/lib/staffRank";
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
 * One person, for the staff: their profile as everyone sees it, with a way to
 * take down any part of it, and every case about them. Never anything they
 * said in a conversation: the panel has no way to see it.
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
  // the cases tab is open on its own address, so a case can be linked to
  const loc = useLocation();
  const onCases = /^\/cases(\/|$)/.test(loc.pathname.slice(`/channels/@admin/users/${userId}`.length));
  // Whether the panel is wide enough to show a case beside the list. Its own
  // width, as its layout goes by (Moderation.module.css), not the window's.
  const [roomy, setRoomy] = useState(() => typeof window !== "undefined" && window.innerWidth >= 768);
  useLayoutEffect(() => {
    const panel = document.querySelector<HTMLElement>("[data-testid=admin-panel]");
    if (!panel || typeof ResizeObserver === "undefined") return;
    const measure = () => setRoomy(panel.clientWidth > 767);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(panel);
    return () => ro.disconnect();
  }, []);

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

  const newest = cases?.[0]?.id;
  const selected = caseId ?? (roomy && onCases ? newest : undefined);
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
  const awaiting = cases.filter((c) => c.status === "open" && c.awaiting === "staff").length;
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
              {atLeast(mine, "admin") ? <AltBadge user={user} onOpen={() => setAltsOpen(true)} /> : null}
              {rank ? <RankPill rank={rank} /> : null}
            </span>
            <span className={m.userSub}>
              {deleted ? t("moderation.user.deleted") : `@${user.username}`} · {t("moderation.casesCount", { count: cases.length })}
              {standing.length ? ` · ${standing.join(" · ")}` : ""}
            </span>
            {!deleted ? <StatusSummary st={st} compact /> : null}
          </div>
        </div>
        <div className={m.userActions}>
          {!deleted ? (
            <Button size="sm" variant="ghost" onClick={() => openModal("profile", { userId: user.id })}>
              <UserRound size={15} aria-hidden="true" /> {t("moderation.menu.profile")}
            </Button>
          ) : null}
          {/* the owner gives and takes ranks, from here as from the grid's menu */}
          {mine === "owner" && user.blocked !== "self" && user.blocked !== "owner" && !deleted ? (
            <Button size="sm" variant="secondary" onClick={() => setRanking(true)} data-testid="user-rank">
              <ShieldCheck size={15} aria-hidden="true" /> {t("staff.rankModal.menu")}
            </Button>
          ) : null}
          {/* admins give Contributor and event badges by hand; bots hold none */}
          {atLeast(mine, "admin") && !deleted && !isBot({ flags: user.admin_flags }) ? (
            <Button size="sm" variant="secondary" onClick={() => setBadging(true)} data-testid="user-badges">
              <Award size={15} aria-hidden="true" /> {t("badges.staff.giveMenu")}
            </Button>
          ) : null}
          {!user.blocked && !deleted ? (
            <Button size="sm" onClick={() => setModerating({})} data-testid="user-moderate">
              <Gavel size={15} aria-hidden="true" /> {t("moderation.moderate")}
            </Button>
          ) : null}
        </div>
      </div>

      <nav className={m.subTabs} role="tablist" aria-label={user.display_name}>
        <button type="button" role="tab" className={m.subTab} aria-selected={!onCases} data-selected={!onCases} onClick={() => nav(base)} data-testid="person-profile-tab">
          <IdCard size={16} aria-hidden="true" />
          {t("moderation.person.profileTab")}
        </button>
        <button type="button" role="tab" className={m.subTab} aria-selected={onCases} data-selected={onCases} onClick={() => nav(`${base}/cases`)} data-testid="person-cases-tab">
          <FolderOpen size={16} aria-hidden="true" />
          {t("moderation.person.casesTab")}
          <span className={m.chipCount}>{cases.length}</span>
          {awaiting ? <span className={m.badge} aria-label={t("moderation.needsReplyCount", { count: awaiting })}>{awaiting}</span> : null}
        </button>
      </nav>

      {!onCases ? (
        <ProfileSheet user={user} servers={servers} owner={user.id === ownerId} onModerate={(intent) => setModerating(intent)} onOpenServer={(id) => nav(`/channels/@admin/servers/${id}`)} packs={packs} onOpenPack={(id) => nav(`/channels/@admin/expressions/packs/${id}`)} />
      ) : cases.length === 0 ? (
        <div className={m.empty}>
          <FolderOpen size={40} aria-hidden="true" />
          <span className={m.emptyTitle}>{t("moderation.user.noCases")}</span>
          <span>{t("moderation.user.noCasesHint")}</span>
        </div>
      ) : (
        <div className={m.workspace} data-open={!!caseId} style={{ flex: 1 }}>
          <div className={m.listCol}>
            <CaseList cases={cases} people={people} selected={selected} onPick={(id) => nav(`${base}/cases/${id}`)} showTarget={false} />
          </div>
          <div className={m.pane}>
            {selected ? (
              <StaffCasePane key={selected} caseId={selected} showMember={false} onBack={() => nav(`${base}/cases`)} onChanged={(c) => setCases((cur) => cur?.map((x) => (x.id === c.id ? c : x)) ?? cur)} />
            ) : (
              <div className={m.paneEmpty}>
                <FolderOpen size={40} aria-hidden="true" />
                <span className={m.emptyTitle}>{t("moderation.case.pick")}</span>
              </div>
            )}
          </div>
        </div>
      )}
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
          onDone={(c) => (c.action === "delete" ? void load() : nav(`${base}/cases/${c.id}`))}
        />
      ) : null}
    </>
  );
}
