import { PersonName } from "@/components/common/PersonName";
import { BellOff, Phone } from "lucide-react";
import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Avatar } from "@/components/common/Avatar";
import { Modal } from "@/components/common/Modal";
import { channelDisplayName } from "@/lib/names";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { displayName, useUsers } from "@/stores/users";
import { useVoice } from "@/stores/voice";
import { declineCall, ignoreCall, joinVoice } from "@/voice/session";
import { HangUp } from "./HangUp";
import s from "./Voice.module.css";

import { t } from "@/i18n";
/**
 * Rings for incoming DM calls: accept joins the DM's voice, decline tells the
 * caller, and ignore just stops the ringing here — the caller still hears it
 * ring, and the call can be joined from the conversation until it ends.
 */
export function IncomingCallHost() {
  const ringing = useVoice((v) => v.ringing);
  const users = useUsers((u) => u.byId);
  const channels = useChannels((c) => c.byId);
  const meId = useAuth((a) => a.me?.id);
  const nav = useNavigate();
  const entry = Object.entries(ringing).sort((a, b) => a[1].at - b[1].at)[0];
  const ringingId = entry?.[0];
  // A call arriving mid-sentence closes the keyboard, which would otherwise
  // cover the buttons on a phone (the dialog leaves focus alone on touch).
  useEffect(() => {
    if (!ringingId) return;
    const el = document.activeElement;
    if (el instanceof HTMLElement && (el.isContentEditable || el.matches("input, textarea"))) el.blur();
  }, [ringingId]);
  if (!entry) return null;
  const [channelId, { from }] = entry;
  const channel = channels[channelId];
  const caller = users[from];
  const accept = async () => {
    nav(`/channels/@me/${channelId}`);
    await joinVoice(channelId);
  };
  return (
    // Escape puts the call away rather than saying no to the caller
    <Modal open onClose={() => ignoreCall(channelId)} width={360} closeOnOverlay={false} bare ariaLabel={t("voice.incomingCall.incomingCall")}>
      <div className={s.ring} style={{ background: "var(--bg-surface)", borderRadius: "var(--radius-md)", padding: 24 }} data-testid="incoming-call">
        <div className={s.ringAvatar}>
          <Avatar id={from} avatarId={caller?.avatar_id ?? null} name={displayName(caller)} size={80} />
        </div>
        <div className={s.ringName}><PersonName userId={from} user={caller}>{displayName(caller)}</PersonName></div>
        <div className={s.ringSub}>{channel && channel.type === "group_dm" ? t("voice.incomingCall.inGroup", { name: channelDisplayName(channel, users, meId) }) : t("voice.incomingCall.incomingCall")}</div>
        <div className={s.ringBtns}>
          <button type="button" className={s.ringAction} onClick={() => void declineCall(channelId)} data-testid="decline-call">
            <span className={[s.ringBtn, s.ringDecline].join(" ")} aria-hidden="true">
              <HangUp size={24} />
            </span>
            <span className={s.ringLabel}>{t("voice.incomingCall.decline")}</span>
          </button>
          <button type="button" className={s.ringAction} onClick={() => ignoreCall(channelId)} title={t("voice.incomingCall.ignoreHint")} data-testid="ignore-call">
            <span className={[s.ringBtn, s.ringIgnore].join(" ")} aria-hidden="true">
              <BellOff size={22} />
            </span>
            <span className={s.ringLabel}>{t("voice.incomingCall.ignore")}</span>
          </button>
          <button type="button" className={s.ringAction} onClick={() => void accept()} data-testid="accept-call">
            <span className={[s.ringBtn, s.ringAccept].join(" ")} aria-hidden="true">
              <Phone size={24} />
            </span>
            <span className={s.ringLabel}>{t("voice.incomingCall.accept")}</span>
          </button>
        </div>
      </div>
    </Modal>
  );
}
