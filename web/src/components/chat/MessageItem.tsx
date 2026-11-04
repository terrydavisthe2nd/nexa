import { jumpTarget } from "@/lib/appLinks";
import { useDownloadsHidden } from "@/lib/mediaDownloads";
import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { Check, CornerUpRight, Hash, Link2, Lock, MailOpen, Megaphone, MessageSquareText, MoreHorizontal, Pencil, Pin, PinOff, Reply, RotateCw, ShieldAlert, SmilePlus, Trash2 } from "lucide-react";
import { type ReactNode, memo, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Avatar } from "@/components/common/Avatar";
import { Markdown, isEmojiOnly, parse, plainText } from "@/markdown";
import { useRowRenderCtx } from "@/lib/markdownCtx";
import { formatExact, formatFull, formatShortDate, formatTime, formatTimestamp } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { useMessages, type DecryptedMessage, type PendingUpload } from "@/stores/messages";
import { useSettings } from "@/stores/settings";
import { useUI, type ContextMenuState } from "@/stores/ui";
import { rangeBetween, useSelection } from "@/stores/selection";
import { useUsers } from "@/stores/users";
import { useServers } from "@/stores/servers";
import { useMemberLook } from "@/lib/useMemberLook";
import { nameClass, nameStyle } from "@/lib/roleLook";
import { RoleIcon } from "@/components/common/RoleIcon";
import { useChannels } from "@/stores/channels";
import { openProfilePopout } from "@/stores/profilePopout";
import { useNavigate } from "react-router-dom";
import { openPersonMenu } from "./personMenu";
import { useRelationships } from "@/stores/relationships";
import { ingestReferences, requestReplyTarget, retryReplyTarget, usedReplyTarget, useReplyTargets } from "@/stores/replyTargets";
import { mentionsMe } from "@/lib/mentionsMe";
import { useShiftHeld } from "@/hooks/useShiftHeld";
import { stickerUrl } from "@/api/users";
import { StickerView as StickerMedia } from "@/components/stickers/StickerView";
import { stickerFormatOf, useExpressions } from "@/stores/expressions";
import { imageTarget, type ImageTarget } from "@/lib/imageActions";
import type { MessagePayload } from "@/crypto/message";
import { Attachments } from "./Attachments";
import { UploadCard } from "./UploadCard";
import { Embeds } from "./Embeds";
import { InviteCards } from "./InviteCards";
import { PackCards } from "./PackCards";
import { ProfileCards } from "./ProfileCards";
import { AppLinkCards } from "./AppLinkCards";
import { NoteCards } from "@/components/notes/NoteCards";
import { MSG_FLAG_EPHEMERAL, MSG_FLAG_LOADING, MSG_FLAG_SUPPRESS_EMBEDS } from "@/api/types";
import { useAutomodHeld } from "@/lib/automod/useHeld";
import { canI, P } from "@/stores/servers";
import { authorHas } from "@/lib/authorPerms";
import { ThreadChip } from "./ThreadChip";
import { canPublish, publish } from "./publish";
import type { UnfurlResult } from "@/api/types";
import { EditMessage } from "./EditMessage";
import { isOnlyMediaLinks } from "./mediaLinks";
import { Reactions, toggleReaction } from "./Reactions";
import { StickerPopout } from "./StickerPopout";
import { ReplyWords, useReplyWords } from "./ReplyWords";
import { SystemMessage } from "./SystemMessage";
import { Tooltip } from "@/components/common/Tooltip";
import { menuAnchor } from "@/components/common/ContextMenu";
import { copyText } from "@/lib/clipboard";
import { MSG_FLAG_PINNED } from "@/api/types";
import { discardPending, retryPending } from "@/components/composer/send";
import { canDelete, canEdit, canRemoveEmbeds, confirmRemoveEmbeds, canPick, canPin, canReact, canThread, copyMessageLink, deleteMessageNow, markUnread, messageMenu, messageTimes, quickReactions, togglePin } from "./messageMenu";
import { useRowActive } from "./activeRow";
import { useShownName } from "./useShownName";
import { HookAvatar, HookPopout, HookTags } from "./webhook/HookHeader";
import { UserTag } from "@/components/servertag/UserTag";
import { HookBody } from "./webhook/HookBody";
import { AppMsgProvider, InteractionLine, OnlyYou, Thinking, usedCommand } from "./apps/AppMessage";
import { HookPoll } from "./webhook/HookPoll";
import { hookText } from "@/lib/webhookMessage";
import { hookPollOf, pollWords } from "@/lib/poll";
import s from "./Chat.module.css";

import { t } from "@/i18n";
interface Props {
  preview?: boolean;
  evidence?: ReactNode;
  previewAuthor?: { name: string; avatarId: string | null };
  msg: DecryptedMessage;
  groupStart: boolean;
  pending?: "sending" | "failed";
  /** A pending message's files still going up, shown in place of its attachments. */
  upload?: PendingUpload | undefined;
  highlight?: boolean;
  /** The topmost row in the list; its hover toolbar must not overhang out of view. */
  first?: boolean;
}

