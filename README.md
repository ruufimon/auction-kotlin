# Gavel Auction

Gavel is an auction-platform prototype with a Kotlin/Ktor API starter and a
Next.js-compatible web interface. The product design covers four business
subsystems: user onboarding, product catalog, bidding, and settlement.

The complete functional and modular-monolith specification is available in
[`requirement.md`](requirement.md).

## Project structure

```text
.
├── src/           Kotlin/Ktor API starter
├── web/           Marketplace and onboarding web prototype
└── requirement.md Product and technical requirements
```

The frontend is grouped by business domain under `web/domains`. Bidder
registration and seller enablement share the `user-onboarding` domain, while
auction discovery and bidding presentation live in `marketplace`.

## Run the API

Requirements: JDK 21 or newer.

```bash
./gradlew run
```

The API listens on `http://localhost:8080` by default. Verify it with:

```bash
curl http://localhost:8080/
```

Expected response:

```text
Hello, World!
```

Set the `PORT` environment variable to use another port.

### Run the API with Docker

```bash
docker build -t gavel-api .
docker run --rm -p 8080:8080 gavel-api
```

The image runs as a non-root user and honours `PORT` and `JAVA_OPTS`.

### Health endpoints

- `GET /health/live`: liveness, always `200 OK` while the process runs.
- `GET /health/ready`: readiness, `503` when required infrastructure is
  unavailable.

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

Set secrets in the App Platform console or as `type: SECRET` variables. Never
commit secret values.

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

Run API tests:

```bash
./gradlew test
```

Validate the frontend:

```bash
cd web
npm run lint
npm run build
```
