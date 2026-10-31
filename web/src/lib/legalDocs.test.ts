import { beforeAll, describe, expect, it } from "vitest";
import { LEGAL_EFFECTIVE, LEGAL_VERSION } from "./legal";
import { headingCase, loadLegalDocs, parseLegal, type LegalDoc } from "./legalDocs";

describe("legal documents", () => {
  let terms: LegalDoc;
  let privacy: LegalDoc;
  beforeAll(async () => {
    ({ terms, privacy } = await loadLegalDocs());
  });

  it("are the version the app asks people to agree to", () => {
    // bumping the wording without LEGAL_VERSION would never ask anyone again
    expect(terms.effective).toBe(LEGAL_EFFECTIVE);
    expect(privacy.effective).toBe(LEGAL_EFFECTIVE);
    expect(LEGAL_VERSION.startsWith(LEGAL_EFFECTIVE)).toBe(true);
  });

  it("parse into their titles and every numbered section, in order", () => {
    expect(terms.title).toBe("Terms of service");
    expect(privacy.title).toBe("Privacy policy");
    for (const doc of [terms, privacy]) {
      expect(doc.sections.length).toBeGreaterThan(5);
      doc.sections.forEach((sec, i) => {
        expect(sec.number).toBe(String(i + 1));
        expect(sec.blocks.length, sec.title).toBeGreaterThan(0);
      });
      expect(new Set(doc.sections.map((s) => s.id)).size).toBe(doc.sections.length);
    }
  });

  it("keeps the rules as a numbered list and the lettered items with their sub-points", () => {
    const rules = terms.sections.find((s) => s.title.startsWith("The rules"))!;
    const list = rules.blocks.find((b) => b.kind === "ol");
    expect(list && list.kind === "ol" && list.items.length).toBe(6);
    const seen = privacy.sections.find((s) => s.title.startsWith("What the owner and staff can see"))!;
    const lettered = seen.blocks.filter((b) => b.kind === "item");
    expect(lettered.map((b) => (b.kind === "item" ? b.label : ""))).toEqual(["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"]);
    const moderation = lettered.find((b) => b.kind === "item" && b.label === "g");
    expect(moderation?.kind === "item" && moderation.lead).toBe("Moderation records (not end-to-end encrypted, kept as the record of how Nexa is moderated)");
    expect(moderation?.kind === "item" && moderation.sub.length).toBeGreaterThanOrEqual(5);
  });

  it("says what moderation threads are, in both documents", () => {
    const all = JSON.stringify([terms, privacy]);
    expect(all).toContain("not end-to-end encrypted");
    expect(all).toContain("append-only");
  });

  it("joins wrapped lines and reads the small format", () => {
    const doc = parseLegal(
      ["NEXA - EXAMPLE", "Effective: 2026-01-01", "", "Intro line one", "and two.", "", "1. FIRST PART", "", "- one", "  continued", "- two", "", "  Email: x@y.z", "", "END OF EXAMPLE", "ignored"].join("\n"),
      "ex",
    );
    expect(doc.intro).toEqual([{ kind: "p", text: "Intro line one and two." }]);
    expect(doc.sections).toEqual([{ id: "ex-1", number: "1", title: "First part", blocks: [{ kind: "ul", items: ["one continued", "two"] }, { kind: "lines", lines: ["Email: x@y.z"] }] }]);
    expect(headingCase("WHAT IS ENCRYPTED (NOBODY WHO RUNS NEXA CAN READ IT)")).toBe("What is encrypted (nobody who runs Nexa can read it)");
  });
});