/**
 * The line over a reply: who it answers and what they said. The name opens
 * their profile and the quote goes to the message, both as real buttons; the
 * rest of the line goes to the message too, for a pointer.
 */
function ReplyPreview({ msg }: { msg: DecryptedMessage }) {
  const inList = useMessages((m) => (msg.reply_to_id ? m.channels[msg.channel_id]?.byId[msg.reply_to_id] : undefined));
  // most originals are not in the stretch that is loaded: the server sends
  // them along, and anything it did not is fetched (stores/replyTargets)
  const kept = useReplyTargets((r) => (msg.reply_to_id && !inList ? r.byId[msg.reply_to_id] : undefined));
  const target = inList ?? (typeof kept === "object" && kept.channel_id === msg.channel_id ? kept : undefined);
  const deleted = !target && (kept === "deleted" || (kept === undefined && msg.referenced_message === null));
  const failed = !target && kept === "failed";
  const userAvatar = useUsers((u) => (target ? (u.byId[target.author_id]?.avatar_id ?? null) : null));
  // in a server, the picture the quoted person set for it, as their own row shows
  const memberAvatar = useServers((st) => {
    if (!target) return null;
    const sid = useChannels.getState().byId[msg.channel_id]?.server_id;
    return sid ? (st.byId[sid]?.members[target.author_id]?.avatar_id ?? null) : null;
  });
  const avatarId = memberAvatar ?? userAvatar;
  // a webhook's message goes by the name it was sent with; an app's, by its bot's
  const byHook = !!target?.hook && !!target.webhook_id;
  const shownName = useShownName(msg.channel_id, byHook ? undefined : target?.author_id);
  const name = byHook ? target!.hook!.name : shownName;
  const setJumpTo = useUI((u) => u.setJumpTo);
  const openModal = useUI((u) => u.openModal);
  const nav = useNavigate();
  const ctx = useRowRenderCtx();
  // what someone blocked wrote is not quoted back, even over a reply to it
  const targetBlocked = useRelationships((r) => (target ? r.byUser[target.author_id]?.type === "blocked" : false));
  const hook = target?.hook;
  // its words with their formatting, parsed when the quoted message changes, not on every render of the row
  const words = useReplyWords(target);
  const replyId = msg.reply_to_id;
  useEffect(() => {
    if (!replyId || inList) return;
    if (typeof kept === "object") {
      usedReplyTarget(replyId);
      return;
    }
    if (kept !== undefined || msg.referenced_message === null) return;
    // sent along but not kept (yet, or any more): opened here, without asking anyone
    if (msg.referenced_message) void ingestReferences([msg]);
    else requestReplyTarget(msg.channel_id, replyId);
  }, [replyId, inList, kept, msg]);
  if (!replyId) return null;
  // Jumping works whether or not the original is loaded: the list fetches the
  // page around it, and says so if it really is gone.
  const jump = () => setJumpTo({ channelId: msg.channel_id, messageId: replyId });
  if (deleted) {
    return (
      <div className={s.replyLine} data-state="deleted">
        <span className={[s.replyText, s.replyGone].join(" ")}>{t("chat.messageItem.originalDeleted")}</span>
      </div>
    );
  }
  if (failed) {
    return (
      <div className={s.replyLine}>
        <button type="button" className={s.replyText} onClick={() => retryReplyTarget(msg.channel_id, replyId)} title={t("chat.messageList.tryAgain")}>{t("chat.messageItem.originalFailed")}</button>
      </div>
    );
  }
  if (!target) {
    return (
      <div className={s.replyLine} aria-busy="true" aria-label={t("chat.messageItem.originalLoading")} onClick={jump}>
        <span className={s.replySkeleton} aria-hidden="true" />
      </div>
    );
  }
  if (targetBlocked) {
    return (
      <div className={s.replyLine}>
        <button type="button" className={s.replyText} onClick={jump} title={t("chat.messageItem.goToOriginal")} data-testid="reply-blocked">{t("people.blocked.replyPreview")}</button>
      </div>
    );
  }
  return (
    <div className={s.replyLine} onClick={jump}>
      <span className={s.replyAvatar} aria-hidden="true">{hook && target.webhook_id ? <HookAvatar hook={hook} webhookId={target.webhook_id} size={16} /> : <Avatar id={target.author_id} avatarId={avatarId} name={name} size={16} />}</span>
      <button
        type="button"
        className={s.replyName}
        dir="auto"
        onClick={(e) => {
          e.stopPropagation();
          // a webhook has no profile; its message is where to go
          if (byHook) jump();
          else openProfilePopout(target.author_id, e.currentTarget, useChannels.getState().byId[msg.channel_id]?.server_id ?? undefined);
        }}
        onContextMenu={(e) => {
          // the person a reply quotes has the same menu as the one who wrote it
          if (byHook || !openPersonMenu(e, target.author_id, msg.channel_id, nav, openModal)) return;
          e.preventDefault();
          e.stopPropagation();
        }}
      >
        {byHook ? name : <PersonName userId={target.author_id} serverId={useChannels.getState().byId[msg.channel_id]?.server_id}>{name}</PersonName>}
      </button>
      <button type="button" className={s.replyText} dir="auto" title={t("chat.messageItem.goToOriginal")} onClick={(e) => { e.stopPropagation(); jump(); }}>
        <ReplyWords words={words} ctx={ctx} />
      </button>
    </div>
  );
}

