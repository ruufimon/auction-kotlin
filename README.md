# Gavel Auction

Gavel is an auction-platform prototype with a Kotlin/Ktor API starter and a
Next.js-compatible web interface. The product design covers four business
subsystems: user onboarding, product catalog, bidding, and settlement.

The complete functional and modular-monolith specification is available in
[`requirement.md`](requirement.md).

## ⚡ Agent Friendly Architecture (AFA)

This project is an **experimental implementation** of Agent Friendly Architecture
(AFA), a design pattern optimized for AI code generation and autonomous agents.

### AFA Principles

AFA applies five architectural rules to create codebases that are naturally
navigable and modifiable by AI agents:

1. **Conventional paths reduce decisions** — Predictable directory structures
   allow agents to locate and modify code without exploration
2. **Forbidden dependencies fail mechanically** — Build systems enforce
   architectural boundaries automatically
3. **Single writer pattern** — One module owns domain types, preventing
   circular dependencies and conflicts
4. **Features live in features/** — All business logic organized in predictable
   feature directories
5. **Exceptions are explicit** — Special cases documented in architecture
   decisions, not hidden in code

### Project Restructuring for AFA

This codebase was restructured to align with AFA principles:

**Backend (Kotlin/Gradle):**
- `kernel/` — Shared events, audit log, API error model and Keycloak token validation (single writer)
- `features/` — Four feature modules:
  - `features/user-onboarding/`
  - `features/product-catalog/`
  - `features/bidding/`
  - `features/settlement/`
- Gradle configuration ensures forbidden dependencies fail at build time

**Frontend (Next.js):**
- `web/src/app/` — Pages and route handlers (sign-in, API proxy, onboarding screens)
- `web/src/lib/` — Typed API client, error codes, money helpers, server-side session
- `web/src/components/` — Shared UI components

See [AGENT.md](AGENT.md) for the complete AFA specification and
[web/README.md](web/README.md) for the web app.

## Project structure

```text
.
├── kernel/                      Shared events, audit, errors, token validation
├── features/                    Business logic modules
│   ├── user-onboarding/
│   ├── product-catalog/
│   ├── bidding/
│   └── settlement/
├── app/                         Application entry point
├── web/                         Next.js web app (src/app, src/lib, src/components, e2e)
├── deploy/keycloak/             Local Keycloak realm and the Gavel login theme
├── AGENT.md                    AFA specification
└── requirement.md              Product and technical requirements
```

The backend is organized by feature under `features/`, with shared domain types
in `kernel/`. The web app talks to the API only through its typed client and
same-origin proxy, so it never handles tokens in the browser.

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
docker build -t gavel-api -f Dockerfile .
docker run --rm -p 8080:8080 gavel-api
```

The image runs as a non-root user and honours `PORT` and `JAVA_OPTS`.

### Run Full Stack with Docker Compose

Run both API and web frontend together:

```bash
# Production mode
docker-compose build
docker-compose up

# Development mode (with hot reload)
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up
```

- API: http://localhost:8080
- Web: http://localhost:8787

See [docker-compose.yml](docker-compose.yml) and
[WEB_DOCKER_GUIDE.md](WEB_DOCKER_GUIDE.md) for details.

### Run locally with Keycloak

Sign-in uses Keycloak. Start PostgreSQL, a local Keycloak with the `gavel` realm, and Mailpit,
then run the API on the host:

```bash
docker compose -f docker-compose.dev.yml up -d postgres keycloak mailpit
./gradlew :app:run
```

Seeded users, token commands and URLs are in [deploy/keycloak/README.md](deploy/keycloak/README.md).

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

Requirements: Node.js 22.13 or newer, and the local stack from
[Run locally with Keycloak](#run-locally-with-keycloak) (PostgreSQL, Keycloak, Mailpit, API).

```bash
cd web
npm install
npm run dev        # http://localhost:3000
```

Create an account from the home page. Keycloak sends the verification email to
Mailpit at `http://localhost:8025`. After verifying, the app walks through the
eligibility check, profile, and seller setup. Details, settings and the Docker
image are in [web/README.md](web/README.md).

The earlier click-through prototype is hosted at
[gavel-auction-marketplace.ruufimon.chatgpt.site](https://gavel-auction-marketplace.ruufimon.chatgpt.site).

## Validation

Run API tests:

```bash
./gradlew test
```

Validate the web app:

```bash
cd web
npm run typecheck && npm test && npm run build
npm run e2e        # browser tests; needs the local stack and the app on :8787
```

## Agent Friendly Architecture Resources

This project demonstrates AFA principles in action. Key resources:

- **[AGENT.md](AGENT.md)** — Complete AFA specification and rules
- **[RESTRUCTURING.md](RESTRUCTURING.md)** — Backend restructuring details
- **[web/RESTRUCTURING.md](web/RESTRUCTURING.md)** — Frontend restructuring details
- **[WEB_DOCKER_GUIDE.md](WEB_DOCKER_GUIDE.md)** — Web frontend Docker documentation
- **[docker-compose.yml](docker-compose.yml)** — Multi-service orchestration
- **[scripts/docker-web.sh](scripts/docker-web.sh)** — Automation script for Docker operations

### Why AFA Matters

Traditional codebases often require extensive exploration to understand structure
and dependencies. AFA codebases follow predictable patterns that enable:

- **AI agents** to navigate and modify code confidently
- **Faster onboarding** for new team members
- **Reduced cognitive load** through conventional organization
- **Mechanical enforcement** of architectural boundaries
- **Autonomous refactoring** with confidence in build-time validation

This project is an experimental testbed for AFA principles in a real-world
auction platform prototype.
