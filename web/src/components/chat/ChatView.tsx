import { useMemberLook } from "@/lib/useMemberLook";
import { nameClass, nameStyle } from "@/lib/roleLook";
import { AtSign, BellPlus, ChevronLeft, MessageCircle, MessageSquareText, MoreVertical, Phone, Pin, Search, UserPlus, UserRound, Users, Video, X } from "lucide-react";
import { ThreadsPanel } from "@/components/threads/ThreadsPanel";
import { ThreadActions, ThreadParentLink, ThreadStateBar, useThreadMenu } from "@/components/threads/ThreadBar";
import { isThread } from "@/stores/threads";
import { holdsThreads, isForum } from "@/lib/threads";
import { parse, plainText } from "@/markdown";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { Channel } from "@/api/types";
import { Avatar, StatusDot, statusLabel } from "@/components/common/Avatar";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { KeyStatusChip } from "@/components/profile/KeyVerify";
import { Composer } from "@/components/composer/Composer";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { Tooltip } from "@/components/common/Tooltip";
import { menuAnchor } from "@/components/common/ContextMenu";
import { SearchBox } from "@/components/common/SearchBox";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { useAuth } from "@/stores/auth";
import { activitiesOf, customStatus, statusOf, usePresence } from "@/stores/presence";
import { StatusLine } from "@/components/activity/StatusLine";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { conversationOf, useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { useIsMobile, useIsNarrow, useMediaQuery } from "@/hooks/useIsMobile";
import { useMemo } from "react";
import { useServers } from "@/stores/servers";
import { isPrivateChannel } from "@/lib/perms";
import { GroupMemberList } from "./GroupMemberList";
import { MemberList } from "./MemberList";
import { CallBanner } from "@/components/voice/CallBanner";
import { SearchPanel, useSearchPlaceholder } from "@/components/search/SearchPanel";
import sc from "@/components/search/Search.module.css";
import { VoiceView } from "@/components/voice/VoiceView";
import { useVoice } from "@/stores/voice";
import { useSelection } from "@/stores/selection";
import { useMessages } from "@/stores/messages";
import { joinVoice, toggleCamera } from "@/voice/session";
import { MessageList } from "./MessageList";
import { SubchatPanel } from "./SubchatPanel";
import { PinsPanel } from "./PinsPanel";
import { ProfileCard } from "@/components/profile/ProfileCard";
import { LockedChannel } from "./LockedChannel";
import { lockState } from "@/lib/subchatLock";
import { storage } from "@/lib/storage";
import { SelectionBar } from "./SelectionBar";
import { TypingBar } from "./TypingBar";
import { MutedBar } from "@/components/nexa/NexaNotices";
import { useMute } from "@/stores/nexa";
import { ReportBugButton } from "@/components/bugs/ReportBugButton";
import { chatMoreMenu } from "./chatMoreMenu";
import s from "./Chat.module.css";
import { SnowFall } from "./SnowFall";

import { t as tr_ } from "@/i18n";
const PROFILE_PANEL_KEY = "ec:dmProfilePanel";

/**
 * A channel's messages and the box to write in: the heart of every chat,
 * and the chat beside a voice channel's call.
 */
export function ChannelTalk({ channel }: { channel: Channel }) {
  const mute = useMute();
  const selecting = useSelection((x) => x.channelId === channel.id);
  return (
    <>
      <MessageList key={channel.id} channel={channel} />
      {/* the composer stays mounted underneath, so a draft and its attachments survive picking — and a mute */}
      <div className={s.composerSlot} hidden={selecting}>
        <TypingBar channelId={channel.id} />
        {isThread(channel) ? <ThreadStateBar thread={channel} /> : null}
        <div hidden={!!mute} className={s.composerSlot}>
          <Composer channel={channel} />
        </div>
        {mute ? <MutedBar mute={mute} /> : null}
      </div>
      {selecting ? <SelectionBar channelId={channel.id} /> : null}
    </>
  );
}

export function ChatView({ channel }: { channel: Channel }) {
  const me = useAuth((a) => a.me);
  const mute = useMute();
  const users = useUsers((u) => u.byId);
  const presence = usePresence((p) => p.byUser);
  const openModal = useUI((u) => u.openModal);
  const membersOpenDesktop = useUI((u) => u.membersOpen);
  const toggleMembersDesktop = useUI((u) => u.toggleMembers);
  // phones, tablets and narrow windows open the member list as a drawer that
  // starts closed on every channel: beside the chat it would squeeze it to nothing
  const rightOpen = useUI((u) => u.rightOpen);
  const setRightOpen = useUI((u) => u.setRightOpen);
  const [pins, setPins] = useState(false);
  const [threadsOpen, setThreadsOpen] = useState(false);
  const [subchats, setSubchats] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  // the header's search box works the panel's filter suggestions with the keyboard
  const searchKeys = useRef<((e: React.KeyboardEvent<HTMLInputElement>) => boolean) | null>(null);
  const [suggestActive, setSuggestActive] = useState<string | undefined>(undefined);
  const [suggestList, setSuggestList] = useState<string | undefined>(undefined);
  const onSuggest = useCallback((active: string | undefined, list: string | undefined) => {
    setSuggestActive(active);
    setSuggestList(list);
  }, []);
  // where the caret is in the box, so the suggestions follow a filter being edited in the middle
  const [searchCaret, setSearchCaret] = useState(0);
  const searchPlaceholder = useSearchPlaceholder(channel);
  // The profile of the person a DM is with, in a column beside it, as Discord shows it, remembered on
  // this device. Where it would cover the chat (a narrow window) it only opens when asked, each time.
  const [profileWide, setProfileWide] = useState(() => storage.get(PROFILE_PANEL_KEY) !== "0");
  const [profileNarrow, setProfileNarrow] = useState(false);
  const mobile = useIsMobile();
  const drawer = useIsNarrow();
  // A tablet standing up has finger-sized buttons and about 500px of header:
  // with the search box as well, the name was down to its first letter. It
  // searches the way a phone does, from a button, with the box in the panel.
  const compactSearch = useMediaQuery("(max-width: 1099px) and (pointer: coarse)") || mobile;
  const profileOpen = drawer ? profileNarrow : profileWide;
  const toggleProfile = () => {
    if (drawer) {
      setProfileNarrow((open) => !open);
      return;
    }
    storage.set(PROFILE_PANEL_KEY, profileWide ? "0" : "1");
    setProfileWide(!profileWide);
  };
  const membersOpen = drawer ? rightOpen : membersOpenDesktop;
  const toggleMembers = () => (drawer ? setRightOpen(!rightOpen) : toggleMembersDesktop());
  // Beside the conversation, the list slides in from its edge when it is
  // opened (from the header or a shortcut), never when a conversation that
  // already shows it is opened. Worked out as it renders, so the list is never
  // drawn in place for a frame before the slide starts.
  const [membersWere, setMembersWere] = useState(membersOpenDesktop);
  const [membersEnter, setMembersEnter] = useState(false);
  if (membersWere !== membersOpenDesktop) {
    setMembersWere(membersOpenDesktop);
    setMembersEnter(membersOpenDesktop);
  }
  // The drawer's member list is mounted only while open, and for its slide-out
  // after, so a closed drawer never pages in and sorts every member of a big server.
  const [membersMounted, setMembersMounted] = useState(membersOpen);
  useEffect(() => {
    if (membersOpen) {
      setMembersMounted(true);
      return;
    }
    const t = setTimeout(() => setMembersMounted(false), 250);
    return () => clearTimeout(t);
  }, [membersOpen]);
  const nav = useNavigate();
  const channelsById = useChannels((c) => c.byId);
  // A subchat borrows its conversation for calls, invites and the member
  // list: it is a room inside that chat, not a separate one.
  const convo = conversationOf(channelsById, channel) ?? channel;
  const isSub = channel.type === "subchat";
  const serverId = channel.server_id;
  const serverState = useServers((st) => (serverId ? st.byId[serverId] : undefined));
  const rels = useRelationships((r) => r.byUser);
  const nicknames = useMemo(() => friendNicknames(rels), [rels]);
  const name = channelDisplayName(channel, users, me?.id, nicknames);
  const dmLook = useMemberLook(null, channel.type === "dm" ? channel.recipient_ids.find((id) => id !== me?.id) : undefined);
  // a topic can be formatted: the header gives its words on one line, the topic's dialog the whole of it
  const topicLine = useMemo(() => (channel.topic ? plainText(parse(channel.topic)).replace(/\s+/g, " ").trim() : ""), [channel.topic]);
  const thread = isThread(channel);
  // threads start in text and announcement channels (a forum's posts are its threads)
  const threadsHere = !!channel.server_id && holdsThreads(channel) && !isForum(channel);
  const isServer = !!serverId && !!serverState;
  // on a phone a thread's choices are at the top of the More menu (ThreadBar)
  const threadMenu = useThreadMenu(isServer && thread ? channel : null, true);
  const inThisCall = useVoice((v) => v.mine?.channel_id === convo.id);
  const startCall = async (video: boolean) => {
    await joinVoice(convo.id);
    if (video) await toggleCamera();
  };
  // Panels belong to the chat they were opened in. On a phone they cover it,
  // so leaving one open across a channel switch hides the new conversation.
  useEffect(() => {
    setPins(false);
    setThreadsOpen(false);
    setSubchats(false);
    setSearchOpen(false);
    setSearchQuery("");
    setProfileNarrow(false);
    // picking belongs to the channel it started in too
    return () => {
      if (useSelection.getState().channelId === channel.id) useSelection.getState().clear();
    };
  }, [channel.id]);
  const selecting = useSelection((x) => x.channelId === channel.id);
  // read by the key handler below rather than inside a setState updater: React
  // runs updaters on the next render, long after the keydown has finished
  // dispatching, so stopping it there stopped nothing
  const open = useRef({ searchOpen, pins, subchats });
  open.current = { searchOpen, pins: pins || threadsOpen, subchats };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      if (document.querySelector("[data-modal-layer], [role='dialog'][aria-modal='true']")) return;
      // One Escape closes one thing, the one on top: the pinned messages or
      // the subchat list over the chat, then search. It used to close all
      // three at once, and a second Escape found nothing left to close.
      const now = open.current;
      if (now.pins) {
        setPins(false);
        setThreadsOpen(false);
      }
      else if (now.subchats) setSubchats(false);
      else if (now.searchOpen) setSearchOpen(false);
      else return;
      e.stopPropagation();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  const priv = isServer && isPrivateChannel(serverId, channel.overwrites);
  // A subchat with a password stays shut until this sign-in has typed it.
  const [unlocked, setUnlocked] = useState(!channel.locked);
  // A check that failed says nothing about whether this sign-in already opened
  // the subchat; swallowing it left the password wall standing for good.
  const [lockFailed, setLockFailed] = useState(false);
  const [recheck, setRecheck] = useState(0);
  useEffect(() => {
    if (!channel.locked) return setUnlocked(true);
    let alive = true;
    setUnlocked(false);
    setLockFailed(false);
    lockState(channel.id)
      .then((st) => alive && setUnlocked(!st.locked || st.unlocked))
      .catch(() => alive && setLockFailed(true));
    return () => {
      alive = false;
    };
  }, [channel.id, channel.locked, recheck]);
  useEffect(() => {
    if (!lockFailed) return;
    const again = () => setRecheck((n) => n + 1);
    window.addEventListener("online", again);
    return () => window.removeEventListener("online", again);
  }, [lockFailed]);
  const locked = !!channel.locked && !unlocked;
  const other = convo.type === "dm" ? convo.recipient_ids.find((id) => id !== me?.id) : undefined;
  const otherStatus = other ? statusOf(presence[other]) : "offline";
  const cs = other ? customStatus(presence[other]) : null;
  const act = other ? activitiesOf(presence[other])[0] : undefined;

  return (
    <div className={s.chat}>
      {/* the bug button measures this header to step aside where the name needs the room (ReportBugButton) */}
      <header className={s.header} data-chat-header="">
        <PageHeaderButtons />
        {channel.type === "group_dm" ? (
          // a group's icon and name open its settings, as Discord's header does
          <button className={s.hGroup} onClick={() => openModal("editGroup", { channelId: channel.id })} aria-label={tr_("chat.chat.editGroup")} data-testid="edit-group">
            <span className={s.hIcon}>{channel.icon_id ? <Avatar id={channel.id} avatarId={channel.icon_id} name={name} size={24} kind="icon" /> : <Users size={22} />}</span>
            <span className={s.hName} dir="auto">{name}</span>
          </button>
        ) : (
          <>
            {mobile && channel.type === "dm" && other ? (
              // A phone has no room for a status line beside the name, so the
              // person's picture carries it, as a dot, and the name gets the space.
              <span className={s.hIcon}>
                <Avatar id={other} avatarId={users[other]?.avatar_id ?? null} name={name} size={24} status={otherStatus} />
              </span>
            ) : (
              <span className={s.hIcon}>{isSub ? <MessageCircle size={22} /> : channel.type === "dm" ? <AtSign size={22} /> : isServer ? <ChannelGlyph channel={channel} size={22} locked={priv} /> : <Users size={22} />}</span>
            )}
            {channel.type === "dm" && other ? (
              // the other person's name is the way to their profile, as their avatar is
              <button className={[s.hName, s.hNameBtn, nameClass(dmLook)].join(" ")} style={nameStyle(dmLook)} onClick={() => openModal("profile", { userId: other })} data-testid="dm-header-name" dir="auto">{name}</button>
            ) : (
              <span className={s.hName} dir="auto">{name}</span>
            )}
          </>
        )}
        {isSub ? (
          <button className={s.hParent} onClick={() => nav(`/channels/@me/${convo.id}`)} data-testid="subchat-parent">
            <ChevronLeft size={14} />
            <span className={s.hParentText}>{tr_("chat.chatView.inConversation", { name: channelDisplayName(convo, users, me?.id, nicknames) })}</span>
          </button>
        ) : channel.type === "dm" && mobile ? null : channel.type === "dm" ? (
          // the status reads in its own colour; a custom status or activity keeps a dot of it in front
          cs || act ? (
            <span className={[s.hSub, s.hStatus].join(" ")} data-testid="dm-status">
              <StatusDot status={otherStatus} size={10} />
              <StatusLine className={s.hStatusText} status={cs} activity={act} more={Math.max(0, activitiesOf(presence[other!]).length - 1)} />
            </span>
          ) : (
            <span className={s.hSub} data-status={otherStatus} data-testid="dm-status">{statusLabel(otherStatus)}</span>
          )
        ) : isServer && thread ? (
          <ThreadParentLink thread={channel} />
        ) : isServer ? (
          topicLine ? <button className={[s.hSub, s.hTopic].join(" ")} title={topicLine} dir="auto" onClick={() => openModal("channelTopic", { channelId: channel.id })}>{topicLine}</button> : null
        ) : (
          // a phone keeps the header for the group's name; the count is in the member list
          mobile ? null : <span className={s.hSub}>{tr_("chat.chatView.membersCount", { count: channel.recipient_ids.length })}</span>
        )}
        {channel.type === "dm" && other ? (
          // Whether this device has seen this person's encryption key before,
          // and the way to the digits that let two people check it out of band.
          // A DM is where that matters most; an icon-only shield keeps the
          // header compact on every screen size.
          <span className={s.hKey}><KeyStatusChip userId={other} iconOnly onClick={() => openModal("verifyKey", { userId: other })} /></span>
        ) : null}
        <span className={s.hSpacer} />
        {!isServer && !mobile ? (
          <Tooltip text={tr_("chat.chat.subchats")} side="bottom">
            <button className={s.hBtn} data-on={subchats} aria-label={tr_("chat.chat.subchats")} onClick={() => setSubchats((v) => !v)} data-testid="subchats-button" data-pop-toggle="subchats"><MessageCircle size={22} /></button>
          </Tooltip>
        ) : null}
        {!isServer ? (
          <>
            <Tooltip text={tr_("chat.chat.startVoiceCall")} side="bottom">
              <button className={s.hBtn} aria-label={tr_("chat.chat.startVoiceCall")} onClick={() => void startCall(false)} data-testid="start-call"><Phone size={22} /></button>
            </Tooltip>
            <Tooltip text={tr_("chat.chat.startVideoCall")} side="bottom">
              <button className={s.hBtn} aria-label={tr_("chat.chat.startVideoCall")} onClick={() => void startCall(true)}><Video size={22} /></button>
            </Tooltip>
          </>
        ) : null}
        {compactSearch ? (
          <Tooltip text={tr_("chat.chat.search")} side="bottom">
            <button className={s.hBtn} data-on={searchOpen} aria-label={tr_("chat.chat.searchMessages")} onClick={() => setSearchOpen((v) => !v)}><Search size={22} /></button>
          </Tooltip>
        ) : (
          <SearchBox
            className={sc.searchBox}
            value={searchQuery}
            onChange={(v) => {
              setSearchQuery(v);
              setSearchCaret(v.length);
              setSearchOpen(true);
            }}
            onSelect={(e) => setSearchCaret(e.currentTarget.selectionStart ?? searchQuery.length)}
            placeholder={searchPlaceholder}
            shorterPlaceholders={[tr_("chat.chat.search")]}
            aria-label={tr_("chat.chat.searchMessages")}
            title={tr_("chat.chat.searchThisDeviceSDecryptedMessages")}
            data-testid="search-input"
            dir="auto"
            data-header-search=""
            role="combobox"
            aria-expanded={searchOpen && !!suggestList}
            aria-controls={suggestList}
            aria-autocomplete="list"
            aria-activedescendant={suggestActive}
            onFocus={() => setSearchOpen(true)}
            onClear={() => setSearchOpen(false)}
            onEscape={() => {
              setSearchOpen(false);
              (document.activeElement as HTMLElement | null)?.blur();
            }}
            // ↓ ↑ and Enter choose from the filter suggestions in the panel
            onKeyDown={(e) => void searchKeys.current?.(e)}
          />
        )}
        {isServer && thread && !mobile ? <ThreadActions thread={channel} /> : null}
        {!mobile && threadsHere ? (
          <Tooltip text={tr_("threads.panel.title")} side="bottom">
            <button className={s.hBtn} data-on={threadsOpen} aria-label={tr_("threads.panel.title")} onClick={() => setThreadsOpen((v) => !v)} data-pop-toggle="threads" data-testid="threads-button"><MessageSquareText size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile && isServer && channel.type === "announcement" ? (
          <Tooltip text={tr_("follows.follow")} side="bottom">
            <button className={s.hBtn} aria-label={tr_("follows.follow")} onClick={() => openModal("follow", { channelId: channel.id })} data-testid="follow-button"><BellPlus size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile ? (
          <Tooltip text={tr_("chat.chat.pinnedMessages")} side="bottom">
            <button className={s.hBtn} data-on={pins} aria-label={tr_("chat.chat.pinnedMessages")} onClick={() => setPins((v) => !v)} data-pop-toggle="pins"><Pin size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile && isServer ? (
          <Tooltip text={tr_("chat.chat.invitePeople")} side="bottom">
            <button className={s.hBtn} aria-label={tr_("chat.chat.invitePeople")} onClick={() => openModal("invite", { serverId, channelId: channel.id })}><UserPlus size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile && !isServer ? (
          <Tooltip text={convo.type === "dm" ? tr_("chat.chat.addFriendsToDm") : tr_("chat.chat.inviteToGroup")} side="bottom">
            <button className={s.hBtn} aria-label={tr_("chat.chat.addFriends")} onClick={() => openModal(convo.type === "group_dm" ? "groupInvite" : "createDM", convo.type === "dm" ? { initial: other ? [other] : [] } : { channelId: convo.id })}><UserPlus size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile && channel.type === "dm" && other ? (
          <Tooltip text={profileOpen ? tr_("chat.chat.hideUserProfile") : tr_("chat.chat.showUserProfile")} side="bottom">
            <button className={s.hBtn} data-on={profileOpen} aria-label={profileOpen ? tr_("chat.chat.hideUserProfile") : tr_("chat.chat.showUserProfile")} aria-pressed={profileOpen} onClick={toggleProfile} data-testid="toggle-profile"><UserRound size={22} /></button>
          </Tooltip>
        ) : null}
        {!mobile && (convo.type === "group_dm" || isServer) ? (
          <Tooltip text={tr_("chat.chat.memberList")} side="bottom">
            <button className={s.hBtn} data-on={membersOpen} aria-label={tr_("chat.chat.memberList")} onClick={toggleMembers}><Users size={22} /></button>
          </Tooltip>
        ) : null}
        {/* every conversation's header has the way to tell the team something broke; a phone has it in More */}
        {!mobile && !isServer ? <ReportBugButton from={convo.type === "group_dm" ? "group" : "dm"} className={s.hBtn} minHeaderWidth={600} /> : null}
        {mobile ? (
          <button
            className={s.hBtn}
            aria-label={tr_("chat.chat.more")}
            data-testid="chat-more"
            onClick={(e) => {
              const anchor = menuAnchor(e.currentTarget);
              chatMoreMenu.open(
                {
                  channel,
                  convo,
                  isServer,
                  ...(other ? { other } : {}),
                  thread: threadMenu.ctx,
                  threadsHere,
                  membersOpen,
                  inThisCall,
                  openPins: () => setPins(true),
                  openThreads: () => setThreadsOpen(true),
                  openSubchats: () => setSubchats(true),
                  toggleMembers,
                  openModal,
                },
                { x: anchor.left, y: anchor.bottom + 4, anchor, label: tr_("chat.chat.more") },
              );
            }}
          >
            <MoreVertical size={22} />
          </button>
        ) : null}
      </header>
      {subchats && !isServer ? <SubchatPanel parent={convo} activeId={channel.id} onClose={() => setSubchats(false)} /> : null}
      <div className={s.body} data-search={searchOpen}>
        {/* The call and the composer belong to the conversation's column, so the member list, search and the
            profile run the full height beside them, and pinned messages open at the top rather than under the call. */}
        {/* under a search that covers it, the chat is out of reach of Tab and of a screen reader */}
        <div className={s.main} {...(searchOpen && compactSearch ? ({ inert: "" } as Record<string, string>) : {})}>
          <SnowFall />
          {!isServer ? <CallBanner channelId={convo.id} /> : null}
          {!isServer && inThisCall ? <VoiceView channel={convo} embedded /> : null}
          {locked ? (
            <LockedChannel
              channel={channel}
              onUnlocked={() => {
                // A message that came in while it was locked made the list try to
                // load it, and the server said no; it said "could not be loaded"
                // for a second after the password before trying again.
                useMessages.getState().forgetFailures(channel.id);
                setUnlocked(true);
              }}
              checkFailed={lockFailed}
              onRecheck={() => setRecheck((n) => n + 1)}
            />
          ) : (
            <ChannelTalk channel={channel} />
          )}
        </div>
        {pins ? <PinsPanel channelId={channel.id} onClose={() => setPins(false)} /> : null}
        {threadsOpen && threadsHere ? <ThreadsPanel channel={channel} onClose={() => setThreadsOpen(false)} /> : null}
        {/* where search covers the chat, a picked result closes it (keeping the query) so the message shows */}
        {searchOpen ? <SearchPanel channel={channel} query={searchQuery} onQuery={setSearchQuery} caret={searchCaret} inline={compactSearch} keysRef={searchKeys} onSuggest={onSuggest} onJump={drawer ? () => setSearchOpen(false) : undefined} onClose={() => { setSearchOpen(false); setSearchQuery(""); }} /> : null}
        {isServer && serverId && (drawer ? membersOpen || membersMounted : membersOpen) ? <MemberList serverId={serverId} channelId={channel.id} drawer={drawer} open={membersOpen} entering={!drawer && membersEnter} onEntered={() => setMembersEnter(false)} /> : null}
        {drawer && membersOpen && (isServer || convo.type === "group_dm") ? <div className={s.membersBackdrop} onClick={toggleMembers} /> : null}
        {convo.type === "group_dm" && (drawer || membersOpen) ? <GroupMemberList channel={convo} drawer={drawer} open={membersOpen} /> : null}
        {!mobile && channel.type === "dm" && other && profileOpen && !searchOpen ? (
          <aside className={s.profilePanel} aria-label={tr_("chat.chat.userProfile")} data-testid="dm-profile-panel">
            <ProfileCard key={other} userId={other} variant="panel" />
          </aside>
        ) : null}
      </div>
    </div>
  );
}
