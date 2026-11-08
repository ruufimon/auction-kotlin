import type { TokenSet } from "./oidc";

/**
 * Keycloak refresh tokens are single-use (rotation with reuse detection). When a page fires
 * several API calls with the same expired session, only one refresh may reach the provider;
 * the others must receive the same new tokens. Results are kept briefly so requests that
 * still carry the old cookie after the rotation reuse them instead of replaying the old token.
 */
export function singleFlightRefresher(refresh: (refreshToken: string) => Promise<TokenSet>, retainMs = 30_000, clock: () => number = Date.now) {
  const inFlight = new Map<string, { promise: Promise<TokenSet>; settledAt?: number }>();

  function sweep(now: number) {
    for (const [key, entry] of inFlight) {
      if (entry.settledAt !== undefined && now - entry.settledAt > retainMs) inFlight.delete(key);
    }
  }

  return function refreshOnce(refreshToken: string): Promise<TokenSet> {
    sweep(clock());
    const existing = inFlight.get(refreshToken);
    if (existing) return existing.promise;
    const entry: { promise: Promise<TokenSet>; settledAt?: number } = { promise: Promise.resolve(null as never) };
    entry.promise = refresh(refreshToken).then(
      (tokens) => { entry.settledAt = clock(); return tokens; },
      (error) => { inFlight.delete(refreshToken); throw error; },
    );
    inFlight.set(refreshToken, entry);
    return entry.promise;
  };
}
