import { ArrowLeftRight, AtSign, Bell, ChevronRight, Download, Headphones, HeadphoneOff, LogOut, Mic, MicOff, Pencil, Settings } from "lucide-react";
import { applyUpdate } from "@/lib/appUpdate";
import { useDesktop } from "@/stores/desktop";
import { useEffect, useRef, useState } from "react";
import AccountsFlyout from "./AccountsFlyout";
import { Avatar, StatusDot, statusLabel } from "@/components/common/Avatar";
import { Tooltip } from "@/components/common/Tooltip";
import { gateway } from "@/gateway/session";
import { useAuth } from "@/stores/auth";
import { activitiesOf, customStatus, statusOf, usePresence, type StatusKind } from "@/stores/presence";
import { StatusLine } from "@/components/activity/StatusLine";
import { useUI } from "@/stores/ui";
import { useVoice } from "@/stores/voice";
import { useVoiceLocal } from "@/stores/voiceLocal";
import s from "./UserPanel.module.css";
import ts from "@/components/common/Toggle.module.css";
import { openDeviceMenu } from "@/components/voice/deviceMenus";
import { useReadStates } from "@/stores/readStates";
import { bindText, keybindsOf } from "@/lib/keybinds";
import { useSettings } from "@/stores/settings";
import { useMediaQuery } from "@/hooks/useIsMobile";

import { PersonName } from "@/components/common/PersonName";
import { t } from "@/i18n";
import { confirmLogout } from "@/lib/confirmLogout";
function statusOptions(): { value: "online" | "idle" | "dnd" | "invisible"; label: string; sub?: string }[] {
  return [
  { value: "online", label: t("shell.user.online") },
  { value: "idle", label: t("shell.user.idle") },
  { value: "dnd", label: t("shell.user.doNotDisturb"), sub: t("shell.user.youWillNotReceiveAnyDesktop") },
  { value: "invisible", label: t("shell.user.invisible"), sub: t("shell.user.youWillAppearOfflineButKeep") },
];
}

/** Starts fetching the settings' code as the gear is pressed, so it is usually there by the time the press ends. */
function warmSettings(): void {
  void import("@/components/settings/SettingsModal").catch(() => undefined);
}

/** The same for Mentions. */
function warmMentions(): void {
  void import("@/components/mentions/MentionsModal").catch(() => undefined);
}

