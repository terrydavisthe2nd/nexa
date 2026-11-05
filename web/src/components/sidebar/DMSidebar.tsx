import { useMediaAnimation } from "@/lib/mediaAnimation";
import { withOwn } from "@/lib/useMemberLook";
import { nameClass, nameStyle } from "@/lib/roleLook";
import { ChevronDown, GripVertical, Lock, MessageCircle, MoreHorizontal, Heart, NotebookPen, Pin, Plus, Search, Shield, Users, X } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";
import { Avatar, showsTyping } from "@/components/common/Avatar";
import { menuAnchor } from "@/components/common/ContextMenu";
import { StaffMark } from "@/components/common/OwnerBadge";
import { rankOf, type StaffRank } from "@/lib/staffRank";
import { PickBar, PickMark } from "@/components/common/Pick";
import { useMediaQuery } from "@/hooks/useIsMobile";
import { useReorder } from "@/hooks/useReorder";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { compareIds, idAt } from "@/lib/ids";
import { dmListView, dropDM, groupOf, isGroupId, moveInMain, movePinnedDM, NEXA_ID, pruneDMs, rowId, setGroupCollapsed, type DMDrop, type DMRow } from "@/lib/dmLayout";
import { useOwnerId } from "@/lib/owner";
import { useAuth } from "@/stores/auth";
import { selectDMs, selectSubchats, useChannels } from "@/stores/channels";
import { activitiesOf, customStatus, statusOf, usePresence, type StatusKind } from "@/stores/presence";
import { useTypingIn } from "@/stores/typing";
import { StatusLine } from "@/components/activity/StatusLine";
import { isUnread, useReadStates } from "@/stores/readStates";
import { useUI } from "@/stores/ui";
import type { MenuPlacement } from "@/lib/contextMenus/registry";
import { useUsers } from "@/stores/users";
import { useRelationships } from "@/stores/relationships";
import { pickClick, usePick } from "@/stores/pick";
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent, type MouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { iconUrl } from "@/api/users";
import { subchatMenu } from "@/components/chat/subchatMenu";
import { badgeText, conversationCount } from "@/lib/unread";
import { useSettings } from "@/stores/settings";
import { modKeys } from "@/lib/textMenu";
import { textWidth } from "@/lib/fitText";
import { closeDM, dmMenu } from "./dmMenu";
import { dmBulk } from "./dmBulk";
import { changeDMList, closeNexa, dmGroupMenu, dmListMenu, groupName, nexaListed, nexaMenu } from "./dmArrange";
import { menuPoint } from "./ServerSidebar";
import { NexaAvatar, OfficialBadge } from "@/components/nexa/NexaBits";
import { useNexa } from "@/stores/nexa";
import { useNotes } from "@/stores/notes";
import { attentionCount, useModeration } from "@/stores/moderation";
import { moderationMenu } from "@/components/moderation/modMenu";
import { UserTag } from "@/components/servertag/UserTag";
import { BotTag } from "@/components/apps/BotTag";
import s from "./Sidebar.module.css";

import { t } from "@/i18n";
import { useMyRank } from "@/lib/staffRank";

/** What a dragged conversation or group is over, and what dropping it there would do. */
type Over = { at: string; where?: "before" | "after" | "into" | "main" | "top"; drop: DMDrop };

/** Where a row is drawn: among the pins, in the main list, or inside one of its groups. */
type Place = "pinned" | "main" | "group";

const NO_PICKS: string[] = [];
/** How near the top or bottom of the list a finger dragging a row has to be for the list to scroll. */
const EDGE = 56;

