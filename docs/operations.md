# Operations Runbook

Operational procedures for the Marker pilot on the VPS. Covers secrets at rest,
secret rotation per marketplace, applying Caddy edge changes, the API key, and
the health check.

- Runtime state lives in `/srv/analitica` (see `docs/deploy.md`).
- `.env` and `config.edn` are gitignored and never committed.
- The app is bound to `127.0.0.1:3000`; public HTTPS + Basic Auth is fronted by Caddy.

## Secrets at rest

`.env` and `config.edn` hold marketplace credentials and the API key. Both are
gitignored — they must never be committed. On the VPS they must be owner-only
readable, and any backup that contains them must be access-controlled (same file
permissions, restricted directory).

```bash
chmod 600 /srv/analitica/.env /srv/analitica/config.edn
```

## Secret rotation

Each marketplace token is revoked and reissued in that marketplace's portal,
then applied to Marker. Applying without downtime: paste the new token into the
Settings UI — `PUT /api/v1/settings/marketplace/:mp` triggers `config/reload!`,
so no restart is needed. Alternatively edit `.env` and restart the container.

- **WB:** Seller portal -> Настройки -> Доступ к API -> revoke the old token,
  generate a new one -> paste into the Settings UI (or set `.env` `WB_API_TOKEN`).
- **Ozon:** Seller -> Настройки -> API-ключи -> revoke `OZON_API_KEY`, create a
  new key. `OZON_CLIENT_ID` is stable and does not rotate.
- **YM:** Партнёрский кабинет / OAuth app -> reissue `YM_OAUTH_TOKEN`.
  `YM_BUSINESS_ID` and `YM_CAMPAIGN_ID` are stable and do not rotate.

## API key (Plan A)

The `API_KEY` in `.env` authorizes mutating routes via the `X-API-Key` header.
Generate a fresh one with:

```bash
openssl rand -hex 24
```

The SPA reads it from the injected meta tag automatically — no client config
needed. Rotating it requires updating `.env` and restarting the container (or
calling `config/reload!`).

## Applying Caddy changes on the VPS

`deploy/Caddyfile.example` is the in-repo template (HSTS, CSP, X-Frame-Options,
hidden `Server` header, 55 MB request-body cap). The 55 MB cap sits just above
the app's 50 MB multipart limit so honest oversized uploads get a clean app-level
413 while Caddy kills truly hostile or chunked bodies — if either limit moves,
move the other.

Asset MIME types are fixed at the app via Ring `wrap-content-type`, so no Caddy
`Content-Type` override is needed.

To apply: copy the `deploy/Caddyfile.example` content into the live Caddyfile
(`/etc/caddy/Caddyfile`), set the `basic_auth` hash from `caddy hash-password`,
validate, then reload:

```bash
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy   # or: caddy reload --config /etc/caddy/Caddyfile
```

## Health check

`GET /healthz` is unauthenticated at the app, but reaches the public domain
behind Caddy Basic Auth. It runs a guarded `SELECT 1` DB probe and never throws.

```bash
curl -fs https://marker.shegida.ru/healthz
```

A healthy instance returns `{"status":"ok","db-ok?":true}`. If `db-ok?` is
`false`, the process is up but the SQLite database is unreachable.

## Logs

Plan B adds `data/logs/events.jsonl`; backup and rotation for that file are
documented there.
