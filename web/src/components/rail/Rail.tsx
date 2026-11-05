import { useMediaAnimation } from "@/lib/mediaAnimation";
import { Pin, Plus, Shield, Users, Compass, Volume2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent, type MouseEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { Channel } from "@/api/types";
import { iconUrl } from "@/api/users";
import { Avatar } from "@/components/common/Avatar";
import { NexaMark } from "@/components/common/Brand";
import { Tooltip } from "@/components/common/Tooltip";
import { useMediaQuery } from "@/hooks/useIsMobile";
import { useReorder } from "@/hooks/useReorder";
import { initials } from "@/lib/avatarColor";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { moveEntry, resolveRail, siblingIds, dropEntry, type RailDrop, type RailEntry, type ServerFolder } from "@/lib/railLayout";
import { railDropAt, type RailDrag, type RailRow } from "@/lib/railDrop";
import { badgeText, serverUnread, unreadConversations } from "@/lib/unread";
import { compareIds, idAt } from "@/lib/ids";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useReadStates } from "@/stores/readStates";
import { useRelationships } from "@/stores/relationships";
import { canI, useServers } from "@/stores/servers";
import { useVoice } from "@/stores/voice";
import { P } from "@/lib/perms";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { dmMenu } from "@/components/sidebar/dmMenu";
import { nexaListed, nexaMenu } from "@/components/sidebar/dmArrange";
import { serverMenu } from "@/components/sidebar/serverMenu";
import { menuPoint } from "@/components/sidebar/ServerSidebar";
import { lastChannelOf } from "@/stores/serverNav";
import { useNexa } from "@/stores/nexa";
import { attentionCount, useModeration } from "@/stores/moderation";
import { moderationMenu } from "@/components/moderation/modMenu";
import { NexaAvatar } from "@/components/nexa/NexaBits";
import { FolderFace } from "./FolderFace";
import { adoptRail, changeRail, folderMenu, folderName, toggleFolder } from "./railActions";
import { railBulk } from "./railBulk";
import { PickBar, PickMark } from "@/components/common/Pick";
import { pickClick, usePick } from "@/stores/pick";
import s from "./Rail.module.css";

import { t } from "@/i18n";
import { useMyRank } from "@/lib/staffRank";

/** What Home's badge counts, read out: "3 unread messages and 1 friend request". */
export function homeLabel(messages: number, requests: number): string {
  const parts: string[] = [];
  if (messages > 0) parts.push(t("rail.rail.unreadMessagesCount", { count: messages }));
  if (requests > 0) parts.push(t("rail.rail.friendRequestsCount", { count: requests }));
  if (parts.length === 2) return t("rail.rail.and", { first: parts[0]!, second: parts[1]! });
  return parts.join("");
}

/** How many waiting conversations the rail shows under home; the DM list has the rest. */
const MAX_WAITING = 10;

/** A conversation with messages waiting, or Nexa's (no channel), and when it last spoke as an id to sort by. */
type Waiting = { channel: Channel | null; count: number; at: string };

const NO_PICKS: string[] = [];

/** The type a drag from the rail carries: not text, so a text box it passes over does not take the id. */
const RAIL_DRAG = "application/x-nexa-rail";

/**
 * The 72 px server rail: home (DMs), the conversations with messages waiting,
 * separator, the pinned servers and folders, the rest, add.
 */
