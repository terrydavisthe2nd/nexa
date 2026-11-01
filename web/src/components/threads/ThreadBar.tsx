import { Archive, ChevronLeft, Lock, MoreHorizontal } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import { threads as threadsApi } from "@/api/threads";
import type { Channel } from "@/api/types";
import { menuAnchor } from "@/components/common/ContextMenu";
import { Tooltip } from "@/components/common/Tooltip";
import { t } from "@/i18n";
import { P } from "@/lib/perms";
import { canManageThread } from "@/lib/threads";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { canI, useServers } from "@/stores/servers";
import { useThreads } from "@/stores/threads";
import { useUI } from "@/stores/ui";
import { about, button, defineMenu, type MenuButton } from "@/lib/contextMenus/registry";
import chat from "@/components/chat/Chat.module.css";
import s from "./Threads.module.css";

/** In a thread's header: the channel it belongs to, the way back to it. */
export function ThreadParentLink({ thread }: { thread: Channel }) {
  const nav = useNavigate();
  const parent = useChannels((c) => (thread.parent_id ? c.byId[thread.parent_id] : undefined));
  if (!parent) return null;
  return (
    <button className={chat.hParent} onClick={() => nav(`/channels/${thread.server_id}/${parent.id}`)} data-testid="thread-parent">
      <ChevronLeft size={14} />
      <span className={chat.hParentText}>{parent.type === "forum" || parent.type === "media" ? t("threads.bar.inForum", { name: parent.name }) : t("threads.bar.inChannel", { name: parent.name })}</span>
    </button>
  );
}

async function patchThread(thread: Channel, body: { archived?: boolean; locked?: boolean }): Promise<void> {
  try {
    const fresh = await serversApi.patchChannel(thread.id, body);
    useChannels.getState().upsert(fresh);
  } catch (err) {
    useUI.getState().toast(err instanceof ApiError ? err.message : t("threads.bar.changeFailed"), "error");
  }
}

/** What a thread's menu is handed. */
export interface ThreadMenuCtx {
  thread: Channel;
  /** On a phone, where joining and leaving has no button of its own and is in the menu. */
  compact: boolean;
  joined: boolean;
  canJoin: boolean;
  toggleJoin: () => void;
  nav: (path: string) => void;
}

/** Whether the signed-in account may manage the thread, or started it. */
function rights(thread: Channel) {
  const meId = useAuth.getState().me?.id;
  return { manage: canManageThread(thread), own: !!meId && thread.owner_id === meId };
}

/**
 * What can be done with a thread, for whoever may: joining or leaving it
 * (in the menu only on a phone, where there is no button for it), closing,
 * locking, its settings and deleting. `ids` names the buttons in the menu
 * they are in; `of` finds the thread's context in that menu's, or null.
 */
export function threadButtons<C>(ids: { join: string; archive: string; lock: string; settings: string; remove: string }, of: (ctx: C) => ThreadMenuCtx | null): MenuButton<C>[] {
  return [
    button(ids.join, (c) => {
      const x = of(c);
      return !!x && x.compact && x.canJoin && { id: "join", label: x.joined ? t("threads.bar.leave") : t("threads.bar.join"), onSelect: x.toggleJoin };
    }),
    button(ids.archive, (c) => {
      const x = of(c);
      if (!x) return null;
      const { manage, own } = rights(x.thread);
      const archived = x.thread.thread?.archived;
      return (manage || own) && { id: "archive", label: archived ? t("threads.bar.open") : t("threads.bar.close"), onSelect: () => void patchThread(x.thread, { archived: !archived }) };
    }),
    button(ids.lock, (c) => {
      const x = of(c);
      if (!x || !rights(x.thread).manage) return null;
      const locked = x.thread.thread?.locked;
      return { id: "lock", label: locked ? t("threads.bar.unlock") : t("threads.bar.lock"), onSelect: () => void patchThread(x.thread, { locked: !locked }) };
    }),
    button(ids.settings, (c) => {
      const x = of(c);
      if (!x) return null;
      const { manage, own } = rights(x.thread);
      return (manage || own) && { id: "settings", label: t("threads.bar.settings"), onSelect: () => useUI.getState().openModal("channelSettings", { channelId: x.thread.id }) };
    }),
    button(ids.remove, (c) => {
      const x = of(c);
      if (!x) return null;
      const { manage, own } = rights(x.thread);
      const thread = x.thread;
      return (manage || own) && {
        id: "delete",
        label: t("threads.bar.delete"),
        danger: true,
        onSelect: () =>
          useUI.getState().openModal("confirm", {
            title: t("threads.bar.deleteTitle"),
            body: t("threads.bar.deleteBody", { name: thread.name }),
            confirmLabel: t("threads.bar.delete"),
            danger: true,
            onConfirm: async () => {
              try {
                await serversApi.deleteChannel(thread.id);
                useChannels.getState().remove(thread.id);
                x.nav(`/channels/${thread.server_id}/${thread.parent_id ?? ""}`);
              } catch (err) {
                useUI.getState().toast(err instanceof ApiError ? err.message : t("threads.bar.deleteFailed"), "error");
              }
            },
          }),
      };
    }),
  ];
}

