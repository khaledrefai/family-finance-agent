#!/usr/bin/env bash
# Resets the demo family, applies the story overlay and captures the app's screens for the video.
#   ./capture.sh [web-url] [out-dir]
# psql reaches the database with the usual PG* variables (PGHOST=localhost ...). With the Docker stack use
#   PSQL="docker compose exec -T db psql -U ffa -d ffa" ./capture.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
psql_cmd=${PSQL:-psql -U ${PGUSER:-ffa} -d ${PGDATABASE:-ffa}}
# "Today" in the seed is the UAE day, like the core's clock (the same as ./deploy.sh reseed)
PGTZ=Asia/Dubai $psql_cmd -q -v ON_ERROR_STOP=1 < "$here/../core/src/main/resources/db/seed/R__demo_family.sql"
$psql_cmd -q -v ON_ERROR_STOP=1 < "$here/story-overlay.sql"
node "$here/capture.js" "${1:-http://localhost:3000}" "${2:-$here/build/shots}"
