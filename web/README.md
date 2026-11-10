# Gavel web app

Next.js 16 (App Router, TypeScript) front end for onboarding: sign-in through Keycloak,
the eligibility check, profile and account status, and seller setup (milestone M2).

## How sign-in works

The app is a backend for frontend. The browser never sees an access or refresh token.

1. `/auth/login` and `/auth/register` start Authorization Code + PKCE (S256) against the
   `gavel-web` Keycloak client. State, nonce and verifier sit in a sealed, short-lived cookie.
2. `/auth/callback` exchanges the code server to server and stores the tokens in two
   AES-256-GCM sealed, httpOnly, SameSite=Lax cookies (`gavel_at`, `gavel_rt`).
3. The browser calls `/api/v1/*` on this origin. The proxy route attaches the access token,
   refreshes it shortly before expiry or when the API answers `TOKEN_EXPIRED`, and retries once.
   Concurrent refreshes of the same token reach Keycloak once, because refresh tokens are
   single-use and a replay would end the session.
4. When Keycloak refuses a refresh (expired, revoked, reused or disabled), the proxy clears the
   cookies and answers `401 SESSION_EXPIRED`; the client sends the user to sign-in.
5. `/auth/logout` (POST only) ends the Keycloak session over the back channel and clears cookies.

After sign-in, `/welcome` calls `GET /api/v1/me` (which creates the account on first use) and
routes by `accountState`: pending verification to "check your email", pending eligibility to the
assessment, active to the marketplace. Suspended accounts are signed out with an explanation.

## Layout

| Path | What |
|---|---|
| `src/app/auth/*` | Login, register, callback and logout route handlers |
| `src/app/api/v1/[...path]` | Same-origin API proxy with token refresh |
| `src/app/(account)/*` | Pages that need the account: welcome, verify-email, eligibility, profile, seller |
| `src/proxy.ts` | Sends visitors without a session to sign-in |
| `src/lib/api/*` | Typed API client, error-code messages, money helpers (integer minor units) |
| `src/lib/server/*` | Settings, cookie sealing, OIDC calls, single-flight refresh, session |
| `src/components/*` | Shared UI: fields, alerts, status banner, account gate |
| `e2e/*` | Playwright journeys against the real local stack |

The Keycloak pages (sign-in, registration, verify email) use the `gavel` login theme in
`deploy/keycloak/themes/gavel`.

## Settings

Server-side, read at request time. Development has defaults for the local stack; with
`NODE_ENV=production` every value is required and the development secret is refused.

| Variable | Development default | Meaning |
|---|---|---|
| `APP_ORIGIN` | `http://localhost:3000` | Public origin of this app; redirect URI base. `https` turns on Secure cookies |
| `OIDC_ISSUER` | `http://localhost:8180/realms/gavel` | Realm issuer used in browser redirects |
| `OIDC_INTERNAL_ISSUER` | same as `OIDC_ISSUER` | Realm URL for server-to-server calls, e.g. `http://keycloak:8080/realms/gavel` in Docker |
| `OIDC_CLIENT_ID` | `gavel-web` | Keycloak client |
| `API_ORIGIN` | `http://localhost:8080` | Gavel API, reached server to server |
| `SESSION_SECRET` | development value | At least 32 characters; derives the cookie encryption key |
| `NEXT_PUBLIC_CURRENCY` | `USD` | Build time. Currency for assessment amounts |

`GAVEL_ALLOW_DEV_DEFAULTS=true` lets a production build use the development defaults. Use it
only for local runs, never in a deployment.

## Run

```bash
docker compose -f ../docker-compose.dev.yml up -d postgres keycloak mailpit
(cd .. && ./gradlew :app:run)          # API on :8080
npm install
npm run dev                            # http://localhost:3000
```

Port 3000 busy? Serve a production build on 8787, which the realm also allows:

```bash
npm run build
APP_ORIGIN=http://localhost:8787 GAVEL_ALLOW_DEV_DEFAULTS=true npm start
```

## Test

```bash
npm run typecheck
npm test          # Vitest: refresh, session, sealing, OIDC, client, error mapping, money
npm run e2e       # Playwright against WEB_URL (default http://localhost:8787)
```

The browser tests register real users at Keycloak, read the verification email from Mailpit,
and suspend an account through the API's operator endpoint. Start the API with
`INTERNAL_API_TOKEN=dev-operator-token` for that test.

## Docker

```bash
docker build -f web/Dockerfile -t gavel-web .      # from the repository root
docker run -p 8787:8787 -e APP_ORIGIN=... -e OIDC_ISSUER=... -e API_ORIGIN=... -e SESSION_SECRET=... gavel-web
```

The image runs the Next.js standalone server as a non-root user on port 8787.
