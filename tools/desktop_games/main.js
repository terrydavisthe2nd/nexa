// Runs the desktop app's built main process muted, with no tray icon and no
// system-wide keys, for tools/desktop_games.py to drive through its debugging port.
const Module = require("node:module");
const real = require("electron");
real.app.commandLine.appendSwitch("mute-audio");
class Win extends real.BrowserWindow {
  constructor(o = {}) {
    super(o);
    this.webContents.setAudioMuted(true);
  }
}
class FakeTray { setToolTip() {} setContextMenu() {} on() { return this; } setImage() {} destroy() {} setTitle() {} setIgnoreDoubleClickEvents() {} }
const shortcuts = { register: () => true, unregister() {}, unregisterAll() {}, isRegistered: () => false };
const proxy = new Proxy(real, { get: (t, k) => (k === "BrowserWindow" ? Win : k === "Tray" ? FakeTray : k === "globalShortcut" ? shortcuts : t[k]) });
const load = Module._load;
Module._load = function (req, ...rest) { return req === "electron" ? proxy : load.call(this, req, ...rest); };
require(process.env.APP_MAIN);
