import { PersonName } from "@/components/common/PersonName";
import { useEffect, useState } from "react";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { channelPerms, useServers } from "@/stores/servers";
import { has, P } from "@/lib/perms";
import { useSettings } from "@/stores/settings";
import { typingUsers, useTyping } from "@/stores/typing";
import { useUsers, displayName, fetchMissingUsers } from "@/stores/users";
import { useBlockRules } from "@/lib/blocked";
import { readTypingStyle } from "@/lib/typingStyle";
import { StyledTyping, TypingCard } from "./TypingLine";
import s from "./Chat.module.css";
import ty from "./Typing.module.css";

import { t as tr_, Tr } from "@/i18n";

/**
 * Who is writing in this conversation, under the messages. One person alone
 * shows their own typing style if they made one (lib/typingStyle), and a
 * press on it shows it off and offers the same; several are named together.
 * Names are the ones the conversation goes by: a server nickname, else a
 * friend's nickname, else the display name.
 */
export function TypingBar({ channelId }: { channelId: string }) {
  const meId = useAuth((a) => a.me?.id);
  const byChannel = useTyping((t) => t.byChannel);
  const sweep = useTyping((t) => t.sweep);
  const users = useUsers((u) => u.byId);
  const rules = useBlockRules();
  const serverId = useChannels((c) => c.byId[channelId]?.server_id ?? null);
  const members = useServers((st) => (serverId ? st.byId[serverId]?.members : undefined));
  const rels = useRelationships((r) => r.byUser);
  const serverState = useServers((st) => (serverId ? st.byId[serverId] : undefined));
  const showStyles = useSettings((x) => x.settings.customTyping !== false);
  const [card, setCard] = useState<HTMLElement | null>(null);
  useEffect(() => {
    const t = setInterval(() => sweep(), 2000);
    return () => clearInterval(t);
  }, [sweep]);
  // someone blocked is never shown typing (their events are dropped as they come; this covers the moment of blocking)
  const ids = typingUsers(byChannel, channelId, meId).filter((id) => !rules.blocked.has(id));
  const idsKey = ids.join(",");
  // someone this app has not met yet is asked for, rather than written as "Unknown User"
  useEffect(() => {
    if (idsKey) fetchMissingUsers(idsKey.split(","));
  }, [idsKey]);
  const nameOf = (id: string) => members?.[id]?.nickname || rels[id]?.nickname || displayName(users[id]);
  const who = ids.map(nameOf);
  const solo = ids.length === 1 ? ids[0]! : null;
  // a server channel can take typing styles away (Use Custom Typing Indicators); roles not loaded count as allowed
  const allowed = !solo || !serverState || (serverState.server.owner_id !== solo && !serverState.members[solo]) || has(channelPerms(serverState, channelId, solo), P.USE_CUSTOM_TYPING);
  const style = solo && showStyles && allowed ? readTypingStyle(users[solo]?.typing_style) : null;
  const styled = !!style;
  // the card goes with the style it shows off
  useEffect(() => {
    if (!styled) setCard(null);
  }, [styled]);

  let text: React.ReactNode = null;
  if (style && solo) {
    text = (
      <button type="button" className={ty.lineBtn} onClick={(e) => setCard(card ? null : e.currentTarget)} aria-haspopup="dialog" aria-expanded={!!card} data-testid="typing-styled">
        <StyledTyping style={style} name={who[0]!} userId={solo} serverId={serverId} />
      </button>
    );
  } else if (who.length > 0) {
    const line =
      who.length === 1 ? <Tr id="chat.typingBar.oneTyping" values={{ a: <b dir="auto"><PersonName userId={ids[0]} serverId={serverId}>{who[0]}</PersonName></b> }} /> :
      who.length === 2 ? <Tr id="chat.typingBar.twoTyping" values={{ a: <b dir="auto"><PersonName userId={ids[0]} serverId={serverId}>{who[0]}</PersonName></b>, b: <b dir="auto"><PersonName userId={ids[1]} serverId={serverId}>{who[1]}</PersonName></b> }} /> :
      who.length === 3 ? <Tr id="chat.typingBar.threeTyping" values={{ a: <b dir="auto"><PersonName userId={ids[0]} serverId={serverId}>{who[0]}</PersonName></b>, b: <b dir="auto"><PersonName userId={ids[1]} serverId={serverId}>{who[1]}</PersonName></b>, c: <b dir="auto"><PersonName userId={ids[2]} serverId={serverId}>{who[2]}</PersonName></b> }} /> :
      <>{tr_("chat.typingBar.severalPeopleAreTyping")}</>;
    text = (
      <>
        <span className={s.dots}><i /><i /><i /></span>
        <span className={ty.words}>{line}</span>
      </>
    );
  }
  // The same region whether or not anyone is typing, so a screen reader is
  // already listening when the first person starts; it used to appear with
  // its words, and the first announcement could be missed.
  return (
    <div className={s.typing} aria-live="polite" data-testid="typing-bar">
      {text}
      {card && style ? <TypingCard anchor={card} style={style} name={who[0]!} userId={solo} serverId={serverId} onClose={() => setCard(null)} /> : null}
    </div>
  );
}
