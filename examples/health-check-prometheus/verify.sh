#!/usr/bin/env bash
#
# Copyright © 2026 Fluent Health (https://fluentinhealth.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

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

# The probe target returns 401 (auth-protected backend); the assertion treats
# that as success, so the failure counter must remain absent. Track it
# explicitly so a regression in assertion semantics is caught.
info "Ensuring no failure samples were recorded"
if grep -qE 'gravitee_api_health_checks_total\{api_name="Stable Health Check Example",endpoint="primary",result="failure"' <<<"$body"; then
  fail "Failure counter present — the assertion is treating 401 as failure"
else
  ok "No failure samples — assertion correctly accepts 401 alongside 200"
fi

echo
ok "Verification complete — Prometheus reporter is emitting both request and health-check metrics."
