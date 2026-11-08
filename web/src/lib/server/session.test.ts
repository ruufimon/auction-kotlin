import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
const refreshTokens = vi.fn();
vi.mock("./oidc", async (importOriginal) => ({ ...(await importOriginal<typeof import("./oidc")>()), refreshTokens: (...a: unknown[]) => refreshTokens(...a) }));

const { ACCESS_COOKIE, REFRESH_COOKIE, ensureSession, safeReturnTo } = await import("./session");
const { sealer } = await import("./seal");
const { OidcError } = await import("./oidc");
const { config } = await import("./config");

const NOW = 1_000_000_000_000;
let counter = 0;

function jar(access: { t: string; e: number } | null, refresh: { t: string; e: number } | null) {
  const s = sealer(config().sessionSecret);
  const values: Record<string, string> = {};
  if (access) values[ACCESS_COOKIE] = s.seal(access);
  if (refresh) values[REFRESH_COOKIE] = s.seal(refresh);
  return { get: (name: string) => (values[name] ? { value: values[name] } : undefined) };
}

const fresh = () => ({ accessToken: "new-at", accessExpiresAt: NOW + 300_000, refreshToken: "new-rt", refreshExpiresAt: NOW + 1_800_000 });

describe("ensureSession", () => {
  beforeEach(() => { refreshTokens.mockReset(); counter++; });
  // Distinct refresh tokens per test keep the single-flight cache from leaking between tests.
  const rt = () => ({ t: `rt-${counter}`, e: NOW + 1_000_000 });

  it("reports no session without a refresh cookie", async () => {
    expect(await ensureSession(jar({ t: "at", e: NOW + 60_000 }, null), false, NOW)).toEqual({ kind: "none" });
  });

  it("uses a valid access token without calling the provider", async () => {
    const result = await ensureSession(jar({ t: "at", e: NOW + 60_000 }, rt()), false, NOW);
    expect(result).toMatchObject({ kind: "valid", refreshed: false, tokens: { accessToken: "at" } });
    expect(refreshTokens).not.toHaveBeenCalled();
  });

  it("refreshes an access token that is missing or about to expire", async () => {
    refreshTokens.mockResolvedValue(fresh());
    expect(await ensureSession(jar({ t: "at", e: NOW + 5_000 }, rt()), false, NOW)).toMatchObject({ kind: "valid", refreshed: true, tokens: { accessToken: "new-at" } });
    counter++;
    expect(await ensureSession(jar(null, rt()), false, NOW)).toMatchObject({ kind: "valid", refreshed: true });
  });

  it("refreshes on demand after the API answered TOKEN_EXPIRED", async () => {
    refreshTokens.mockResolvedValue(fresh());
    expect(await ensureSession(jar({ t: "at", e: NOW + 60_000 }, rt()), true, NOW)).toMatchObject({ refreshed: true });
  });

  it("ends the session when the refresh cookie has expired", async () => {
    expect(await ensureSession(jar(null, { t: "rt-old", e: NOW - 1 }), false, NOW)).toEqual({ kind: "expired", reason: "SESSION_EXPIRED" });
    expect(refreshTokens).not.toHaveBeenCalled();
  });

  it("ends the session when the provider refuses the refresh token (reuse, revocation, disabled user)", async () => {
    refreshTokens.mockRejectedValue(new OidcError("invalid_grant", 400, "Token is not active"));
    expect(await ensureSession(jar(null, rt()), false, NOW)).toEqual({ kind: "expired", reason: "SESSION_EXPIRED" });
  });

  it("keeps the session when the provider is unreachable", async () => {
    refreshTokens.mockRejectedValue(new TypeError("fetch failed"));
    expect(await ensureSession(jar(null, rt()), false, NOW)).toEqual({ kind: "expired", reason: "IDENTITY_PROVIDER_UNAVAILABLE" });
  });

  it("ignores tampered cookies", async () => {
    const tampered = { get: (name: string) => (name === REFRESH_COOKIE ? { value: "garbage" } : undefined) };
    expect(await ensureSession(tampered, false, NOW)).toEqual({ kind: "none" });
  });
});

describe("safeReturnTo", () => {
  it("allows same-site paths only", () => {
    expect(safeReturnTo("/profile/seller?x=1")).toBe("/profile/seller?x=1");
    expect(safeReturnTo("https://evil.example")).toBe("/welcome");
    expect(safeReturnTo("//evil.example")).toBe("/welcome");
    expect(safeReturnTo("/\\evil.example")).toBe("/welcome");
    expect(safeReturnTo("/auth/logout")).toBe("/welcome");
    expect(safeReturnTo(null)).toBe("/welcome");
  });
});
