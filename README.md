# Gavel Auction

Gavel is an auction-platform prototype with a Kotlin/Ktor API starter and a
Next.js-compatible web interface. The product design covers four business
subsystems: user onboarding, product catalog, bidding, and settlement.

The complete functional and modular-monolith specification is available in
[`requirement.md`](requirement.md).

## Project structure

```text
.
├── app/              Ktor startup, wiring, HTTP plugins, outbox, schedulers
├── shared-kernel/    Money, opaque IDs, errors, pagination, event/audit ports
├── shared-web/       Ktor transport helpers shared by module routes
├── user-onboarding/  Accounts, verification, sessions, profiles, eligibility
├── product-catalog/  Listings, categories, search, listing lifecycle
├── bidding/          Bids, price progression, concurrency, winner selection
├── settlement/       Orders, checkout, payment webhooks, fulfillment
├── web/              Marketplace and onboarding web prototype (own repository)
└── requirement.md    Product and technical requirements
```

Each business module has `api`, `application`, `domain` and `infrastructure`
packages and owns one PostgreSQL schema with its own Flyway migrations.
Modules talk to each other only through `api` interfaces and outbox events;
`ArchitectureTest` enforces this. See [`docs/traceability.md`](docs/traceability.md)
for which tests cover each requirement.

The frontend is grouped by business domain under `web/domains`. Bidder
registration and seller enablement share the `user-onboarding` domain, while
auction discovery and bidding presentation live in `marketplace`.

## API

The full contract is [`app/src/main/resources/openapi/documentation.yaml`](app/src/main/resources/openapi/documentation.yaml)
(OpenAPI 3.1), also served at `GET /api/v1/openapi.yaml`. Generate web types with:

```bash
npx openapi-typescript http://localhost:8080/api/v1/openapi.yaml -o types/api.d.ts
```

| Area | Endpoints (under `/api/v1`) |
|---|---|
| Auth | `POST /auth/register`, `/auth/verify-email`, `/auth/verify-email/resend`, `/auth/sign-in`, `/auth/refresh`, `/auth/sign-out` |
| Account | `GET`/`PATCH /me`, `PUT /me/seller-profile` |
| Catalog | `GET /categories`, `GET /listings`, `GET /listings/{id}` |
| Selling | `GET`/`POST /me/listings`, `GET`/`PATCH /me/listings/{id}`, `POST /me/listings/{id}/publish`, `/cancel` |
| Bidding | `POST /listings/{id}/bids` (needs `Idempotency-Key`), `GET /listings/{id}/bids`, `GET /me/bids` |
| Orders | `GET /me/orders?role=buyer\|seller`, `GET /orders/{id}`, `POST /orders/{id}/checkout`, `/shipment`, `/delivery` |
| Payments | `POST /payments/webhooks/{provider}` (signed) |

Money is always `{ "amountMinor": 324000, "currency": "USD" }`. Access tokens
last 15 minutes; the refresh token is an HttpOnly cookie scoped to
`/api/v1/auth`, so the web app must call the API with `credentials: "include"`.

## Run the API locally

Requirements: JDK 21 or newer and Docker.

```bash
docker compose up -d db
set -a; . ./.env.example; set +a
./gradlew :app:run
```

The API listens on `http://localhost:8080`. With the example settings it seeds
the four demo lots from the web prototype and logs email verification links
instead of sending them (`DEV_EMAIL_LINKS=true`, local only).

### Configuration

| Variable | Required | Purpose |
|---|---|---|
| `DATABASE_URL` | yes | `postgresql://user:pass@host:port/db` (or `JDBC_DATABASE_URL`) |
| `JWT_SECRET` | yes | HMAC key for access tokens, 32+ characters |
| `PAYMENT_WEBHOOK_SECRET` | yes | Verifies payment webhook signatures |
| `WEB_BASE_URL` | | Web app URL used in verification links (default `http://localhost:3000`) |
| `WEB_ORIGINS` | | Comma-separated CORS origins (default `WEB_BASE_URL`) |
| `COOKIE_SECURE` | | `false` only for plain-HTTP local development (default `true`) |
| `CURRENCY`, `MIN_BID_INCREMENT_MINOR`, `PAYMENT_WINDOW_HOURS` | | Defaults `USD`, `100`, `24` |
| `ADMIN_TOKEN` | | Enables operator endpoints under `/admin/v1` |
| `METRICS_TOKEN` | | Enables Prometheus metrics at `GET /metrics` |
| `SEED_DEMO_DATA`, `DEV_EMAIL_LINKS` | | Demo listings; development email links |

### Run the API with Docker

```bash
docker build -t gavel-api .
docker run --rm -p 8080:8080 -e DATABASE_URL=... -e JWT_SECRET=... -e PAYMENT_WEBHOOK_SECRET=... gavel-api
```

The image runs as a non-root user and honours `PORT` and `JAVA_OPTS`.

### Health endpoints

- `GET /health/live`: liveness, always `200 OK` while the process runs.
- `GET /health/ready`: readiness, `503` when the database is unreachable.

### Not yet implemented

- **Email delivery:** production uses a placeholder sender that only logs that
  a message was queued. Plug a provider into `EmailSender` before launch.
- **Payment provider:** `SandboxPaymentProvider` stands in for a real one
  behind the `PaymentProvider` port. Checkout URLs point at `CHECKOUT_BASE_URL`.
- **Image uploads:** listings take `https` image URLs; there is no upload endpoint yet.
- **Carrier delivery updates:** only the buyer can confirm delivery today.

## Deploy to DigitalOcean

The API deploys to DigitalOcean App Platform from the spec in
[`.do/app.yaml`](.do/app.yaml). App Platform builds the `Dockerfile`, terminates
TLS, and redeploys on every push to `main`.

1. Authenticate and create the app:

   ```bash
   doctl auth init
   doctl apps create --spec .do/app.yaml
   ```

2. After the spec changes, apply them with
   `doctl apps update <app-id> --spec .do/app.yaml`.

The spec provisions a dev PostgreSQL database and expects the secrets
`JWT_SECRET`, `PAYMENT_WEBHOOK_SECRET`, `ADMIN_TOKEN` and `METRICS_TOKEN`. Set
them in the App Platform console or as `type: SECRET` variables, and apply the
spec **before** merging API changes to `main`, because the API won't start without a
database. Never commit secret values.

## Run the web app

Requirements: Node.js 22.13 or newer and npm.

```bash
cd web
npm install
npm run dev
```

Open `http://localhost:3000` in a browser.

### User journeys

- Marketplace: `http://localhost:3000/`
- Bidder onboarding: `http://localhost:3000/onboarding`
- Seller and product onboarding: `http://localhost:3000/sell/onboarding`

Every user starts as a bidder. Selecting **My account** or attempting to bid
opens bidder onboarding. Selecting **Sell an item** also requires bidder setup
first, then returns the user to seller onboarding. The seller flow collects the
seller profile, shipping origin, payout connection, product details, images,
price, and auction schedule before creating a draft listing.

Authentication, email verification, and persistence are simulated in the web
prototype with browser session storage. Passwords are never persisted.

The private hosted prototype is available at
[gavel-auction-marketplace.ruufimon.chatgpt.site](https://gavel-auction-marketplace.ruufimon.chatgpt.site).

## Validation

Run API tests (Docker is required; integration tests start PostgreSQL with
Testcontainers):

```bash
./gradlew test
```

Validate the frontend:

```bash
cd web
npm run lint
npm run build
```
