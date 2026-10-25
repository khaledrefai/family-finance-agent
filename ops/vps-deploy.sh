#!/usr/bin/env bash
# Runs on the VPS, called by .github/workflows/deploy.yml. Not for local use (use ./deploy.sh).
#
#   ~/ffa/releases/<sha>/   one folder per deployed commit (the last 3 are kept)
#   ~/ffa/current           link to the running release
#   ~/ffa/shared/.env       settings; APP_JWT_SECRET is made once here and kept
#
# The database lives in the Docker volume ffa_dbdata, so it survives every deploy.
set -euo pipefail
sha="${1:?usage: vps-deploy.sh <commit sha>}"
base="$HOME/ffa"
release="$base/releases/$sha"
shared="$base/shared"

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  echo "Docker with the compose plugin is not installed on this VPS (or $USER cannot use it)." >&2
  echo "Install it: https://docs.docker.com/engine/install/  then: sudo usermod -aG docker $USER" >&2
  exit 1
fi

# Settings: the values sent by GitHub, plus a JWT secret that stays the same between deploys.
umask 077
env_new="$shared/incoming.env"
if ! grep -q '^APP_JWT_SECRET=' "$env_new"; then
  secret="$(grep '^APP_JWT_SECRET=' "$shared/.env" 2>/dev/null | cut -d= -f2- || true)"
  [[ -n "$secret" ]] || secret="$(openssl rand -base64 48 | tr -d '\n')"
  echo "APP_JWT_SECRET=$secret" >> "$env_new"
fi
mv "$env_new" "$shared/.env" && chmod 600 "$shared/.env"
cp "$shared/.env" "$release/.env"

# Fixed project name, so every release uses the same containers and database volume.
export COMPOSE_PROJECT_NAME=ffa
ln -sfn "$release" "$base/current"
bash "$release/deploy.sh" up

# Tidy up: keep the 3 newest releases and drop unused images.
ls -1dt "$base"/releases/*/ | tail -n +4 | xargs -r rm -rf
docker image prune -f >/dev/null