export function UserPanel() {
  const me = useAuth((a) => a.me);
  const presence = usePresence((p) => (me ? p.byUser[me.id] : undefined));
  const setPresence = usePresence((p) => p.set);
  const openModal = useUI((u) => u.openModal);
  const update = useDesktop((x) => x.update);
  const muted = useVoiceLocal((v) => v.muted);
  const deafened = useVoiceLocal((v) => v.deafened);
  const toggleMute = useVoiceLocal((v) => v.toggleMute);
  const toggleDeafen = useVoiceLocal((v) => v.toggleDeafen);
  // the account's own keys for these, shown in their tooltips (Settings, Keybinds)
  const keys = keybindsOf(useSettings((x) => x.settings.keybinds));
  // in a call, the ring the call draws round whoever talks goes round your own picture here too
  const speaking = useVoice((v) => !!me && !!v.mine && !!v.speaking[me.id]);
  // a moderator's mute and deafen hold whatever these buttons say, so the buttons say so
  const modMute = useVoice((v) => !!v.mine?.server_mute && !v.mine.suppress);
  const modDeaf = useVoice((v) => !!v.mine?.server_deaf);
  const toast = useUI((u) => u.toast);
  const sounds = useSettings((x) => x.settings.notifications.sounds);
  // every unread mention, from every conversation and server, on the Mentions button
  const mentionCount = useReadStates((r) => {
    let n = 0;
    for (const id in r.byChannel) n += r.byChannel[id]!.mentions;
    return n;
  });
  const [open, setOpen] = useState(false);
  // Switch Accounts' side menu: opened by hovering where a pointer hovers, by a tap or click elsewhere
  const [sub, setSub] = useState(false);
  const hovers = useMediaQuery("(hover: hover)");
  const subTrigger = useRef<HTMLButtonElement>(null);
  const flyout = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) setSub(false);
  }, [open]);
  const ref = useRef<HTMLDivElement>(null);

  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    // Opening never moved focus in and Escape never gave it back, so the menu
    // could be opened from the keyboard and then never reached.
    pop.current?.querySelector<HTMLElement>("button")?.focus({ preventScroll: true });
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      trigger.current?.focus({ preventScroll: true });
    };
  }, [open]);
  const pop = useRef<HTMLDivElement>(null);

  // Up and down through the choices, as a menu moves.
  const onPopKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const step = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : e.key === "Home" ? -99 : e.key === "End" ? 99 : 0;
    if (!step) return;
    e.preventDefault();
    const items = [...(pop.current?.querySelectorAll<HTMLElement>("button") ?? [])];
    const at = items.indexOf(document.activeElement as HTMLElement);
    items[Math.min(items.length - 1, Math.max(0, (at < 0 ? 0 : at) + step))]?.focus();
  };

  if (!me) return null;
  const status: StatusKind = statusOf(presence);
  const custom = customStatus(presence);
  const activities = activitiesOf(presence);
  const activity = activities[0];
  const choose = (value: "online" | "idle" | "dnd" | "invisible") => {
    gateway()?.sendPresence({ status: value });
    setPresence({ user_id: me.id, status: value, status_text: presence?.status_text ?? "", status_emoji: presence?.status_emoji ?? "", status_expires_at: presence?.status_expires_at ?? null });
    setOpen(false);
  };

  const setSounds = (on: boolean) => {
    const n = useSettings.getState().settings.notifications;
    useSettings.getState().patch({ notifications: { ...n, sounds: on } });
  };

  const openMicMenu = (at: { x: number; y: number }) =>
    void openDeviceMenu("mic", openModal, { ...at, above: true, label: t("voice.voice.microphoneOptions") });

  return (
    <div className={s.panel} ref={ref}>
      <button ref={trigger} className={s.me} onClick={() => setOpen((v) => !v)} aria-haspopup="menu" aria-expanded={open} data-testid="user-panel">
        <Avatar id={me.id} avatarId={me.avatar_id} name={me.display_name} size={32} status={status} className={s.avatar} data-speaking={speaking} />
        <span className={s.text}>
          <PersonName user={me} className={s.name}>{me.display_name}</PersonName>
          {custom || activity ? (
            <StatusLine className={s.sub} status={custom} activity={activity} more={Math.max(0, activities.length - 1)} />
          ) : (
            <span className={s.sub}>{statusLabel(status)}</span>
          )}
        </span>
      </button>
      <div className={s.btns}>
        <Tooltip text={modMute ? t("voice.moderation.mutedByModerator") : muted ? t("settings.notifications.unmute") : t("settings.notifications.mute")} keys={modMute ? undefined : bindText(keys.mute) || undefined}>
          <button
            className={s.icon}
            data-on={!muted && !modMute}
            onClick={() => (modMute ? toast(t("voice.moderation.mutedHint")) : toggleMute())}
            aria-label={modMute ? t("voice.moderation.mutedByModerator") : muted ? t("voice.voice.unmute") : t("voice.voice.mute")}
            onContextMenu={(e) => {
              // a right click chooses the microphone, as the arrow on the call's mute button does
              e.preventDefault();
              openMicMenu({ x: e.clientX, y: e.clientY });
            }}
            onKeyDown={(e) => {
              // Shift+F10 and the Menu key are how a keyboard opens a context
              // menu; without them the microphone could not be changed from
              // here at all without a mouse.
              if (e.key !== "ContextMenu" && !(e.key === "F10" && e.shiftKey)) return;
              e.preventDefault();
              const r = e.currentTarget.getBoundingClientRect();
              openMicMenu({ x: document.documentElement.dir === "rtl" ? r.right : r.left, y: r.top });
            }}
          >
            {muted || modMute ? <MicOff size={20} /> : <Mic size={20} />}
          </button>
        </Tooltip>
        <Tooltip text={modDeaf ? t("voice.moderation.deafenedByModerator") : deafened ? t("shell.user.undeafen") : t("shell.user.deafen")} keys={modDeaf ? undefined : bindText(keys.deafen) || undefined}>
          <button className={s.icon} data-on={!deafened && !modDeaf} onClick={() => (modDeaf ? toast(t("voice.moderation.deafenedHint")) : toggleDeafen())} aria-label={modDeaf ? t("voice.moderation.deafenedByModerator") : deafened ? t("shell.user.undeafen") : t("shell.user.deafen")}>
            {deafened || modDeaf ? <HeadphoneOff size={20} /> : <Headphones size={20} />}
          </button>
        </Tooltip>
        <Tooltip text={t("mentions.title")}>
          <button className={[s.icon, s.mentions].join(" ")} onPointerDown={warmMentions} onClick={() => openModal("mentions")} aria-label={mentionCount ? t("mentions.buttonUnread", { count: mentionCount }) : t("mentions.title")} data-testid="mentions-button">
            <AtSign size={20} />
            {mentionCount ? <span className={s.badge} aria-hidden="true">{mentionCount > 99 ? "99+" : mentionCount}</span> : null}
          </button>
        </Tooltip>
        {update?.status === "ready" ? (
          <Tooltip text={t("shell.user.restartToUpdateTo", { version: update.version ?? t("shell.user.theNewVersion") })}>
            <button className={[s.icon, s.update].join(" ")} onClick={() => void applyUpdate("restart")} aria-label={t("settings.desktop.restartToUpdate")} data-testid="update-ready">
              <Download size={20} />
            </button>
          </Tooltip>
        ) : null}
        <Tooltip text={t("settings.settings.userSettings")}>
          <button className={s.icon} onPointerDown={warmSettings} onClick={() => openModal("settings", { page: "account" })} aria-label={t("settings.settings.userSettings")} data-testid="user-settings">
            <Settings size={20} />
          </button>
        </Tooltip>
      </div>
      {open ? (
        <div className={s.pop} ref={pop} role="menu" aria-label={me.display_name} onKeyDown={onPopKey} data-modal-layer>
          <button className={s.popHead} role="menuitem" onClick={() => { setOpen(false); openModal("profile", { userId: me.id }); }} data-testid="menu-profile">
            <Avatar id={me.id} avatarId={me.avatar_id} name={me.display_name} size={40} status={status} />
            <div className={s.popIdentity}>
              <div className={s.popName}><PersonName user={me}>{me.display_name}</PersonName></div>
              <div className={s.popUser}>@{me.username}</div>
            </div>
          </button>
          <div className={s.sep} role="separator" />
          <button className={s.opt} role="menuitem" onClick={() => { setOpen(false); openModal("customStatus"); }}>
            <Pencil size={16} />
            <span className={s.optText}><span>{custom ? t("shell.user.editCustomStatus") : t("modals.customStatus.setACustomStatus")}</span></span>
          </button>
          <div className={s.sep} role="separator" />
          {statusOptions().map((o) => (
            <button key={o.value} className={[s.opt, s.statusOpt].join(" ")} onClick={() => choose(o.value)} role="menuitemradio" aria-checked={status === o.value}>
              <StatusDot status={o.value} size={12} className={s.statusDot} />
              <span className={s.optText}>
                <span>{o.label}</span>
                {o.sub ? <span className={s.optSub}>{o.sub}</span> : null}
              </span>
            </button>
          ))}
          <div className={s.sep} role="separator" />
          <button className={s.opt} role="menuitemcheckbox" aria-checked={sounds} onClick={() => setSounds(!sounds)} data-testid="menu-sounds">
            <Bell size={16} />
            <span className={s.optText} style={{ flex: 1 }}><span>{t("shell.user.notificationSounds")}</span></span>
            <span className={ts.switch} data-on={sounds} aria-hidden><span className={ts.knob} /></span>
          </button>
          <div className={s.sep} role="separator" />
          <div
            className={s.switchWrap}
            onMouseEnter={hovers ? () => setSub(true) : undefined}
            onMouseLeave={hovers ? () => setSub(false) : undefined}
          >
            <button
              ref={subTrigger}
              className={s.opt}
              role="menuitem"
              aria-haspopup="menu"
              aria-expanded={sub}
              data-open={sub || undefined}
              onClick={() => setSub((v) => !v)}
              onKeyDown={(e) => {
                if (e.key !== (document.documentElement.dir === "rtl" ? "ArrowLeft" : "ArrowRight")) return;
                e.preventDefault();
                setSub(true);
                requestAnimationFrame(() => flyout.current?.querySelector<HTMLElement>("button")?.focus());
              }}
              data-testid="menu-switch-accounts"
            >
              <ArrowLeftRight size={16} />
              <span className={s.optText} style={{ flex: 1 }}><span>{t("accounts.switchAccounts")}</span></span>
              <ChevronRight size={16} className={s.chev} />
            </button>
            {sub ? (
              <AccountsFlyout
                meId={me.id}
                flyoutRef={flyout}
                closeMenu={() => setOpen(false)}
                back={() => {
                  setSub(false);
                  subTrigger.current?.focus();
                }}
              />
            ) : null}
          </div>
          <div className={s.sep} role="separator" />
          <button className={[s.opt, s.danger].join(" ")} role="menuitem" onClick={() => { setOpen(false); confirmLogout(); }}>
            <LogOut size={16} />
            <span>{t("settings.settings.logOut")}</span>
          </button>
        </div>
      ) : null}
    </div>
  );
}
