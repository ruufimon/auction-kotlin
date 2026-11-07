# Self-hosted SigNoz for Gavel metrics

One Droplet in `sgp1` running the SigNoz Docker Compose stack behind Caddy.
Chosen over Grafana Cloud / New Relic to keep metrics on infrastructure we own; see
`docs/M2_ONBOARDING_SLO.md` §4.3.

| Item | Value |
|---|---|
| Droplet | `signoz-sgp1`, `s-2vcpu-4gb`, Ubuntu 24.04, tag `signoz` |
| Firewall | `signoz-fw` (tag `signoz`): inbound 22, 80, 443 only |
| Hostname | `<droplet-ip>.sslip.io` (Caddy obtains the Let's Encrypt cert; swap for a real DNS name when one exists) |
| UI | `https://<host>/` — create the admin account on first visit |
| Ingest | `https://<host>/v1/metrics`, `/v1/logs`, `/v1/traces` — require `Authorization: Bearer <INGEST_TOKEN>`, proxied to the OTel collector on 4318 |
| Data | ClickHouse on the Droplet disk; set retention in SigNoz → Settings before it fills 80 GB |

## Create or rebuild

```bash
export INGEST_TOKEN=$(openssl rand -hex 32)       # store it in a password manager
envsubst '${INGEST_TOKEN}' < deploy/signoz/cloud-init.yaml > /tmp/signoz-init.yaml
doctl compute tag create signoz
doctl compute firewall create --name signoz-fw --tag-names signoz \
  --inbound-rules "protocol:tcp,ports:22,address:0.0.0.0/0 protocol:tcp,ports:80,address:0.0.0.0/0 protocol:tcp,ports:443,address:0.0.0.0/0" \
  --outbound-rules "protocol:tcp,ports:all,address:0.0.0.0/0 protocol:udp,ports:all,address:0.0.0.0/0 protocol:icmp,address:0.0.0.0/0"
doctl compute droplet create signoz-sgp1 --region sgp1 --size s-2vcpu-4gb --image ubuntu-24-04-x64 \
  --ssh-keys <key-ids> --tag-names signoz --user-data-file /tmp/signoz-init.yaml --enable-monitoring --wait
```

Bootstrap progress: `ssh root@<ip> tail -f /var/log/signoz-bootstrap.log`; done when `/var/lib/signoz-ready` exists.

## Point the API at it

The app reads the standard OpenTelemetry variables (`AppConfig.observability`):

| Variable | Value |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://<host>` (no path) |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Bearer <INGEST_TOKEN>` — a SECRET in App Platform |
| `OTEL_SERVICE_NAME` | `gavel-api` |
| `OTEL_METRIC_EXPORT_INTERVAL` | seconds, default 30 |

Unset endpoint = export off (local development default).

## Rotate the token

Edit `/etc/caddy/Caddyfile` on the Droplet, `systemctl reload caddy`, then update the
App Platform secret. Nothing in SigNoz itself holds the token.

## Upgrade

```bash
ssh root@<ip> 'cd /opt/signoz && git pull && cd deploy/docker && docker compose pull && docker compose up -d'
```
