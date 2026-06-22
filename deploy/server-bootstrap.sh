#!/usr/bin/env sh
set -eu

APP_DIR="${APP_DIR:-/srv/analitica}"
SRC_DIR="${SRC_DIR:-/srv/analitica-src}"
DOMAIN="${DOMAIN:-marker.shegida.ru}"
HOST_PORT="${ANALITICA_HOST_PORT:-3000}"

if [ "$(id -u)" -ne 0 ]; then
  echo "server-bootstrap.sh must run as root" >&2
  exit 1
fi

apt-get update
apt-get install -y ca-certificates curl git gnupg lsb-release sqlite3

if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

systemctl enable --now docker

if ! command -v caddy >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key \
    | gpg --dearmor -o /etc/apt/keyrings/caddy-stable-archive-keyring.gpg
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update
  apt-get install -y caddy
fi

systemctl enable --now caddy

mkdir -p "$SRC_DIR" "$APP_DIR/data" "$APP_DIR/reports" /etc/caddy/sites /root/.ssh
chmod 0755 "$SRC_DIR" "$APP_DIR" "$APP_DIR/data" "$APP_DIR/reports"
chmod 0700 /root/.ssh

if [ ! -f "$APP_DIR/.env" ]; then
  touch "$APP_DIR/.env"
  chmod 0600 "$APP_DIR/.env"
fi

if [ ! -f "$APP_DIR/config.edn" ]; then
  cat > "$APP_DIR/config.edn" <<'EOF'
{:marketplaces
 {:wb {:api-token "CHANGE_ME"}
  :ozon {:client-id "CHANGE_ME"
         :api-key "CHANGE_ME"}
  :ym {:oauth-token "CHANGE_ME"
       :campaign-id "CHANGE_ME"
       :business-id "CHANGE_ME"}}}
EOF
  chmod 0600 "$APP_DIR/config.edn"
fi

if ! grep -q '/etc/caddy/sites/\*\.caddy' /etc/caddy/Caddyfile 2>/dev/null; then
  cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
  {
    printf '\n'
    printf 'import /etc/caddy/sites/*.caddy\n'
  } >> /etc/caddy/Caddyfile
fi

if [ -n "${MARKER_BASIC_AUTH_HASH:-}" ]; then
  cat > /etc/caddy/sites/marker.caddy <<EOF
$DOMAIN {
	encode zstd gzip

	header {
		X-Content-Type-Options nosniff
		Referrer-Policy no-referrer
		Permissions-Policy "geolocation=(), microphone=(), camera=()"
	}

	basic_auth {
		admin $MARKER_BASIC_AUTH_HASH
	}

	reverse_proxy 127.0.0.1:$HOST_PORT
}
EOF
  caddy validate --config /etc/caddy/Caddyfile
  systemctl reload caddy
else
  cat > /etc/caddy/sites/marker.caddy.todo <<EOF
# Generate a password hash, then rerun:
# MARKER_BASIC_AUTH_HASH='<hash>' sh deploy/server-bootstrap.sh
#
# Hash command:
# caddy hash-password --plaintext 'change-me'
#
# Domain: $DOMAIN
# App upstream: 127.0.0.1:$HOST_PORT
EOF
fi

echo "bootstrap complete"
echo "domain=$DOMAIN"
echo "src_dir=$SRC_DIR"
echo "app_dir=$APP_DIR"
echo "docker=$(docker --version)"
echo "compose=$(docker compose version)"
echo "caddy=$(caddy version)"
echo
echo "Next steps:"
echo "1. Fill $APP_DIR/config.edn with real marketplace credentials."
echo "2. Run deploy/server-deploy.sh from the source checkout or GitHub Actions."
