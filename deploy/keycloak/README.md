# Local Keycloak

`gavel-realm.dev.json` is the realm for local development. Keycloak imports it on first start
from `docker-compose.dev.yml`. The deployed realm (issue #19) will be derived from it, without the
dev-only parts listed below.

## Start

```bash
docker compose -f docker-compose.dev.yml up -d postgres keycloak mailpit
./gradlew :app:run          # API on http://localhost:8080, development defaults
```

The API's development defaults already point at this realm: issuer
`http://localhost:8180/realms/gavel`, audience `gavel-api`.

| What | Where |
|---|---|
| Admin console | http://localhost:8180/admin (`admin` / `admin`) |
| Account / login pages | http://localhost:8180/realms/gavel/account |
| Web app | http://localhost:3000 (`npm run dev` in `web/`) |
| Mail inbox (verification, password reset) | http://localhost:8025 |

The Keycloak container has no volume. Recreating it re-imports the realm and drops any users you
registered. Edit the JSON, then `docker compose -f docker-compose.dev.yml up -d --force-recreate keycloak`.

## Seeded users

| Email | Password | State |
|---|---|---|
| `buyer@gavel.local` | `dev-password-buyer` | email verified; bootstraps to `PENDING_CREDIT_ELIGIBILITY` |
| `unverified@gavel.local` | `dev-password-unverified` | not verified; Keycloak refuses sign-in until the link in Mailpit is used |

## Get a token from the command line

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=gavel-dev-cli \
  -d username=buyer@gavel.local -d password=dev-password-buyer \
  http://localhost:8180/realms/gavel/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me | jq
```

Or run the whole check: `scripts/local-onboarding-smoke.sh` gets a token, bootstraps the account,
submits a passing assessment and sends a verification email to Mailpit. The assessment answers 409
on later runs, because the account is already active.

## What the realm sets

- Registration with email as username, email verification, password reset, unique emails.
- Password policy: 12 to 128 characters, not the username or email.
- Brute-force lockout after 5 failures, growing wait up to 15 minutes.
- Access tokens live 5 minutes. Refresh tokens rotate and are single-use.
- Client `gavel-web`: public, authorization code with PKCE S256, redirects to the web app on
  ports 3000 and 8787. An audience mapper adds `gavel-api` to access tokens.
- SMTP goes to Mailpit.

## Login theme

`themes/gavel/login` restyles Keycloak's sign-in, registration and email-verification pages to
the design canvas (issue #30): Gavel colours and type, light and dark mode, password-policy hints
on registration, and Gavel wording for duplicate emails, expired links and used links. It extends
the built-in `keycloak` theme, so only `gavel.css`, the messages and `register.ftl` are ours.
`register.ftl` is a copy of Keycloak 26.0.7's template with the hints added; re-check it when
upgrading Keycloak. Bump `themeVersion` in `theme.properties` with every change.

`start-dev` disables theme caching, so CSS and message edits show on reload.

## Dev-only, never in a deployed realm

- Client `gavel-dev-cli` with the password grant.
- `sslRequired: none`, localhost redirect URIs, Mailpit SMTP, seeded users, `admin`/`admin`.

## Not here yet

- Google sign-in. It needs an OAuth client ID and secret, which belong in the deployed realm's
  secrets, not in this file. Add it locally through the admin console under Identity providers if
  you have test credentials.
