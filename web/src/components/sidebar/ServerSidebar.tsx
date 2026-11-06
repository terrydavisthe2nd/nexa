import { useMediaAnimation } from "@/lib/mediaAnimation";
import { ArrowUpRight, ChevronDown, MessageSquareText, Plus, Settings, UserPlus, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent, type MouseEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { Channel } from "@/api/types";
import { ApiError } from "@/api/client";
import { servers as serversApi, type ChannelPositionPatch } from "@/api/servers";
import { bannerUrl } from "@/api/users";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { CallTimer } from "@/components/voice/CallTimer";
import { VoiceParticipants } from "@/components/voice/VoiceParticipants";
import { moveMember, moveTargets } from "@/components/voice/voiceModeration";
import { joinVoice } from "@/voice/session";
import { useVoice } from "@/stores/voice";
import { useVoiceDrag } from "@/stores/voiceDrag";
import { Tooltip } from "@/components/common/Tooltip";
import { P, isPrivateChannel } from "@/lib/perms";
import { isMuted } from "@/lib/unread";
import { useViewAs } from "@/stores/viewAs";
import { channelTree, selectThreads, useChannels } from "@/stores/channels";
import { useThreads } from "@/stores/threads";
import { holdsThreads } from "@/lib/threads";
import { linkDestination, openLinkChannel } from "@/lib/linkChannel";
import { isUnread, useReadStates } from "@/stores/readStates";
import { canI, useServers } from "@/stores/servers";
import { useSettings } from "@/stores/settings";
import { useAuth } from "@/stores/auth";
import { useTypingIn } from "@/stores/typing";
import { useUsers } from "@/stores/users";
import { memberName } from "@/lib/names";
import { useUI } from "@/stores/ui";
import { categoryMenu, channelMenu, serverMenu } from "./serverMenu";
import s from "./ServerSidebar.module.css";

import { t } from "@/i18n";
type Drag = { id: string; kind: "channel" | "category" };
type Drop = { id: string; where: "before" | "after" | "into" };

/** The banner's height, and the header's over it: kept in step with ServerSidebar.module.css. */
const BANNER_H = 138;
const HEADER_H = 49;

/** Where a context menu opens: at the pointer, or by the row when the keyboard opened it (which reports 0,0). */
export function menuPoint(e: MouseEvent): { x: number; y: number } {
  if (e.clientX !== 0 || e.clientY !== 0) return { x: e.clientX, y: e.clientY };
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
  return { x: document.documentElement.dir === "rtl" ? r.right - 8 : r.left + 8, y: r.bottom };
}

export function ServerSidebar({ serverId }: { serverId: string }) {
  useMediaAnimation();
  const nav = useNavigate();
  const loc = useLocation();
  const st = useServers((x) => x.byId[serverId]);
  const channels = useChannels((c) => c.byId);
  const upsert = useChannels((c) => c.upsert);
  const applyPositions = useChannels((c) => c.applyPositions);
  const reads = useReadStates((r) => r.byChannel);
  const joinedThreads = useThreads((x) => x.joined);
  const settings = useSettings((x) => x.settings);
  const patch = useSettings((x) => x.patch);
  const openModal = useUI((u) => u.openModal);
  const toast = useUI((u) => u.toast);
  const [menuOpen, setMenuOpen] = useState(false);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [drop, setDrop] = useState<Drop | null>(null);
  const voiceByChannel = useVoice((v) => v.byChannel);
  // someone being dragged out of a voice channel by a moderator, and where they would land
  const memberDrag = useVoiceDrag((d) => d.drag);
  const memberOver = useVoiceDrag((d) => d.over);
  const headRef = useRef<HTMLButtonElement>(null);
  // A banner scrolls away with the channels under the name, which sits over
  // it until the list has scrolled past it and then takes the plain header's
  // background, so the name is readable on the picture and on the list alike.
  const banner = bannerUrl(st?.server.banner_id ?? null, 480);
  const [pastBanner, setPastBanner] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const measureBanner = useCallback(() => {
    const el = listRef.current;
    setPastBanner(!!el && el.scrollTop > BANNER_H - HEADER_H);
  }, []);
  // another server's list, or a banner added or taken away, starts from where the list is now
  useEffect(measureBanner, [serverId, banner, measureBanner]);
  // whether the menu was open when the header was pressed: pressing closes it, and the click that follows must not open it again
  const wasOpen = useRef(false);
  const menuWatch = useRef<(() => void) | null>(null);
  const active = loc.pathname.match(/^\/channels\/\d+\/(\d+)/)?.[1];
  const viewAs = useViewAs((v) => v.current);
  const tree = useMemo(() => {
    const full = channelTree(channels, serverId);
    // View Server as Role: the server sends what this account sees, so the preview hides what the role would not
    if (!st || viewAs?.serverId !== serverId) return full;
    const sees = (id: string) => canI(st, P.VIEW_CHANNEL, id);
    const categories = full.categories
      .map(({ category, children }) => ({ category, children: children.filter((c) => sees(c.id)) }))
      .filter(({ category, children }) => children.length > 0 || sees(category.id));
    return { loose: full.loose.filter((c) => sees(c.id)), categories };
  }, [channels, serverId, st, viewAs]);
  const canManage = canI(st, P.MANAGE_CHANNELS);
  const collapsed = settings.collapsedCategories;
  // A closed category still shows the open channel and the ones with
  // something new; the rest come back when it is opened.
  const keptClosed = (c: Channel) => c.id === active || (isUnread(reads[c.id], c.last_message_id) && !isMuted(settings, c.id, serverId)) || (reads[c.id]?.mentions ?? 0) > 0;
  // The category just opened here and the channels it brought back, which
  // slide into place one after another. Fixed as it opens, so a channel that
  // is read or turns unread later never plays it again, and the ones that
  // were showing all along stay where they are.
  const [opened, setOpened] = useState<{ id: string; rows: ReadonlyMap<string, number> } | null>(null);

  const toggleCategory = useCallback(
    (id: string) => {
      const opening = collapsed.includes(id);
      const back = opening ? (tree.categories.find((c) => c.category.id === id)?.children ?? []).filter((c) => !keptClosed(c)) : [];
      setOpened(opening ? { id, rows: new Map(back.map((c, i) => [c.id, i])) } : null);
      patch({ collapsedCategories: opening ? collapsed.filter((x) => x !== id) : [...collapsed, id] });
    },
    // keptClosed reads this render's read states, which is what the list shows as it opens
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [collapsed, patch, tree, reads, settings, active, serverId],
  );

  const openHeaderMenu = () => {
    if (wasOpen.current) {
      wasOpen.current = false;
      return;
    }
    if (!headRef.current) return;
    const r = headRef.current.getBoundingClientRect();
    setMenuOpen(true);
    // from the header's start edge, which is its right one in a right-to-left language
    const x = document.documentElement.dir === "rtl" ? r.right - 8 : r.left + 8;
    serverMenu.open({ serverId, nav, openModal }, { x, y: r.bottom + 4 });
    menuWatch.current?.();
    menuWatch.current = useUI.subscribe((state) => {
      if (!state.contextMenu) {
        setMenuOpen(false);
        menuWatch.current?.();
        menuWatch.current = null;
      }
    });
  };
  // the quick switcher can take this sidebar away with the menu still open,
  // and the store would keep the subscriber and its closure for good
  useEffect(
    () => () => {
      menuWatch.current?.();
      menuWatch.current = null;
    },
    [],
  );

  const onChannelContext = (e: MouseEvent, id: string) => {
    e.preventDefault();
    channelMenu.open({ channelId: id, nav, openModal }, menuPoint(e));
  };
  const onCategoryContext = (e: MouseEvent, id: string) => {
    e.preventDefault();
    categoryMenu.open({ categoryId: id, nav, openModal, collapsed: collapsed.includes(id), toggle: () => toggleCategory(id) }, menuPoint(e));
  };

  // --- drag and drop reordering (Manage Channels only) -------------------
  const onDragStart = (e: DragEvent, d: Drag) => {
    if (!canManage) return e.preventDefault();
    setDrag(d);
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", d.id);
  };
  // dragover fires some sixty times a second; a fresh object each time re-rendered
  // the whole channel tree, every tooltip and every call's participant rows
  const dropAt = (id: string, where: Drop["where"]) => setDrop((d) => (d && d.id === id && d.where === where ? d : { id, where }));
  /**
   * Where what is dragged would land, from the pointer's height alone. The
   * whole list answers, gaps between rows and above categories included, so
   * no spot along it refuses the drop. A category lands between categories: before the first one
   * whose head's middle is below the pointer, else after the last. A channel
   * lands by the row nearest the pointer: above or below a channel, at the
   * end of whatever comes before a category's head when over the top of it,
   * or at the top of the category when over the rest.
   */
  const dropFor = (y: number): Drop | null => {
    const list = listRef.current;
    if (!list || !drag) return null;
    if (drag.kind === "category") {
      const heads = [...list.querySelectorAll<HTMLElement>("[data-cat-head]")].filter((h) => h.dataset.catHead !== drag.id);
      for (const h of heads) {
        const r = h.getBoundingClientRect();
        if (y < r.top + r.height / 2) return { id: h.dataset.catHead!, where: "before" };
      }
      const last = heads[heads.length - 1];
      return last ? { id: last.dataset.catHead!, where: "after" } : null;
    }
    let best: HTMLElement | null = null;
    let bestGap = Infinity;
    for (const el of list.querySelectorAll<HTMLElement>("[data-dnd]")) {
      if (el.dataset.dnd === drag.id) continue;
      const r = el.getBoundingClientRect();
      const gap = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0;
      if (gap < bestGap) {
        best = el;
        bestGap = gap;
      }
      if (gap === 0) break;
    }
    if (!best) return null;
    const r = best.getBoundingClientRect();
    if (best.dataset.catHead) return { id: best.dataset.dnd!, where: y < r.top + r.height * 0.3 ? "before" : "into" };
    return { id: best.dataset.dnd!, where: y < r.top + r.height / 2 ? "before" : "after" };
  };
  const onListDragOver = (e: DragEvent) => {
    // a person being moved is the voice channel's to take
    if (memberDrag || !drag) return;
    const at = dropFor(e.clientY);
    if (!at) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    dropAt(at.id, at.where);
  };
  const onListDragLeave = (e: DragEvent) => {
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDrop(null);
  };
  const finish = async (list: ChannelPositionPatch[]) => {
    applyPositions(list.map((p) => ({ id: p.id, position: p.position, parent_id: p.parent_id === undefined ? (channels[p.id]?.parent_id ?? null) : p.parent_id })));
    try {
      upsert(await serversApi.setChannelPositions(serverId, list));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("sidebar.serverSidebar.couldNotReorderChannels"), "error");
      try {
        upsert(await serversApi.channels(serverId));
      } catch {
        /* the next event will resync */
      }
    }
  };
  const onDrop = (e: DragEvent) => {
    if (memberDrag) return;
    e.preventDefault();
    const d = drag;
    const at = dropFor(e.clientY) ?? drop;
    setDrag(null);
    setDrop(null);
    if (!d || !at || d.id === at.id) return;
    const target = channels[at.id];
    const moving = channels[d.id];
    if (!target || !moving) return;
    if (d.kind === "category") {
      // categories order among themselves; a channel target maps to its parent category
      const targetCat = target.type === "category" ? target : target.parent_id ? channels[target.parent_id] : undefined;
      if (!targetCat || targetCat.id === d.id) return;
      const cats = tree.categories.map((c) => c.category).filter((c) => c.id !== d.id);
      const idx = cats.findIndex((c) => c.id === targetCat.id) + (at.where === "after" ? 1 : 0);
      cats.splice(idx, 0, moving);
      void finish(cats.map((c, i) => ({ id: c.id, position: i })));
      return;
    }
    // a channel: into a category (top), or before/after a sibling
    let parent: string | null;
    let siblings: Channel[];
    let index: number;
    if (target.type === "category") {
      // the line above a category's head is the end of whatever comes before it:
      // the category above, or the channels outside any category when it is the first
      const ci = tree.categories.findIndex((c) => c.category.id === target.id);
      parent = at.where === "into" ? target.id : ci > 0 ? (tree.categories[ci - 1]?.category.id ?? null) : null;
      siblings = parent ? (tree.categories.find((c) => c.category.id === parent)?.children ?? []) : tree.loose;
      index = at.where === "into" ? 0 : siblings.length;
    } else {
      parent = target.parent_id && channels[target.parent_id]?.type === "category" ? target.parent_id : null;
      siblings = parent ? (tree.categories.find((c) => c.category.id === parent)?.children ?? []) : tree.loose;
      index = siblings.findIndex((c) => c.id === target.id) + (at.where === "after" ? 1 : 0);
    }
    const list = siblings.filter((c) => c.id !== d.id);
    if (index > list.length) index = list.length;
    list.splice(index, 0, moving);
    void finish(list.map((c, i) => (c.id === d.id ? { id: c.id, position: i, parent_id: parent } : { id: c.id, position: i })));
  };
  const endDrag = () => {
    setDrag(null);
    setDrop(null);
  };

  // --- moving someone in a call to another voice channel (Move Members) ---
  /** The drop handlers of a voice channel's block, its row and the people under it: a person dragged there is moved there. */
  const memberDrop = (ch: Channel) => {
    const lands = () => !!memberDrag && memberDrag.serverId === serverId && memberDrag.from !== ch.id && moveTargets(serverId, memberDrag.userId, memberDrag.from).some((c) => c.id === ch.id);
    return {
      onDragOver: (e: DragEvent) => {
        if (!memberDrag) return;
        if (!lands()) {
          useVoiceDrag.getState().hover(null);
          return;
        }
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
        useVoiceDrag.getState().hover(ch.id);
      },
      onDragLeave: (e: DragEvent) => {
        // out of the block altogether, not onto something inside it
        if (memberOver === ch.id && !e.currentTarget.contains(e.relatedTarget as Node | null)) useVoiceDrag.getState().hover(null);
      },
      onDrop: (e: DragEvent) => {
        if (!memberDrag) return;
        e.preventDefault();
        e.stopPropagation();
        const d = memberDrag;
        useVoiceDrag.getState().end();
        if (lands()) void moveMember(serverId, d.userId, ch.id);
      },
    };
  };

  if (!st) return <div className={s.sidebar} />;

  /** A thread under its channel, joined or open: the line from the channel says whose it is. */
  const renderThread = (th: Channel, last: boolean) => {
    const rs = reads[th.id];
    const selected = active === th.id;
    const unread = isUnread(rs, th.last_message_id) && !isMuted(settings, th.id, serverId) && !isMuted(settings, th.parent_id ?? "", serverId);
    const mentions = rs?.mentions ?? 0;
    return (
      <div
        key={th.id}
        className={[s.row, s.threadRow].join(" ")}
        data-selected={selected}
        data-unread={unread && !selected}
        data-last={last || undefined}
        data-channel={th.id}
        onContextMenu={(e) => onChannelContext(e, th.id)}
      >
        <span className={s.threadSpine} aria-hidden="true" />
        {last ? null : <span className={s.threadRail} aria-hidden="true" />}
        <button
          className={s.channel}
          onClick={() => {
            nav(`/channels/${serverId}/${th.id}`);
            useUI.getState().setLeftOpen(false);
          }}
          aria-current={selected ? "page" : undefined}
          data-testid="sidebar-thread"
        >
          <span className={s.name} dir="auto">{th.name}</span>
          <ChannelTyping channelId={th.id} serverId={serverId} />
          {mentions > 0 ? <span className="visually-hidden">{`, ${t("sidebar.serverSidebar.mentionsCount", { count: mentions })}`}</span> : unread && !selected ? <span className="visually-hidden">{`, ${t("sidebar.serverSidebar.unread")}`}</span> : null}
        </button>
        {mentions > 0 ? <span className={s.mention} aria-hidden="true">{mentions > 99 ? "99+" : mentions}</span> : null}
      </div>
    );
  };

  const renderChannel = (ch: Channel, reveal?: number) => {
    const rs = reads[ch.id];
    const unread = (ch.type === "text" || ch.type === "announcement" || ch.type === "voice") && isUnread(rs, ch.last_message_id) && !isMuted(settings, ch.id, serverId);
    // The threads this account is in, still going, under the channel they
    // belong to: ones it started, wrote in or was brought into. Looking at a
    // thread or a forum post is not joining it, so it does not stand here
    // while it is open; its channel is shown as the place it is in instead.
    const threads = holdsThreads(ch) ? selectThreads(channels, ch.id).filter((th) => !th.thread?.archived && joinedThreads[th.id]) : [];
    const mentions = rs?.mentions ?? 0;
    const inside = !!active && channels[active]?.parent_id === ch.id && !joinedThreads[active] && (channels[active]?.type === "thread" || channels[active]?.type === "private_thread");
    const selected = active === ch.id || inside;
    const priv = isPrivateChannel(serverId, ch.overwrites);
    const muted = settings.notifications.muted.includes(ch.id);
    return (
      <div key={ch.id} className={ch.type === "voice" ? s.voiceBlock : undefined} data-reveal={reveal !== undefined || undefined} style={reveal !== undefined ? ({ "--reveal-i": reveal } as CSSProperties) : undefined} data-member-drop={(ch.type === "voice" && memberOver === ch.id) || undefined} {...(ch.type === "voice" ? memberDrop(ch) : {})}>
      <div
        className={s.row}
        data-selected={selected}
        data-unread={unread && !selected}
        data-muted={muted}
        data-drop={drop?.id === ch.id ? drop.where : undefined}
        data-dragging={drag?.id === ch.id}
        data-channel={ch.id}
        data-dnd={ch.id}
        draggable={canManage}
        onDragStart={(e) => onDragStart(e, { id: ch.id, kind: "channel" })}
        onDragEnd={endDrag}
        onContextMenu={(e) => onChannelContext(e, ch.id)}
      >
        <button
          className={s.channel}
          onClick={() => {
            // a link channel opens its address and leaves the open channel where it is
            if (ch.type === "link") return openLinkChannel(ch, nav);
            nav(`/channels/${serverId}/${ch.id}`);
            // closes the phone drawer even when this channel was already open
            useUI.getState().setLeftOpen(false);
            if (ch.type === "voice") void joinVoice(ch.id);
          }}
          aria-current={active === ch.id ? "page" : undefined}
          data-link={ch.type === "link" || undefined}
          title={ch.type === "link" ? linkDestination(ch.url)?.host : undefined}
        >
          <span className={s.icon}><ChannelGlyph channel={ch} size={20} locked={priv && ch.type !== "voice"} /></span>
          <span className={s.name}>{ch.name}</span>
          <ChannelTyping channelId={ch.id} serverId={serverId} />
          {/* The dot and the count sit outside the button, so the button said
              nothing about either; they are said as part of its name now. */}
          {mentions > 0 ? <span className="visually-hidden">{`, ${t("sidebar.serverSidebar.mentionsCount", { count: mentions })}`}</span> : unread && !selected ? <span className="visually-hidden">{`, ${t("sidebar.serverSidebar.unread")}`}</span> : null}
          {ch.type === "voice" ? <CallTimer channelId={ch.id} pill className={s.callTimer} /> : null}
          {ch.type === "voice" && ch.user_limit > 0 ? <span className={s.limit}>{Object.keys(voiceByChannel[ch.id] ?? {}).length}/{ch.user_limit}</span> : null}
          {ch.type === "link" ? <ArrowUpRight size={14} className={s.leaves} aria-hidden="true" /> : null}
        </button>
        <span className={s.actions}>
          {/* a voice channel's chat, without joining its call; reading it needs Connect too */}
          {ch.type === "voice" && canI(st, P.VIEW_CHANNEL | P.CONNECT, ch.id) ? (
            <Tooltip text={t("voiceChat.open")} side="top">
              <button
                className={[s.action, s.voiceChat].join(" ")}
                aria-label={t("voiceChat.open")}
                data-testid="voice-chat-open"
                onClick={() => {
                  nav(`/channels/${serverId}/${ch.id}`, { state: { voiceChat: true } });
                  useUI.getState().setLeftOpen(false);
                }}
              >
                <MessageSquareText size={16} />
              </button>
            </Tooltip>
          ) : null}
          {ch.type !== "link" && canI(st, P.CREATE_INSTANT_INVITE, ch.id) ? (
            <Tooltip text={t("server.invites.createInvite")} side="top">
              <button className={s.action} aria-label={t("server.invites.createInvite")} onClick={() => openModal("invite", { serverId, channelId: ch.id })}><UserPlus size={16} /></button>
            </Tooltip>
          ) : null}
          {canI(st, P.MANAGE_CHANNELS, ch.id) ? (
            <Tooltip text={t("sidebar.serverSidebar.editChannel")} side="top">
              <button className={s.action} aria-label={t("sidebar.serverSidebar.editChannel")} onClick={() => openModal("channelSettings", { channelId: ch.id })}><Settings size={16} /></button>
            </Tooltip>
          ) : null}
        </span>
        {mentions > 0 ? <span className={s.mention} aria-hidden="true">{mentions > 99 ? "99+" : mentions}</span> : null}
      </div>
      {ch.type === "voice" ? <VoiceParticipants channelId={ch.id} serverId={serverId} /> : null}
      {threads.length ? <div className={s.threads}>{threads.map((th, i) => renderThread(th, i === threads.length - 1))}</div> : null}
      </div>
    );
  };

  return (
    <div className={s.sidebar} data-banner={!!banner} onDrop={(e) => e.preventDefault()}>
      <button ref={headRef} className={s.head} data-open={menuOpen} data-over-banner={banner ? !pastBanner : undefined} onPointerDown={() => { wasOpen.current = menuOpen; }} onClick={openHeaderMenu} aria-haspopup="menu" aria-expanded={menuOpen}>
        <span className={s.headName}>{st.server.name}</span>
        {menuOpen ? <X size={18} /> : <ChevronDown size={18} />}
      </button>
      <div className={s.scroll} ref={listRef} onScroll={banner ? measureBanner : undefined} onDragOver={onListDragOver} onDragLeave={onListDragLeave} onDrop={onDrop}>
        {banner ? <div className={s.banner} style={{ backgroundImage: `url(${banner})` }} aria-hidden="true" /> : <div className={s.top} />}
        {tree.loose.map((c) => renderChannel(c))}
        {tree.categories.map(({ category, children }) => {
          const isCollapsed = collapsed.includes(category.id);
          return (
            <div key={category.id} className={s.category} data-dragging={drag?.id === category.id} data-drop={drag?.kind === "category" && drop?.id === category.id ? drop.where : undefined}>
              <div
                className={s.catHead}
                data-dnd={category.id}
                data-cat-head={category.id}
                data-drop={drag?.kind === "channel" && drop?.id === category.id ? drop.where : undefined}
                draggable={canManage}
                onDragStart={(e) => onDragStart(e, { id: category.id, kind: "category" })}
                onDragEnd={endDrag}
                onContextMenu={(e) => onCategoryContext(e, category.id)}
              >
                <button className={s.catToggle} onClick={() => toggleCategory(category.id)} aria-expanded={!isCollapsed}>
                  <ChevronDown size={12} className={s.catChevron} data-collapsed={isCollapsed || undefined} />
                  <ChannelGlyph channel={category} size={14} className={s.catGlyph} />
                  <span className={s.catName}>{category.name}</span>
                </button>
                {canManage ? (
                  <Tooltip text={t("modals.createChannel.createChannel")} side="top">
                    <button className={s.catAdd} aria-label={t("modals.createChannel.createChannel")} onClick={() => openModal("createChannel", { serverId, parentId: category.id })}><Plus size={16} /></button>
                  </Tooltip>
                ) : null}
              </div>
              {children.filter((c) => !isCollapsed || keptClosed(c)).map((c) => renderChannel(c, opened?.id === category.id ? opened.rows.get(c.id) : undefined))}
            </div>
          );
        })}
        {tree.loose.length === 0 && tree.categories.length === 0 ? <div className={s.empty}>{t("sidebar.serverSidebar.noChannelsYouCanSeeYet")}</div> : null}
      </div>
    </div>
  );
}

