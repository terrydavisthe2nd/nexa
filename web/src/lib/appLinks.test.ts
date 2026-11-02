import { afterEach, describe, expect, it } from "vitest";
import { appLink, inviteLinksIn, isNoteLink, jumpTarget, messageTarget, noteLinksIn, packLinksIn, profileLinksIn, setPublicUrl, shareOrigin } from "./appLinks";
import { parse } from "@/markdown/parse";

const here = { href: "https://chat.evilion.xyz/channels/1/2", host: "chat.evilion.xyz" };
const elsewhere = { href: "http://127.0.0.1:4000/channels/@me", host: "127.0.0.1:4000" };

afterEach(() => setPublicUrl(null));

describe("appLink", () => {
  it("opens invites in the app, keeping the key in the fragment", () => {
    expect(appLink("https://chat.evilion.xyz/invite/AbC-12_x", here)).toEqual({ kind: "invite", code: "AbC-12_x", hash: "" });
    expect(appLink("https://chat.evilion.xyz/invite/AbC#k=3.abcdef", here)).toEqual({ kind: "invite", code: "AbC", hash: "#k=3.abcdef" });
    expect(appLink("/invite/xyz/", here)).toEqual({ kind: "invite", code: "xyz", hash: "" });
  });

  it("opens a note's share link in the app, keeping its key in the fragment", () => {
    const token = "dG9rZW4tZm9yLWEtbm90ZQ";
    const key = "k".repeat(43);
    expect(appLink(`https://chat.evilion.xyz/note/${token}#${key}`, here)).toEqual({ kind: "note", token, hash: `#${key}` });
    expect(appLink(`/note/${token}/`, here)).toEqual({ kind: "note", token, hash: "" });
    expect(isNoteLink(`https://chat.evilion.xyz/note/${token}#${key}`, here)).toBe(true);
    // a token too short to be one, and another site's page of the same name, are not
    expect(appLink("https://chat.evilion.xyz/note/short", here)).toEqual({ kind: "route", path: "/note/short" });
    expect(isNoteLink(`https://elsewhere.example/note/${token}`, here)).toBe(false);
    expect(isNoteLink("not a link", here)).toBe(false);
  });

  it("routes channels, messages, sticker packs, the download page and unknown pages", () => {
    expect(appLink("https://chat.evilion.xyz/channels/5/6/7", here)).toEqual({ kind: "route", path: "/channels/5/6/7" });
    expect(appLink("https://chat.evilion.xyz/channels/@me/9", here)).toEqual({ kind: "route", path: "/channels/@me/9" });
    expect(appLink("https://chat.evilion.xyz/stickers/pack/42", here)).toEqual({ kind: "route", path: "/stickers/pack/42" });
    expect(appLink("https://chat.evilion.xyz/download", here)).toEqual({ kind: "route", path: "/download" });
    expect(appLink("https://chat.evilion.xyz/somewhere/new?x=1#y", here)).toEqual({ kind: "route", path: "/somewhere/new?x=1#y" });
    expect(appLink("https://chat.evilion.xyz", here)).toEqual({ kind: "route", path: "/" });
  });

  it("opens a profile link as the profile, whatever case it was typed in, dots and all", () => {
    expect(appLink("https://chat.evilion.xyz/@maya", here)).toEqual({ kind: "profile", username: "maya" });
    expect(appLink("https://chat.evilion.xyz/@Maya.Dev/", here)).toEqual({ kind: "profile", username: "maya.dev" });
    expect(appLink("/@night_owl", here)).toEqual({ kind: "profile", username: "night_owl" });
    // too short to be a username, more than one part, and another site's are not
    expect(appLink("https://chat.evilion.xyz/@m", here)).toEqual({ kind: "route", path: "/@m" });
    expect(appLink("https://chat.evilion.xyz/@maya/posts", here)).toEqual({ kind: "route", path: "/@maya/posts" });
    expect(appLink("https://mastodon.example/@maya", here)).toBeNull();
  });

  it("makes links to share at Nexa's public address once it is known", () => {
    setPublicUrl("https://nexa.example/");
    expect(shareOrigin()).toBe("https://nexa.example");
  });

  it("counts plain http and any letter case as the same site", () => {
    expect(appLink("http://CHAT.evilion.xyz/invite/abc", here)).toEqual({ kind: "invite", code: "abc", hash: "" });
  });

  it("counts Nexa's public address from a page served under another name", () => {
    expect(appLink("https://chat.evilion.xyz/invite/abc", elsewhere)).toBeNull();
    setPublicUrl("https://chat.evilion.xyz");
    expect(appLink("https://chat.evilion.xyz/invite/abc", elsewhere)).toEqual({ kind: "invite", code: "abc", hash: "" });
    expect(appLink("http://127.0.0.1:4000/channels/1", elsewhere)).toEqual({ kind: "route", path: "/channels/1" });
    setPublicUrl("not a url");
    expect(appLink("https://chat.evilion.xyz/invite/abc", elsewhere)).toBeNull();
  });

  it("leaves other sites, other ports and other schemes alone", () => {
    expect(appLink("https://evilion.xyz/invite/abc", here)).toBeNull();
    expect(appLink("https://chat.evilion.xyz.example.com/invite/abc", here)).toBeNull();
    expect(appLink("https://chat.evilion.xyz:8443/invite/abc", here)).toBeNull();
    expect(appLink("mailto:someone@chat.evilion.xyz", here)).toBeNull();
    expect(appLink("javascript:alert(1)", here)).toBeNull();
    expect(appLink("http://[::1", here)).toBeNull();
  });

  it("leaves the server's own files and guest call links to the browser", () => {
    for (const p of ["/api/v1/users/@me", "/media/attachments/1", "/media/icon/2?size=64", "/assets/index-abc.js", "/twemoji/1f600.svg", "/sw.js", "/manifest.webmanifest", "/icons/icon-192.png", "/home/app.png", "/__download/x", "/call/AbCdEf"]) {
      expect(appLink("https://chat.evilion.xyz" + p, here)).toBeNull();
    }
  });

  it("leaves a jump within the same page to the browser", () => {
    expect(appLink("#fn-1", here)).toBeNull();
    expect(appLink("https://chat.evilion.xyz/channels/1/2#top", here)).toBeNull();
    // the same page without a fragment is still the app's, so a message link can jump again
    expect(appLink("https://chat.evilion.xyz/channels/1/2", here)).toEqual({ kind: "route", path: "/channels/1/2" });
  });
});