export function Rail() {
  useMediaAnimation();
  const nav = useNavigate();
  const loc = useLocation();
  const channels = useChannels((c) => c.byId);
  const reads = useReadStates((r) => r.byChannel);
  const servers = useServers((st) => st.byId);
  const order = useServers((st) => st.order);
  const settings = useSettings((st) => st.settings);
  const loaded = useSettings((st) => st.loaded);
  const openModal = useUI((u) => u.openModal);
  const meId = useAuth((a) => a.me?.id);
  const users = useUsers((u) => u.byId);
  const rels = useRelationships((r) => r.byUser);
  const nicknames = useMemo(() => friendNicknames(rels), [rels]);
  const home = loc.pathname.startsWith("/channels/@me");
  const activeServer = loc.pathname.match(/^\/channels\/(\d+)/)?.[1];
  const openConvo = loc.pathname.match(/^\/channels\/@me\/(\d+)/)?.[1];
  // HTML5 dragging is for a mouse; on a touch screen a held server opens its menu, which makes the same moves
  const canDrag = useMediaQuery("(hover: hover) and (pointer: fine)");
  const [drag, setDragShown] = useState<RailDrag | null>(null);
  const [over, setOverShown] = useState<RailDrop | null>(null);
  // The same two for the drag events themselves, which come faster than the
  // rail is drawn: one reading the state could still see no drag at all, and
  // a drop could land where the pointer was a moment before.
  const dragRef = useRef<RailDrag | null>(null);
  const overRef = useRef<RailDrop | null>(null);
  const railRef = useRef<HTMLElement>(null);
  const sepRef = useRef<HTMLDivElement>(null);

  // Home's pill and count are for friend requests alone. A conversation with
  // messages waiting already stands under it with its own count, and a second
  // number on home for the same messages was one badge too many.
  const friendRequests = useMemo(() => Object.values(rels).filter((r) => r.type === "incoming").length, [rels]);
  // The servers where people are in a voice channel this account could join
  // too: there is a call going, and the speaker on the icon says so.
  const voiceByChannel = useVoice((v) => v.byChannel);
  const voiceLive = useMemo(() => {
    const live = new Set<string>();
    for (const [chId, people] of Object.entries(voiceByChannel)) {
      const ch = channels[chId];
      if (!ch?.server_id || ch.type !== "voice" || live.has(ch.server_id) || Object.keys(people).length === 0) continue;
      if (canI(servers[ch.server_id], P.VIEW_CHANNEL | P.CONNECT, chId)) live.add(ch.server_id);
    }
    return live;
  }, [voiceByChannel, channels, servers]);
  // what Nexa sent waits under home like any conversation; once its conversation is closed, only something new counts
  const nexaClosed = settings.nexaClosed;
  const nexaState = useNexa((n) => n.state);
  const nexaOpen = loc.pathname.startsWith("/channels/@me/nexa");
  const nexaUnread = nexaState && !nexaOpen && nexaListed(nexaState.latest, nexaClosed) ? nexaState.unread : 0;
  // every rank of staff opens the panel
  const isAdmin = useMyRank() !== null;
  // case threads and bug reports waiting on the staff that hold something this person has not read
  const needsReply = useModeration(attentionCount);
  const inPanel = loc.pathname.startsWith("/channels/@admin");
  // like the people themselves waiting at the door: who sent something, and how much; Nexa among them, by when it spoke
  const waiting: Waiting[] = unreadConversations(channels, reads, settings, openConvo).map((w) => ({ ...w, at: w.channel.last_message_id ?? w.channel.id }));
  if (nexaUnread > 0 && nexaState) waiting.push({ channel: null, count: nexaUnread, at: idAt(nexaState.latest) });
  waiting.sort((a, b) => compareIds(b.at, a.at));
  waiting.splice(MAX_WAITING);

  const layout = useMemo(() => resolveRail(settings.rail, order, settings.serverPositions), [settings.rail, order, settings.serverPositions]);
  // the order an account had before folders becomes its layout, once its settings are in
  useEffect(() => {
    if (loaded) adoptRail();
  }, [loaded]);

  const folderById = useMemo(() => {
    const m = new Map<string, ServerFolder>();
    for (const e of [...layout.pinned, ...layout.rest]) if (typeof e !== "string") m.set(e.id, e);
    return m;
  }, [layout]);

  const openServer = useCallback(
    (id: string) => {
      const last = lastChannelOf(id);
      // on phones the drawer stays open, showing this server's channels to pick from
      nav(last && channels[last]?.server_id === id ? `/channels/${id}/${last}` : `/channels/${id}`, { state: { keepDrawer: true } });
    },
    [nav, channels],
  );

  // The same moves for a keyboard and for a finger. HTML5 drag events never
  // fire on a touch screen, so the rail's order could once only be changed
  // with a mouse. Each entry moves among its own list: a folder among the
  // entries of its section, a server in a folder among that folder's servers.
  const shown = useMemo(() => [...layout.pinned, ...layout.rest].flatMap((e) => (typeof e === "string" ? [e] : [e.id, ...(e.open ? e.servers : [])])), [layout]);
  const reorder = useReorder({
    ids: shown,
    siblings: (id) => siblingIds(layout, id),
    name: (id) => servers[id]?.server.name ?? (folderById.has(id) ? folderName(folderById.get(id)!) : ""),
    move: (id, delta) => changeRail((l) => moveEntry(l, id, delta)),
  });

  // picking several servers at once (stores/pick.ts)
  const picked = usePick((p) => (p.list === "servers" ? p.ids : NO_PICKS));
  const pickMode = usePick((p) => p.list === "servers" && p.mode);
  const isPicking = pickMode || picked.length > 0;
  const serverOrder = useMemo(() => shown.filter((id) => !!servers[id]), [shown, servers]);
  // what was picked and has since gone (left, deleted) is let go of
  useEffect(() => {
    const gone = picked.filter((id) => !servers[id]);
    if (gone.length) usePick.getState().drop(gone);
  }, [picked, servers]);

  const onServerContext = (e: MouseEvent, id: string) => {
    e.preventDefault();
    const pick = usePick.getState();
    // a right click on one of several picked acts on all of them
    if (pick.list === "servers" && pick.ids.includes(id) && pick.ids.length > 1) {
      railBulk(pick.ids, { nav, openModal }).menu({ ...menuPoint(e), label: t("pick.count", { count: pick.ids.length }) });
      return;
    }
    if (pick.list === "servers" && !pick.mode && pick.ids.length) pick.clear();
    serverMenu.open({ serverId: id, nav, openModal, rail: { moves: reorder.menuItems(id) } }, { ...menuPoint(e), label: servers[id]?.server.name ?? "" });
  };
  const onFolderContext = (e: MouseEvent, f: ServerFolder) => {
    e.preventDefault();
    folderMenu.open({ folderId: f.id, openModal, moves: reorder.menuItems(f.id) }, { ...menuPoint(e), label: folderName(f) });
  };

  const setDrag = (next: RailDrag | null) => {
    dragRef.current = next;
    setDragShown(next);
  };
  const setOver = (next: RailDrop | null) => {
    overRef.current = next;
    setOverShown(next);
  };
  const endDrag = () => {
    setDrag(null);
    setOver(null);
  };
  const onDragStart = (e: DragEvent, id: string, folder: boolean) => {
    e.stopPropagation();
    setDrag({ id, folder });
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData(RAIL_DRAG, id);
  };
  /** The rows as drawn right now: unread conversations can come and go above them mid-drag. */
  const measureRows = (): RailRow[] => {
    const rail = railRef.current;
    if (!rail) return [];
    return [...rail.querySelectorAll<HTMLElement>("[data-rail-row]")].map((el) => {
      const r = el.getBoundingClientRect();
      const folder = el.hasAttribute("data-rail-folder");
      const row: RailRow = { id: el.dataset["railRow"]!, folder, top: r.top, bottom: r.bottom };
      const parent = el.dataset["railParent"];
      if (parent) row.parent = parent;
      // an open folder's sleeve is the wrapper round its tile and its servers
      const sleeve = el.parentElement;
      if (folder && sleeve?.dataset["open"] === "true") row.end = sleeve.getBoundingClientRect().bottom;
      return row;
    });
  };
  /** Where a drop with the pointer at this height would land: nowhere above the line under home. */
  const dropAt = (d: RailDrag, y: number): RailDrop | null => {
    const sep = sepRef.current?.getBoundingClientRect();
    if (sep && y < sep.top) return null;
    return railDropAt(measureRows(), d, y);
  };
  // One target for the whole rail rather than one per tile, so the space
  // between tiles and round a folder's sleeve takes a drop too (lib/railDrop).
  const onRailDragOver = (e: DragEvent) => {
    const d = dragRef.current;
    // a file or some text dragged in from elsewhere is not the rail's to take
    if (!d) return;
    const next = dropAt(d, e.clientY);
    if (!next) {
      if (overRef.current) setOver(null);
      return;
    }
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const was = overRef.current;
    if (was?.id !== next.id || was.where !== next.where) setOver(next);
  };
  const onRailDragLeave = (e: DragEvent) => {
    // out of the rail altogether, not from one of its tiles onto the next
    if (dragRef.current && !e.currentTarget.contains(e.relatedTarget as Node | null)) setOver(null);
  };
  const onDrop = (e: DragEvent) => {
    const d = dragRef.current;
    if (!d) return;
    e.preventDefault();
    // where the pointer is as it lets go, which the last line drawn may be a move behind
    const o = dropAt(d, e.clientY) ?? overRef.current;
    if (o) changeRail((l) => dropEntry(l, d.id, o));
    endDrag();
  };
  const dropOf = (id: string) => (over?.id === id ? over.where : undefined);

  const renderServer = (id: string, folder: ServerFolder | null) => {
    const st = servers[id];
    if (!st) return null;
    const url = iconUrl(st.server.icon_id, 48);
    const u = serverUnread(id, channels, reads, settings);
    const selected = activeServer === id;
    const muted = settings.notifications.muted.includes(id);
    const inVoice = voiceLive.has(id);
    const label = u.mentions > 0 ? t("rail.rail.serverWithMentions", { name: st.server.name, count: u.mentions }) : st.server.name;
    return (
      <div
        key={id}
        className={[s.item, s.server].join(" ")}
        data-selected={selected}
        data-voice={inVoice || undefined}
        data-mentioned={u.mentions > 0 || undefined}
        data-unread={u.unread && !selected}
        data-muted={muted}
        data-drop={dropOf(id)}
        data-dragging={drag?.id === id}
        data-rail-row={id}
        data-rail-parent={folder?.id}
        draggable={canDrag}
        onDragStart={(e) => onDragStart(e, id, false)}
        onContextMenu={(e) => onServerContext(e, id)}
        onKeyDown={reorder.onKeyDown(id)}
        data-server={id}
        data-reorder-id={id}
        data-picked={picked.includes(id) || undefined}
      >
        <span className={s.pill} />
        <Tooltip text={st.server.name} side="right">
          <button
            className={[s.btn, url ? s.hasIcon : ""].join(" ")}
            // Home and Waiting already fold their count into the name and
            // hide the badge; the servers did neither, so a badge announced
            // itself as a loose "3".
            aria-label={inVoice ? t("rail.rail.serverInVoice", { server: label }) : label}
            aria-current={selected ? "page" : undefined}
            aria-pressed={isPicking ? picked.includes(id) : undefined}
            onClick={(e) => {
              // Ctrl or ⌘ picks a server, Shift a run of them, a tap one while picking
              if (pickClick(e, "servers", id, serverOrder)) return;
              openServer(id);
            }}
          >
            {url ? <img src={url} alt="" draggable={false} /> : <span className={s.initials}>{initials(st.server.name)}</span>}
          </button>
        </Tooltip>
        {inVoice ? (
          <span className={s.voiceBadge} aria-hidden="true" data-testid="rail-voice">
            <Volume2 size={12} strokeWidth={2.6} />
          </span>
        ) : null}
        {u.mentions > 0 ? <span className={s.badge} aria-hidden="true">{u.mentions > 99 ? "99+" : u.mentions}</span> : null}
        {isPicking ? <span className={s.pickMark}><PickMark on={picked.includes(id)} label={t("pick.pickNamed", { name: st.server.name })} /></span> : null}
      </div>
    );
  };

  const renderFolder = (f: ServerFolder) => {
    const ids = f.servers.filter((id) => servers[id]);
    if (ids.length === 0) return null;
    const open = !!f.open;
    let fUnread = false;
    let mentions = 0;
    for (const id of ids) {
      const u = serverUnread(id, channels, reads, settings);
      if (u.unread) fUnread = true;
      mentions += u.mentions;
    }
    // a closed folder stands in for the server open inside it, and for a call going on in any of them
    const holdsActive = !!activeServer && ids.includes(activeServer);
    const inVoice = !open && ids.some((id) => voiceLive.has(id));
    const name = folderName(f);
    const label = t("organize.rail.folderLabel", { name, count: ids.length });
    const drop = dropOf(f.id);
    // a server about to land among this folder's servers lights the sleeve it lands in
    const into = open && !!over && over.where !== "combine" && ids.includes(over.id) && over.id !== drag?.id;
    return (
      <div
        key={f.id}
        className={s.folder}
        data-open={open}
        data-into={into || undefined}
        data-drop={drop === "before" || drop === "after" ? drop : undefined}
        data-dragging={drag?.id === f.id}
        data-folder={f.id}
        style={{ "--folder": f.color ?? "var(--brand)" } as CSSProperties}
        // open, it holds its servers, and a screen reader hears where they end
        {...(open ? { role: "group", "aria-label": name } : {})}
      >
        <div
          className={[s.item, s.folderItem].join(" ")}
          data-selected={!open && holdsActive}
          data-unread={!open && fUnread && !holdsActive}
          data-voice={inVoice || undefined}
          data-mentioned={(!open && mentions > 0) || undefined}
          data-drop={drop === "combine" ? "combine" : undefined}
          data-rail-row={f.id}
          data-rail-folder=""
          draggable={canDrag}
          onDragStart={(e) => onDragStart(e, f.id, true)}
          onContextMenu={(e) => onFolderContext(e, f)}
          onKeyDown={reorder.onKeyDown(f.id)}
          data-reorder-id={f.id}
        >
          <span className={s.pill} />
          <Tooltip text={name} side="right">
            <button
              className={[s.btn, s.folderBtn].join(" ")}
              aria-expanded={open}
              aria-label={((l: string) => (inVoice ? t("rail.rail.serverInVoice", { server: l }) : l))(!open && mentions > 0 ? t("rail.rail.serverWithMentions", { name: label, count: mentions }) : label)}
              onClick={() => toggleFolder(f)}
              data-testid="folder-button"
            >
              <FolderFace serverIds={ids} open={open} />
            </button>
          </Tooltip>
          {inVoice ? (
            <span className={s.voiceBadge} aria-hidden="true" data-testid="rail-voice">
              <Volume2 size={12} strokeWidth={2.6} />
            </span>
          ) : null}
          {!open && mentions > 0 ? <span className={s.badge} aria-hidden="true">{badgeText(mentions)}</span> : null}
        </div>
        {open ? ids.map((id) => renderServer(id, f)) : null}
      </div>
    );
  };

  const renderEntry = (e: RailEntry) => (typeof e === "string" ? renderServer(e, null) : renderFolder(e));
  const bulk = isPicking ? railBulk(picked, { nav, openModal }) : null;

  return (
    <nav className={s.rail} aria-label={t("rail.rail.servers")} ref={railRef} onDragOver={onRailDragOver} onDragEnter={onRailDragOver} onDragLeave={onRailDragLeave} onDrop={onDrop} onDragEnd={endDrag}>
      {reorder.live}
      <div className={s.item} data-selected={home} data-unread={friendRequests > 0 && !home} data-testid="home">
        <span className={s.pill} />
        <Tooltip text={t("sidebar.dMSidebar.directMessages")} side="right">
          <button className={s.btn} aria-label={friendRequests > 0 ? t("rail.rail.directMessagesWith", { what: homeLabel(0, friendRequests) }) : t("sidebar.dMSidebar.directMessages")} onClick={() => nav("/channels/@me", { state: { keepDrawer: true } })}>
            <NexaMark size={30} />
          </button>
        </Tooltip>
        {friendRequests > 0 ? <span className={s.badge} aria-hidden="true" data-testid="home-count">{badgeText(friendRequests)}</span> : null}
      </div>
      {isAdmin ? (
        <div className={s.item} data-selected={inPanel} data-unread={needsReply > 0 && !inPanel} data-testid="rail-moderation">
          <span className={s.pill} />
          <Tooltip text={t("moderation.title")} side="right">
            <button
              className={s.btn}
              aria-label={needsReply > 0 ? t("moderation.railWithCount", { count: needsReply }) : t("moderation.title")}
              onClick={() => nav("/channels/@admin")}
              onContextMenu={(e) => {
                e.preventDefault();
                moderationMenu.open(null, { ...menuPoint(e), label: t("moderation.title") });
              }}
            >
              <Shield size={24} />
            </button>
          </Tooltip>
          {needsReply > 0 ? <span className={s.badge} aria-hidden="true">{badgeText(needsReply)}</span> : null}
        </div>
      ) : null}
      {waiting.map(({ channel: ch, count }) => {
        if (!ch) {
          const label = t("rail.rail.channelUnread", { name: "Nexa", what: count > 99 ? t("rail.rail.moreThan99Unread") : t("rail.rail.unreadMessagesCount", { count }) });
          return (
            <div key="nexa" className={[s.item, s.waiting].join(" ")} data-unread="true" data-waiting="nexa">
              <span className={s.pill} />
              <Tooltip text="Nexa" side="right">
                <button
                  className={[s.btn, s.hasIcon].join(" ")}
                  aria-label={label}
                  onClick={() => {
                    nav("/channels/@me/nexa");
                    useUI.getState().setLeftOpen(false);
                  }}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    nexaMenu.open({ nav }, { ...menuPoint(e), label: "Nexa" });
                  }}
                >
                  <NexaAvatar size={48} />
                </button>
              </Tooltip>
              <span className={s.badge} aria-hidden="true" data-testid="waiting-count">{badgeText(count)}</span>
            </div>
          );
        }
        const name = channelDisplayName(ch, users, meId, nicknames);
        const other = ch.type === "dm" ? (ch.recipient_ids.find((id) => id !== meId) ?? ch.recipient_ids[0] ?? "") : "";
        const label = t("rail.rail.channelUnread", { name, what: count > 99 ? t("rail.rail.moreThan99Unread") : t("rail.rail.unreadMessagesCount", { count }) });
        return (
          <div key={ch.id} className={[s.item, s.waiting].join(" ")} data-unread="true" data-waiting={ch.id}>
            <span className={s.pill} />
            <Tooltip text={name} side="right">
              <button
                className={[s.btn, s.hasIcon].join(" ")}
                aria-label={label}
                onClick={() => {
                  nav(`/channels/@me/${ch.id}`);
                  useUI.getState().setLeftOpen(false);
                }}
                onContextMenu={(e) => {
                  e.preventDefault();
                  dmMenu.open({ channelId: ch.id, nav, openModal }, { x: e.clientX, y: e.clientY });
                }}
              >
                {ch.type === "dm" ? (
                  <Avatar id={other} avatarId={users[other]?.avatar_id ?? null} name={name} size={48} />
                ) : ch.icon_id ? (
                  <Avatar id={ch.id} avatarId={ch.icon_id} name={name} size={48} kind="icon" />
                ) : (
                  <span className={s.groupIcon}><Users size={24} /></span>
                )}
              </button>
            </Tooltip>
            <span className={s.badge} aria-hidden="true" data-testid="waiting-count">{badgeText(count)}</span>
          </div>
        );
      })}
      <div className={s.sep} ref={sepRef} />
      {layout.pinned.length > 0 ? (
        // named, so a screen reader hears where the pinned ones end
        <div className={s.section} role="group" aria-label={t("organize.pinned")} data-testid="rail-pinned">
          {layout.pinned.map(renderEntry)}
        </div>
      ) : null}
      {layout.pinned.length > 0 && layout.rest.length > 0 ? (
        <div className={s.pinSep} aria-hidden="true">
          <Pin size={12} />
        </div>
      ) : null}
      {layout.rest.map(renderEntry)}
      <div className={[s.item, s.add].join(" ")}>
        <span className={s.pill} />
        <Tooltip text={t("rail.rail.addAServer")} side="right">
          <button className={s.btn} aria-label={t("rail.rail.addAServer")} data-testid="add-server" onClick={() => openModal("createServer")}>
            <Plus size={24} />
          </button>
        </Tooltip>
      </div>
      <div className={[s.item, s.add].join(" ")} data-selected={loc.pathname.startsWith("/channels/@discover")} data-testid="rail-discover">
        <span className={s.pill} />
        <Tooltip text={t("discovery.title")} side="right">
          <button className={s.btn} aria-label={t("discovery.title")} aria-current={loc.pathname.startsWith("/channels/@discover") ? "page" : undefined} onClick={() => { nav("/channels/@discover"); useUI.getState().setLeftOpen(false); }}>
            <Compass size={24} />
          </button>
        </Tooltip>
      </div>
      {/* the rail is too narrow for it: the bar floats over the foot of the column beside */}
      {bulk ? (
        <div className={s.pickBar}>
          <PickBar list="servers" count={picked.length} label={t("pick.serverBar")} actions={bulk.bar} more={bulk.menu} />
        </div>
      ) : null}
    </nav>
  );
}
