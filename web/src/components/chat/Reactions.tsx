import { useMediaAnimation } from "@/lib/mediaAnimation";
import { SmilePlus } from "lucide-react";
import { useCallback, useState } from "react";
import { messages as messagesApi } from "@/api/messages";
import { emojiUrl } from "@/api/users";
import { Twemoji } from "@/components/common/Twemoji";
import { Tooltip } from "@/components/common/Tooltip";
import { ExpressionPicker } from "@/components/pickers/ExpressionPicker";
import { emojiByUnicode, emojiLoaded } from "@/components/pickers/emojiIndex";
import type { EmojiPick } from "@/components/pickers/EmojiPicker";
import { noteEmojiUse } from "@/lib/emojiSuggest";
import { reactionKey } from "@/lib/emojiUsage";
import { useAuth } from "@/stores/auth";
import { emojiReactionKey } from "@/stores/expressions";
import { useMessages, type DecryptedMessage } from "@/stores/messages";
import { useUI } from "@/stores/ui";
import { useChannels } from "@/stores/channels";
import { canI, P, useServers } from "@/stores/servers";
import { useUsers, displayName } from "@/stores/users";
import { canReact } from "./messageMenu";
import { REACTORS_SHOWN, ReactorsPopover } from "./ReactorsPopover";
import s from "./Chat.module.css";

import { t } from "@/i18n";
/**
 * The reaction key for a pick. Pack emoji arrive as a bare <a?:name:id> token
 * without `custom`, and the key must not keep the angle brackets.
 */
export function reactionKeyOf(e: EmojiPick): string {
  if (e.custom) return emojiReactionKey(e.custom);
  const m = /^<(a?):(\w+):(\d+)>$/.exec(e.unicode);
  return m ? `${m[1]}:${m[2]}:${m[3]}` : e.unicode;
}

function isCustom(emoji: string): { id: string; name: string; animated: boolean } | null {
  const m = /^(a?):?([\w]+):(\d+)$/.exec(emoji);
  if (!m) return null;
  return { id: m[3]!, name: m[2]!, animated: m[1] === "a" };
}

/**
 * A reaction's name for the card that lists who reacted: a custom emoji's
 * :name:, a standard one's shortcode once the emoji set is here (the emoji
 * itself is already drawn beside it).
 */
function nameOf(emoji: string): string {
  if (isCustom(emoji)) return emojiLabel(emoji);
  const entry = emojiLoaded() ? emojiByUnicode(emoji) : undefined;
  return entry?.shortcodes[0] ? `:${entry.shortcodes[0]}:` : "";
}

/** How a reaction is named to people: a custom emoji by its :name:, not by the key the server stores. */
function emojiLabel(emoji: string): string {
  const custom = isCustom(emoji);
  return custom ? `:${custom.name}:` : emoji;
}

/** Toggles a reaction with optimistic UI; reverts on failure. */
export async function toggleReaction(msg: DecryptedMessage, emoji: string): Promise<void> {
  const me = useAuth.getState().me;
  if (!me) return;
  const has = msg.reactions.some((r) => r.emoji === emoji && r.me);
  const store = useMessages.getState();
  store.setReaction(msg.channel_id, msg.id, emoji, me.id, !has, true);
  try {
    if (has) await messagesApi.unreact(msg.channel_id, msg.id, emoji);
    else {
      await messagesApi.react(msg.channel_id, msg.id, emoji);
      noteEmojiUse([reactionKey(emoji)]);
    }
  } catch (err) {
    store.setReaction(msg.channel_id, msg.id, emoji, me.id, has, true);
    useUI.getState().toast(err instanceof Error ? err.message : t("chat.reactions.couldNotReact"), "error");
  }
}

interface Props {
  msg: DecryptedMessage;
  pickerAnchor: DOMRect | null;
  onOpenPicker: (anchor: DOMRect) => void;
  onPickerClose: () => void;
}