/** A hover-toolbar button that always says what it does. */
function ToolButton({ label, danger, onClick, children }: { label: string; danger?: boolean; onClick: (e: React.MouseEvent<HTMLButtonElement>) => void; children: React.ReactNode }) {
  return (
    <Tooltip text={label} side="top">
      <button className={[s.tBtn, danger ? s.tDanger : ""].join(" ")} aria-label={label} onClick={onClick}>
        {children}
      </button>
    </Tooltip>
  );
}

export const MessageItem = memo(function MessageItem({ msg, groupStart, pending, upload, highlight, first, preview, evidence, previewAuthor }: Props) {
  const me = useAuth((a) => a.me);
  const ownAvatar = useUsers((u) => u.byId[msg.author_id]?.avatar_id ?? null);
  // in a server, the picture someone set for it
  const serverAvatar = useServers((st) => {
    const sid = useChannels.getState().byId[msg.channel_id]?.server_id;
    return sid ? (st.byId[sid]?.members[msg.author_id]?.avatar_id ?? null) : null;
  });
  const avatarId = previewAuthor ? previewAuthor.avatarId : serverAvatar ?? ownAvatar;
  // A webhook's message goes by the name and picture it was sent with; an
  // app's goes by its bot, like anyone's, but says it is an app's.
  const hook = msg.hook;
  const webhookHook = hook && msg.webhook_id ? hook : undefined;
  const fromApp = !!hook?.application_id;
  const ephemeral = (msg.flags & MSG_FLAG_EPHEMERAL) !== 0;
  const command = fromApp ? usedCommand(hook?.interaction) : null;
  // A nickname you gave a friend applies everywhere their name appears, not
  // just in the sidebar; a server nickname wins inside that server.
  // a post published from an announcement channel this one follows goes by where it came from
  const copy = msg.type === "crosspost" ? msg.crosspost : undefined;
  const shownName = useShownName(msg.channel_id, webhookHook || copy ? undefined : msg.author_id);
  const name = previewAuthor?.name ?? (copy ? `${copy.server_name} #${copy.channel_name}` : webhookHook ? webhookHook.name : shownName);
  const [hookCard, setHookCard] = useState<HTMLElement | null>(null);
  const closeHookCard = useCallback(() => setHookCard(null), []);
  const editing = useUI((u) => !preview && u.editing?.channelId === msg.channel_id && u.editing.messageId === msg.id);
  const dev = useSettings((st) => st.settings.developerMode);
  const serverId = useChannels((c) => c.byId[msg.channel_id]?.server_id ?? null);
  // the author's colours and role icon only, so a change to anyone else in the server leaves this row alone
  const look = useMemberLook(serverId, msg.webhook_id ? undefined : msg.author_id);
  const [picker, setPicker] = useState<DOMRect | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuWatch = useRef<(() => void) | null>(null);
  // only the row being pointed at or focused draws its toolbar (see activeRow)
  const active = useRowActive(msg.id);
  const uid = useId();
  const selecting = useSelection((x) => x.channelId === msg.channel_id);
  const nav = useNavigate();
  const openModal = useUI((u) => u.openModal);
  const picked = useSelection((x) => x.channelId === msg.channel_id && !!x.picked[msg.id]);
  const pickable = !preview && selecting && !pending && canPick(msg);

  // A role of mine in the server counts too. The row listens to my roles
  // there, so its tint follows a role given or taken away.
  useServers((st) => (serverId ? st.byId[serverId]?.me?.role_ids : undefined));
  // someone blocked does not ping, even in a run of theirs opened to read
  const authorBlocked = useRelationships((r) => r.byUser[msg.author_id]?.type === "blocked");
  const mentioned = !!me && msg.author_id !== me.id && !authorBlocked && mentionsMe(msg, me.id);

  const openMenu = useCallback((x: number, y: number, image?: ImageTarget | null, anchor?: ContextMenuState["anchor"]) => {
    setMenuOpen(true);
    // the menu is named by when the message was sent: its last lines say so to the eye
    const more = () => setPicker(new DOMRect(x, y, 0, 0));
    const reactions = canReact(msg) ? { emoji: quickReactions(), mine: msg.reactions.filter((r) => r.me).map((r) => r.emoji), pick: (e: string) => void toggleReaction(msg, e), more } : undefined;
    messageMenu.open({ msg, openReactionPicker: more, image }, { x, y, ...(anchor ? { anchor } : {}), label: messageTimes(msg).join(", "), reactions });
    menuWatch.current?.();
    menuWatch.current = useUI.subscribe((state) => {
      if (!state.contextMenu) {
        setMenuOpen(false);
        menuWatch.current?.();
        menuWatch.current = null;
      }
    });
  }, [msg]);
  // Switching channels unmounts every row, menu open or not, and the store
  // would keep this subscriber and the closure behind it for good.
  useEffect(
    () => () => {
      menuWatch.current?.();
      menuWatch.current = null;
    },
    [],
  );

  /** While picking, a tap anywhere on the row ticks it; Shift ticks everything from the last one. */
  const pick = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    const sel = useSelection.getState();
    if (e.shiftKey && sel.anchor && sel.anchor !== msg.id) {
      const ch = useMessages.getState().channels[msg.channel_id];
      const range = rangeBetween(ch?.ids ?? [], sel.anchor, msg.id).filter((id) => {
        const m = ch?.byId[id];
        return !!m && canPick(m);
      });
      sel.addRange(range, msg.id);
    } else sel.toggle(msg.id);
  };

  if (msg.type.startsWith("system_")) return <SystemMessage msg={msg} />;

  // an app's answer to a command says who used it, on the line a reply's quote takes
  const isReply = !!msg.reply_to_id || !!command;
  const pinned = (msg.flags & MSG_FLAG_PINNED) !== 0;

  const showHeader = groupStart || isReply;
  const profile = (e: React.MouseEvent<HTMLElement>) => {
    // a published copy speaks for a channel elsewhere, not for a person
    if (copy) return;
    // a webhook is not a person with a profile: its card says what it is
    if (webhookHook) {
      const el = e.currentTarget;
      setHookCard((cur) => (cur ? null : el));
      return;
    }
    openProfilePopout(msg.author_id, e.currentTarget, serverId ?? undefined);
  };
  // A right click on the picture or the name is about the person, as in the
  // member list; anything else on the row, and anyone who is not a person
  // here (a webhook, a published copy), gets the message's menu.
  const personMenu = (e: React.MouseEvent<HTMLElement>) => {
    if (copy || webhookHook || pending || ephemeral || selecting) return;
    if (!openPersonMenu(e, msg.author_id, msg.channel_id, nav, openModal)) return;
    e.preventDefault();
    e.stopPropagation();
  };
  // A row reads as who, when and what, whether or not it shows the name: a
  // message grouped under the one before still says who wrote it.
  const whoId = `${uid}who`;
  const bodyId = `${uid}body`;

  return (
    <div
      role="article"
      aria-labelledby={`${whoId} ${bodyId}`}
      className={s.msg}
      data-id={msg.id}
      data-group-start={showHeader}
      data-mentioned={mentioned}
      data-highlight={highlight}
      data-first={first}
      data-reply={isReply}
      data-ephemeral={ephemeral || undefined}
      data-pending={pending === "sending"}
      data-failed={pending === "failed"}
      data-uploading={!!upload || undefined}
      data-menu={menuOpen}
      data-pickable={pickable || undefined}
      data-picked={picked || undefined}
      onClickCapture={pickable ? pick : undefined}
      onContextMenu={(e) => {
        // an answer only you see is nobody else's to react to or reply to: it has no menu
        if (preview || pending || ephemeral) return;
        // a right click in the edit box is for the text: spelling suggestions, cut, copy and paste
        if (e.target instanceof Element && e.target.closest("textarea, input, [contenteditable='true']")) return;
        e.preventDefault();
        if (selecting) {
          // a long press or right click while picking ticks the row instead of opening its menu
          if (pickable) pick(e);
          return;
        }
        // the menu key or Shift+F10 on a focused row has no pointer: open by the row
        if (e.clientX === 0 && e.clientY === 0) {
          const r = e.currentTarget.getBoundingClientRect();
          openMenu(r.left + 72, r.top + 8);
          return;
        }
        openMenu(e.clientX, e.clientY, imageTarget(e.target));
      }}
      // reachable with the arrow keys from the list (MessageList), and its
      // toolbar shows while it has focus
      tabIndex={-1}
      data-row=""
    >
      {pickable ? (
        <button type="button" className={s.pick} role="checkbox" aria-checked={picked} aria-label={picked ? t("chat.messageItem.unselectMessage") : t("chat.messageItem.selectMessage")} data-testid="pick-message">
          {picked ? <Check size={14} strokeWidth={3} /> : null}
        </button>
      ) : null}
      {command ? <InteractionLine msg={msg} it={command} /> : isReply ? <ReplyPreview msg={msg} /> : null}
      {showHeader ? (
        <>
          {/* the same way to the profile as the name beside it, for a pointer only: one stop for the keyboard is enough */}
          <button type="button" className={s.avatar} data-group-start={showHeader} data-avatar="" onClick={profile} onContextMenu={personMenu} tabIndex={-1} aria-hidden="true">
            {copy ? <Avatar id={copy.server_id} avatarId={copy.server_icon_id} name={copy.server_name} size={40} kind="icon" /> : hook && msg.webhook_id ? <HookAvatar hook={hook} webhookId={msg.webhook_id} /> : <Avatar id={msg.author_id} avatarId={avatarId} name={name} size={40} />}
          </button>
          <div className={s.headerRow} id={whoId}>
            <button type="button" className={[s.author, copy ? "" : nameClass(look)].join(" ")} data-author={msg.author_id} data-webhook={msg.webhook_id} style={copy ? undefined : nameStyle(look)} onClick={profile} onContextMenu={personMenu} dir="auto" aria-haspopup={webhookHook ? "dialog" : undefined}>{name}</button>
            {look?.icon && !copy && !webhookHook ? <RoleIcon icon={look.icon} size={18} /> : null}
            {copy ? (
              <span className={s.followTag} title={t("follows.tagHint")}>
                <Megaphone size={11} aria-hidden="true" />
                {t("follows.tag")}
              </span>
            ) : hook ? <HookTags app={fromApp} /> : <UserTag userId={msg.author_id} open />}
            <HeaderTime at={msg.created_at} />
          </div>
          {hookCard && webhookHook && msg.webhook_id ? <HookPopout hook={webhookHook} webhookId={msg.webhook_id} anchor={hookCard} onClose={closeHookCard} /> : null}
        </>
      ) : (
        <>
          <span className={s.gutterTime} title={formatFull(msg.created_at)} aria-hidden="true">{formatTime(msg.created_at)}</span>
          <span className="visually-hidden" id={whoId}>{t("chat.messageItem.whoWhen", { name, time: formatTimestamp(msg.created_at) })}</span>
        </>
      )}
      {preview ? <div ref={(el) => el?.setAttribute("inert", "")}><MessageBody msg={msg} name={name} id={bodyId} pending={pending} upload={upload} /></div> : editing ? <EditMessage msg={msg} /> : <MessageBody msg={msg} name={name} id={bodyId} pending={pending} upload={upload} />}
      {evidence}
      {!preview && !pending && !editing && canPublish(msg) ? (
        <button type="button" className={s.publishBtn} onClick={() => void publish(msg)} data-testid="publish">
          <Megaphone size={15} aria-hidden="true" />
          {t("follows.publish")}
        </button>
      ) : null}
      {!pending && !ephemeral ? <Reactions msg={msg} pickerAnchor={picker} onOpenPicker={setPicker} onPickerClose={() => setPicker(null)} /> : null}
      {/* the thread it started hangs under everything the message carries, its reactions too */}
      {msg.thread && serverId && !pending ? <ThreadChip serverId={serverId} summary={msg.thread} /> : null}
      {ephemeral ? <OnlyYou msg={msg} /> : null}
      {!preview && !pending && !ephemeral && !editing && !selecting && (active || menuOpen) ? <RowToolbar msg={msg} pinned={pinned} dev={dev} setPicker={setPicker} openMenu={openMenu} /> : null}
      {dev && !pending ? <span hidden data-dev-id={msg.id} /> : null}
    </div>
  );
});

