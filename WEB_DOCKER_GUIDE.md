# Web app Docker guide

The web app image is built from `web/Dockerfile` with the repository root as the build context.
`web/Dockerfile.dockerignore` replaces the root `.dockerignore` for this build (the root file
excludes `web/` from the API image).

## Images and stages

| Stage | Contents | Used by |
|---|---|---|
| `deps` | Node 22.13 and installed packages | `docker-compose.dev.yml` (`next dev` on mounted source) |
| `builder` | `next build` output | intermediate |
| final | Next.js standalone server, non-root user, port 8787 | `docker-compose.yml`, deployments |

## Build and run

```bash
docker build -f web/Dockerfile -t gavel-web .
docker run --rm -p 8787:8787 \
  -e APP_ORIGIN=https://gavel.example.com \
  -e OIDC_ISSUER=https://id.gavel.example.com/realms/gavel \
  -e API_ORIGIN=http://api:8080 \
  -e SESSION_SECRET="$(openssl rand -hex 32)" \
  gavel-web
```

The container runs in production mode and refuses requests until every setting is present.
All settings are listed in [web/README.md](web/README.md#settings).

## Compose

```bash
# Production-like: web on :8787 (API image must exist; see docker-compose.yml)
docker compose build web
docker compose up web

# Development: hot reload on :3000 against the local Keycloak and API
docker compose -f docker-compose.yml -f docker-compose.dev.yml up web
```

The compose files set `GAVEL_ALLOW_DEV_DEFAULTS=true` so the local stack can use the
development session secret. Deployments must set `SESSION_SECRET` instead.

## Health

The image's health check requests `/` on port 8787. The API proxy answers
`401 SESSION_MISSING` for `/api/v1/me` without a session, which CI uses as a smoke test.