export function DMSidebar() {
  const nav = useNavigate();
  const loc = useLocation();
  const meId = useAuth((a) => a.me?.id);
  const channels = useChannels((c) => c.byId);
  const users = useUsers((u) => u.byId);
  const presence = usePresence((p) => p.byUser);
  const reads = useReadStates((r) => r.byChannel);
  const settings = useSettings((st) => st.settings);
  const patch = useSettings((st) => st.patch);
  const openModal = useUI((u) => u.openModal);
  const setQuickSwitcher = useUI((u) => u.setQuickSwitcher);
  const rels = useRelationships((r) => r.byUser);
  const nicknames = useMemo(() => friendNicknames(rels), [rels]);
  const friendRequests = useMemo(() => Object.values(rels).filter((r) => r.type === "incoming").length, [rels]);
  // notes people asked to share, waiting for an answer
  const noteRequests = useNotes((n) => n.invites.length);
  const ownerId = useOwnerId();
  const nexa = useNexa((n) => n.state);
  const nexaOpen = loc.pathname.startsWith("/channels/@me/nexa");
  // Nexa's conversation is listed once Nexa has said something, and after it
  // was closed only once it says something new; while it is open it shows.
  const nexaShown = !!nexa && nexa.latest > 0 && (nexaListed(nexa.latest, settings.nexaClosed) || nexaOpen);
  // every rank of staff opens the panel
  const isAdmin = useMyRank() !== null;
  // cases the member wrote in last, and bug reports waiting for an answer
  // case threads and bug reports waiting on the staff that hold something this person has not read
  const needsReply = useModeration(attentionCount);
  const active = loc.pathname.match(/^\/channels\/@me\/(\d+)/)?.[1];
  // A subchat keeps its conversation highlighted, with its siblings listed
  // underneath, so you can see where you are.
  const activeChannel = active ? channels[active] : undefined;
  const activeConvo = activeChannel?.type === "subchat" ? (activeChannel.parent_id ?? undefined) : active;
  const layout = settings.dmList;
  // HTML5 dragging is for a mouse; on a touch screen rows are dragged by their handles while picking
  const canDrag = useMediaQuery("(hover: hover) and (pointer: fine)");
  const [drag, setDragShown] = useState<string | null>(null);
  const [over, setOverShown] = useState<Over | null>(null);
  // The same two for the drag events themselves, which come faster than the
  // list is drawn: one reading the state could still see no drag at all, and
  // a drop could land where the pointer was a moment before.
  const dragRef = useRef<string | null>(null);
  const overRef = useRef<Over | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // picking several conversations at once (stores/pick.ts)
  const picked = usePick((p) => (p.list === "dms" ? p.ids : NO_PICKS));
  const pickMode = usePick((p) => p.list === "dms" && p.mode);
  const isPicking = pickMode || picked.length > 0;
  // a finger drags a row by its handle, which the rows grow while picking by tap
  const handles = pickMode && !canDrag;

  // Going to the Nexa conversation while it is closed opens it again, as
  // messaging someone brings back a conversation that was closed. It being
  // closed while it is on screen is the other case: another device of this
  // account closed it (this one closes it by leaving first), so this device
  // goes to the default screen. Reopening it there undid the close on every
  // device, which made it come back for no reason anyone could see.
  const settingsLoaded = useSettings((st) => st.loaded);
  const nexaWas = useRef<{ open: boolean; listed: boolean | null }>({ open: false, listed: null });
  useEffect(() => {
    if (!settingsLoaded || !nexa || nexa.latest <= 0) return;
    const listed = nexaListed(nexa.latest, settings.nexaClosed);
    const was = nexaWas.current;
    nexaWas.current = { open: nexaOpen, listed };
    if (!nexaOpen || listed) return;
    if (!was.open) patch({ nexaClosed: 0 });
    else if (was.listed) nav("/channels/@me", { replace: true });
  }, [settingsLoaded, nexaOpen, nexa, settings.nexaClosed, patch, nav]);

  // Picking something closes the phone drawer, even the page already open,
  // which navigating to the same address would not.
  const go = (path: string) => {
    nav(path);
    useUI.getState().setLeftOpen(false);
  };

  const dms = useMemo(() => selectDMs(channels), [channels]);
  const view = useMemo(() => {
    const ids = [...dms.map((c) => c.id), ...(nexaShown ? [NEXA_ID] : [])];
    const byId = new Map(dms.map((c) => [c.id, c]));
    const known = (id: string) => (id === NEXA_ID ? nexaShown : byId.has(id));
    const activity = (id: string) => (id === NEXA_ID ? idAt(nexa?.latest ?? 0) : (byId.get(id)?.last_message_id ?? id));
    return dmListView(layout, ids, known, activity, compareIds);
  }, [dms, nexaShown, nexa?.latest, layout]);
  /** The main list's rows as they are shown, which a drop beside one of them puts in order. */
  const restIds = useMemo(() => view.rest.map(rowId), [view.rest]);
  /** Every conversation as the list shows it, top to bottom, for a Shift-click to pick a run of. */
  const shownOrder = useMemo(() => {
    const out: string[] = [];
    const add = (r: DMRow) => (r.kind === "group" ? out.push(...r.ids) : out.push(r.id));
    view.pinned.forEach(add);
    view.rest.forEach(add);
    return out;
  }, [view]);
  // what was picked and has since gone (closed, left) is let go of
  useEffect(() => {
    if (!picked.length) return;
    const gone = picked.filter((id) => !shownOrder.includes(id));
    if (gone.length) usePick.getState().drop(gone);
  }, [picked, shownOrder]);

  /** What waits in a conversation: its count, and whether anything is unread at all (a muted one counts only mentions). */
  const waitingIn = (id: string): { count: number; unread: boolean } => {
    if (id === NEXA_ID) return { count: nexaOpen ? 0 : (nexa?.unread ?? 0), unread: !nexaOpen && (nexa?.unread ?? 0) > 0 };
    const ch = channels[id];
    if (!ch) return { count: 0, unread: false };
    const rs = reads[id];
    return { count: active === id ? 0 : conversationCount(ch, rs, settings), unread: active !== id && isUnread(rs, ch.last_message_id) };
  };

  // Pinned conversations and groups move up and down among themselves, with
  // Alt and an arrow or from their menus, and so do the main list's rows,
  // which then stay put until something new happens in them.
  const pinnedIds = view.pinned.map((r) => (r.kind === "group" ? r.group.id : r.id));
  const reorder = useReorder({
    ids: pinnedIds,
    canMove: (id) => pinnedIds.includes(id),
    name: (id) => rowName(id),
    // pins of conversations that have gone are dropped first, or a move could land beside one that is not drawn
    move: (id, delta) => changeDMList((l) => movePinnedDM(pruneDMs(l, (x) => x === NEXA_ID || !!channels[x]), id, delta)),
  });
  const reorderMain = useReorder({
    ids: restIds,
    canMove: (id) => restIds.includes(id),
    name: (id) => rowName(id),
    move: (id, delta) => {
      const to = restIds[restIds.indexOf(id) + delta];
      return !!to && changeDMList((l) => moveInMain(l, id, to, delta > 0 ? "after" : "before", restIds, view.keys, view.now));
    },
  });
  const movesFor = (id: string, place: Place) => (place === "pinned" ? reorder.menuItems(id) : place === "main" ? reorderMain.menuItems(id) : []);

  function rowName(id: string): string {
    if (id === NEXA_ID) return "Nexa";
    if (isGroupId(id)) {
      const g = layout.groups.find((x) => x.id === id);
      return g ? groupName(g) : "";
    }
    const ch = channels[id];
    return ch ? channelDisplayName(ch, users, meId, nicknames) : "";
  }

  // --- dragging -------------------------------------------------------------
  // A mouse drags with the browser's own drag and drop; a finger drags a row
  // by its handle. Both ask overAt what is under them, from the data the rows
  // carry, so a drop means the same thing however it was made.

  const setDrag = (id: string | null) => {
    dragRef.current = id;
    setDragShown(id);
  };
  const setOver = (next: Over | null) => {
    const was = overRef.current;
    overRef.current = next;
    if (was?.at !== next?.at || was?.where !== next?.where) setOverShown(next);
  };
  const endDrag = () => {
    setDrag(null);
    setOver(null);
  };
  const onDragStart = (e: DragEvent, id: string) => {
    e.stopPropagation();
    setDrag(id);
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", id);
  };
  const half = (el: Element, y: number): "before" | "after" => {
    const r = el.getBoundingClientRect();
    return y < r.top + r.height / 2 ? "before" : "after";
  };

  /** What dropping the dragged row at this point of the list would do; null where it would do nothing. */
  const overAt = (el: Element | null, y: number): Over | null => {
    const d = dragRef.current;
    if (!d || !el) return null;
    if (el.closest("[data-pin-zone], [data-dm-head='pinned']")) return { at: "top", where: "top", drop: { kind: "pinned", index: 0 } };
    const row = el.closest<HTMLElement>("[data-dm-row]");
    if (row && row.dataset["place"] !== "group") {
      const id = row.dataset["dmRow"]!;
      if (id === d) return null;
      const where = half(row, y);
      if (row.dataset["place"] === "pinned") {
        const at = layout.pins.indexOf(id);
        return at < 0 ? null : { at: id, where, drop: { kind: "pinned", index: at + (where === "after" ? 1 : 0) } };
      }
      return { at: id, where, drop: { kind: "main", at: id, where, rest: restIds, keys: view.keys, now: view.now } };
    }
    const groupEl = el.closest<HTMLElement>("[data-group]");
    if (groupEl) {
      const gid = groupEl.dataset["group"]!;
      if (gid === d || !layout.groups.some((g) => g.id === gid)) return null;
      // a conversation goes into a group
      if (!isGroupId(d)) return groupOf(layout, d)?.id === gid ? null : { at: gid, where: "into", drop: { kind: "group", groupId: gid } };
      // a group goes beside another, among the pins or in the main list
      const where = half(groupEl, y);
      const at = layout.pins.indexOf(gid);
      if (at >= 0) return { at: gid, where, drop: { kind: "pinned", index: at + (where === "after" ? 1 : 0) } };
      return { at: gid, where, drop: { kind: "main", at: gid, where, rest: restIds, keys: view.keys, now: view.now } };
    }
    if (el.closest("[data-dm-head='main'], [data-dm-main]")) {
      // back to the main list from the pins or a group; anything already in it stays where it is
      if (!layout.pins.includes(d) && !groupOf(layout, d)) return null;
      return { at: "main", where: "main", drop: { kind: "main" } };
    }
    return null;
  };

  const onListDragOver = (e: DragEvent) => {
    if (!dragRef.current) return;
    const next = overAt(e.target as Element, e.clientY);
    setOver(next);
    if (!next) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
  };
  const onListDrop = (e: DragEvent) => {
    e.preventDefault();
    const id = dragRef.current;
    const target = overRef.current;
    if (id && target) changeDMList((l) => dropDM(l, id, target.drop));
    endDrag();
  };
  const dropAt = (id: string) => (over?.at === id ? over.where : undefined);

  // a finger dragging a row by its handle: a chip with its name follows the finger
  const [ghost, setGhost] = useState<{ x: number; y: number; name: string } | null>(null);
  const finger = useRef<{ pointer: number; x: number; y: number; frame: number } | null>(null);
  const aim = () => {
    const f = finger.current;
    if (!f) return;
    setOver(overAt(document.elementFromPoint(f.x, f.y), f.y));
  };
  /** Near the top or bottom of the list, it scrolls under the finger. */
  const creep = () => {
    const f = finger.current;
    const box = scrollRef.current;
    if (!f || !box) return;
    const r = box.getBoundingClientRect();
    const speed = f.y < r.top + EDGE ? -(r.top + EDGE - f.y) / 4 : f.y > r.bottom - EDGE ? (f.y - (r.bottom - EDGE)) / 4 : 0;
    if (speed) {
      box.scrollTop += speed;
      aim();
    }
    f.frame = requestAnimationFrame(creep);
  };
  const handleDown = (e: ReactPointerEvent<HTMLElement>, id: string) => {
    e.preventDefault();
    e.stopPropagation();
    e.currentTarget.setPointerCapture?.(e.pointerId);
    setDrag(id);
    finger.current = { pointer: e.pointerId, x: e.clientX, y: e.clientY, frame: 0 };
    finger.current.frame = requestAnimationFrame(creep);
    setGhost({ x: e.clientX, y: e.clientY, name: rowName(id) });
    try {
      navigator.vibrate?.(8);
    } catch {
      /* no haptics here */
    }
  };
  const handleMove = (e: ReactPointerEvent<HTMLElement>) => {
    const f = finger.current;
    if (!f || f.pointer !== e.pointerId) return;
    f.x = e.clientX;
    f.y = e.clientY;
    setGhost((g) => (g ? { ...g, x: f.x, y: f.y } : g));
    aim();
  };
  const handleUp = (e: ReactPointerEvent<HTMLElement>, cancelled = false) => {
    const f = finger.current;
    if (!f || f.pointer !== e.pointerId) return;
    cancelAnimationFrame(f.frame);
    finger.current = null;
    const id = dragRef.current;
    const target = overRef.current;
    if (!cancelled && id && target) changeDMList((l) => dropDM(l, id, target.drop));
    endDrag();
    setGhost(null);
  };
  useEffect(() => () => cancelAnimationFrame(finger.current?.frame ?? 0), []);

  // --- rows -------------------------------------------------------------------

  /** A click on a row: picking (Ctrl or ⌘, Shift, or a tap while picking), or opening it. */
  const openRow = (e: MouseEvent, id: string, path: string) => {
    if (pickClick(e, "dms", id, shownOrder)) {
      e.preventDefault();
      return;
    }
    go(path);
  };

  const openRowMenu = (e: MouseEvent, id: string, place: Place) => {
    e.preventDefault();
    const pick = usePick.getState();
    // a right click on one of several picked acts on all of them
    if (pick.list === "dms" && pick.ids.includes(id) && pick.ids.length > 1) {
      dmBulk(pick.ids, { nav, openModal }).menu({ ...menuPoint(e), label: t("pick.count", { count: pick.ids.length }) });
      return;
    }
    // on one that is not picked, it is about that one, as a file manager does
    if (pick.list === "dms" && !pick.mode && pick.ids.length) pick.clear();
    const moves = movesFor(id, place);
    const at = { ...menuPoint(e), label: rowName(id) };
    if (id === NEXA_ID) nexaMenu.open({ nav, arrange: { moves }, select: true }, at);
    else dmMenu.open({ channelId: id, nav, openModal, moves, select: true }, at);
  };

  /** Props every conversation row takes for being dragged, picked and arranged. */
  const rowProps = (id: string, place: Place) => {
    const keys = place === "pinned" ? reorder.onKeyDown(id) : place === "main" ? reorderMain.onKeyDown(id) : undefined;
    return {
      draggable: canDrag,
      "data-dm-row": id,
      "data-place": place,
      "data-dragging": drag === id,
      "data-drop": place === "group" ? undefined : dropAt(id),
      "data-reorder-id": keys ? id : undefined,
      "data-picked": picked.includes(id) || undefined,
      onDragStart: (e: DragEvent) => onDragStart(e, id),
      onKeyDown: keys,
    };
  };

  /** The circle in front of a row while the list is picked from. */
  const mark = (id: string) => (isPicking ? <PickMark on={picked.includes(id)} label={t("pick.pickNamed", { name: rowName(id) })} /> : null);
  /** The handle a finger drags a row by, in place of its ✕ while picking by tap. */
  const handle = (id: string) =>
    handles ? (
      <span
        className={s.dragHandle}
        aria-hidden="true"
        data-testid="dm-drag-handle"
        onPointerDown={(e) => handleDown(e, id)}
        onPointerMove={handleMove}
        onPointerUp={(e) => handleUp(e)}
        onPointerCancel={(e) => handleUp(e, true)}
      >
        <GripVertical size={18} />
      </span>
    ) : null;

  const nexaRow = (place: Place) => {
    if (!nexa) return null;
    const { count } = waitingIn(NEXA_ID);
    return (
      <div key={NEXA_ID} className={s.item} data-selected={nexaOpen} data-unread={count > 0} data-testid="nexa-dm" onContextMenu={(e) => openRowMenu(e, NEXA_ID, place)} {...rowProps(NEXA_ID, place)}>
        <button type="button" className={s.itemMain} onClick={(e) => openRow(e, NEXA_ID, "/channels/@me/nexa")}>
          {mark(NEXA_ID)}
          <NexaAvatar size={32} />
          <span className={s.text}>
            <span className={s.name} style={{ display: "flex", alignItems: "center", gap: 6 }}>Nexa <OfficialBadge compact /></span>
            <span className={s.sub}>{t("nexa.sidebarSub")}</span>
          </span>
        </button>
        {count > 0 ? <span className={s.mention} data-testid="nexa-unread" aria-label={t("nexa.unreadCount", { count })}>{badgeText(count)}</span> : null}
        {handles ? handle(NEXA_ID) : isPicking ? null : (
          <button
            type="button"
            className={s.close}
            aria-label={t("sidebar.dMSidebar.closeDm")}
            title={t("sidebar.dMSidebar.closeDm")}
            data-testid="nexa-close"
            onClick={(e) => {
              e.stopPropagation();
              closeNexa(nav);
            }}
          >
            <X size={16} />
          </button>
        )}
      </div>
    );
  };

  const conversationRow = (id: string, place: Place) => {
    if (id === NEXA_ID) return nexaRow(place);
    const ch = channels[id];
    if (!ch) return null;
    const name = channelDisplayName(ch, users, meId, nicknames);
    const { count, unread } = waitingIn(id);
    let sub: React.ReactNode = null;
    let avatar: React.ReactNode;
    let rank: StaffRank | null = null;
    let tagOf: string | null = null;
    if (ch.type === "dm") {
      const other = ch.recipient_ids.find((uid) => uid !== meId) ?? ch.recipient_ids[0] ?? "";
      tagOf = other;
      const u = users[other];
      const st = customStatus(presence[other]);
      const act = activitiesOf(presence[other])[0];
      sub = <StatusLine className={s.sub} status={st} activity={act} more={Math.max(0, activitiesOf(presence[other]).length - 1)} />;
      avatar = <TypingAvatar channelId={ch.id} id={other} avatarId={u?.avatar_id ?? null} name={name} status={statusOf(presence[other])} />;
      rank = rankOf(other, other === meId ? undefined : u?.flags, ownerId);
    } else {
      sub = <span className={s.sub}>{t("chat.groupMemberList.membersOnly", { count: ch.recipient_ids.length })}</span>;
      avatar = <TypingAvatar channelId={ch.id} id={ch.id} avatarId={ch.icon_id} name={name} group />;
    }
    const look = withOwn(undefined, tagOf ? users[tagOf]?.name_style : null);
    const subs = !isPicking ? selectSubchats(channels, ch.id).filter((sc) => activeConvo === ch.id || isUnread(reads[sc.id], sc.last_message_id)) : [];
    return (
      <div key={ch.id}>
        {/* A row of two buttons rather than one button with a role="button"
            span inside it: nested interactive content is invalid, and the
            close control it advertised could not be reached by Tab at all,
            so no keyboard could ever close a conversation from here. */}
        <div
          className={s.item}
          data-selected={active === ch.id}
          data-parent-selected={activeConvo === ch.id && active !== ch.id}
          data-unread={unread}
          data-channel={ch.id}
          onContextMenu={(e) => openRowMenu(e, ch.id, place)}
          {...rowProps(ch.id, place)}
        >
          <button type="button" className={s.itemMain} onClick={(e) => openRow(e, ch.id, `/channels/@me/${ch.id}`)} aria-pressed={isPicking ? picked.includes(ch.id) : undefined}>
            {mark(ch.id)}
            {avatar}
            <span className={s.text}>
              <span className={s.nameRow}>
                <span className={[s.name, nameClass(look)].join(" ")} style={nameStyle(look)} dir="auto">{name}</span>
                {/* Nexa's staff carry their rank's mark wherever they are listed */}
                {rank ? <StaffMark rank={rank} size={14} /> : null}
                {tagOf ? <BotTag userId={tagOf} /> : null}
                {tagOf ? <UserTag userId={tagOf} size="sm" /> : null}
              </span>
              {sub}
            </span>
            {/* the count is said with the conversation's name, the button being what a screen reader lands on */}
            {count > 0 ? <span className="visually-hidden">{`, ${count > 99 ? t("rail.rail.moreThan99Unread") : t("rail.rail.unreadMessagesCount", { count })}`}</span> : null}
          </button>
          {count > 0 ? <span className={s.mention} data-testid="dm-unread" aria-hidden="true">{badgeText(count)}</span> : null}
          {handles ? handle(ch.id) : isPicking ? null : (
            <button
              type="button"
              className={s.close}
              aria-label={ch.type === "group_dm" ? t("sidebar.dMSidebar.leaveGroup") : t("sidebar.dMSidebar.closeDm")}
              title={ch.type === "group_dm" ? t("sidebar.dMSidebar.leaveGroup") : t("sidebar.dMSidebar.closeDm")}
              onClick={(e) => {
                e.stopPropagation();
                if (ch.type === "group_dm") dmMenu.open({ channelId: ch.id, nav, openModal }, { x: e.clientX, y: e.clientY });
                else void closeDM(ch.id, nav);
              }}
            >
              <X size={16} />
            </button>
          )}
        </div>
        {subs.map((sc) => {
          const srs = reads[sc.id];
          const count = isUnread(srs, sc.last_message_id) ? Math.max(srs?.unread ?? 0, srs?.mentions ?? 0) : 0;
          return (
            <button
              key={sc.id}
              className={s.subItem}
              data-selected={active === sc.id}
              data-unread={isUnread(srs, sc.last_message_id) && active !== sc.id}
              data-channel={sc.id}
              onClick={() => go(`/channels/@me/${sc.id}`)}
              onContextMenu={(e) => {
                e.preventDefault();
                subchatMenu.open({ channel: sc, openModal, nav }, { x: e.clientX, y: e.clientY });
              }}
            >
              {sc.locked ? <Lock size={15} /> : <MessageCircle size={15} />}
              <span className={s.name}>{sc.name}</span>
              {count > 0 ? <span className={s.mention} data-testid="subchat-unread" aria-label={t("rail.rail.unreadMessagesCount", { count })}>{badgeText(count)}</span> : null}
            </button>
          );
        })}
      </div>
    );
  };

  const groupSection = (row: Extract<DMRow, { kind: "group" }>, pinned: boolean) => {
    const g = row.group;
    const name = groupName(g);
    const collapsed = !!g.collapsed;
    let count = 0;
    let anyUnread = false;
    for (const id of row.ids) {
      const w = waitingIn(id);
      count += w.count;
      if (w.unread) anyUnread = true;
    }
    // collapsed, it still shows the conversation open in it, so you can see where you are
    const shown = collapsed ? row.ids.filter((id) => id === activeConvo || (id === NEXA_ID && nexaOpen) || picked.includes(id)) : row.ids;
    const moves = pinned ? reorder.menuItems(g.id) : reorderMain.menuItems(g.id);
    const menu = (at: MenuPlacement) => dmGroupMenu.open({ group: g, openModal, unread: anyUnread, moves }, at);
    const keys = pinned ? reorder.onKeyDown(g.id) : reorderMain.onKeyDown(g.id);
    return (
      <div
        key={g.id}
        className={s.group}
        data-collapsed={collapsed}
        data-drop={dropAt(g.id)}
        data-dragging={drag === g.id}
        data-group={g.id}
        // named, so a screen reader hears which conversations are in it and where it ends
        role="group"
        aria-label={name}
      >
        <div
          className={s.groupHead}
          data-unread={collapsed && anyUnread}
          draggable={canDrag}
          onDragStart={(e) => onDragStart(e, g.id)}
          onContextMenu={(e) => {
            e.preventDefault();
            menu({ ...menuPoint(e), label: name });
          }}
          onKeyDown={keys}
          data-reorder-id={g.id}
        >
          <button type="button" className={s.groupToggle} aria-expanded={!collapsed} onClick={() => changeDMList((l) => setGroupCollapsed(l, g.id, !collapsed))} data-testid="dm-group-toggle">
            <ChevronDown size={14} className={s.chev} aria-hidden="true" />
            <span className={s.groupName} dir="auto">{name}</span>
            {collapsed && count > 0 ? <span className="visually-hidden">{`, ${count > 99 ? t("rail.rail.moreThan99Unread") : t("rail.rail.unreadMessagesCount", { count })}`}</span> : null}
          </button>
          {collapsed && count > 0 ? <span className={s.mention} aria-hidden="true" data-testid="dm-group-unread">{badgeText(count)}</span> : null}
          {handles ? handle(g.id) : (
            <button
              type="button"
              className={s.groupMore}
              aria-label={t("organize.dm.groupOptions", { name })}
              title={t("organize.dm.groupOptions", { name })}
              onClick={(e) => menu({ x: 0, y: 0, anchor: menuAnchor(e.currentTarget), label: name })}
              data-testid="dm-group-more"
            >
              <MoreHorizontal size={16} />
            </button>
          )}
        </div>
        {shown.map((id) => conversationRow(id, "group"))}
        {!collapsed && row.ids.length === 0 ? <div className={s.groupEmpty}>{t("organize.dm.emptyGroup")}</div> : null}
      </div>
    );
  };

  const renderRow = (row: DMRow, pinned: boolean) => (row.kind === "group" ? groupSection(row, pinned) : conversationRow(row.id, pinned ? "pinned" : "main"));
  const nothing = view.pinned.length === 0 && view.rest.length === 0;

  /**
   * The Direct Messages heading's menu: picking from the list. There is no
   * sorting to choose: the list goes by activity, and a conversation moved by
   * hand stays where it was put until something new happens in it.
   */
  const listMenu = (e: MouseEvent<HTMLButtonElement>) => dmListMenu.open(null, { x: 0, y: 0, anchor: menuAnchor(e.currentTarget), label: t("sidebar.dMSidebar.directMessages") });

  const bulk = isPicking ? dmBulk(picked, { nav, openModal }) : null;

  return (
    <div className={s.sidebar} onDragEnd={endDrag} data-picking={isPicking || undefined}>
      <div className={s.search}>
        <SearchButton onOpen={() => setQuickSwitcher(true)} />
      </div>
      <div ref={scrollRef} className={s.scroll} onDragOver={onListDragOver} onDragEnter={onListDragOver} onDrop={onListDrop}>
        {reorder.live}
        {reorderMain.live}
        {drag && view.pinned.length === 0 ? (
          // Nothing pinned yet: while something is dragged, a place to pin it.
          // It floats over the top of the list rather than pushing the list,
          // and the row being dragged, down from under the pointer.
          <div className={s.pinZoneWrap}>
            <div className={s.pinZone} data-pin-zone="" data-drop={dropAt("top") === "top" || undefined} data-testid="dm-pin-zone">
              <Pin size={14} aria-hidden="true" />
              {t("organize.dm.dropToPin")}
            </div>
          </div>
        ) : null}
        <button className={s.nav} data-selected={loc.pathname === "/channels/@me"} onClick={() => go("/channels/@me")} data-testid="nav-friends">
          <span className={s.navIcon}><Users size={22} /></span>
          <span className={s.navLabel}>{t("sidebar.dMSidebar.friends")}</span>
          {/* friend requests waiting for an answer, counted where the answering is done */}
          {friendRequests > 0 ? (
            <>
              <span className="visually-hidden">{`, ${t("rail.rail.friendRequestsCount", { count: friendRequests })}`}</span>
              <span className={s.mention} aria-hidden="true" data-testid="friend-requests">{badgeText(friendRequests)}</span>
            </>
          ) : null}
        </button>
        <button className={s.nav} data-selected={loc.pathname.startsWith("/channels/@me/notes")} onClick={() => go("/channels/@me/notes")} data-testid="nav-notes">
          <span className={s.navIcon}><NotebookPen size={22} /></span>
          <span className={s.navLabel}>{t("notes.title")}</span>
          {noteRequests > 0 ? (
            <>
              <span className="visually-hidden">{`, ${t("notes.requests.count", { count: noteRequests })}`}</span>
              <span className={s.mention} aria-hidden="true" data-testid="note-requests">{badgeText(noteRequests)}</span>
            </>
          ) : null}
        </button>
        <button className={s.nav} data-selected={loc.pathname.startsWith("/channels/@me/support")} onClick={() => go("/channels/@me/support")} data-testid="nav-support">
          <span className={s.navIcon} data-support=""><Heart size={22} /></span>
          <span className={s.navLabel}>{t("support.nav")}</span>
        </button>
        {isAdmin ? (
          <button
            className={s.nav}
            data-selected={loc.pathname.startsWith("/channels/@admin")}
            onClick={() => go("/channels/@admin")}
            onContextMenu={(e) => {
              e.preventDefault();
              moderationMenu.open(null, { ...menuPoint(e), label: t("moderation.title") });
            }}
            data-testid="nav-moderation"
          >
            <span className={s.navIcon}><Shield size={22} /></span>
            <span style={{ flex: 1, textAlign: "start" }}>{t("moderation.navLabel")}</span>
            {needsReply > 0 ? <span className={s.mention} aria-label={t("moderation.waitingCount", { count: needsReply })}>{badgeText(needsReply)}</span> : null}
          </button>
        ) : null}
        {view.pinned.length > 0 ? (
          <div role="group" aria-label={t("organize.pinned")} data-testid="dm-pinned">
            <div className={s.header} data-dm-head="pinned" data-drop={dropAt("top") === "top" || undefined}>
              <span className={s.headerLabel}><Pin size={12} aria-hidden="true" />{t("organize.pinned")}</span>
            </div>
            {view.pinned.map((r) => renderRow(r, true))}
          </div>
        ) : null}
        <div className={s.header} data-dm-head="main" data-drop={dropAt("main") === "main" || undefined} data-testid="dm-header">
          <span className={s.headerLabel}>{t("sidebar.dMSidebar.directMessages")}</span>
          <span className={s.headerBtns}>
            <button className={s.headerBtn} aria-label={t("pick.listOptions")} title={t("pick.listOptions")} onClick={listMenu} aria-haspopup="menu" data-testid="dm-list-options">
              <MoreHorizontal size={16} />
            </button>
            <button className={s.headerBtn} aria-label={t("sidebar.dMSidebar.createDm")} title={t("sidebar.dMSidebar.createDm")} onClick={() => openModal("createDM")}>
              <Plus size={16} />
            </button>
          </span>
        </div>
        {nothing ? <div className={s.empty}>{t("sidebar.dMSidebar.noConversationsYetAddAFriend")}</div> : null}
        <div data-dm-main="" data-testid="dm-main">
          {view.rest.map((r) => renderRow(r, false))}
        </div>
      </div>
      {bulk ? <PickBar list="dms" count={picked.length} label={t("pick.dmBar")} actions={bulk.bar} more={bulk.menu} /> : null}
      {ghost ? (
        <div className={s.ghost} style={{ transform: `translate(${ghost.x + 14}px, ${ghost.y - 18}px)` }} aria-hidden="true">
          <GripVertical size={14} />
          <span>{ghost.name}</span>
        </div>
      ) : null}
    </div>
  );
}

