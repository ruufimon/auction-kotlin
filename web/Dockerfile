# Gavel web app (Next.js, standalone server). Build from the repository root:
#   docker build -f web/Dockerfile -t gavel-web .
# Runtime settings (server side, never sent to the browser): APP_ORIGIN, OIDC_ISSUER,
# OIDC_INTERNAL_ISSUER, OIDC_CLIENT_ID, API_ORIGIN, SESSION_SECRET. See web/README.md.
ARG NODE_VERSION=22.13.0

FROM node:${NODE_VERSION}-alpine AS deps
WORKDIR /app
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:${NODE_VERSION}-alpine AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
# Build-time public setting: currency the assessment amounts are entered in.
ARG NEXT_PUBLIC_CURRENCY=USD
ENV NEXT_PUBLIC_CURRENCY=${NEXT_PUBLIC_CURRENCY}
COPY --from=deps /app/node_modules ./node_modules
COPY web/ ./
RUN npm run build

FROM node:${NODE_VERSION}-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=8787 HOSTNAME=0.0.0.0
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /app/.next/standalone ./
COPY --from=builder --chown=app:app /app/.next/static ./.next/static
COPY --from=builder --chown=app:app /app/public ./public
USER app
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8787/ || exit 1
CMD ["node", "server.js"]
