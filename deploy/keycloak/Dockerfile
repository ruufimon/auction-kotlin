# Production Keycloak for Gavel: the Gavel login theme and the realm baked into an optimized
# image. Build from the repository root:
#   docker build -f deploy/keycloak/Dockerfile -t gavel-keycloak .
# Runtime settings (deploy/keycloak/README.md): KC_HOSTNAME, KC_DB_URL, KC_DB_USERNAME,
# KC_DB_PASSWORD, KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD, GAVEL_WEB_ORIGIN, GAVEL_ADMIN_CLIENT_SECRET.
# Keep the version in step with docker-compose.dev.yml and themes/gavel/login/register.ftl.
ARG KEYCLOAK_VERSION=26.0.7

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=false \
    KC_CACHE=local
COPY deploy/keycloak/themes/gavel /opt/keycloak/themes/gavel
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# Imported on first start only: a realm that already exists is left alone, so changes made
# later must be applied in the admin console or by re-importing (see the README).
COPY deploy/keycloak/realm/gavel-realm.json /opt/keycloak/data/import/gavel-realm.json
# Behind App Platform's TLS-terminating proxy: plain HTTP inside, forwarded headers trusted.
# One instance, local cache: App Platform blocks the multicast the clustered cache needs.
# Read at startup in Keycloak 26, so it is set here as well as in the build stage.
ENV KC_HTTP_ENABLED=true \
    KC_PROXY_HEADERS=xforwarded \
    KC_CACHE=local
# 8080 serves the realm and console; 9000 serves /health/ready and /health/live.
EXPOSE 8080 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