/** A thread's menu in its header on a computer. */
export const threadMenu = defineMenu<ThreadMenuCtx>("thread", [threadButtons({ join: "joinThread", archive: "closeThread", lock: "lockThread", settings: "threadSettings", remove: "deleteThread" }, (x) => x)]);
about(threadMenu, ["channel", "server"], ({ thread }) => ({ channelId: thread.id }));


/**
 * A thread's state for its menus and its Join button: the context its menus
 * are opened with (null for something that is not a thread), whether it is
 * joined, and joining or leaving it.
 */
export function useThreadMenu(thread: Channel | null, compact: boolean): { ctx: ThreadMenuCtx | null; joined: boolean; busy: boolean; toggleJoin: () => Promise<void>; canJoin: boolean } {
  const nav = useNavigate();
  const joined = useThreads((x) => (thread ? !!x.joined[thread.id] : false));
  const st = useServers((x) => (thread?.server_id ? x.byId[thread.server_id] : undefined));
  const [busy, setBusy] = useState(false);
  const isThread = !!thread && (thread.type === "thread" || thread.type === "private_thread");
  // a private thread is joined by being brought in; leaving it is still one's own to do
  const canJoin = isThread && (thread.type !== "private_thread" || joined || canI(st, P.MANAGE_THREADS, thread.id));
  const toggleJoin = async () => {
    if (!thread) return;
    setBusy(true);
    try {
      if (joined) await threadsApi.leave(thread.id);
      else await threadsApi.join(thread.id);
      useThreads.getState().setJoined(thread.id, !joined);
    } catch (err) {
      useUI.getState().toast(err instanceof ApiError ? err.message : t("threads.bar.changeFailed"), "error");
    } finally {
      setBusy(false);
    }
  };
  const ctx = thread && isThread ? { thread, compact, joined, canJoin, toggleJoin: () => void toggleJoin(), nav } : null;
  return { ctx, joined, busy, toggleJoin, canJoin };
}

/**
 * In a thread's header on a computer: Join or Leave, and a menu of what else
 * can be done with it. A phone has these at the top of the header's More
 * menu instead, as the phone app does, so the thread's name has the room.
 */
export function ThreadActions({ thread }: { thread: Channel }) {
  const { ctx, joined, busy, toggleJoin, canJoin } = useThreadMenu(thread, false);
  const hasMenu = !!ctx && threadMenu.items(ctx).length > 0;
  const menu = (el: HTMLElement) => {
    if (!ctx) return;
    const anchor = menuAnchor(el);
    threadMenu.open(ctx, { x: anchor.left, y: anchor.bottom + 4, anchor, label: t("threads.bar.more") });
  };
  return (
    <>
      {canJoin ? (
        <button type="button" className={s.joinBtn} data-joined={joined || undefined} disabled={busy} onClick={() => void toggleJoin()} data-testid="thread-join">
          {joined ? t("threads.bar.leave") : t("threads.bar.join")}
        </button>
      ) : null}
      {hasMenu ? (
        <Tooltip text={t("threads.bar.more")} side="bottom">
          <button className={chat.hBtn} aria-label={t("threads.bar.more")} onClick={(e) => menu(e.currentTarget)} data-testid="thread-menu"><MoreHorizontal size={22} /></button>
        </Tooltip>
      ) : null}
    </>
  );
}

/** Over a thread's box: that it is closed (a message opens it again) or locked (only moderators write). */
export function ThreadStateBar({ thread }: { thread: Channel }) {
  const meta = thread.thread;
  const st = useServers((x) => (thread.server_id ? x.byId[thread.server_id] : undefined));
  if (!meta || (!meta.archived && !meta.locked)) return null;
  const mod = canI(st, P.MANAGE_THREADS, thread.id);
  return (
    <div className={s.stateBar} data-testid="thread-state">
      {meta.locked ? <Lock size={16} aria-hidden="true" /> : <Archive size={16} aria-hidden="true" />}
      <span>{meta.locked ? (mod ? t("threads.bar.lockedMod") : t("threads.bar.locked")) : t("threads.bar.closed")}</span>
    </div>
  );
}