describe("messageTarget", () => {
  it("reads the channel and message out of a message link", () => {
    expect(messageTarget("/channels/5/6/7")).toEqual({ channelId: "6", messageId: "7" });
    expect(messageTarget("/channels/@me/6/7?x#y")).toEqual({ channelId: "6", messageId: "7" });
    expect(messageTarget("/channels/5/6")).toBeNull();
    expect(messageTarget("/invite/abc")).toBeNull();
  });
});

describe("inviteLinksIn", () => {
  it("finds each invite to Nexa once, in order, with its key", () => {
    const text = [
      "join us https://chat.evilion.xyz/invite/one#k=0.aaaa",
      "and [the other one](https://chat.evilion.xyz/invite/two)",
      "again https://chat.evilion.xyz/invite/one",
      "not this <https://chat.evilion.xyz/invite/quiet>",
      "nor `https://chat.evilion.xyz/invite/code` nor https://other.example/invite/x",
      "```\nhttps://chat.evilion.xyz/invite/block\n```",
    ].join("\n");
    expect(inviteLinksIn(parse(text), here)).toEqual([
      { code: "one", hash: "#k=0.aaaa", url: "https://chat.evilion.xyz/invite/one#k=0.aaaa" },
      { code: "two", hash: "", url: "https://chat.evilion.xyz/invite/two" },
    ]);
  });

  it("finds invites nested anywhere in the message and stops at three", () => {
    const links = Array.from({ length: 5 }, (_, i) => ({ type: "link", url: `https://chat.evilion.xyz/invite/c${i}`, children: [], masked: false, noEmbed: false }));
    const tree = [{ type: "table", head: [[links[0]]], rows: [[[links[1]]], [[links[2], links[3]]]] }, { type: "details", summary: [], children: [links[4]] }];
    expect(inviteLinksIn(tree, here).map((l) => l.code)).toEqual(["c0", "c1", "c2"]);
    expect(inviteLinksIn(null, here)).toEqual([]);
  });
});

