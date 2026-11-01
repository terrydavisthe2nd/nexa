// A Nexa plugin of small, useful tools, built on the parts of version 2 that
// work with the message box, the pages of the app and pinned messages.
// See docs/plugins.md.
//
// To try it: type /when in 2 hours (or /when 15:30) to put a timestamp
// everyone sees in their own time zone into your message box; /snippet saves
// or uses a bit of text you type often; right-click a message for Copy as
// Quote and Jump to Pinned; set keys for Last Channel in Settings, Keybinds.

export default {
  name: "Quick Tools",
  description: "Timestamps, saved snippets, quotes and a way back to the last channel.",
  version: "1.0.0",
  author: "Nexa docs",
  apiVersion: 2,
  permissions: [
    "servers.channels.read",
    "servers.messages.read",
    "directmessages.read",
    "events.read",
    "commands.write",
    "keybinds.write",
    "storage.read",
    "storage.write",
    "composer.write",
    "navigation",
    "ui.write",
  ],

  start(api) {
    // /when: "in 2 hours", "in 30 minutes" or "15:30" becomes <t:...:R>
    api.commands.register("when", "Puts a timestamp in your message", (args) => {
      const at = parseWhen(args.trim());
      if (!at) return api.ui.toast('Try "/when in 2 hours" or "/when 15:30".', { type: "warning" });
      api.composer.insert(`${api.utils.timestamp(at, "F")} (${api.utils.timestamp(at, "R")}) `);
    });

    // /snippet: saved text on this device, picked by name
    api.commands.register("snippet", "Saves or uses a bit of text", async (args) => {
      const snippets = api.storage.get("snippets") ?? {};
      const name = args.trim().toLowerCase();
      if (!name) return api.ui.toast(`Saved: ${Object.keys(snippets).join(", ") || "nothing yet"}`);
      if (snippets[name]) return api.composer.insert(snippets[name]);
      const text = await api.ui.prompt({ title: `New Snippet "${name}"`, label: "Text", placeholder: "What /snippet " + name + " should type", maxLength: 1000 });
      if (!text) return;
      api.storage.set("snippets", { ...snippets, [name]: text });
      api.ui.toast(`Saved. /snippet ${name} types it now.`, { type: "success" });
    });

    // the message menu: quote a message into the box, or open the channel's pins
    api.ui.contextMenu.add("message", {
      id: "quickToolsQuote",
      label: "Copy as Quote",
      icon: "quote",
      after: "replyToMessage",
      visible: (ctx) => !!ctx.message?.content,
      onClick: (ctx) => {
        const quoted = ctx.message.content.split("\n").map((line) => `> ${line}`).join("\n");
        api.composer.insert(`${quoted}\n${api.utils.mention.user(ctx.message.authorId)} `);
      },
    });
    api.ui.contextMenu.add("message", {
      id: "quickToolsJumpToPin",
      label: "Jump to Pinned",
      icon: "pin",
      after: "pinMessage",
      visible: (ctx) => !!ctx.channel,
      onClick: async (ctx) => {
        const pins = await api.messages.listPinned(ctx.channel.id);
        if (!pins.length) return api.ui.toast("Nothing is pinned here.");
        api.navigation.openChannel(ctx.channel.id, pins[0].id);
      },
    });

    // remembers the channel before this one, for the keybind below
    let previous = null;
    let current = api.navigation.current.channelId;
    api.events.on("locationChanged", (where) => {
      if (where.channelId && where.channelId !== current) {
        previous = current;
        current = where.channelId;
      }
    });
    api.keybinds.register("lastChannel", { name: "Last Channel", description: "Goes back to the channel you were in before" }, () => {
      if (previous && api.channels.get(previous)) api.navigation.openChannel(previous);
      else api.ui.toast("No channel to go back to yet.");
    });
  },
};

function parseWhen(text) {
  const rel = /^in (\d+) (minute|hour|day)s?$/i.exec(text);
  if (rel) {
    const unit = { minute: 60e3, hour: 3600e3, day: 86400e3 }[rel[2].toLowerCase()];
    return new Date(Date.now() + Number(rel[1]) * unit);
  }
  const clock = /^(\d{1,2}):(\d{2})$/.exec(text);
  if (clock) {
    const d = new Date();
    d.setHours(Number(clock[1]), Number(clock[2]), 0, 0);
    if (d.getTime() < Date.now()) d.setDate(d.getDate() + 1);
    return d;
  }
  return null;
}
