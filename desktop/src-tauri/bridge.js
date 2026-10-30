/* eslint-disable */
/*
 * The desktop bridge, injected into nexa.fish before the page's own scripts.
 * Its shape is the one the old Electron preload put on window.evilionDesktop,
 * so the web app needs no changes to tell the two shells apart.
 */
(function () {
  if (window.evilionDesktop) return;

  var internals = window.__TAURI_INTERNALS__;
  var globalApi = window.__TAURI__;
  function invoke(command, payload, options) {
    if (globalApi && globalApi.core && globalApi.core.invoke) {
      return globalApi.core.invoke(command, payload, options);
    }
    return internals.invoke(command, payload, options);
  }

  function listen(event, handler) {
    var wrapped = function (incoming) {
      try {
        handler(incoming.payload);
      } catch (error) {
        console.error("desktop event " + event, error);
      }
    };
    if (globalApi && globalApi.event) {
      var pending = globalApi.event.listen(event, wrapped);
      // Callers only ever drop the listener; keep a no-op until it resolves.
      return function () {
        pending.then(function (stop) {
          stop();
        });
      };
    }
    var callback = internals.transformCallback(wrapped);
    var label = internals.metadata.currentWebview.label;
    internals.invoke("plugin:event|listen", {
      event: event,
      target: label,
      handler: callback,
    });
    return function () {
      internals.invoke("plugin:event|unlisten", {
        event: event,
        target: label,
        handler: callback,
      });
    };
  }

  var platform = (function () {
    var ua = navigator.userAgent;
    if (/Windows/i.test(ua)) return "win32";
    if (/Macintosh|Mac OS X/i.test(ua)) return "darwin";
    return "linux";
  })();

  var sourceListeners = new Set();
  var capture = null;

  function stopCapture(stream, ended) {
    var active = capture;
    if (!active || (stream && stream !== active.stream)) return;
    capture = null;
    clearTimeout(active.timer);
    if (active.reject) active.reject(new DOMException("Capture cancelled", "AbortError"));
    if (active.peer) active.peer.close();
    if (active.unlisten) active.unlisten();
    active.stream.getTracks().forEach(function (track) {
      track.stop();
      if (ended) track.dispatchEvent(new Event("ended"));
    });
    invoke("capture_stop", { id: active.id }).catch(function () {});
  }

  function waitPeer(peer, event, done) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { finish(new Error("Native media connection timed out")); }, 15000);
      function finish(error) {
        clearTimeout(timer);
        peer.removeEventListener(event, check);
        if (error) reject(error); else resolve();
      }
      function check() {
        if (peer.connectionState === "failed" || peer.connectionState === "closed") {
          finish(new Error("Native media connection closed"));
        } else if (done()) finish();
      }
      peer.addEventListener(event, check);
      check();
    });
  }

  async function captureScreen(options) {
    if (capture) throw new DOMException("Capture already active", "InvalidStateError");
    var active = { id: crypto.randomUUID(), stream: new MediaStream(), peer: null, timer: null, reject: null, choose: null, unlisten: null };
    capture = active;
    try {
      active.timer = setTimeout(function () { if (capture === active) stopCapture(); }, 180000);
      var request = await invoke("capture_sources", { id: active.id });
      if (capture !== active) throw new DOMException("Capture cancelled", "AbortError");
      var choice = await new Promise(function (resolve, reject) {
        active.choose = resolve;
        active.reject = reject;
        if (!sourceListeners.size) { reject(new Error("Screen picker is not ready")); return; }
        sourceListeners.forEach(function (callback) { callback(request); });
      });
      active.reject = null;
      active.choose = null;
      if (!choice.sourceId) throw new DOMException("Capture cancelled", "NotAllowedError");
      clearTimeout(active.timer);
      active.timer = setTimeout(function () { if (capture === active) stopCapture(null, true); }, 60000);
      var withSound = !!options.audio && choice.withAudio;
      if (withSound && !choice.soundId) throw new DOMException("Select an audio application", "NotReadableError");
      var pc = new RTCPeerConnection({ iceServers: [] });
      active.peer = pc;
      pc.ontrack = function (event) { active.stream.addTrack(event.track); };
      pc.addTransceiver("video", { direction: "recvonly" });
      if (withSound) pc.addTransceiver("audio", { direction: "recvonly" });
      var offer = await pc.createOffer();
      // Keep the same-host handoff stereo, before Nexa's encrypted call leg.
      offer.sdp = offer.sdp.replace(/(a=fmtp:\d+ [^\r\n]*useinbandfec=1[^\r\n]*)/g, "$1;stereo=1;sprop-stereo=1;maxaveragebitrate=192000");
      await pc.setLocalDescription(offer);
      await waitPeer(pc, "icegatheringstatechange", function () { return pc.iceGatheringState === "complete"; });
      var answer = await invoke("capture_start", {
        id: active.id,
        config: { sourceId: choice.sourceId, soundId: withSound ? choice.soundId : null, width: options.width, height: options.height, fps: options.fps },
        offer: pc.localDescription.sdp,
      });
      if (capture !== active) throw new DOMException("Capture cancelled", "AbortError");
      await pc.setRemoteDescription({ type: "answer", sdp: answer });
      await waitPeer(pc, "connectionstatechange", function () { return pc.connectionState === "connected"; });
      if (!active.stream.getVideoTracks().length || (withSound && !active.stream.getAudioTracks().length)) {
        throw new Error("Native capture did not provide the requested tracks");
      }
      pc.onconnectionstatechange = function () {
        if (pc.connectionState === "failed" || pc.connectionState === "closed") stopCapture(active.stream, true);
      };
      active.unlisten = listen("desktop://capture-ended", function (event) {
        if (event.id === active.id) stopCapture(active.stream, true);
      });
      clearTimeout(active.timer);
      return active.stream;
    } catch (error) {
      if (capture === active) stopCapture();
      throw error instanceof DOMException ? error : new DOMException(String(error), "NotReadableError");
    }
  }

  window.addEventListener("pagehide", function () { stopCapture(); });

  function bytesOf(value) {
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (value && value.buffer instanceof ArrayBuffer) {
      return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    }
    if (Array.isArray(value)) return new Uint8Array(value);
    return new Uint8Array(0);
  }

  var bridge = {
    platform: platform,
    version: "2.0.0",
    versions: { electron: null, chrome: null, node: null },
    captureScreen: captureScreen,
    stopScreen: function (stream) { stopCapture(stream); },
    // Sound is an explicitly selected external application, not an output mix.
    shareAudio: true,
    shareAudioMode: "application",
    windowAudio: true,

    setBadge: function (count) {
      return invoke("set_badge", { count: count | 0 });
    },
    requestAttention: function () {
      return invoke("request_attention_command", { on: true });
    },
    setCallActive: function (on) {
      return invoke("set_call_active", { on: !!on });
    },
    getWindowState: function () {
      return invoke("get_window_state");
    },
    onWindowState: function (callback) {
      return listen("desktop://window-state", callback);
    },
    closeToTray: function () {
      return invoke("get_settings").then(function (value) {
        return !!value.closeToTray;
      });
    },
    startMinimized: function () {
      return invoke("get_settings").then(function (value) {
        return !!value.startMinimized;
      });
    },
    launchAtLogin: function () {
      return invoke("get_settings").then(function (value) {
        return !!value.launchAtLogin;
      });
    },
    setLaunchAtLogin: function (on) {
      return bridge.settings.get().then(function (current) {
        return bridge.settings.set({
          launchAtLogin: !!on,
          startMinimized: current.startMinimized,
          closeToTray: current.closeToTray,
          hardwareAcceleration: current.hardwareAcceleration,
        });
      });
    },
    openExternal: function (url) {
      return invoke("open_external", { url: String(url) });
    },

    onDisplaySources: function (callback) {
      sourceListeners.add(callback);
      return function () { sourceListeners.delete(callback); };
    },
    chooseDisplaySource: function (requestId, sourceId, withAudio, soundId) {
      if (capture && capture.id === requestId && capture.choose) {
        capture.choose({ sourceId: sourceId, withAudio: !!withAudio, soundId: soundId });
      }
    },
    shareAudioFailed: function () {
      return Promise.resolve(false);
    },
    screenAccess: function () {
      return invoke("screen_access");
    },
    openScreenSettings: function () {
      return invoke("open_screen_settings");
    },
    resetScreenAccess: function () {
      return invoke("reset_screen_access").then(function () {
        return true;
      });
    },

    setStrings: function (strings) {
      return invoke("set_strings", { strings: strings });
    },
    setIdentity: function (identity) {
      return invoke("set_identity", { identity: identity });
    },
    setShortcuts: function (shortcuts) {
      return invoke("set_shortcuts", { shortcuts: shortcuts });
    },
    onShortcut: function (callback) {
      return listen("desktop://shortcut", callback);
    },
    onActivities: function (callback) {
      return listen("desktop://activity", callback);
    },
    onTrayAction: function (callback) {
      return listen("desktop://tray-action", callback);
    },
    paste: function () {
      return invoke("paste");
    },
    copy: function (text) {
      return invoke("copy", { text: String(text) });
    },

    updates: {
      getState: function () {
        return invoke("get_update_state");
      },
      check: function () {
        return invoke("check_for_updates");
      },
      restart: function () {
        return invoke("install_update");
      },
      onState: function (callback) {
        return listen("desktop://update", callback);
      },
    },

    rpc: {
      getState: function () {
        return invoke("rpc_state");
      },
      setEnabled: function (on) {
        return invoke("set_rpc_enabled", { on: !!on });
      },
      setRelay: function (on) {
        return invoke("set_rpc_relay", { on: !!on });
      },
      setHidden: function (clientId, hidden) {
        return invoke("set_rpc_hidden", {
          clientId: String(clientId),
          hidden: !!hidden,
        });
      },
      restartDiscord: function () {
        return invoke("restart_discord");
      },
      onState: function (callback) {
        return listen("desktop://rpc-state", callback);
      },
    },

    settings: {
      get: function () {
        return invoke("get_settings");
      },
      set: function (next) {
        return invoke("set_settings", { next: next });
      },
    },

    openOriginal: function (bytes, type, name) {
      var headers = {
        "x-nexa-type": String(type || "application/octet-stream"),
        "x-nexa-name": encodeURIComponent(String(name || "file")),
      };
      return invoke("open_original", bytesOf(bytes), { headers: headers });
    },

    focusWindow: function () {
      return invoke("focus_window");
    },
    restartApp: function () {
      return invoke("restart_app");
    },
  };

  // WebKit has no Web Notifications. Nexus's own notification code still runs,
  // so give it the browser's shape backed by the native notification centre.
  if (typeof window.Notification === "undefined") {
    function DesktopNotification(title, options) {
      options = options || {};
      this.title = title;
      this.body = options.body || "";
      this.tag = options.tag || "";
      this.onclick = null;
      this.close = function () {};
      invoke("notify_app", {
        title: String(title),
        body: String(options.body || ""),
      }).catch(function () {});
    }
    DesktopNotification.permission = "granted";
    DesktopNotification.requestPermission = function () {
      return Promise.resolve("granted");
    };
    window.Notification = DesktopNotification;
  }

  window.evilionDesktop = bridge;

  invoke("get_versions")
    .then(function (versions) {
      if (!versions) return;
      bridge.version = String(versions.app || bridge.version);
      bridge.versions = {
        electron: null,
        chrome: versions.webview || null,
        node: null,
      };
    })
    .catch(function () {});
})();