/**
 * The box that opens the quick switcher. Its words have to fit a sidebar the
 * person can make narrow, in every language: the shortcut beside them goes
 * first, then they give way to shorter ones, rather than being cut off as
 * "Find or Start a Conv…".
 */
function SearchButton({ onOpen }: { onOpen: () => void }) {
  const ref = useRef<HTMLButtonElement>(null);
  const keys = modKeys("K");
  const choices = [t("sidebar.dMSidebar.findOrStartAConversation"), t("sidebar.dMSidebar.findAConversation"), t("chat.chat.search")];
  const [fit, setFit] = useState<{ label: string; keys: boolean }>({ label: choices[0]!, keys: true });
  const key = choices.join("\u0000");
  useLayoutEffect(() => {
    const btn = ref.current;
    if (!btn) return;
    const measure = () => {
      const text = btn.querySelector<HTMLElement>("[data-label]");
      const icon = btn.querySelector<SVGElement>("svg");
      if (!text) return;
      const cs = getComputedStyle(btn);
      const gap = parseFloat(cs.columnGap) || 8;
      const room = btn.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0) - (icon?.getBoundingClientRect().width ?? 15) - gap;
      if (room <= 0) return;
      // the shortcut only where a keyboard is the way in
      const hasKeys = !!window.matchMedia?.("(pointer: fine)").matches;
      const kbd = btn.querySelector("kbd");
      const keysWidth = hasKeys && kbd ? (textWidth(keys, kbd) ?? 24) + 12 + gap : 0;
      const list = key.split("\u0000");
      const widths = list.map((l) => textWidth(l, text));
      if (widths.some((w) => w === null)) return;
      for (const withKeys of hasKeys ? [true, false] : [false]) {
        const at = widths.findIndex((w) => w! <= room - (withKeys ? keysWidth : 0) + 0.5);
        // the full words without the shortcut beat shorter words with it
        if (at === 0 || (at > 0 && !withKeys)) return setFit({ label: list[at]!, keys: withKeys });
      }
      setFit({ label: list[list.length - 1]!, keys: false });
    };
    measure();
    const ro = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(measure);
    ro?.observe(btn);
    document.fonts?.addEventListener?.("loadingdone", measure);
    return () => {
      ro?.disconnect();
      document.fonts?.removeEventListener?.("loadingdone", measure);
    };
  }, [key, keys]);
  return (
    <button ref={ref} className={s.searchBtn} onClick={onOpen} aria-label={choices[0]} data-testid="dm-search">
      <Search size={15} className={s.searchIcon} aria-hidden="true" />
      <span className={s.searchText} data-label="">{fit.label}</span>
      <kbd className={s.searchKeys} aria-hidden="true" data-hidden={fit.keys ? undefined : ""}>{keys}</kbd>
    </button>
  );
}

