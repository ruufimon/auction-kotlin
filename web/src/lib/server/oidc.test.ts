import { describe, expect, it, vi } from "vitest";
import { endpoints, exchangeCode, OidcError, pkceChallenge, refreshTokens } from "./oidc";

const ep = endpoints("http://localhost:8180/realms/gavel", "http://keycloak:8080/realms/gavel");

describe("oidc", () => {
  it("uses the public issuer for browser URLs and the internal one for back-channel calls", () => {
    expect(ep.authorization).toBe("http://localhost:8180/realms/gavel/protocol/openid-connect/auth");
    expect(ep.token).toBe("http://keycloak:8080/realms/gavel/protocol/openid-connect/token");
  });

  it("computes the RFC 7636 S256 challenge", () => {
    // Appendix B test vector.
    expect(pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });

  it("exchanges the code with the verifier and computes expiries", async () => {
    const fetchImpl = vi.fn(async (_u: RequestInfo | URL, init?: RequestInit) => {
      const form = new URLSearchParams(String(init!.body));
      expect(form.get("code_verifier")).toBe("verifier");
      expect(form.get("grant_type")).toBe("authorization_code");
      return new Response(JSON.stringify({ access_token: "at", expires_in: 300, refresh_token: "rt", refresh_expires_in: 1800, id_token: "id" }));
    });
    const result = await exchangeCode(ep, "gavel-web", "code", "http://localhost:3000/auth/callback", "verifier", fetchImpl as typeof fetch, 1_000);
    expect(result.tokens).toEqual({ accessToken: "at", accessExpiresAt: 301_000, refreshToken: "rt", refreshExpiresAt: 1_801_000 });
    expect(result.idToken).toBe("id");
  });

  it("surfaces provider refusals as OidcError with the OAuth error code", async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ error: "invalid_grant", error_description: "Token is not active" }), { status: 400 }));
    const error = await refreshTokens(ep, "gavel-web", "old", fetchImpl as typeof fetch).catch((e) => e);
    expect(error).toBeInstanceOf(OidcError);
    expect(error.error).toBe("invalid_grant");
  });
});
