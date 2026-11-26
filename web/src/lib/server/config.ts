import "server-only";

/**
 * Server-side settings. The browser never sees tokens or these values: it talks to this
 * Next.js server, which holds the session in encrypted httpOnly cookies (backend for frontend).
 */
export interface ServerConfig {
  /** Public origin of this web app, used for redirect URIs and cookie security. */
  appOrigin: string;
  /** Keycloak realm issuer as it appears in tokens and in browser redirects. */
  oidcIssuer: string;
  /** Base URL this server uses to reach the realm; differs from the issuer inside Docker. */
  oidcInternalIssuer: string;
  oidcClientId: string;
  /** Gavel API origin, reached server-to-server. */
  apiOrigin: string;
  /** 32+ characters; derives the cookie encryption key. */
  sessionSecret: string;
  secureCookies: boolean;
}

const DEV_SESSION_SECRET = "dev-only-session-secret-change-me-0123456789";

export function loadConfig(env: Record<string, string | undefined> = process.env): ServerConfig {
  const production = env.NODE_ENV === "production" && env.GAVEL_ALLOW_DEV_DEFAULTS !== "true";
  const value = (key: string, fallback: string) => {
    const v = env[key]?.trim();
    if (v) return v.replace(/\/+$/, "");
    if (production) throw new Error(`${key} is required in production`);
    return fallback;
  };
  const appOrigin = value("APP_ORIGIN", "http://localhost:3000");
  const oidcIssuer = value("OIDC_ISSUER", "http://localhost:8180/realms/gavel");
  const sessionSecret = value("SESSION_SECRET", DEV_SESSION_SECRET);
  if (sessionSecret.length < 32) throw new Error("SESSION_SECRET must be at least 32 characters");
  if (production && sessionSecret === DEV_SESSION_SECRET) throw new Error("SESSION_SECRET must not be the development default");
  return {
    appOrigin,
    oidcIssuer,
    oidcInternalIssuer: env.OIDC_INTERNAL_ISSUER?.trim().replace(/\/+$/, "") || oidcIssuer,
    oidcClientId: env.OIDC_CLIENT_ID?.trim() || "gavel-web",
    apiOrigin: value("API_ORIGIN", "http://localhost:8080"),
    sessionSecret,
    secureCookies: appOrigin.startsWith("https://"),
  };
}

let cached: ServerConfig | undefined;
export function config(): ServerConfig {
  return (cached ??= loadConfig());
}