describe("noteLinksIn", () => {
  const token = (n: number) => `dG9rZW4tZm9yLWEtbm90ZQ${n}`;
  const key = "k".repeat(43);
  it("finds each note link once, in order, with its key, and none in code or written as <url>", () => {
    const text = [
      `a note https://chat.evilion.xyz/note/${token(1)}#${key}`,
      `[another](https://chat.evilion.xyz/note/${token(2)}#${key})`,
      `again https://chat.evilion.xyz/note/${token(1)}#${key}`,
      `quiet <https://chat.evilion.xyz/note/${token(3)}#${key}>`,
      `\`https://chat.evilion.xyz/note/${token(4)}#${key}\``,
      "and an invite https://chat.evilion.xyz/invite/abc",
    ].join("\n");
    expect(noteLinksIn(parse(text), here)).toEqual([
      { token: token(1), hash: `#${key}`, url: `https://chat.evilion.xyz/note/${token(1)}#${key}` },
      { token: token(2), hash: `#${key}`, url: `https://chat.evilion.xyz/note/${token(2)}#${key}` },
    ]);
    // and an invite list does not pick notes up
    expect(inviteLinksIn(parse(text), here).map((l) => l.code)).toEqual(["abc"]);
  });

  it("stops at three", () => {
    const text = [1, 2, 3, 4, 5].map((n) => `https://chat.evilion.xyz/note/${token(n)}#${key}`).join(" ");
    expect(noteLinksIn(parse(text), here).map((l) => l.token)).toEqual([token(1), token(2), token(3)]);
  });
});

describe("profileLinksIn", () => {
  it("finds each profile link once, in order, and none in code or written as <url>", () => {
    const nodes = parse("meet https://chat.evilion.xyz/@maya and https://chat.evilion.xyz/@MAYA, `https://chat.evilion.xyz/@kai` <https://chat.evilion.xyz/@rin> https://chat.evilion.xyz/@theo");
    expect(profileLinksIn(nodes, here).map((l) => l.username)).toEqual(["maya", "theo"]);
  });

  it("stops at three", () => {
    const nodes = parse(["a1", "b2", "c3", "d4"].map((n) => `https://chat.evilion.xyz/@user${n}`).join(" "));
    expect(profileLinksIn(nodes, here)).toHaveLength(3);
  });
});

describe("packLinksIn", () => {
  it("finds each sticker or emoji pack link once, in order, and none in code, written as <url> or on another site", () => {
    const nodes = parse("look https://chat.evilion.xyz/stickers/pack/123 and /stickers/pack/123/ `https://chat.evilion.xyz/stickers/pack/5` <https://chat.evilion.xyz/stickers/pack/6> https://elsewhere.example/stickers/pack/7 https://chat.evilion.xyz/stickers/pack/456");
    expect(packLinksIn(nodes, here).map((l) => l.id)).toEqual(["123", "456"]);
  });

  it("stops at three", () => {
    const nodes = parse([1, 2, 3, 4].map((n) => `https://chat.evilion.xyz/stickers/pack/${n}`).join(" "));
    expect(packLinksIn(nodes, here)).toHaveLength(3);
  });
});

describe("jumpTarget", () => {
  it("reads a message link and a channel link", () => {
    expect(jumpTarget("https://chat.evilion.xyz/channels/@me/108900831054528512/110217209371951104", here)).toEqual({ channelId: "108900831054528512", messageId: "110217209371951104" });
    expect(jumpTarget("https://chat.evilion.xyz/channels/5/6", here)).toEqual({ channelId: "6", messageId: null });
  });
  it("leaves other links alone", () => {
    expect(jumpTarget("https://example.com/channels/5/6", here)).toBeNull();
    expect(jumpTarget("https://chat.evilion.xyz/channels/5/6?x=1", here)).toBeNull();
    expect(jumpTarget("https://chat.evilion.xyz/channels/@me", here)).toBeNull();
    expect(jumpTarget("https://chat.evilion.xyz/channels/5", here)).toBeNull();
  });
});
