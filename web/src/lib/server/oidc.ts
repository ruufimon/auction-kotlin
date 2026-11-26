import { createHash, randomBytes } from "node:crypto";

/** Tokens as kept in the session. Times are epoch milliseconds. */
export interface TokenSet {
  accessToken: string;
  accessExpiresAt: number;
  refreshToken: string;
  refreshExpiresAt: number;
}

export class OidcError extends Error {
  constructor(readonly error: string, readonly status: number, message?: string) {
    super(message ?? error);
  }
}

export interface OidcEndpoints {
  authorization: string;
  registration: string;
  token: string;
  logout: string;
}

/** Keycloak's fixed endpoint layout: browser-facing URLs use the public issuer, back-channel calls the internal one. */
export function endpoints(publicIssuer: string, internalIssuer: string): OidcEndpoints {
  return {
    authorization: `${publicIssuer}/protocol/openid-connect/auth`,
    registration: `${publicIssuer}/protocol/openid-connect/registrations`,
    token: `${internalIssuer}/protocol/openid-connect/token`,
    logout: `${internalIssuer}/protocol/openid-connect/logout`,
  };
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export function pkceChallenge(verifier: string): string {
  return createHash("sha256").update(verifier).digest("base64url");
}

interface TokenResponse {
  access_token: string;
  expires_in: number;
  refresh_token: string;
  refresh_expires_in: number;
  id_token?: string;
}

export function toTokenSet(body: TokenResponse, now: number): TokenSet {
  if (!body.access_token || !body.refresh_token) throw new OidcError("invalid_token_response", 502);
  return {
    accessToken: body.access_token,
    accessExpiresAt: now + body.expires_in * 1000,
    refreshToken: body.refresh_token,
    // 0 means "no idle limit" in Keycloak; cap the cookie at 30 days.
    refreshExpiresAt: now + (body.refresh_expires_in > 0 ? body.refresh_expires_in : 30 * 86400) * 1000,
  };
}

async function postForm(url: string, form: Record<string, string>, fetchImpl: typeof fetch): Promise<TokenResponse> {
  const res = await fetchImpl(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: new URLSearchParams(form),
    cache: "no-store",
  });
  const text = await res.text();
  let json: Record<string, unknown> = {};
  try { json = text ? JSON.parse(text) : {}; } catch { /* non-JSON error page */ }
  if (!res.ok) {
    throw new OidcError(String(json.error ?? "token_endpoint_error"), res.status, String(json.error_description ?? res.statusText));
  }
  return json as unknown as TokenResponse;
}

export async function exchangeCode(
  ep: OidcEndpoints, clientId: string, code: string, redirectUri: string, verifier: string,
  fetchImpl: typeof fetch = fetch, now = Date.now(),
): Promise<{ tokens: TokenSet; idToken?: string }> {
  const body = await postForm(ep.token, {
    grant_type: "authorization_code", client_id: clientId, code, redirect_uri: redirectUri, code_verifier: verifier,
  }, fetchImpl);
  return { tokens: toTokenSet(body, now), idToken: body.id_token };
}

export async function refreshTokens(
  ep: OidcEndpoints, clientId: string, refreshToken: string, fetchImpl: typeof fetch = fetch, now = Date.now(),
): Promise<TokenSet> {
  const body = await postForm(ep.token, { grant_type: "refresh_token", client_id: clientId, refresh_token: refreshToken }, fetchImpl);
  return toTokenSet(body, now);
}

/** Ends the provider session without a browser round trip (Keycloak back-channel logout). Best effort. */
export async function endProviderSession(ep: OidcEndpoints, clientId: string, refreshToken: string, fetchImpl: typeof fetch = fetch): Promise<void> {
  try {
    await fetchImpl(ep.logout, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: clientId, refresh_token: refreshToken }),
      cache: "no-store",
    });
  } catch {
    // The local session is cleared regardless; the provider session idles out.
  }
}

/** Reads JWT claims without verifying: only for display and nonce checks on tokens received over TLS from the token endpoint. */
export function decodeClaims(jwt: string | undefined): Record<string, unknown> {
  if (!jwt) return {};
  try {
    return JSON.parse(Buffer.from(jwt.split(".")[1] ?? "", "base64url").toString("utf8"));
  } catch {
    return {};
  }
}