/**
 * A conversation's picture in the list. While someone in it types, the
 * presence dot becomes a pill of dots in their status's colour: the other
 * person's own dot in a DM, and in a group a dot that only shows then, in the
 * colour of whoever is typing. Nobody who shows as offline (or is invisible)
 * shows it, and the setting turns it off (Settings, Chat).
 */
function TypingAvatar({ channelId, id, avatarId, name, status = null, group = false }: { channelId: string; id: string; avatarId: string | null; name: string; status?: StatusKind | null; group?: boolean }) {
  useMediaAnimation();
  const meId = useAuth((a) => a.me?.id);
  const on = useSettings((x) => x.settings.typingAvatars !== false);
  const typers = useTypingIn(on ? channelId : null, meId);
  const presence = usePresence((p) => (group && typers.length ? p.byUser : null));
  if (!group) return <Avatar id={id} avatarId={avatarId} name={name} size={32} status={status} typing={on ? typers.includes(id) : undefined} />;
  // the first of them who shows as present, in their own colour
  const typer = presence ? typers.map((uid) => statusOf(presence[uid])).find(showsTyping) : undefined;
  const face = iconUrl(avatarId, 32) ? undefined : (
    <span className={s.groupIcon}>
      <Users size={18} />
    </span>
  );
  return <Avatar id={id} avatarId={avatarId} name={name} size={32} kind="icon" face={face} status={typer ?? null} typing={on ? !!typer : undefined} />;
}
