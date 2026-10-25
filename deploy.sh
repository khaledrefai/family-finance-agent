#!/usr/bin/env bash
# The one deploy script.
#
#   ./deploy.sh          build and start everything (db, core, agents, web)
#   ./deploy.sh reseed   reset the demo family to its starting state (run before every recording)
#   ./deploy.sh test     run all tests (core needs Docker running for its test database)
#   ./deploy.sh logs     follow logs
#   ./deploy.sh down     stop everything (data is kept)
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "No .env found. Copy .env.example to .env and set the values first." >&2
  exit 1
fi

case "${1:-up}" in
  up)
    docker compose up -d --build
    echo "Waiting for the core API..."
    until docker compose exec -T core bash -c 'exec 3<>/dev/tcp/localhost/8080' 2>/dev/null; do sleep 2; done
    echo "Ready: http://localhost:${WEB_PORT:-3000}  (logins: sara, omar, layla, adam / demo1234)"
    domain="$(grep '^DOMAIN=' .env | cut -d= -f2- || true)"
    [[ -z "$domain" ]] || echo "With DNS set up it is also on https://$domain"
    ;;
  reseed)
    docker compose exec -T db psql -v ON_ERROR_STOP=1 -U ffa -d ffa < core/src/main/resources/db/seed/R__demo_family.sql
    echo "Demo family reset."
    ;;
  test)
    (cd core && mvn -B test)
    (cd agents && python3 -m pytest -q)
    (cd web && npm run typecheck)
    ;;
  logs)
    docker compose logs -f
    ;;
  down)
    docker compose down
    ;;
  *)
    echo "Usage: $0 [up|reseed|test|logs|down]" >&2
    exit 1
    ;;
esac
