# Keycloak

Two realms, one theme:

- `gavel-realm.dev.json`: local development, imported by `docker-compose.dev.yml`.
- `realm/gavel-realm.json`: deployed environments, baked into the image built by `Dockerfile`.
  Per-environment values are `${ENV}` placeholders that Keycloak fills in at import.

The local setup comes first; [Production](#production) follows.

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
- Access tokens live 15 minutes; sessions and refresh tokens 30 days. Refresh tokens rotate and are single-use.
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

## Not in the dev realm

- Google sign-in. Add it locally through the admin console under Identity providers if you have
  test credentials. The production realm carries a disabled `google` provider (below).

## Production

Keycloak runs as the App Platform app `gavel-keycloak` (spec `.do/keycloak.yaml`), built from
`Dockerfile` here: Keycloak 26.0.7 optimized for Postgres, the Gavel theme, health on port 9000,
single-instance cache. Its database is `keycloak` in the `gavel-db` cluster, owned by the user
`keycloak`.

### What the production realm sets

- Registration with email as username, unique emails, the same password policy and brute-force
  lockout as dev, `sslRequired: external`.
- Access tokens 15 minutes; sessions and refresh tokens 30 days; rotation with no reuse.
- `gavel-web`: public client, PKCE S256, one redirect URI `${GAVEL_WEB_ORIGIN}/auth/callback`,
  `gavel-api` audience mapper.
- `gavel-admin`: confidential service account with `realm-management` `manage-users` and
  `view-users`. The API uses it to disable users and end their sessions on suspension.
- `google`: configured from `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, **disabled**.
- **No SMTP yet**: email verification and password reset are off, and the API runs with
  `ONBOARDING_REQUIRE_EMAIL_VERIFICATION=false` so new accounts start at the eligibility step.

### Environment

| Variable | Value |
|---|---|
| `KC_HOSTNAME` | `${APP_URL}` (the app's public URL) |
| `KC_DB_URL`, `KC_DB_USERNAME`, `KC_DB_PASSWORD` | from the `gavel-db` binding (`db_name: keycloak`) |
| `GAVEL_WEB_ORIGIN` | the web app's public origin |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` (secret) | master-realm admin, created on first start |
| `GAVEL_ADMIN_CLIENT_SECRET` (secret) | same value as the API's `KEYCLOAK_ADMIN_CLIENT_SECRET` |

The API needs `OIDC_ISSUER=<keycloak URL>/realms/gavel`, `KEYCLOAK_ADMIN_CLIENT_ID=gavel-admin`
and `KEYCLOAK_ADMIN_CLIENT_SECRET`. The web app needs `OIDC_ISSUER` and `OIDC_INTERNAL_ISSUER`
set to the same URL.

### Create from scratch

1. Create the database and user, and hand them the database. On PostgreSQL 15+ only the
   owner may create tables in `public`:
   ```bash
   doctl databases user create <cluster-id> keycloak
   doctl databases db create <cluster-id> keycloak
   URI=$(doctl databases connection <cluster-id> --format URI --no-header --show)
   psql "$URI" -c 'ALTER DATABASE keycloak OWNER TO keycloak;'
   psql "${URI/defaultdb/keycloak}" -c 'ALTER SCHEMA public OWNER TO keycloak;'
   ```
2. Copy `.do/keycloak.yaml`, add `value:` lines for the three secrets, and create the app with
   `doctl apps create --spec <copy> --project-id <project>`. Never commit the copy.
3. Point the API and web app at `<keycloak URL>/realms/gavel` (see Environment).

### Change the realm after the first start

The realm is imported only when it does not exist yet; a restart skips it and keeps users. Make
later changes in the admin console (`<keycloak URL>/admin`, bootstrap admin) and mirror them in
`realm/gavel-realm.json`, so a fresh environment matches.

### Rotate the gavel-admin secret

Admin console → realm `gavel` → Clients → `gavel-admin` → Credentials → Regenerate. Put the new
value in the API's `KEYCLOAK_ADMIN_CLIENT_SECRET` (and the Keycloak app's
`GAVEL_ADMIN_CLIENT_SECRET`, for re-imports). Suspension calls fail between the two steps; the
API records them as `identity-provider.sync-failed` in the audit log.

### When suspension cannot reach Keycloak

The account is still suspended in the application, which rejects its tokens immediately. Find
the `identity-provider.sync-failed` audit entry, then in the admin console disable the user (or
enable it, for a reactivation) and, for a suspension, sign out its sessions.

### Turn on email later

1. Admin console → Realm settings → Email: SMTP host, port, credentials, from address.
2. Realm settings → Login: turn on **Verify email** and **Forgot password**.
3. Set the API's `ONBOARDING_REQUIRE_EMAIL_VERIFICATION=true` (or remove it).
4. Mirror the settings in `realm/gavel-realm.json` with `${SMTP_*}` placeholders.

### Turn on Google later

1. In Google Cloud Console, create an OAuth client (web application) with redirect URI
   `<keycloak URL>/realms/gavel/broker/google/endpoint`.
2. Admin console → Identity providers → `google`: paste the client ID and secret, enable it.
3. Set `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` on the Keycloak app and set
   `"enabled": true` in `realm/gavel-realm.json` for fresh environments.
