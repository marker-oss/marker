# VPS deployment

This deployment mirrors the Reviews project shape:

- GitHub Actions runs `Deploy VPS` manually.
- The action SSHes into the VPS.
- The VPS keeps source in `/srv/analitica-src`.
- Runtime state lives in `/srv/analitica`.
- Docker Compose builds and runs the app on `127.0.0.1:3000`.
- Caddy terminates HTTPS and protects the app with Basic Auth until app-level auth exists.

## One-time server setup

If SSH access works, copy and run the bootstrap script:

```bash
scp deploy/server-bootstrap.sh reviews-vps:/tmp/marker-bootstrap.sh
ssh reviews-vps "MARKER_BASIC_AUTH_HASH='<caddy-hash>' sh /tmp/marker-bootstrap.sh"
```

Create the hash on the VPS or locally:

```bash
caddy hash-password --plaintext 'change-me'
```

The script installs/starts Docker, Docker Compose, Caddy, Git, and SQLite,
creates `/srv/analitica`, and writes the Caddy site for `marker.shegida.ru`.

Manual minimum:

```bash
mkdir -p /srv/analitica/data /srv/analitica/reports
cp config.example.edn /srv/analitica/config.edn
touch /srv/analitica/.env
chmod 600 /srv/analitica/config.edn /srv/analitica/.env
```

Fill `/srv/analitica/config.edn` with marketplace tokens out-of-band.

Install Docker, Docker Compose, Caddy, and add the GitHub deploy key to the
server account used by the GitHub Actions secrets.

The VPS also needs a GitHub deploy key that can read `marker-oss/marker`,
because `deploy/server-deploy.sh` pulls the repository from GitHub on the
server.

## GitHub secrets

- `VPS_HOST`
- `VPS_USER`
- `VPS_PORT` optional, defaults to `22`
- `VPS_SSH_KEY`

## Caddy

Use `deploy/Caddyfile.example` as a starting point, replace the Basic Auth
password hash, then reload Caddy. The production domain is:

```text
marker.shegida.ru
```

Create an `A` record for `marker.shegida.ru` pointing to the VPS IPv4 address
before reloading Caddy. Add `AAAA` too if the VPS serves IPv6.

## Backup

The production SQLite DB is `/srv/analitica/data/analitica.db`.

```cron
0 3 * * * /usr/bin/sqlite3 /srv/analitica/data/analitica.db ".backup /var/backups/analitica-$(date +\%F).db" && find /var/backups -name 'analitica-*.db' -mtime +14 -delete
```
