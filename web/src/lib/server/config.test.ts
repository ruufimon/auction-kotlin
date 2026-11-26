import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
const { loadConfig } = await import("./config");

describe("server config", () => {
  it("uses local-stack defaults in development", () => {
    const c = loadConfig({ NODE_ENV: "development" });
    expect(c).toMatchObject({ appOrigin: "http://localhost:3000", oidcIssuer: "http://localhost:8180/realms/gavel", oidcClientId: "gavel-web", secureCookies: false });
    expect(c.oidcInternalIssuer).toBe(c.oidcIssuer);
  });

  it("requires every setting in production and refuses the development secret", () => {
    expect(() => loadConfig({ NODE_ENV: "production" })).toThrow(/APP_ORIGIN is required/);
    const base = { NODE_ENV: "production", APP_ORIGIN: "https://gavel.example", OIDC_ISSUER: "https://id.gavel.example/realms/gavel", API_ORIGIN: "http://api:8080" };
    expect(() => loadConfig(base)).toThrow(/SESSION_SECRET is required/);
    expect(() => loadConfig({ ...base, SESSION_SECRET: "short" })).toThrow(/at least 32/);
    const c = loadConfig({ ...base, SESSION_SECRET: "x".repeat(40), OIDC_INTERNAL_ISSUER: "http://keycloak:8080/realms/gavel/" });
    expect(c.secureCookies).toBe(true);
    expect(c.oidcInternalIssuer).toBe("http://keycloak:8080/realms/gavel");
  });
});
