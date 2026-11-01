import { describe, expect, it } from "vitest";
import type { PublicUser } from "@/api/types";
import { extractMentions, humanizeMentions, type MentionScope } from "./mentions";

const user = (id: string, username: string, display_name: string) => ({ id, username, display_name }) as PublicUser;
const users = { "42": user("42", "alex", "Alex"), "7": user("7", "alice", "Alice"), "8": user("8", "bob", "Bobby Tables") };

describe("extractMentions", () => {
  it("turns a known @name into a mention", () => {
    expect(extractMentions("hi @alex!", users)).toEqual({ text: "hi <@42>!", mentions: ["42"], roles: [], everyone: false });
  });

  it("leaves @names inside links and code alone", () => {
    const text = "watch https://youtube.com/@alex and <https://x.com/@alex> or `@alex`";
    expect(extractMentions(text, users)).toEqual({ text, mentions: [], roles: [], everyone: false });
  });

  it("still finds a mention right after a link", () => {
    expect(extractMentions("https://example.com/a @alex", users).text).toBe("https://example.com/a <@42>");
  });

  it("takes a username typed with capitals, and one that ends a sentence", () => {
    expect(extractMentions("@Alice, then @alex.", users).text).toBe("<@7>, then <@42>.");
  });

  it("takes the display name of someone in the conversation, the longest that fits", () => {
    const scope: MentionScope = { members: [{ id: "8", names: ["Bobby Tables"] }, { id: "7", names: ["Alice"] }] };
    const out = extractMentions("@bobby tables and @Alice: look", users, scope);
    expect(out.text).toBe("<@8> and <@7>: look");
    expect(out.mentions.sort()).toEqual(["7", "8"]);
  });

  it("leaves a name two people share, part of a longer word, or an escaped one alone", () => {
    const scope: MentionScope = { members: [{ id: "1", names: ["Sam"] }, { id: "2", names: ["sam"] }, { id: "8", names: ["Bobby Tables"] }] };
    expect(extractMentions("@Sam @Bobby Tablesmith \\@alex bob@alex.com", users, scope).text).toBe("@Sam @Bobby Tablesmith \\@alex bob@alex.com");
  });

  it("mentions roles by name, preferring the one typed with the same capitals", () => {
    const scope: MentionScope = { roles: [{ id: "900", name: "Moderator" }, { id: "901", name: "Design Team" }] };
    const out = extractMentions("@moderator and @Design Team, and `@Moderator`", users, scope);
    expect(out.text).toBe("<@&900> and <@&901>, and `@Moderator`");
    expect(out.roles.sort()).toEqual(["900", "901"]);
    expect(out.mentions).toEqual([]);
  });

  it("gives a username priority over a role spelt the same way, unless the role's capitals were typed", () => {
    const scope: MentionScope = { roles: [{ id: "900", name: "Alex" }] };
    expect(extractMentions("@alex", users, scope).text).toBe("<@42>");
    expect(extractMentions("@Alex", users, scope).text).toBe("<@&900>");
  });

  it("counts mentions and roles written out in full, but not in code", () => {
    const out = extractMentions("<@42> <@&900> `<@7>`", users);
    expect(out.mentions).toEqual(["42"]);
    expect(out.roles).toEqual(["900"]);
  });

  it("notes @everyone and @here", () => {
    expect(extractMentions("@here look", users).everyone).toBe(true);
    expect(extractMentions("@everyones", users).everyone).toBe(false);
  });

  it("links #channel names of the server, whole names only", () => {
    const scope: MentionScope = { channels: [{ id: "30", name: "dev" }, { id: "31", name: "dev-chat" }] };
    expect(extractMentions("see #dev-chat and #dev, not #devs or # dev or `#dev`", users, scope).text).toBe("see <#31> and <#30>, not #devs or # dev or `#dev`");
  });

  it("uses this server's own emoji for its name, before the unicode one", () => {
    const scope: MentionScope = { emoji: [{ name: "party", token: "<:party:5>" }], isShortcode: () => true };
    expect(extractMentions("yay :party: `:party:`", users, scope).text).toBe("yay <:party:5> `:party:`");
  });

  it("borrows another server's emoji only for a name no unicode emoji has", () => {
    const scope: MentionScope = {
      emoji: [
        { name: "fire", token: "<:fire:6>", fallback: true },
        { name: "blurple", token: "<:blurple:7>", fallback: true },
      ],
      isShortcode: (name) => name === "fire",
    };
    expect(extractMentions(":fire: :blurple: :nope:", users, scope).text).toBe(":fire: <:blurple:7> :nope:");
  });

  it("leaves emoji tokens already written alone", () => {
    const scope: MentionScope = { emoji: [{ name: "party", token: "<:party:5>" }] };
    expect(extractMentions("<:party:9> <a:party:9>", users, scope).text).toBe("<:party:9> <a:party:9>");
  });
});

describe("humanizeMentions", () => {
  it("gives mentions, roles and channels their names back, and leaves the rest", () => {
    const text = "<@42> <@&900> <#30> <@&1> `<@42>`";
    expect(humanizeMentions(text, users, { roles: { "900": "Moderator" }, channels: { "30": "general" } })).toBe("@alex @Moderator #general <@&1> `<@42>`");
  });

  it("gives a custom emoji its :name: back only where the caller allows it", () => {
    expect(humanizeMentions("<:party:5> <a:other:6>", users, { emoji: (token) => token === "<:party:5>" })).toBe(":party: <a:other:6>");
  });
});
