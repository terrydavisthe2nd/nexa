import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { CalendarDays, Gavel, Hash, Search, Server, Users } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { moderation as modApi, type ModServer, type People, type ServerSort } from "@/api/moderation";
import { bannerUrl } from "@/api/users";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { Spinner } from "@/components/common/Spinner";
import { Tooltip } from "@/components/common/Tooltip";
import { profileTheme } from "@/lib/avatarColor";
import { personName, previewText } from "@/lib/moderation";
import { formatShortDate } from "@/lib/time";
import { useModEvents, useModeration } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t, Tr } from "@/i18n";
import { ModerateServerModal } from "./ModerateServerModal";
import { ServerIcon } from "./ProfileSheet";
import m from "./Moderation.module.css";
import p from "./Sheet.module.css";

const PAGE = 48;

export function serverBlockedText(b: ModServer["blocked"]): string {
  switch (b) {
    case "self":
      return t("moderation.servers.blockedSelf");
    case "owner":
      return t("moderation.servers.blockedOwner");
    case "staff":
      return t("moderation.servers.blockedStaff");
    default:
      return "";
  }
}

/**
 * Every server on Nexa, as everyone sees it from outside: its banner, icon,
 * name and description, who owns it and how big it is. Nothing said inside
 * a server is here, or anywhere in the panel.
 */
export function ServersTab() {
  const nav = useNavigate();
  const mergePeople = useModeration((s) => s.mergePeople);
  const [q, setQ] = useState("");
  // Discovery is a view of the servers: those on it, listed or held ("" is every server)
  const [params, setParams] = useSearchParams();
  const listing = ["all", "listed", "held"].includes(params.get("discover") ?? "") ? params.get("discover")! : "";
  const discovery = listing !== "";
  const setListing = (v: string) => setParams(v ? { discover: v } : {}, { replace: true });
  const openModal = useUI((u) => u.openModal);
  const [sort, setSort] = useState<ServerSort>("newest");
  const [servers, setServers] = useState<ModServer[] | null>(null);
  const [people, setPeople] = useState<People>({});
  const [total, setTotal] = useState<number | null>(null);
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [moderating, setModerating] = useState<ModServer | null>(null);
  const request = useRef(0);
  const sentinel = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const load = useCallback(
    async (offset: number) => {
      const id = ++request.current;
      setLoading(true);
      setFailed(false);
      try {
        const r = await modApi.servers({ q, sort, offset, limit: PAGE, discovery: discovery ? listing : undefined });
        if (id !== request.current) return;
        setServers((cur) => (offset ? [...(cur ?? []), ...r.servers.filter((x) => !cur?.some((y) => y.id === x.id))] : r.servers));
        setPeople((cur) => ({ ...cur, ...r.people }));
        mergePeople(r.people);
        setTotal(r.total);
        setMore(r.has_more);
        // a list that starts again is read from its top, not from where the old one was left
        if (!offset) scroller.current?.scrollTo({ top: 0 });
      } catch {
        if (id === request.current) setFailed(true);
      } finally {
        if (id === request.current) setLoading(false);
      }
    },
    [q, sort, mergePeople, discovery, listing],
  );

  // A new search or order starts the list again (typing waits a moment). The
  // old order's pages stop at once: one still coming, or asked for by the end
  // of the list in view, would land in the new list.
  useEffect(() => {
    request.current++;
    setMore(false);
    const h = setTimeout(() => {
      setServers(null);
      void load(0);
    }, q ? 250 : 0);
    return () => clearTimeout(h);
  }, [q, sort, load]);

  // the next page loads as the end of the list comes into view
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !more || failed) return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !loading) void load(servers?.length ?? 0);
    }, { rootMargin: "400px" });
    io.observe(el);
    return () => io.disconnect();
  }, [more, loading, load, failed, servers?.length]);

  /** Reads one server again after something happened to it. */
  const refresh = useCallback(async (id: string) => {
    try {
      const r = await modApi.server(id);
      setServers((cur) => cur?.map((x) => (x.id === id ? r.server : x)) ?? cur);
    } catch {
      setServers((cur) => cur?.filter((x) => x.id !== id) ?? cur);
    }
  }, []);

  useModEvents((e) => {
    const sid = e.type === "case" && e.case.action === "server_reset" ? e.case.details.server_id : undefined;
    if (sid && servers?.some((x) => x.id === sid)) { if (discovery) void load(0); else void refresh(sid); }
  });

  const moderateServer = (server: ModServer) => {
    if (!discovery || !server.discovery_hidden) {
      setModerating(server);
      return;
    }
    openModal("confirm", {
      title: t("moderation.servers.discoveryShowTitle", { name: server.name }),
      body: t("moderation.servers.discoveryShowBody"),
      confirmLabel: t("moderation.servers.discoveryShow"),
      onConfirm: async () => {
        await modApi.showOnDiscover(server.id);
        await load(0);
      },
    });
  };

  return (
    <div className={m.scroll} ref={scroller} data-testid={discovery ? "moderation-discovery-tab" : "servers-tab"}>
      <div className={m.toolbar}>

        <label className={m.search}>
          <Search size={17} aria-hidden="true" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("moderation.servers.search")} aria-label={t("moderation.servers.search")} data-testid="servers-search" />
        </label>
        {total !== null ? <span className={p.total}>{t("moderation.servers.total", { count: total })}</span> : null}
        <Select className={m.sort} value={listing} onChange={(e) => setListing(e.target.value)} aria-label={t("moderation.tabs.discovery")} data-testid="servers-discover">
          <option value="">{t("moderation.servers.everyServer")}</option>
          <option value="all">{t("moderation.servers.onDiscover")}</option>
          <option value="listed">{t("moderation.servers.discoveryListed")}</option>
          <option value="held">{t("moderation.servers.discoveryHeld")}</option>
        </Select>
        <Select className={m.sort} value={sort} onChange={(e) => setSort(e.target.value as ServerSort)} aria-label={t("moderation.sort.label")}>
          <option value="newest">{t("moderation.sort.newest")}</option>
          <option value="oldest">{t("moderation.sort.oldest")}</option>
          <option value="members">{t("moderation.servers.sortMembers")}</option>
          <option value="name">{t("moderation.sort.name")}</option>
        </Select>
      </div>

      {servers === null && !failed ? (
        <div className={m.grid} aria-busy="true">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className={m.skeleton} />
          ))}
        </div>
      ) : null}
      {failed && !servers?.length ? (
        <div className={m.empty}>
          <p>{t("moderation.loadFailed")}</p>
          <Button size="sm" variant="secondary" onClick={() => void load(0)}>
            {t("moderation.retry")}
          </Button>
        </div>
      ) : null}
      {servers && servers.length === 0 ? (
        <div className={m.empty}>
          <Server size={40} aria-hidden="true" />
          <span className={m.emptyTitle}>{q ? t("moderation.noMatches") : t("moderation.servers.none")}</span>
          {q ? (
            <Button size="sm" variant="secondary" onClick={() => setQ("")}>
              {t("moderation.clearFilters")}
            </Button>
          ) : null}
        </div>
      ) : null}
      {servers && servers.length > 0 ? (
        <div className={m.grid}>
          {servers.map((s) => (
            <ServerCard key={s.id} s={s} people={people} onModerate={() => moderateServer(s)} onOpen={() => nav(`/channels/@admin/servers/${s.id}`)} onOwner={() => nav(`/channels/@admin/users/${s.owner_id}`)} discovery={discovery} />
          ))}
        </div>
      ) : null}
      <div ref={sentinel} className={m.sentinel} />
      {loading && servers ? (
        <div className={m.loadingRow}>
          <Spinner size={24} label={t("chat.pins.loading")} />
        </div>
      ) : null}
      {moderating ? <ModerateServerModal server={moderating} owner={people[moderating.owner_id]} onClose={() => setModerating(null)} preset={discovery ? ["discovery"] : undefined} onDone={() => discovery ? void load(0) : void refresh(moderating.id)} /> : null}
    </div>
  );
}

