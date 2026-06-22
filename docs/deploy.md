# Deployment

Marker/Analitica is deployed to a VPS with Docker Compose and Caddy.

The current production path mirrors the Reviews project:

- GitHub Actions workflow: `.github/workflows/deploy.yml`
- Server deploy script: `deploy/server-deploy.sh`
- Source checkout on the VPS: `/srv/analitica-src`
- Runtime state on the VPS: `/srv/analitica`
- App container bound to localhost: `127.0.0.1:3000`
- Public HTTPS at `marker.shegida.ru` and temporary Basic Auth via Caddy

## One-time VPS setup

Install Docker, Docker Compose, Caddy, Git, and SSH access for the deploy user.
The deploy user must be able to run Docker Compose; the current deploy script is
intended to run as root, matching the Reviews deployment style.

Create runtime directories:

```bash
mkdir -p /srv/analitica/data /srv/analitica/reports
touch /srv/analitica/.env
chmod 600 /srv/analitica/.env
```

Copy and fill marketplace credentials out-of-band:

```bash
cp config.example.edn /srv/analitica/config.edn
chmod 600 /srv/analitica/config.edn
```

Never commit `/srv/analitica/config.edn`, `/srv/analitica/.env`, SQLite DBs, or
seller exports.

## GitHub setup

Create the GitHub repository, then add it as a remote locally.

Required GitHub Actions secrets:

- `VPS_HOST`
- `VPS_USER`
- `VPS_PORT` optional, defaults to `22`
- `VPS_SSH_KEY`

Run deployment manually from GitHub Actions: `Deploy VPS`.

## Caddy

Use `deploy/Caddyfile.example` as the starting point. The production domain is:

```text
marker.shegida.ru
```

Create an `A` record for `marker.shegida.ru` pointing to the VPS IPv4 address.
Add `AAAA` too if the VPS serves IPv6.

Replace `REPLACE_WITH_CADDY_PASSWORD_HASH` with `caddy hash-password` output.

Keep Caddy Basic Auth enabled until app-level authentication exists.

## Backup

The SQLite database is the production state:

```bash
/srv/analitica/data/analitica.db
```

Recommended daily backup:

```cron
0 3 * * * /usr/bin/sqlite3 /srv/analitica/data/analitica.db ".backup /var/backups/analitica-$(date +\%F).db" && find /var/backups -name 'analitica-*.db' -mtime +14 -delete
```

## Public release warning

Before the first public GitHub push, rewrite local git history to remove old
development-only paths and seller data that existed in previous commits:

- `.claude/`
- `docs/superpowers/`
- `specs/`
- `1c/`

Do this before adding the GitHub remote or pushing any branch publicly.