/**
 * What a message says and carries: its words, sticker, poll, files, link
 * previews, a webhook's embeds and invites. The same wherever the message is
 * shown, in the conversation and in its pins alike.
 */
export function MessageBody({ msg, name, id, pending, upload }: { msg: DecryptedMessage; name: string; id?: string; pending?: "sending" | "failed" | undefined; upload?: PendingUpload | undefined }) {
  const held = useAutomodHeld(msg);
  const [reveal, setReveal] = useState(false);
  // a moderator can still read what AutoMod holds back, on purpose
  const mayReveal = useServers((st) => {
    const sid = held ? useChannels.getState().byId[msg.channel_id]?.server_id : null;
    return sid ? canI(st.byId[sid], P.MANAGE_MESSAGES, msg.channel_id) : false;
  });
  if (held && !reveal) {
    return (
      <div id={id} className={s.content}>
        <span className={s.heldLine} data-testid="automod-held">
          <ShieldAlert size={15} aria-hidden="true" />
          {t("automod.held.text")}
          {mayReveal ? (
            <button type="button" className={s.sysLink} onClick={() => setReveal(true)}>{t("automod.held.show")}</button>
          ) : null}
        </span>
      </div>
    );
  }
  if (msg.type === "crosspost" && msg.crosspost?.deleted) {
    return (
      <div id={id} className={s.content}>
        <span className={s.waiting}>{t("follows.originalDeleted")}</span>
      </div>
    );
  }
  return <MessageContent msg={msg} name={name} id={id} pending={pending} upload={upload} />;
}