export function Reactions({ msg, pickerAnchor, onOpenPicker, onPickerClose }: Props) {
  useMediaAnimation();
  const users = useUsers((u) => u.byId);
  // reacting with another server's emoji takes Use External Emoji, as the server holds it
  const reactServer = useChannels((c) => c.byId[msg.channel_id]?.server_id ?? null);
  const ownOnly = useServers((x) => (reactServer ? !canI(x.byId[reactServer], P.USE_EXTERNAL_EMOJIS, msg.channel_id) : false));
  // who reacted, asked for once per emoji and count: a count that moved means
  // the list did too, where it used to be kept from the first look for good
  const [who, setWho] = useState<Record<string, string[]>>({});
  // What the pills last showed, so a reaction that arrives pops in and a count
  // that moves ticks over, while what was here when the message was drawn
  // stays still. Each one's motion is let go only when it has finished, so a
  // render on the way (the names coming in on hover) cannot cut it short.
  const counts = msg.reactions.map((r) => `${r.emoji}\u0001${r.count}`).join("\u0000");
  const [seen, setSeen] = useState(() => ({ counts, by: new Map(msg.reactions.map((r) => [r.emoji, r.count])) }));
  const [moving, setMoving] = useState<ReadonlyMap<string, "pop" | "up" | "down">>(() => new Map());
  if (seen.counts !== counts) {
    const next = new Map(moving);
    for (const r of msg.reactions) {
      const was = seen.by.get(r.emoji);
      if (was === undefined) next.set(r.emoji, "pop");
      else if (was !== r.count && next.get(r.emoji) !== "pop") next.set(r.emoji, r.count > was ? "up" : "down");
    }
    setSeen({ counts, by: new Map(msg.reactions.map((r) => [r.emoji, r.count])) });
    setMoving(next);
  }
  const landed = (emoji: string) =>
    setMoving((m) => {
      if (!m.has(emoji)) return m;
      const n = new Map(m);
      n.delete(emoji);
      return n;
    });
  const [showing, setShowing] = useState<{ emoji: string; anchor: DOMRect } | null>(null);
  const closeShowing = useCallback(() => setShowing(null), []);
  const closePicker = onPickerClose;
  // Where a new reaction may not be added (an announcements channel, say), no
  // + is offered and only one's own reactions can be taken back: the server
  // refused the rest after the pick.
  const mayReact = canReact(msg);
  if (msg.reactions.length === 0 && !pickerAnchor) return null;
  const lookup = async (emoji: string, count: number): Promise<void> => {
    const key = `${emoji}:${count}`;
    if (who[key]) return;
    try {
      const ids = await messagesApi.reactors(msg.channel_id, msg.id, emoji, REACTORS_SHOWN);
      setWho((w) => ({ ...w, [key]: ids }));
    } catch {
      /* the label stays generic */
    }
  };
  // A long press, a right click or the menu key shows who reacted: there is
  // no hover on a touch screen, and no tooltip for a keyboard.
  const show = (e: React.MouseEvent<HTMLButtonElement>, emoji: string, count: number) => {
    e.preventDefault();
    e.stopPropagation();
    setShowing({ emoji, anchor: e.currentTarget.getBoundingClientRect() });
    void lookup(emoji, count);
  };
  const open = showing ? msg.reactions.find((r) => r.emoji === showing.emoji) : undefined;
  return (
    <div className={s.reactions}>
      {msg.reactions.map((r) => {
        const custom = isCustom(r.emoji);
        const label = emojiLabel(r.emoji);
        const ids = who[`${r.emoji}:${r.count}`];
        const names = ids?.map((id) => displayName(users[id])).join(", ");
        const locked = !mayReact && !r.me;
        // the app's own tooltip, as on the toolbar above: it says who, once they are known
        return (
          <Tooltip key={r.emoji} text={names ? t("chat.reactions.reactedWith", { who: names, emoji: label }) : t("chat.reactions.reactWith", { emoji: label })} side="top">
            <button
              className={s.pill}
              data-me={r.me}
              data-new={moving.get(r.emoji) === "pop" || undefined}
              onAnimationEnd={(e) => e.target === e.currentTarget && landed(r.emoji)}
              data-locked={locked || undefined}
              aria-pressed={r.me}
              aria-disabled={locked || undefined}
              aria-label={t("chat.reactions.pillLabel", { emoji: label, count: r.count })}
              onMouseEnter={() => void lookup(r.emoji, r.count)}
              onFocus={() => void lookup(r.emoji, r.count)}
              onContextMenu={(e) => show(e, r.emoji, r.count)}
              onClick={() => !locked && void toggleReaction(msg, r.emoji)}
            >
              {custom ? <img src={emojiUrl(custom.id, custom.animated, 24)} alt="" /> : <Twemoji text={r.emoji} className={s.pillImg} fallbackClassName={s.pillEmoji} title="" />}
              {/* keyed by the count, so a new number is a new element that ticks in */}
              <span key={r.count} aria-hidden="true" className={s.pillCount} data-tick={moving.get(r.emoji) === "up" || moving.get(r.emoji) === "down" ? moving.get(r.emoji) : undefined} onAnimationEnd={() => landed(r.emoji)}>{r.count}</span>
            </button>
          </Tooltip>
        );
      })}
      {msg.reactions.length > 0 && mayReact ? (
        <button className={s.addReact} aria-label={t("chat.reactions.addReaction")} onClick={(e) => onOpenPicker(e.currentTarget.getBoundingClientRect())}><SmilePlus size={19} /></button>
      ) : null}
      {showing && open ? (
        <ReactorsPopover
          anchor={showing.anchor}
          emoji={open.emoji}
          custom={isCustom(open.emoji)}
          label={nameOf(open.emoji)}
          count={open.count}
          ids={who[`${open.emoji}:${open.count}`] ?? null}
          onClose={closeShowing}
        />
      ) : null}
      {pickerAnchor ? <ExpressionPicker title={t("chat.reactions.addReaction")} anchor={pickerAnchor} serverId={reactServer} onlyServer={ownOnly} onClose={closePicker} onEmoji={(e) => { closePicker(); void toggleReaction(msg, reactionKeyOf(e)); }} /> : null}
    </div>
  );
}
