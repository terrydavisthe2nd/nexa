import { useEffect, useState } from "react";
import { t, useLocale } from "@/i18n";
import { gateway } from "@/gateway/session";
import { desktopBridge, type DisplaySourceRequest, type RpcActivity } from "@/lib/desktopBridge";
import { accelerator, KEYBIND_ACTIONS, keybindsOf, type KeybindAction } from "@/lib/keybinds";
import { runKeybind } from "@/lib/keybindActions";
import { unreadTotal } from "@/lib/title";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useDesktop } from "@/stores/desktop";
import { useReadStates } from "@/stores/readStates";
import { useRelationships } from "@/stores/relationships";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useVoice } from "@/stores/voice";
import { useVoiceLocal } from "@/stores/voiceLocal";
import { DisplayPicker } from "./DisplayPicker";

/**
 * Everything the page does for the desktop app while it runs inside it: sends
 * Rich Presence activities to chatd, tells the app who is signed in, how many
 * messages wait and whether a call is on, answers the tray's Mute and Deafen,
 * holds the keybinds system-wide, and shows the screen picker. Renders nothing
 * in a browser.
 */
export function DesktopEffects() {
  const [picker, setPicker] = useState<DisplaySourceRequest | null>(null);
  const locale = useLocale();

  // The app's tray, menu bar, right-click menu and offline page are drawn by
  // the operating system, but the only catalogue is here, so the words go over
  // whenever the language changes. The app keeps the last set, which is what
  // makes a tray icon read correctly before any page has loaded.
  useEffect(() => {
    desktopBridge()?.setStrings?.({
      open: t("desktop.native.open"),
      mute: t("settings.notifications.mute"),
      deafen: t("shell.user.deafen"),
      checkForUpdates: t("settings.desktop.checkForUpdates"),
      quit: t("desktop.native.quit"),
      view: t("desktop.native.view"),
      reload: t("shell.errorBoundary.reload"),
      addToDictionary: t("desktop.native.addToDictionary"),
      copyLink: t("sidebar.serverMenu.copyLink"),
      openLinkInBrowser: t("desktop.native.openLinkInBrowser"),
      offlineTitle: t("desktop.native.offlineTitle"),
      offlineBody: t("desktop.native.offlineBody"),
      offlineRetry: t("desktop.native.offlineRetry"),
      about: t("desktop.native.about"),
      moveTitle: t("desktop.native.moveTitle"),
      moveBody: t("desktop.native.moveBody"),
      moveReplaceBody: t("desktop.native.moveReplaceBody"),
      moveButton: t("desktop.native.moveButton"),
      notNow: t("desktop.native.notNow"),
      moveFailed: t("desktop.native.moveFailed"),
    });
  }, [locale]);

  useEffect(() => {
    const bridge = desktopBridge();
    if (!bridge) return;
    const offs: (() => void)[] = [];
    let latest: RpcActivity[] = [];
    let sent = "";

    // chatd keeps a connection's activities only while that connection lives,
    // so they go again whenever the gateway (re)connects; otherwise only changes go
    const send = (force = false) => {
      const share = useSettings.getState().settings.shareActivity !== false;
      const body = JSON.stringify(share ? latest : []);
      if (!force && body === sent) return;
      sent = body;
      gateway()?.send("activities", { activities: share ? latest : [] });
    };
    offs.push(
      bridge.onActivities((list) => {
        latest = list;
        send();
      }),
    );
    void bridge.rpc.getState().then((state) => {
      useDesktop.getState().setRpc(state);
      latest = state.apps.filter((a) => a.activity && !a.hidden).map((a) => a.activity!);
      if (latest.length) send();
    });
    offs.push(bridge.rpc.onState((state) => useDesktop.getState().setRpc(state)));
    offs.push(
      useUI.subscribe((s, prev) => {
        if (s.connection === "open" && prev.connection !== "open") send(true);
      }),
    );
    offs.push(
      useSettings.subscribe((s, prev) => {
        if (s.settings.shareActivity !== prev.settings.shareActivity) send();
      }),
    );

    // who programs asking over RPC are told is signed in
    const identify = () => {
      const me = useAuth.getState().me;
      bridge.setIdentity(me ? { id: me.id, username: me.username, global_name: me.display_name || me.username, avatar: null } : null);
    };
    identify();
    offs.push(useAuth.subscribe(identify));

    let badge = -1;
    const count = () => {
      const n = unreadTotal();
      if (n === badge) return;
      badge = n;
      bridge.setBadge(n);
    };
    count();
    offs.push(useChannels.subscribe(count), useReadStates.subscribe(count), useSettings.subscribe(count), useRelationships.subscribe(count));

    let inCall = false;
    offs.push(
      useVoice.subscribe((v) => {
        const now = !!v.mine;
        if (now === inCall) return;
        inCall = now;
        // a call keeps running at full speed while the window is hidden
        bridge.setCallActive(now);
      }),
    );

    offs.push(
      bridge.onTrayAction((action) => {
        if (action === "mute") useVoiceLocal.getState().toggleMute();
        else useVoiceLocal.getState().toggleDeafen();
      }),
    );
    offs.push(bridge.onDisplaySources((req) => setPicker(req)));

    // The keybinds, held system-wide so they answer with a game or anything
    // else in front (app 1.2.2 and later). The whole set goes each time it
    // changes; the app says which keys it got.
    if (bridge.setShortcuts && bridge.onShortcut) {
      const setShortcuts = bridge.setShortcuts;
      offs.push(
        bridge.onShortcut((action) => {
          if ((KEYBIND_ACTIONS as string[]).includes(action)) runKeybind(action as KeybindAction);
        }),
      );
      let held = "";
      const hold = () => {
        const st = useSettings.getState().settings;
        const binds = keybindsOf(st.keybinds);
        const list = st.backgroundKeybinds === false ? [] : KEYBIND_ACTIONS.flatMap((action) => {
          const accel = accelerator(binds[action]);
          return accel ? [{ action, accelerator: accel }] : [];
        });
        const key = JSON.stringify(list);
        if (key === held) return;
        held = key;
        void setShortcuts(list)
          .then((res) => {
            if (held !== key) return;
            if (list.length === 0) useDesktop.getState().setShortcuts(null);
            else useDesktop.getState().setShortcuts(Object.fromEntries(res.map((r) => [r.action, r.ok])));
          })
          .catch(() => useDesktop.getState().setShortcuts(null));
      };
      hold();
      offs.push(useSettings.subscribe((s, prev) => {
        if (s.settings.keybinds !== prev.settings.keybinds || s.settings.backgroundKeybinds !== prev.settings.backgroundKeybinds) hold();
      }));
      // signed out, nobody's keys are held
      offs.push(() => {
        void setShortcuts([]).catch(() => undefined);
        useDesktop.getState().setShortcuts(null);
      });
    }
    void bridge.updates.getState().then((u) => useDesktop.getState().setUpdate(u));
    offs.push(bridge.updates.onState((u) => useDesktop.getState().setUpdate(u)));

    // The window system's word on whether the app is in front, for notifications and read marks.
    // The page counts as visible while hidden in a call (the app keeps a call at full speed, and
    // apps before 1.2.5 every page), and its own focus reading was seen to stay true with another
    // app in front.
    const onWindow = (w: { focused: boolean; visible: boolean; minimized: boolean }) => {
      useDesktop.getState().setWindow(w);
      useUI.getState().setFocused(w.focused && w.visible && !w.minimized);
    };
    if (bridge.getWindowState && bridge.onWindowState) {
      void bridge.getWindowState().then(onWindow);
      offs.push(bridge.onWindowState(onWindow));
    }

    return () => {
      for (const off of offs) off();
    };
  }, []);

  if (!picker) return null;
  return (
    <DisplayPicker
      request={picker}
      onChoose={(sourceId, withAudio, soundId) => {
        desktopBridge()?.chooseDisplaySource(picker.requestId, sourceId, withAudio, soundId);
        setPicker(null);
      }}
    />
  );
}