function ServerCard({ s, people, onModerate, onOpen, onOwner, discovery = false }: { discovery?: boolean; s: ModServer; people: People; onModerate: () => void; onOpen: () => void; onOwner: () => void }) {
  useMediaAnimation();
  const banner = bannerUrl(s.banner_id, 320);
  const owner = people[s.owner_id];
  const blocked = serverBlockedText(s.blocked);
  const moderate = (
    <Button size="sm" variant="primary" onClick={onModerate} disabled={!!blocked} data-testid="server-card-moderate">
      <Gavel size={15} aria-hidden="true" /> {discovery ? t(s.discovery_hidden ? "moderation.servers.discoveryShow" : "moderation.servers.discoveryHide") : t("moderation.moderate")}
    </Button>
  );
  return (
    <article className={m.card} data-testid="server-card" data-server={s.id}>
      <div className={m.cardBanner} style={banner ? { backgroundImage: `url(${banner})` } : { background: profileTheme(null, null, s.id) }} />
      <div className={m.cardAvatarWrap}>
        <span className={p.cardIcon}>
          <ServerIcon id={s.id} iconId={s.icon_id} name={s.name} size={64} />
        </span>
      </div>
      <div className={m.cardBody}>
        <div className={m.cardName}>
          <span className={m.cardNameText} dir="auto">{s.name}</span>
          {s.nsfw ? <span>{t("nsfw.label")}</span> : null}
        </div>
        <button type="button" className={p.ownerChip} onClick={onOwner} data-testid="server-card-owner">
          <Avatar id={s.owner_id} avatarId={owner?.avatar_id ?? null} name={personName(owner)} size={18} />
          <span>{<Tr id="moderation.servers.ownedBy" values={{ name: <PersonName user={owner}>{personName(owner)}</PersonName> }} />}</span>
        </button>
        <p className={m.cardAbout} data-empty={!s.description || undefined} dir="auto">
          {s.description ? previewText(s.description) : t("moderation.servers.noDescription")}
        </p>
        <div className={m.cardMeta}>
          {discovery ? <span>{t(s.discovery_hidden ? "moderation.servers.discoveryHeld" : "moderation.servers.discoveryListed")}</span> : null}
          <span>
            <Users size={13} aria-hidden="true" />
            {t("moderation.servers.members", { count: s.member_count })}
          </span>
          <span>
            <Hash size={13} aria-hidden="true" />
            {t("moderation.servers.channels", { count: s.channel_count })}
          </span>
          <span>
            <CalendarDays size={13} aria-hidden="true" />
            {formatShortDate(s.created_at)}
          </span>
        </div>
        <div className={m.cardActions}>
          {blocked ? (
            <Tooltip text={blocked}>
              <span className={m.tipWrap} tabIndex={0}>
                {moderate}
              </span>
            </Tooltip>
          ) : (
            moderate
          )}
          <Button size="sm" variant="secondary" onClick={onOpen} data-testid="server-card-open">
            <Server size={15} aria-hidden="true" /> {t("moderation.servers.open")}
          </Button>
        </div>
        {blocked ? <p className={m.blockedNote} aria-hidden="true">{blocked}</p> : null}
      </div>
    </article>
  );
}
