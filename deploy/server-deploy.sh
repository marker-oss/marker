#!/usr/bin/env sh
set -eu

REPO_URL="${REPO_URL:-https://github.com/marker-oss/marker.git}"
DEPLOY_REF="${DEPLOY_REF:-main}"
SRC_DIR="${SRC_DIR:-/srv/analitica-src}"
APP_DIR="${APP_DIR:-/srv/analitica}"
HOST_PORT="${ANALITICA_HOST_PORT:-3000}"

if [ "$(id -u)" -ne 0 ]; then
  echo "server-deploy.sh must run as root" >&2
  exit 1
fi

if [ -d "$SRC_DIR/.git" ]; then
  cd "$SRC_DIR"
  git fetch --prune origin
else
  rm -rf "$SRC_DIR"
  git clone "$REPO_URL" "$SRC_DIR"
  cd "$SRC_DIR"
fi

git checkout -B "$DEPLOY_REF" "origin/$DEPLOY_REF"
git reset --hard "origin/$DEPLOY_REF"

mkdir -p "$APP_DIR/data" "$APP_DIR/reports"

if [ ! -f "$APP_DIR/config.edn" ]; then
  echo "Missing $APP_DIR/config.edn. Copy config.example.edn there and fill marketplace tokens." >&2
  exit 1
fi

if [ ! -f "$APP_DIR/.env" ]; then
  touch "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
fi

ANALITICA_BUILD_CONTEXT="$SRC_DIR" \
ANALITICA_CONFIG="$APP_DIR/config.edn" \
ANALITICA_ENV_FILE="$APP_DIR/.env" \
ANALITICA_DATA_DIR="$APP_DIR/data" \
ANALITICA_REPORTS_DIR="$APP_DIR/reports" \
ANALITICA_HOST_PORT="$HOST_PORT" \
docker compose -f "$SRC_DIR/docker-compose.yml" -p analitica up -d --build

docker image prune -f --filter "until=24h" >/dev/null

echo "deploy complete"
echo "ref=$DEPLOY_REF"
echo "commit=$(git -C "$SRC_DIR" rev-parse --short HEAD)"
