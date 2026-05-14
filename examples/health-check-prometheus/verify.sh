#!/usr/bin/env bash
# Verifies that the example stack is healthy:
#   * /example proxies through the gateway and returns 200 (API not 503'd by HC)
#   * /metrics on the gateway exposes the health-check gauges and counters
#
# Run after up.sh.

set -euo pipefail
BLUE='\033[0;34m'; GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
info() { printf "${BLUE}▶ %s${NC}\n" "$*"; }
ok()   { printf "${GREEN}✔ %s${NC}\n" "$*"; }
fail() { printf "${RED}✘ %s${NC}\n" "$*"; exit 1; }

GATEWAY="http://localhost:8082"
METRICS="http://localhost:9090/metrics"

# 1. The API path still serves traffic (the question the user actually cares about)
info "GET ${GATEWAY}/example — expect 200"
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "${GATEWAY}/example")
  if [[ "$code" == "200" ]]; then
    ok "Gateway returns 200 for /example (health check did NOT take the API down)"
    break
  fi
  sleep 2
  [[ $i -eq 30 ]] && fail "Gateway never returned 200 (got $code) — check 'docker logs gravitee-prom-gw'"
done

# 2. Drive a bit of traffic so request/error counters have something to show
info "Sending 5 sample requests to populate request counters"
for _ in $(seq 1 5); do curl -fsS "${GATEWAY}/example" >/dev/null; done

# 3. Wait long enough for the health-check schedule (*/10 sec) to fire at least once
info "Sleeping 12s for the health-check scheduler to fire"
sleep 12

# 4. Scrape /metrics and check the health-check signal is present
info "GET ${METRICS}"
body=$(curl -fsS "$METRICS")

check() {
  if grep -qE "$1" <<<"$body"; then ok "metrics contain: $1"; else fail "metrics missing: $1"; fi
}

check 'gravitee_api_requests_total\{api_name="Stable Health Check Example"'
check 'gravitee_api_endpoint_up\{api_name="Stable Health Check Example",endpoint="primary"\} 1\.0'
check 'gravitee_api_health_checks_total\{api_name="Stable Health Check Example",endpoint="primary",result="success"'

echo
ok "Verification complete — Prometheus reporter is emitting both request and health-check metrics."
