# Deployment — analitica on internal LAN

> Self-hosted Docker deployment to a Debian LXC at `192.168.2.140`,
> reachable via `https://analitica.lan` (Caddy reverse-proxy on
> `192.168.2.50`). CI/CD runs on a Forgejo Actions runner.

## Topology

```
┌── developer laptop ──┐
│  git push origin → ──┼──► Forgejo (forge.lan)
└──────────────────────┘            │
                                    │ webhook
                                    ▼
                            Forgejo Actions runner
                                    │
                                    │ ssh + docker compose
                                    ▼
┌── analitica LXC (.140) ─┐    Caddy (.50) ── https://analitica.lan
│  /opt/analitica          │       │
│    Dockerfile            │       │
│    docker-compose.yml    │   reverse_proxy
│    config.edn  (mounted) │       │
│    .env        (mounted) ◄───────┘
│    data/analitica.db     │
│    docker container :3000│
└──────────────────────────┘
```

## One-time setup

### 1. Forgejo Actions runner

Where to run it — same LXC where you want CI artifacts to live, OR a
dedicated runner LXC. For a single-seller setup a separate LXC is
overkill; place the runner on the analitica LXC since it already has
Docker + nesting enabled.

On the analitica LXC:

```bash
# Get latest runner version from
# https://code.forgejo.org/forgejo/runner/releases
RUNNER_VERSION=6.2.2
wget -O /usr/local/bin/forgejo-runner \
  "https://code.forgejo.org/forgejo/runner/releases/download/v${RUNNER_VERSION}/forgejo-runner-${RUNNER_VERSION}-linux-amd64"
chmod +x /usr/local/bin/forgejo-runner

# Token from Forgejo: Site Administration → Actions → Runners → Create new Runner
mkdir -p /etc/forgejo-runner && cd /etc/forgejo-runner
forgejo-runner register \
  --no-interactive \
  --instance https://forge.lan \
  --token <TOKEN_HERE> \
  --name analitica-runner \
  --labels docker

# systemd service
cat > /etc/systemd/system/forgejo-runner.service <<'EOF'
[Unit]
After=network.target docker.service
Wants=docker.service

[Service]
WorkingDirectory=/etc/forgejo-runner
ExecStart=/usr/local/bin/forgejo-runner daemon
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now forgejo-runner
systemctl status forgejo-runner --no-pager | head -5
```

If runner can't reach `forge.lan` over HTTPS due to self-signed Caddy
cert, install the Caddy root CA on this LXC too (see below).

### 2. Trust Caddy CA on the runner LXC

Needed so the runner can `git clone https://forge.lan/...` without
TLS errors:

```bash
# Copy Caddy root CA from AdGuard LXC
scp root@192.168.2.50:/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt \
    /usr/local/share/ca-certificates/caddy-local.crt
update-ca-certificates
```

### 3. Deploy SSH key

The `deploy.yml` workflow uses an SSH keypair to push code from the
runner to the analitica host. Generate it once:

```bash
# On any machine — local laptop is fine
ssh-keygen -t ed25519 -f /tmp/deploy_key -N ''  -C 'analitica-deploy'
```

- **Private key** (`/tmp/deploy_key`) goes into Forgejo repo:
  Settings → Secrets and variables → Actions → New repository secret →
  Name: `DEPLOY_SSH_KEY`, value: paste the entire file content
  including BEGIN/END lines.

- **Public key** (`/tmp/deploy_key.pub`) goes onto the analitica LXC
  authorized_keys:

  ```bash
  cat /tmp/deploy_key.pub | ssh root@192.168.2.140 \
    'cat >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys'
  ```

- **Delete the keypair file** from `/tmp` afterwards.

### 4. Bootstrap /opt/analitica on the host

On `192.168.2.140`:

```bash
# Clone the repo (HTTPS path uses Caddy CA installed in step 2;
# alternatively SSH path uses the deploy key from step 3).
mkdir -p /opt && cd /opt
git clone https://forge.lan/alexdev/Analitica.git analitica

cd analitica
mkdir -p data reports

# Operator copies these out-of-band — never commit them.
cp /tmp/config.edn   .            # WB / Ozon / YM tokens
cp /tmp/.env         .            # env-prop overrides
cp /tmp/analitica.db data/        # production DB

# First build + start
docker compose up -d --build

# Verify
docker compose ps
docker compose logs --tail 30
curl -sI http://localhost:3000/
```

Subsequent deploys are automatic via the Forgejo Actions workflow.

### 5. Caddy front + DNS

Already wired during initial deployment. For reference:

**AdGuard LXC `.50` Caddyfile** (`/etc/caddy/Caddyfile`):
```
analitica.lan {
    tls internal
    reverse_proxy 192.168.2.140:3000
}
```

**Keenetic CLI**:
```
ip host analitica.lan 192.168.2.50
system configuration save
```

**AdGuard rewrites** (Filters → DNS rewrites):
- `analitica.lan` → `192.168.2.50`

## CI workflows

### `.forgejo/workflows/test.yml`

Runs the 800+ test suite on every push and PR. Uses
`clojure:temurin-21-tools-deps-bookworm-slim` container with cached
`~/.m2`. Exit non-zero blocks the deploy workflow (since deploy is
gated on master push, but if tests are broken on master, deploy fails
on the build step too).

### `.forgejo/workflows/deploy.yml`

Runs on master push. SSHes to `192.168.2.140`, pulls the latest code,
rebuilds the Docker image, restarts the container, prunes old images
older than 24h. Smoke-checks `localhost:3000` via the same SSH tunnel
for up to 90s before declaring success.

To skip auto-deploy for a particular commit: include `[skip ci]` in
the commit message.

## Backup

The SQLite DB at `/opt/analitica/data/analitica.db` is the only state.
Recommended cron entry on the analitica LXC:

```cron
0 3 * * * /usr/bin/sqlite3 /opt/analitica/data/analitica.db \
            ".backup /var/backups/analitica-$(date +\%F).db" \
          && find /var/backups -name 'analitica-*.db' -mtime +14 -delete
```

In addition, Proxmox `vzdump` of the whole LXC weekly catches both DB
and any local config state.

## Operator playbook

| Situation | Action |
|---|---|
| Update production code | `git push forge master` from laptop — runner deploys automatically. |
| Hotfix without redeploy | Edit `/opt/analitica/...` on `.140`, `docker compose up -d`. Note: next CI deploy will overwrite. |
| Rollback | `cd /opt/analitica && git reset --hard <prev-sha> && docker compose up -d --build`. |
| View logs | `docker compose logs -f` on `.140`, or via Forgejo Actions tab in the Forgejo UI. |
| Restart | `docker compose restart` on `.140`. |
| Inspect DB | `sqlite3 /opt/analitica/data/analitica.db` on `.140` (do NOT edit while container running — use `.backup` first). |
| Update tokens | Edit `config.edn` / `.env` on `.140`, `docker compose restart`. |
