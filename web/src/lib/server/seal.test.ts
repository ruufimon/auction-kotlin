import { describe, expect, it } from "vitest";
import { sealer } from "./seal";

describe("cookie sealing", () => {
  const s = sealer("a-test-secret-that-is-long-enough-123456");

  it("round-trips values", () => {
    expect(s.open(s.seal({ t: "token", e: 42 }))).toEqual({ t: "token", e: 42 });
  });

  it("produces different ciphertext each time", () => {
    expect(s.seal("x")).not.toBe(s.seal("x"));
  });

  it("opens tampered, truncated or foreign values to null", () => {
    const sealed = s.seal({ t: "token" });
    const flipped = sealed.slice(0, -2) + (sealed.endsWith("A") ? "BB" : "AA");
    expect(s.open(flipped)).toBeNull();
    expect(s.open(sealed.slice(0, 10))).toBeNull();
    expect(sealer("another-secret-that-is-long-enough-12345").open(sealed)).toBeNull();
    expect(s.open(undefined)).toBeNull();
  });
});
