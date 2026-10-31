import { describe, expect, it } from "vitest";
import { barWidth, formatCallTime, formatDataSize, shareOfAllowance } from "./callUsage";

describe("call usage", () => {
  it("writes sizes in thousands, as the allowance is set", () => {
    expect(formatDataSize(0)).toBe("0 B");
    expect(formatDataSize(999)).toBe("999 B");
    expect(formatDataSize(740_000_000)).toBe("740 MB");
    expect(formatDataSize(2_450_000_000)).toBe("2.5 GB");
    expect(formatDataSize(106_148_826_778)).toBe("106 GB");
    expect(formatDataSize(2_291_977_037_042)).toBe("2,292 GB");
    expect(formatDataSize(1_000_000_000_000)).toBe("1,000 GB");
  });

  it("writes time in calls in hours and minutes", () => {
    expect(formatCallTime(40 * 60)).toMatch(/40\s?min/);
    const both = formatCallTime(2 * 3600 + 5 * 60);
    expect(both).toMatch(/2\s?hr/);
    expect(both).toMatch(/5\s?min/);
    expect(formatCallTime(-5)).toMatch(/0\s?min/);
  });

  it("measures a share of the allowance", () => {
    expect(shareOfAllowance(100_000_000_000, 1_000_000_000_000)).toBe(10);
    expect(shareOfAllowance(5, 0)).toBe(0);
    expect(barWidth(0)).toBe("0%");
    expect(barWidth(0.01)).toBe("0.6%");
    expect(barWidth(250)).toBe("100%");
  });
});
