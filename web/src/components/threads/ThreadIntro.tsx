import { MessageSquareText, MessagesSquare } from "lucide-react";
import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import type { Channel, ForumTag } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { ReplyWords, useReplyWords } from "@/components/chat/ReplyWords";
import { useShownName } from "@/components/chat/useShownName";
import { t, Tr } from "@/i18n";
import { useRowRenderCtx } from "@/lib/markdownCtx";
import { formatTimestamp } from "@/lib/time";
import { useChannels } from "@/stores/channels";
import { useMessages } from "@/stores/messages";
import { useRelationships } from "@/stores/relationships";
import { requestReplyTarget, retryReplyTarget, usedReplyTarget, useReplyTargets } from "@/stores/replyTargets";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import chat from "@/components/chat/Chat.module.css";
import { isForum } from "@/lib/threads";
import s from "./Threads.module.css";

/**
 * The top of a thread: its name, who started it and where, a forum post's
 * tags, and the message a thread grew from, which opens it in its channel.
 */
export function ThreadIntro({ thread }: { thread: Channel }) {
  const parent = useChannels((c) => (thread.parent_id ? c.byId[thread.parent_id] : undefined));
  const starter = useShownName(thread.id, thread.owner_id ?? undefined);
  const post = isForum(parent);
  const where = parent ? (post ? parent.name : `#${parent.name}`) : "";
  const tags = post ? (thread.thread?.applied_tags ?? []).map((id) => parent?.forum?.tags.find((x) => x.id === id)).filter((x): x is ForumTag => !!x) : [];
  const line = post ? "chat.messageList.postStart" : thread.type === "private_thread" ? "chat.messageList.privateThreadStart" : "chat.messageList.threadStart";
  const from = !post && parent ? thread.thread?.starter_id : null;
  return (
    <div className={chat.empty} data-testid="thread-intro">
      <div className={s.introIcon} aria-hidden="true">{post ? <MessagesSquare size={30} /> : <MessageSquareText size={30} />}</div>
      <h2 className={chat.emptyName} dir="auto">{thread.name}</h2>
      {tags.length ? (
        <div className={s.introTags}>
          {tags.map((tag) => (
            <span key={tag.id} className={s.introTag}>
              {tag.emoji ? <span aria-hidden="true">{tag.emoji}</span> : null}
              {tag.name}
            </span>
          ))}
        </div>
      ) : null}
      <p className={chat.emptyText}>
        {thread.owner_id ? <Tr id={line} values={{ name: <b dir="auto">{starter}</b>, where: <b dir="auto">{where}</b> }} /> : null} {t("chat.messageList.endToEnd")}
      </p>
      {from && parent ? <StarterCard serverId={thread.server_id} parentId={parent.id} messageId={from} /> : null}
    </div>
  );
}

/** The message a thread was started from, as it is now in its channel. */
function StarterCard({ serverId, parentId, messageId }: { serverId: string | null; parentId: string; messageId: string }) {
  const nav = useNavigate();
  const inList = useMessages((m) => m.channels[parentId]?.byId[messageId]);
  // usually not loaded here: fetched and kept the way replies' originals are
  const kept = useReplyTargets((r) => (inList ? undefined : r.byId[messageId]));
  const target = inList ?? (typeof kept === "object" && kept.channel_id === parentId ? kept : undefined);
  useEffect(() => {
    if (inList) return;
    if (typeof kept === "object") usedReplyTarget(messageId);
    else if (kept === undefined) requestReplyTarget(parentId, messageId);
  }, [inList, kept, parentId, messageId]);
  const words = useReplyWords(target);
  const ctx = useRowRenderCtx();
  const byHook = !!target?.hook && !!target.webhook_id;
  const shown = useShownName(parentId, byHook ? undefined : target?.author_id);
  const avatarId = useUsers((u) => (target ? (u.byId[target.author_id]?.avatar_id ?? null) : null));
  const blocked = useRelationships((r) => (target ? r.byUser[target.author_id]?.type === "blocked" : false));
  if (!target) {
    if (kept === "deleted") return <div className={s.starter} data-state="gone">{t("threads.intro.starterDeleted")}</div>;
    if (kept === "failed") {
      return (
        <button type="button" className={s.starter} onClick={() => retryReplyTarget(parentId, messageId)}>
          {t("chat.messageItem.originalFailed")}
        </button>
      );
    }
    return (
      <div className={s.starter} aria-busy="true" aria-label={t("chat.messageItem.originalLoading")}>
        <span className={chat.replySkeleton} aria-hidden="true" />
      </div>
    );
  }
  const name = byHook ? target.hook!.name : shown;
  const open = () => {
    nav(serverId ? `/channels/${serverId}/${parentId}/${messageId}` : `/channels/@me/${parentId}/${messageId}`);
    useUI.getState().setJumpTo({ channelId: parentId, messageId });
  };
  return (
    <button type="button" className={s.starter} onClick={open} title={t("chat.messageItem.goToOriginal")} data-testid="thread-starter">
      <span className={s.starterHead}>
        <Avatar id={target.author_id} avatarId={avatarId} name={name} size={20} />
        <span className={s.starterName} dir="auto">{name}</span>
        <span className={s.starterWhen}>{formatTimestamp(target.created_at)}</span>
      </span>
      <span className={s.starterText} dir="auto">{blocked ? t("people.blocked.replyPreview") : <ReplyWords words={words} ctx={ctx} />}</span>
    </button>
  );
}
