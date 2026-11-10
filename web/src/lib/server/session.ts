import "server-only";
import type { NextRequest, NextResponse } from "next/server";
import { config } from "./config";
import { sealer } from "./seal";
import { decodeClaims, endpoints, OidcError, refreshTokens, type TokenSet } from "./oidc";
import { singleFlightRefresher } from "./refresh";

/** Cookie names. Two cookies keep each value well under the 4 KB browser limit. */
export const ACCESS_COOKIE = "gavel_at";
export const REFRESH_COOKIE = "gavel_rt";
export const LOGIN_COOKIE = "gavel_login";

/** Refresh this long before the access token expires, so a request never carries an expiring token. */
const EXPIRY_SKEW_MS = 20_000;

interface AccessCookie { t: string; e: number }
interface RefreshCookie { t: string; e: number }

export interface LoginState { state: string; verifier: string; nonce: string; returnTo: string }

const seal = () => sealer(config().sessionSecret);
export const oidc = () => { const c = config(); return endpoints(c.oidcIssuer, c.oidcInternalIssuer); };

type CookieReader = { get(name: string): { value: string } | undefined };

export function readTokens(cookies: CookieReader): Partial<TokenSet> | null {
  const s = seal();
  const refresh = s.open<RefreshCookie>(cookies.get(REFRESH_COOKIE)?.value);
  if (!refresh) return null;
  const access = s.open<AccessCookie>(cookies.get(ACCESS_COOKIE)?.value);
  return {
    refreshToken: refresh.t,
    refreshExpiresAt: refresh.e,
    accessToken: access?.t,
    accessExpiresAt: access?.e,
  };
}

function cookieOptions(maxAgeSeconds: number, path = "/") {
  return { httpOnly: true, secure: config().secureCookies, sameSite: "lax" as const, path, maxAge: Math.max(0, Math.floor(maxAgeSeconds)) };
}

export function writeTokens(res: NextResponse, tokens: TokenSet, now = Date.now()) {
  const s = seal();
  const ttl = (tokens.refreshExpiresAt - now) / 1000;
  res.cookies.set(REFRESH_COOKIE, s.seal({ t: tokens.refreshToken, e: tokens.refreshExpiresAt } satisfies RefreshCookie), cookieOptions(ttl));
  res.cookies.set(ACCESS_COOKIE, s.seal({ t: tokens.accessToken, e: tokens.accessExpiresAt } satisfies AccessCookie), cookieOptions(ttl));
}

export function clearSession(res: NextResponse) {
  res.cookies.set(REFRESH_COOKIE, "", cookieOptions(0));
  res.cookies.set(ACCESS_COOKIE, "", cookieOptions(0));
}

export function writeLoginState(res: NextResponse, state: LoginState) {
  res.cookies.set(LOGIN_COOKIE, seal().seal(state), cookieOptions(600, "/auth"));
}

export function readLoginState(req: NextRequest): LoginState | null {
  return seal().open<LoginState>(req.cookies.get(LOGIN_COOKIE)?.value);
}

export function clearLoginState(res: NextResponse) {
  res.cookies.set(LOGIN_COOKIE, "", cookieOptions(0, "/auth"));
}

const refreshOnce = singleFlightRefresher((rt) => refreshTokens(oidc(), config().oidcClientId, rt));

export type SessionResult =
  | { kind: "valid"; tokens: TokenSet; refreshed: boolean }
  | { kind: "none" }
  | { kind: "expired"; reason: string };

/**
 * Returns a usable access token, refreshing when it is missing or about to expire.
 * `force` refreshes even if the token looks valid (after the API answered TOKEN_EXPIRED).
 */
export async function ensureSession(cookies: CookieReader, force = false, now = Date.now()): Promise<SessionResult> {
  const current = readTokens(cookies);
  if (!current?.refreshToken) return { kind: "none" };
  const usable = current.accessToken && current.accessExpiresAt && current.accessExpiresAt - EXPIRY_SKEW_MS > now;
  if (usable && !force) return { kind: "valid", tokens: current as TokenSet, refreshed: false };
  if (current.refreshExpiresAt && current.refreshExpiresAt <= now) return { kind: "expired", reason: "SESSION_EXPIRED" };
  try {
    return { kind: "valid", tokens: await refreshOnce(current.refreshToken), refreshed: true };
  } catch (error) {
    // invalid_grant covers an expired session, a disabled user and reuse of a rotated refresh token.
    const reason = error instanceof OidcError && error.error === "invalid_grant" ? "SESSION_EXPIRED" : "IDENTITY_PROVIDER_UNAVAILABLE";
    return { kind: "expired", reason };
  }
}

export interface SessionUser { name?: string; email?: string }

/** Display-only identity for the header; the API remains the authority on the account. */
export function sessionUser(cookies: CookieReader): SessionUser | null {
  const tokens = readTokens(cookies);
  if (!tokens) return null;
  const claims = decodeClaims(tokens.accessToken);
  return { name: (claims.name ?? claims.given_name) as string | undefined, email: claims.email as string | undefined };
}

/** Only same-site relative paths are allowed as post-login destinations. */
export function safeReturnTo(value: string | null | undefined, fallback = "/welcome"): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\") || value.startsWith("/auth/")) return fallback;
  return value;
}
