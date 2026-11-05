import { CheckCircle2, Info, TriangleAlert, Unplug } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { activities as activitiesApi } from "@/api/activities";
import { ApiError } from "@/api/client";
import { connections, DISCORD_KINDS, type DiscordState, type LinkedDiscord } from "@/api/connections";
import type { Activity } from "@/api/types";
import { ActivityCard } from "@/components/activity/ActivityCard";
import { Button } from "@/components/common/Button";
import { Toggle } from "@/components/common/Toggle";
import d from "@/components/desktop/Desktop.module.css";
import { t } from "@/i18n";
import { desktopBridge, type RpcApp } from "@/lib/desktopBridge";
import { describeError } from "@/lib/errors";
import { formatRelative } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { useDesktop } from "@/stores/desktop";
import { awaitDiscordLink, useDiscord } from "@/stores/discord";
import { usePresence } from "@/stores/presence";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { GameDetectionSection } from "./GameDetection";
import a from "./Activity.module.css";
import st from "./Settings.module.css";

/**
 * User settings → Activity: whether what you are doing shows under your name,
 * the Discord account it can come from, and, in the desktop app, the programs
 * it hears through Rich Presence and the games it recognises running.
 */
export default function ActivityPage() {
  const share = useSettings((x) => x.settings.shareActivity !== false);
  const autoIdle = useSettings((x) => x.settings.autoIdle !== false);
  const patch = useSettings((x) => x.patch);
  const inApp = !!desktopBridge();
  useDiscordResult();
  return (
    <>
      <h2 className={st.title}>{t("settings.activity.title")}</h2>
      <h3 data-setting="activityStatus" className={st.h5}>{t("settings.activity.status")}</h3>
      <div className={st.form}>
        <Toggle checked={share} onChange={(v) => patch({ shareActivity: v })} label={t("settings.privacy.shareMyActivity")} description={t("settings.activity.shareHint")} />
        <Toggle checked={autoIdle} onChange={(v) => patch({ autoIdle: v })} label={t("settings.privacy.goIdleWhenAway")} description={t("settings.privacy.goIdleWhenAwayHint")} />
      </div>
      <h3 data-setting="discord" className={st.h5}>{t("settings.activity.discord")}</h3>
      <DiscordSection share={share} />
      {inApp ? <RichPresenceSection share={share} /> : null}
      {inApp ? <GameDetectionForActivity share={share} /> : null}
    </>
  );
}

function GameDetectionForActivity({ share }: { share: boolean }) {
  const rpcOn = useDesktop((x) => !!x.rpc?.enabled);
  return <GameDetectionSection share={share} rpcOn={rpcOn} />;
}

/** Says how linking went, once, when Discord has sent the person back here. */
function useDiscordResult(): void {
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const result = params.get("discord");
    if (!result) return;
    params.delete("discord");
    const rest = params.toString();
    window.history.replaceState(window.history.state, "", window.location.pathname + (rest ? `?${rest}` : "") + window.location.hash);
    const toast = useUI.getState().toast;
    if (result === "linked") {
      toast(t("settings.activity.discordLinked"), "success");
      void useDiscord.getState().load();
    } else if (result === "denied") toast(t("settings.activity.discordDenied"), "info");
    else if (result === "expired") toast(t("settings.activity.discordExpired"), "error");
    else if (result === "taken") toast(t("settings.activity.discordTaken"), "error");
    else toast(t("settings.activity.discordFailed"), "error");
  }, []);
}

/**
 * Goes to Discord's page for linking. A browser goes there itself and is sent
 * back to these settings. The desktop app hands the page to the system's
 * browser, which is told how it went on a page of its own, and says so here
 * when the server sends the new link.
 */
export async function connectDiscord(): Promise<void> {
  try {
    const inApp = !!desktopBridge();
    const { url } = await connections.authorizeDiscord(inApp);
    if (inApp) {
      awaitDiscordLink(() => useUI.getState().toast(t("settings.activity.discordLinked"), "success"));
      window.open(url, "_blank", "noopener");
      useUI.getState().toast(t("settings.activity.finishInBrowser"), "info");
      return;
    }
    window.location.assign(url);
  } catch (err) {
    useUI.getState().toast(describeError(err, t("settings.activity.discordFailed")), "error");
  }
}

