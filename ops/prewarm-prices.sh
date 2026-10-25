#!/usr/bin/env bash
# Fills the price search cache before recording the demo, so the video never waits on (or pays for) a live search.
#
#   ops/prewarm-prices.sh                         the app on this machine (http://localhost:$WEB_PORT, default 3000)
#   ops/prewarm-prices.sh https://family-finance-coach.com
#   ./deploy.sh prewarm                           the same, from the repo folder
#
# Run it with SEARCH_LIVE=live (the default) and the agents on AGENTS_LLM=off, so the words are read the same way
# as in the recording. Then set SEARCH_LIVE=cache for the recording: every search below comes from the cache
# (any age), anything else from the saved price list, and no live search is made.
# Each new search costs up to 4 SerpApi searches (the search and 3 re-checks). Already cached ones cost nothing.
set -euo pipefail
base="${1:-http://localhost:${WEB_PORT:-3000}}"
user="${PREWARM_USER:-omar}"
password="${PREWARM_PASSWORD:-demo1234}"

# What the demo types or taps: the chips on the kid's Ask screen and the parent's Compare prices screen
searches=(
  "Sony WH-CH720N headphones"
  "wireless headphones"
  "LEGO building set"
  "Nintendo Switch game"
  "Air fryer"
  "Samsung 55-inch TV"
  "Dyson V8"
  "Kids scooter"
  "peanut butter cups"
)

token="$(curl -fsS "$base/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$user\",\"password\":\"$password\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
if [[ -z "$token" ]]; then
  echo "Could not log in to $base as $user." >&2
  exit 1
fi

status="$(curl -fsS "$base/api/pricing/status" -H "Authorization: Bearer $token")"
echo "Price search: $status"
case "$status" in
  *'"mode":"LIVE"'*'"liveAvailable":true'*) ;;
  *) echo "Live search is not on (needs SEARCH_LIVE=live and a SERPAPI_KEY), so nothing new can be cached." >&2 ;;
esac

for q in "${searches[@]}"; do
  body="{\"text\":\"$q\"}"
  answer="$(curl -fsS --max-time 60 "$base/agent/prices" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" -d "$body" || true)"
  source="$(printf '%s' "$answer" | grep -o '"searchedFor":"[^"]*","source":"[A-Z]*"' | sed 's/.*"source":"//; s/"$//' || true)"
  best="$(printf '%s' "$answer" | grep -o '"best":{"key":"[^"]*","store":"[^"]*"' | sed 's/.*"store":"//; s/"$//' || true)"
  printf '%-28s %-7s %s\n' "$q" "${source:-failed}" "${best:+best at $best}"
done
echo "Done. For the recording set SEARCH_LIVE=cache (GitHub variable, then redeploy) and AGENTS_LLM=off."
