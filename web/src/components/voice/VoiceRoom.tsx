import { MessageSquareText, X } from "lucide-react";
import { useEffect, useLayoutEffect, useState } from "react";
import { useLocation, useParams } from "react-router-dom";
import type { Channel } from "@/api/types";
import { ChannelTalk } from "@/components/chat/ChatView";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { Tooltip } from "@/components/common/Tooltip";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { useIsNarrow } from "@/hooks/useIsMobile";
import { t } from "@/i18n";
import { storage } from "@/lib/storage";
import { isUnread, useReadStates } from "@/stores/readStates";
import { useUI } from "@/stores/ui";
import { VoiceView } from "./VoiceView";
import c from "@/components/chat/Chat.module.css";
import s from "./VoiceRoom.module.css";

const CHAT_KEY = "ec:voiceChat";

/**
 * A server's voice channel: the call, and the channel's own chat. With room
 * the chat stands beside the call; where there is none (a phone, a narrow
 * window) it covers the call until it is closed, and the call carries on
 * underneath. Wide, whether it is open is remembered on this device; narrow,
 * it opens only when asked, as the member list does.
 */
export function VoiceRoom({ channel }: { channel: Channel }) {
  const narrow = useIsNarrow();
  const [wideOpen, setWideOpen] = useState(() => storage.get(CHAT_KEY) === "1");
  const [narrowOpen, setNarrowOpen] = useState(false);
  const open = narrow ? narrowOpen : wideOpen;
  const toggle = () => {
    if (narrow) return setNarrowOpen((v) => !v);
    storage.set(CHAT_KEY, wideOpen ? "0" : "1");
    setWideOpen(!wideOpen);
  };
  // A narrow screen's chat belongs to the visit that opened it: a link to one of
  // its messages or the channel list's chat button opens it, with no call
  // started, and pressing the channel's row (which joins) shows the call again.
  const { messageId } = useParams();
  const { key: visit, state } = useLocation();
  const asked = !!messageId || (state as { voiceChat?: boolean } | null)?.voiceChat === true;
  useLayoutEffect(() => {
    setNarrowOpen(asked);
    if (asked) setWideOpen(true);
  }, [channel.id, visit, asked]);
  // While the chat shows, this is the channel being read: what arrives is
  // marked read, and its notifications stay quiet.
  const setActiveChannel = useUI((u) => u.setActiveChannel);
  useEffect(() => {
    if (!open) return;
    setActiveChannel(channel.id);
    return () => setActiveChannel(null);
  }, [open, channel.id, setActiveChannel]);
  const read = useReadStates((r) => r.byChannel[channel.id]);
  const mentions = read?.mentions ?? 0;
  const unread = !open && isUnread(read, channel.last_message_id);
  const label = open ? t("voiceChat.hide") : t("voiceChat.show");
  return (
    <div className={c.chat}>
      <header className={c.header}>
        <PageHeaderButtons />
        <span className={c.hIcon}><ChannelGlyph channel={channel} size={22} /></span>
        <span className={c.hName} dir="auto">{channel.name}</span>
        <span className={c.hSpacer} />
        <Tooltip text={label} side="bottom">
          <button className={[c.hBtn, s.chatBtn].join(" ")} data-on={open} aria-label={label} aria-pressed={open} onClick={toggle} data-testid="voice-chat-toggle">
            <MessageSquareText size={22} />
            {!open && mentions > 0 ? <span className={s.mentions}>{mentions > 99 ? "99+" : mentions}</span> : unread ? <span className={s.dot} /> : null}
          </button>
        </Tooltip>
      </header>
      <div className={c.body}>
        {/* the call stays where it is under a chat that covers it: out of reach of Tab and a screen reader meanwhile */}
        <div className={s.stage} {...(open && narrow ? ({ inert: "" } as Record<string, string>) : {})}>
          <VoiceView channel={channel} />
        </div>
        {open ? (
          <section className={s.chat} data-cover={narrow} aria-label={t("voiceChat.title", { name: channel.name })} data-testid="voice-chat">
            {narrow ? (
              <div className={s.chatHead}>
                <MessageSquareText size={18} aria-hidden="true" />
                <span className={s.chatTitle} dir="auto">{t("voiceChat.title", { name: channel.name })}</span>
                <button className={c.hBtn} aria-label={t("voiceChat.hide")} onClick={toggle}><X size={20} /></button>
              </div>
            ) : null}
            <ChannelTalk channel={channel} />
          </section>
        ) : null}
      </div>
    </div>
  );
}