/** Puts the linked account back in the activity server; one Discord no longer accepts goes through Discord's page again. */
export async function rejoinDiscord(): Promise<boolean> {
  try {
    useDiscord.getState().set(await connections.rejoinDiscord());
    useUI.getState().toast(t("settings.activity.rejoined"), "success");
    return true;
  } catch (err) {
    if (err instanceof ApiError && err.code === "reauthorize") {
      await connectDiscord();
      return false;
    }
    useUI.getState().toast(describeError(err, t("settings.activity.rejoinFailed")), "error");
    return false;
  }
}

function DiscordSection({ share }: { share: boolean }) {
  const state = useDiscord((x) => x.state);
  const [busy, setBusy] = useState<"" | "connect" | "unlink" | "rejoin">("");
  useEffect(() => {
    void useDiscord.getState().load();
  }, []);

  if (!state) return <p className={st.hint}>{t("chat.pins.loading")}</p>;
  if (!state.available) {
    return (
      <>
        <p className={st.hint}>{t("settings.activity.discordUnavailable")}</p>
        {state.bot ? <p className={st.hint}>{t("settings.activity.discordSetupHint")}</p> : null}
      </>
    );
  }
  const run = async (what: typeof busy, fn: () => Promise<unknown>) => {
    setBusy(what);
    try {
      await fn();
    } finally {
      setBusy("");
    }
  };
  const linked = state.linked;
  if (!linked) {
    return (
      <div className={a.connect}>
        <p className={a.lead}>{t("settings.activity.discordIntro")}</p>
        <p className={st.hint}>{t("settings.activity.discordHow")}</p>
        <div>
          <Button loading={busy === "connect"} onClick={() => void run("connect", connectDiscord)} data-testid="discord-connect">{t("settings.activity.connectDiscord")}</Button>
        </div>
        <BotStatus state={state} />
      </div>
    );
  }
  const unlink = () =>
    run("unlink", async () => {
      try {
        await connections.unlinkDiscord();
        useDiscord.getState().set({ ...state, linked: null });
        useUI.getState().toast(t("settings.activity.discordUnlinked"), "success");
      } catch (err) {
        useUI.getState().toast(describeError(err, t("settings.activity.unlinkFailed")), "error");
      }
    });
  return (
    <div className={st.form}>
      <div className={a.account} data-testid="discord-account">
        <DiscordAvatar url={linked.avatar_url} />
        <div className={a.names}>
          <span className={a.name}>{linked.global_name || linked.username}</span>
          <span className={a.handle}>{linked.username}</span>
        </div>
        <Button variant="secondary" size="sm" loading={busy === "unlink"} onClick={() => void unlink()} data-testid="discord-unlink">
          <Unplug size={16} /> {t("settings.activity.disconnect")}
        </Button>
      </div>
      <DiscordStatus linked={linked} busy={busy === "rejoin"} onRejoin={() => void run("rejoin", rejoinDiscord)} />
      <DiscordKinds linked={linked} disabled={!share} />
      <DiscordNow />
      <p className={st.hint}>{t("settings.activity.discordPrivacy")}</p>
      <BotStatus state={state} />
    </div>
  );
}

/** The linked account's picture, or a plain circle when it has none or it will not load. */
function DiscordAvatar({ url }: { url: string | undefined }) {
  const [failed, setFailed] = useState(false);
  if (!url || failed) return <span className={a.avatar} aria-hidden="true" />;
  return <img className={a.avatar} src={url} alt="" onError={() => setFailed(true)} />;
}

