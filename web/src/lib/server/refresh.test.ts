import { describe, expect, it, vi } from "vitest";
import { singleFlightRefresher } from "./refresh";
import type { TokenSet } from "./oidc";

const tokens = (n: number): TokenSet => ({ accessToken: `at${n}`, accessExpiresAt: n, refreshToken: `rt${n}`, refreshExpiresAt: n });

describe("single-flight refresh", () => {
  it("sends concurrent refreshes of the same token to the provider once", async () => {
    let release!: (t: TokenSet) => void;
    const provider = vi.fn(() => new Promise<TokenSet>((r) => { release = r; }));
    const refresh = singleFlightRefresher(provider);
    const calls = [refresh("rt0"), refresh("rt0"), refresh("rt0")];
    release(tokens(1));
    expect(await Promise.all(calls)).toEqual([tokens(1), tokens(1), tokens(1)]);
    expect(provider).toHaveBeenCalledTimes(1);
  });

  it("reuses the result for late requests that still carry the rotated token", async () => {
    let now = 0;
    const provider = vi.fn(async () => tokens(1));
    const refresh = singleFlightRefresher(provider, 30_000, () => now);
    await refresh("rt0");
    now = 10_000;
    expect(await refresh("rt0")).toEqual(tokens(1));
    expect(provider).toHaveBeenCalledTimes(1);
  });

  it("forgets results after the retention window", async () => {
    let now = 0;
    const provider = vi.fn(async () => tokens(1));
    const refresh = singleFlightRefresher(provider, 30_000, () => now);
    await refresh("rt0");
    now = 31_000;
    await refresh("rt0");
    expect(provider).toHaveBeenCalledTimes(2);
  });

  it("does not cache failures", async () => {
    const provider = vi.fn().mockRejectedValueOnce(new Error("invalid_grant")).mockResolvedValueOnce(tokens(2));
    const refresh = singleFlightRefresher(provider);
    await expect(refresh("rt0")).rejects.toThrow("invalid_grant");
    expect(await refresh("rt0")).toEqual(tokens(2));
  });

  it("refreshes different tokens independently", async () => {
    const provider = vi.fn(async (rt: string) => tokens(rt === "a" ? 1 : 2));
    const refresh = singleFlightRefresher(provider);
    expect(await Promise.all([refresh("a"), refresh("b")])).toEqual([tokens(1), tokens(2)]);
    expect(provider).toHaveBeenCalledTimes(2);
  });
});