function MessageContent({ msg, name, id, pending, upload }: { msg: DecryptedMessage; name: string; id?: string | undefined; pending?: "sending" | "failed" | undefined; upload?: PendingUpload | undefined }) {
  const rowCtx = useRowRenderCtx();
  // Use External Emoji and Use External Stickers, held by every reader: what
  // the author may not use here draws as words
  const serverId = useChannels((c) => c.byId[msg.channel_id]?.server_id ?? null);
  // a media channel can leave the download buttons off its pictures and videos
  const noDownload = useDownloadsHidden(msg.channel_id);
  const noOutsideEmoji = useServers((x) => (serverId ? authorHas(x.byId[serverId], msg, P.USE_EXTERNAL_EMOJIS) === false : false));
  const noOutsideStickers = useServers((x) => (serverId ? authorHas(x.byId[serverId], msg, P.USE_EXTERNAL_STICKERS) === false : false));
  const ownEmoji = useExpressions((x) => (serverId ? x.emojis[serverId] : undefined));
  const stickerOutside = useExpressions((x) => {
    const st = msg.payload?.sticker;
    return !!st && noOutsideStickers && !Object.values(x.packs).some((p) => p.server_id === serverId && p.stickers.some((s) => s.id === st.id));
  });
  const ctx = useMemo(() => (noOutsideEmoji ? { ...rowCtx, plainEmoji: (eid: string) => !ownEmoji?.some((e) => e.blob_id === eid) } : rowCtx), [rowCtx, noOutsideEmoji, ownEmoji]);
  const hook = msg.hook;
  const imagePreviews = useSettings((st) => st.settings.imagePreviews);
  // a link to a channel or message is a pill in the text; a card sent for one by an older app is left off
  const shownEmbeds = msg.payload?.embeds?.length && !(msg.flags & MSG_FLAG_SUPPRESS_EMBEDS) ? (msg.payload.embeds as UnfurlResult[]).filter((e) => !jumpTarget(e.url)) : null;
  const embeds = shownEmbeds?.length ? shownEmbeds : null;
  // Pictures and GIFs stand alone; audio and video keep their original link above the player.
  const mediaOnly = !!embeds && imagePreviews && isOnlyMediaLinks(msg.payload?.t ?? "", embeds);
  const text = mediaOnly ? "" : (msg.payload?.t ?? "");
  const nodes = useMemo(() => (text ? parse(text) : []), [text]);
  // A message of nothing but emoji draws them big, all at one size: shrinking
  // them as there were more made two messages side by side look mismatched.
  const jumbo = useMemo(() => nodes.length > 0 && isEmojiOnly(nodes), [nodes]);
  // an app said it would answer, and has not yet
  if (hook?.application_id && msg.flags & MSG_FLAG_LOADING) {
    return (
      <div id={id} className={s.content}>
        <Thinking msg={msg} />
      </div>
    );
  }
  return (
    <div id={id}>
      {msg.payload?.fwd?.comment ? <Markdown source={msg.payload.fwd.comment} ctx={ctx} /> : null}
      <div className={[s.content, jumbo ? s.jumbo : ""].join(" ")} data-forwarded={msg.payload?.fwd ? "" : undefined}>
        {msg.payload?.fwd ? (
          // passed on from another message (lib/forward.ts), with when that one was sent
          <span className={s.forwarded} data-testid="forwarded">
            <CornerUpRight size={13} aria-hidden="true" />
            {t("forward.forwarded")}
            <time dateTime={new Date(msg.payload.fwd.at).toISOString()} title={formatFull(msg.payload.fwd.at)}>{formatShortDate(msg.payload.fwd.at)}</time>
          </span>
        ) : null}
        {msg.decryptError === "no_key" ? (
          <span className={s.waiting}><Lock size={14} /> {t("chat.messageItem.waitingForTheEncryptionKey")}</span>
        ) : msg.decryptError === "invalid" ? (
          <span className={s.waiting} data-testid="message-invalid"><ShieldAlert size={14} /> {t("chat.messageItem.breaksLimits")}</span>
        ) : msg.decryptError === "bad" ? (
          <span className={s.waiting}><Lock size={14} /> {t("chat.messageItem.thisMessageCouldNotBeDecrypted")}</span>
        ) : (
          <>
            {text ? <Markdown source={text} ctx={ctx} jumbo={jumbo} /> : null}
            {msg.edited_at ? <span className={s.edited} title={formatFull(msg.edited_at)}>{t("chat.messageItem.edited")}</span> : null}
          </>
        )}
        {msg.payload?.sticker ? stickerOutside ? <span className={s.edited}>{msg.payload.sticker.name ? `:${msg.payload.sticker.name}:` : t("chat.messageItem.sticker")}</span> : <StickerView sticker={msg.payload.sticker} /> : null}
        {msg.payload?.poll && !hook ? <PersonPoll msg={msg} poll={msg.payload.poll} pending={!!pending} /> : null}
        {upload ? <UploadCard channelId={msg.channel_id} tmpId={msg.id} upload={upload} failed={pending === "failed"} /> : msg.payload?.att?.length ? <Attachments list={msg.payload.att} from={name} noDownload={noDownload} /> : null}
        {embeds ? <Embeds list={embeds} onRemove={!pending && canRemoveEmbeds(msg) ? (ev) => confirmRemoveEmbeds(msg, ev.shiftKey) : undefined} /> : null}
        {hook?.application_id ? (
          // an app's buttons and menus do something, once it is really sent and known to the server
          <AppMsgProvider msg={msg} interactive={!pending && !msg.local}>
            <HookBody msg={msg} from={name} />
          </AppMsgProvider>
        ) : hook ? (
          <HookBody msg={msg} from={name} />
        ) : null}
        {msg.flags & MSG_FLAG_SUPPRESS_EMBEDS ? null : <InviteCards nodes={nodes} embeds={embeds ?? (hook?.link_embeds.length ? hook.link_embeds : null)} />}
        {msg.flags & MSG_FLAG_SUPPRESS_EMBEDS ? null : <ProfileCards nodes={nodes} embeds={embeds ?? (hook?.link_embeds.length ? hook.link_embeds : null)} />}
        {msg.flags & MSG_FLAG_SUPPRESS_EMBEDS ? null : <AppLinkCards nodes={nodes} />}
        {msg.flags & MSG_FLAG_SUPPRESS_EMBEDS ? null : <NoteCards nodes={nodes} />}
        {msg.flags & MSG_FLAG_SUPPRESS_EMBEDS ? null : <PackCards nodes={nodes} />}
        {pending === "failed" ? (
          <div className={s.failedRow}>
            <span>{t("chat.messageItem.messageFailedToSend")}</span>
            <button className={s.retry} onClick={() => void retryPending(msg.channel_id, msg.id)} data-testid="retry-send"><RotateCw size={13} /> {t("chat.messageItem.retry")}</button>
            <button className={s.retry} data-danger="true" onClick={() => discardPending(msg.channel_id, msg.id)}>{t("chat.messageItem.delete")}</button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/**
 * The toolbar over a row, in a component of its own: holding Shift changes
 * what it offers, and with the Shift state in the row itself every loaded
 * message re-rendered — markdown, embeds and all — on each Shift press.
 */
function RowToolbar({ msg, pinned, dev, setPicker, openMenu }: { msg: DecryptedMessage; pinned: boolean; dev: boolean; setPicker: (r: DOMRect | null) => void; openMenu: (x: number, y: number, image?: null, anchor?: ContextMenuState["anchor"]) => void }) {
  const shift = useShiftHeld();
  const setEditing = useUI((u) => u.setEditing);
  const setReplyTo = useUI((u) => u.setReplyTo);
  // "hold Shift" means nothing without a keyboard beside a mouse
  const [keyboardHint] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.("(hover: hover) and (pointer: fine)").matches);
  /** ← and → walk the buttons, as a toolbar's arrows do; the reading direction decides which way is next. */
  const onKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight" && e.key !== "Home" && e.key !== "End") return;
    const buttons = [...e.currentTarget.querySelectorAll<HTMLButtonElement>("button")];
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (at < 0 || buttons.length === 0) return;
    e.preventDefault();
    e.stopPropagation();
    const forward = (e.key === "ArrowRight") !== (document.documentElement.dir === "rtl");
    const next = e.key === "Home" ? 0 : e.key === "End" ? buttons.length - 1 : (at + (forward ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next]!.focus();
  };
  return (
    <div className={s.toolbar} role="toolbar" aria-label={t("chat.messageItem.messageActions")} data-shift={shift} onKeyDown={onKey}>
      {canReact(msg) ? <ToolButton label={t("chat.messageItem.addReaction")} onClick={(e) => setPicker(e.currentTarget.getBoundingClientRect())}><SmilePlus size={18} /></ToolButton> : null}
      {canEdit(msg) ? (
        <ToolButton label={t("chat.messageItem.editMessage")} onClick={() => setEditing({ channelId: msg.channel_id, messageId: msg.id })}><Pencil size={18} /></ToolButton>
      ) : null}
      <ToolButton label={t("chat.messageItem.reply")} onClick={() => setReplyTo({ channelId: msg.channel_id, messageId: msg.id, ping: true })}><Reply size={18} /></ToolButton>
      {canThread(msg) ? <ToolButton label={t("threads.create.title")} onClick={() => useUI.getState().openModal("createThread", { channelId: msg.channel_id, messageId: msg.id })}><MessageSquareText size={18} /></ToolButton> : null}
      {shift ? (
        <>
          {canPin(msg) ? <ToolButton label={pinned ? t("chat.messageItem.unpinMessage") : t("chat.messageItem.pinMessage")} onClick={() => void togglePin(msg)}>{pinned ? <PinOff size={18} /> : <Pin size={18} />}</ToolButton> : null}
          <ToolButton label={t("chat.messageItem.markUnread")} onClick={() => markUnread(msg)}><MailOpen size={18} /></ToolButton>
          <ToolButton label={t("chat.messageItem.copyMessageLink")} onClick={() => copyMessageLink(msg)}><Link2 size={18} /></ToolButton>
          {dev ? <ToolButton label={t("chat.messageItem.copyMessageId")} onClick={() => void copyText(msg.id, t("clipboard.idCopied"))}><Hash size={18} /></ToolButton> : null}
          {canDelete(msg) ? (
            <ToolButton label={t("chat.messageItem.deleteMessageShiftSkipsThePrompt")} danger onClick={() => void deleteMessageNow(msg)}><Trash2 size={18} /></ToolButton>
          ) : null}
        </>
      ) : null}
      <ToolButton label={shift ? t("chat.messageItem.everythingElse") : keyboardHint ? t("chat.messageItem.moreHoldShiftForAllActions") : t("chat.chat.more")} onClick={(e) => { const a = menuAnchor(e.currentTarget); openMenu(a.left, a.bottom + 4, null, a); }}><MoreHorizontal size={18} /></ToolButton>
    </div>
  );
}

/** How long a tapped time stays written out in full. */
const EXACT_FOR_MS = 4000;

/**
 * When a message was sent, by its author's name. A phone shows no tooltip,
 * so a tap writes the time out to the second for a few seconds; the menu says
 * it too, for the keyboard.
 */
function HeaderTime({ at }: { at: number }) {
  const [exact, setExact] = useState(false);
  useEffect(() => {
    if (!exact) return;
    const timer = setTimeout(() => setExact(false), EXACT_FOR_MS);
    return () => clearTimeout(timer);
  }, [exact]);
  return (
    <time className={s.time} title={formatFull(at)} dateTime={new Date(at).toISOString()} data-exact={exact || undefined} onClick={() => setExact(true)}>
      {exact ? formatExact(at) : formatTimestamp(at)}
    </time>
  );
}

/**
 * A person's poll, drawn by the same view as a webhook's: its words from the
 * sealed message, when it closes and its votes from the server's tally. One
 * still on its way, or one the server keeps no poll for, takes no votes.
 */
function PersonPoll({ msg, poll, pending }: { msg: DecryptedMessage; poll: NonNullable<MessagePayload["poll"]>; pending: boolean }) {
  const shaped = useMemo(() => hookPollOf(poll, msg.poll_results, msg.created_at), [poll, msg.poll_results, msg.created_at]);
  return <HookPoll poll={shaped} tally={msg.poll_results} channelId={msg.channel_id} messageId={msg.id} readOnly={pending || !msg.poll_results} />;
}

/** A sticker in a message: 160 px, keeps its aspect; a click shows its name and, for a public pack, the pack. */
function StickerView({ sticker }: { sticker: NonNullable<MessagePayload["sticker"]> }) {
  useMediaAnimation();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  // New messages carry the format; older ones are looked up in the packs on
  // this device. Anything still unknown renders as a picture, exactly as
  // before, which is right for every image format. (Only the lookup is
  // conditional, never the hook: which hooks run must not depend on the data.)
  const known = sticker.format;
  const looked = useExpressions((x) => (known ? undefined : stickerFormatOf(x.packs, sticker.id)));
  const format = known ?? looked;
  if (!sticker.blob) return <span className={s.edited}>{t("chat.messageItem.sticker")}</span>;
  const w = sticker.w && sticker.h ? Math.round((160 * sticker.w) / sticker.h) : 160;
  const toggle = (el: HTMLElement) => setAnchor((cur) => (cur ? null : el));
  // The lightbox only shows pictures, so video and animated stickers get
  // their own branch: no lightbox hook, and their click only opens the popout.
  // The wrapper is inline-block rather than contents: the popout positions
  // itself from this element's box, and a contents box does not have one,
  // which used to strand the popout in the top left corner.
  if (format === "tgs" || format === "webm") {
    const size = { width: Math.min(160, w), height: 160 };
    return (
      <>
        <span
          role="button"
          tabIndex={0}
          aria-label={sticker.name ? t("chat.messageItem.stickerNamed", { name: sticker.name }) : t("attachmentViews.sticker")}
          aria-expanded={!!anchor}
          data-testid="message-sticker"
          onClick={(e) => toggle(e.currentTarget)}
          onKeyDown={(e) => {
            if (e.key !== "Enter" && e.key !== " ") return;
            e.preventDefault();
            toggle(e.currentTarget);
          }}
          style={{ display: "inline-block", lineHeight: 0 }}
        >
          <StickerMedia format={format} src={stickerUrl(sticker.blob, 160)} alt={sticker.name ?? t("attachmentViews.sticker")} className={s.sticker} style={size} />
        </span>
        {anchor ? <StickerPopout sticker={sticker} anchor={anchor} onClose={close} /> : null}
      </>
    );
  }
  return (
    <>
      <img
        className={s.sticker}
        src={stickerUrl(sticker.blob, 160)}
        alt={sticker.name ?? t("attachmentViews.sticker")}
        width={Math.min(160, w)}
        height={160}
        loading="lazy"
        draggable={false}
        role="button"
        tabIndex={0}
        aria-label={sticker.name ? t("chat.messageItem.stickerNamed", { name: sticker.name }) : t("attachmentViews.sticker")}
        aria-expanded={!!anchor}
        data-testid="message-sticker"
        data-image-src={stickerUrl(sticker.blob)}
        data-image-name={sticker.name || "sticker"}
        onClick={(e) => toggle(e.currentTarget)}
        onKeyDown={(e) => {
          if (e.key !== "Enter" && e.key !== " ") return;
          e.preventDefault();
          toggle(e.currentTarget);
        }}
      />
      {anchor ? <StickerPopout sticker={sticker} anchor={anchor} onClose={close} /> : null}
    </>
  );
}