function DiscordStatus({ linked, busy, onRejoin }: { linked: LinkedDiscord; busy: boolean; onRejoin: () => void }) {
  const out = !linked.in_guild;
  const tone = out || !linked.watching ? "warn" : "good";
  const text = out
    ? linked.left_at > 0
      ? t("settings.activity.leftServer")
      : t("settings.activity.notInServer")
    : linked.watching
      ? t("settings.activity.syncing")
      : t("settings.activity.botAway");
  return (
    <div className={d.status} data-tone={tone} data-testid="discord-status">
      {tone === "good" ? <CheckCircle2 size={18} /> : <TriangleAlert size={18} />}
      <div className={d.statusText}>
        <span>{text}</span>
        {out ? (
          <div>
            <Button size="sm" loading={busy} onClick={onRejoin} data-testid="discord-rejoin">{t("settings.activity.rejoin")}</Button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** Which kinds of Discord activity show, each its own switch. */
function DiscordKinds({ linked, disabled }: { linked: LinkedDiscord; disabled: boolean }) {
  const kinds: { bit: number; label: string }[] = [
    { bit: DISCORD_KINDS.listening, label: t("settings.activity.kindListening") },
    { bit: DISCORD_KINDS.playing, label: t("settings.activity.kindPlaying") },
    { bit: DISCORD_KINDS.watching, label: t("settings.activity.kindWatching") },
    { bit: DISCORD_KINDS.streaming, label: t("settings.activity.kindStreaming") },
    { bit: DISCORD_KINDS.competing, label: t("settings.activity.kindCompeting") },
  ];
  const set = async (bit: number, on: boolean) => {
    const next = on ? linked.kinds | bit : linked.kinds & ~bit;
    try {
      useDiscord.getState().set(await connections.setDiscord({ kinds: next }));
    } catch (err) {
      useUI.getState().toast(describeError(err, t("settings.privacy.couldNotSave")), "error");
    }
  };
  return (
    <div>
      <p className={a.sub}>{t("settings.activity.whatShows")}</p>
      {kinds.map((k) => (
        <Toggle key={k.bit} checked={(linked.kinds & k.bit) !== 0} disabled={disabled} onChange={(v) => void set(k.bit, v)} label={k.label} />
      ))}
      {disabled ? <p className={st.hint}>{t("settings.activity.sharingOff")}</p> : null}
    </div>
  );
}

/** What Discord says right now, as others see it. */
function DiscordNow() {
  const meId = useAuth((x) => x.me?.id);
  const acts = usePresence((p) => (meId ? p.byUser[meId]?.activities : undefined));
  const fromDiscord = useMemo(() => (acts ?? []).filter((x) => x.source === "discord"), [acts]);
  if (fromDiscord.length === 0) return null;
  return (
    <div className={a.now}>
      <p className={a.sub}>{t("settings.activity.showingNow")}</p>
      {fromDiscord.map((x) => (
        <ActivityCard key={`${x.application_id ?? ""}:${x.name}:${x.created_at}`} activity={x} />
      ))}
    </div>
  );
}

/** For admins: whether the bot is connected, and the redirect the Developer Portal needs. */
function BotStatus({ state }: { state: DiscordState }) {
  const bot = state.bot;
  if (!bot?.configured) return null;
  return (
    <div className={a.admin} data-testid="discord-bot-status">
      <span className={a.adminHead}>{t("settings.activity.botHead")}</span>
      <span>{bot.connected ? t("settings.activity.botConnected") : t("settings.activity.botDisconnected", { error: bot.error || t("settings.desktop.unknownError") })}</span>
      {bot.redirect ? <span className={a.mono}>{t("settings.activity.botRedirect", { url: bot.redirect })}</span> : null}
      {bot.redirect && bot.redirect_listed === false ? (
        <span className={a.adminWarn} data-testid="discord-redirect-unlisted">
          <TriangleAlert size={14} aria-hidden="true" /> {t("settings.activity.botRedirectUnlisted")}
        </span>
      ) : null}
    </div>
  );
}

/** The desktop app's Rich Presence: the server programs talk to, and each program it has heard from. */
function RichPresenceSection({ share }: { share: boolean }) {
  const bridge = desktopBridge();
  const rpc = useDesktop((x) => x.rpc);
  const toast = useUI((u) => u.toast);
  const [previews, setPreviews] = useState<Record<string, Activity>>({});
  const [restarting, setRestarting] = useState(false);

  useEffect(() => {
    if (!bridge) return;
    void bridge.rpc.getState().then((s) => useDesktop.getState().setRpc(s));
  }, [bridge]);

  // names, icons and art as others will see them, from the server that resolves them for everyone
  const appsKey = useMemo(() => JSON.stringify(rpc?.apps.map((x) => [x.clientId, x.activity]) ?? []), [rpc]);
  useEffect(() => {
    const apps = rpc?.apps ?? [];
    if (apps.length === 0) {
      setPreviews({});
      return;
    }
    let alive = true;
    const timer = setTimeout(() => {
      const wanted = apps.map((x) => x.activity ?? { application_id: x.clientId, type: 0 as const });
      activitiesApi
        .preview(wanted)
        .then((list) => {
          if (!alive) return;
          const next: Record<string, Activity> = {};
          for (const act of list) if (act.application_id) next[act.application_id] = act;
          setPreviews(next);
        })
        .catch(() => {});
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
    // appsKey stands for rpc.apps
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appsKey]);

  if (!bridge) return null;
  const discord = rpc?.discord ?? "absent";
  const status = !rpc?.enabled
    ? { tone: "muted", icon: <Info size={18} />, text: t("settings.desktop.richPresenceIsOffProgramsTalk") }
    : !rpc.relay
      ? { tone: "muted", icon: <Info size={18} />, text: t("settings.desktop.activityShowsHereOnlyDiscordDoes") }
      : discord === "connected"
        ? { tone: "good", icon: <CheckCircle2 size={18} />, text: t("settings.desktop.discordIsRunningActivityShowsHere") }
        : discord === "first"
          ? { tone: "warn", icon: <TriangleAlert size={18} />, text: t("settings.desktop.discordStartedBeforeNexaSoPrograms") }
          : { tone: "good", icon: <CheckCircle2 size={18} />, text: t("settings.desktop.discordIsnTRunningActivityShows") };

  const restartDiscord = async () => {
    setRestarting(true);
    try {
      if (!(await bridge.rpc.restartDiscord())) toast(t("settings.desktop.discordDoesnTSeemToBe"), "info");
    } finally {
      setRestarting(false);
    }
  };

  return (
    <>
      <h3 data-setting="richPresence" className={st.h5}>{t("settings.desktop.richPresence")}</h3>
      <div className={st.form}>
        <Toggle checked={!!rpc?.enabled} onChange={(on) => void bridge.rpc.setEnabled(on)} label={t("settings.desktop.showWhatIMPlayingWatching")} description={t("settings.desktop.gamesMusicPlayersAndOtherPrograms")} />
        <Toggle checked={!!rpc?.relay} onChange={(on) => void bridge.rpc.setRelay(on)} disabled={!rpc?.enabled} label={t("settings.desktop.alsoShowItOnDiscord")} description={t("settings.desktop.whenDiscordIsRunningProgramsReach")} />
        <div className={d.status} data-tone={status.tone} data-testid="rpc-status">
          {status.icon}
          <div className={d.statusText}>
            <span>{status.text}</span>
            {discord === "first" && rpc?.enabled && bridge.platform === "win32" ? (
              <div>
                <Button size="sm" variant="secondary" loading={restarting} onClick={() => void restartDiscord()}>{t("settings.desktop.restartDiscord")}</Button>
              </div>
            ) : null}
          </div>
        </div>
        {!share && rpc?.enabled ? <p className={st.hint}>{t("settings.activity.sharingOff")}</p> : null}
        <p className={a.sub}>{t("settings.desktop.programs")}</p>
        {!rpc || rpc.apps.length === 0 ? (
          <p className={st.hint}>{t("settings.desktop.nothingIsUsingRichPresenceRight")}</p>
        ) : (
          <div className={d.apps}>
            {rpc.apps.map((x) => (
              <ProgramRow key={`${x.clientId}:${x.connectedAt}`} app={x} preview={previews[x.clientId]} onHide={(hidden) => void bridge.rpc.setHidden(x.clientId, hidden)} />
            ))}
          </div>
        )}
      </div>
    </>
  );
}

function ProgramRow({ app, preview, onHide }: { app: RpcApp; preview: Activity | undefined; onHide: (hidden: boolean) => void }) {
  const name = preview?.name || app.name || t("settings.desktop.programNamed", { id: app.clientId });
  return (
    <div className={d.app} data-hidden={app.hidden} data-testid="rpc-app">
      <div className={d.appHead}>
        <div style={{ minWidth: 0 }}>
          <div className={d.appName}>{name}</div>
          <div className={d.appSub}>{t("settings.desktop.connectedAt", { when: formatRelative(app.connectedAt) })}</div>
        </div>
        <Toggle checked={!app.hidden} onChange={(on) => onHide(!on)} ariaLabel={t("settings.desktop.showOnMyProfile", { name })} />
      </div>
      {app.activity && preview ? (
        <div className={d.preview}><ActivityCard activity={preview} /></div>
      ) : (
        <div className={d.appIdle}>{app.activity ? t("settings.desktop.loadingPreview") : t("settings.desktop.connectedNotShowingAnythingRightNow")}</div>
      )}
    </div>
  );
}
