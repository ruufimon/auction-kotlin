import { describe, expect, it } from "vitest";
import { formatMinor, minorDigits, parseWholeUnits } from "./money";

describe("money", () => {
  it("knows minor digits per currency", () => {
    expect(minorDigits("USD")).toBe(2);
    expect(minorDigits("JPY")).toBe(0);
    expect(minorDigits("BHD")).toBe(3);
  });

  it("parses whole units to integer minor units", () => {
    expect(parseWholeUnits("60,000", "USD")).toEqual({ ok: true, minor: 6_000_000 });
    expect(parseWholeUnits(" 0 ", "USD")).toEqual({ ok: true, minor: 0 });
    expect(parseWholeUnits("1500", "JPY")).toEqual({ ok: true, minor: 1500 });
  });

  it("refuses decimals, negatives, blanks and unsafe sizes", () => {
    expect(parseWholeUnits("10.5", "USD")).toEqual({ ok: false, code: "INVALID" });
    expect(parseWholeUnits("-1", "USD")).toEqual({ ok: false, code: "NEGATIVE" });
    expect(parseWholeUnits("", "USD")).toEqual({ ok: false, code: "REQUIRED" });
    expect(parseWholeUnits("1e5", "USD")).toEqual({ ok: false, code: "INVALID" });
    expect(parseWholeUnits("900719925474100", "USD")).toEqual({ ok: false, code: "TOO_LARGE" });
  });

  it("formats minor units exactly, without floating point", () => {
    expect(formatMinor(123456, "USD", "en-US")).toBe("$1,234.56");
    expect(formatMinor(5, "USD", "en-US")).toBe("$0.05");
    expect(formatMinor(-250, "USD", "en-US")).toBe("-$2.50");
    expect(formatMinor(1500, "JPY", "en-US")).toBe("¥1,500");
    // 2^53 + 1 would lose precision as a float.
    expect(formatMinor(9007199254740993n, "USD", "en-US")).toBe("$90,071,992,547,409.93");
  });

  it("rejects unsafe number inputs", () => {
    expect(() => formatMinor(0.5, "USD")).toThrow(RangeError);
  });
});