/** "Alice is typing…", "Alice and Bob are typing…", as the typing line under a conversation says it. */
export function typingSentence(names: string[]): string {
  if (names.length === 1) return t("chat.typingBar.oneTyping", { a: names[0]! });
  if (names.length === 2) return t("chat.typingBar.twoTyping", { a: names[0]!, b: names[1]! });
  if (names.length === 3) return t("chat.typingBar.threeTyping", { a: names[0]!, b: names[1]!, c: names[2]! });
  return t("chat.typingBar.severalPeopleAreTyping");
}

/**
 * Dots just after a channel's name while someone types in it, so a server
 * shows where people are talking before anything is sent. Each row listens to
 * its own channel only. Settings, Chat turns them off.
 */
function ChannelTyping({ channelId, serverId }: { channelId: string; serverId: string }) {
  const meId = useAuth((a) => a.me?.id);
  const on = useSettings((x) => x.settings.typingChannels !== false);
  const typers = useTypingIn(on ? channelId : null, meId);
  if (typers.length === 0) return null;
  const members = useServers.getState().byId[serverId]?.members;
  const users = useUsers.getState().byId;
  const label = typingSentence(typers.map((id) => memberName(members?.[id]?.nickname, users[id], t("names.unknownUser"))));
  return (
    <Tooltip text={label} side="top">
      <span className={s.typing} role="img" aria-label={label} data-testid="channel-typing">
        <i />
        <i />
        <i />
      </span>
    </Tooltip>
  );
}
