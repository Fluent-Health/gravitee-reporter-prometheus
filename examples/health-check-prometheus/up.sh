#!/usr/bin/env bash
# Brings up the example stack, mounts the plugin ZIP, and deploys the
# Stable Health Check Example API via the management API.
#
# Prerequisites:
#   * Docker + docker-compose
#   * curl, jq
#
# Usage:
#   ./up.sh                # uses the latest plugin in plugins/ (downloads 0.2.0 if empty)
#   ./up.sh <path-to-zip>  # uses the given plugin ZIP

set -euo pipefail

# Colours — match Fluent Health bash convention (info=blue, ok=green, warn=yellow).
BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { printf "${BLUE}▶ %s${NC}\n"  "$*"; }
ok()    { printf "${GREEN}✔ %s${NC}\n" "$*"; }
warn()  { printf "${YELLOW}⚠ %s${NC}\n" "$*"; }

# 1. Resolve the plugin ZIP into ./plugins/ ----------------------------------
PLUGIN_DIR="$(dirname "$0")/plugins"
mkdir -p "$PLUGIN_DIR"
rm -f "$PLUGIN_DIR"/gravitee-reporter-prometheus-*.zip

if [[ $# -ge 1 ]]; then
  cp "$1" "$PLUGIN_DIR/"
  ok "Using plugin ZIP: $1"
elif compgen -G "$(dirname "$0")/../../target/gravitee-reporter-prometheus-*.zip" > /dev/null; then
  # shellcheck disable=SC2012
  src="$(ls -t "$(dirname "$0")"/../../target/gravitee-reporter-prometheus-*.zip | head -1)"
  cp "$src" "$PLUGIN_DIR/"
  ok "Using local build: $(basename "$src")"
else
  RELEASE_TAG="${RELEASE_TAG:-0.2.0}"
  url="https://github.com/Fluent-Health/gravitee-reporter-prometheus/releases/download/${RELEASE_TAG}/gravitee-reporter-prometheus-${RELEASE_TAG}.zip"
  info "Downloading plugin ${RELEASE_TAG} from GitHub releases"
  curl -fSL --output "$PLUGIN_DIR/gravitee-reporter-prometheus-${RELEASE_TAG}.zip" "$url"
  ok "Downloaded plugin ${RELEASE_TAG}"
fi

# 2. Bring the stack up ------------------------------------------------------
info "Starting docker compose stack"
docker compose -f "$(dirname "$0")/docker-compose.yml" up -d

info "Waiting for management-api to report healthy"
for i in $(seq 1 120); do
  if curl -fsS http://localhost:8083/management/v2/ui/bootstrap >/dev/null 2>&1; then
    ok "Management API ready"
    break
  fi
  sleep 2
  if [[ $i -eq 120 ]]; then
    warn "Management API did not become ready in 240s — inspect 'docker logs gravitee-prom-mgmt'"
    exit 1
  fi
done

info "Waiting for gateway to report healthy"
for i in $(seq 1 60); do
  if curl -fsS http://localhost:8082 -o /dev/null -w '%{http_code}' 2>/dev/null | grep -qE '^(2|4)'; then
    ok "Gateway accepting connections"
    break
  fi
  sleep 2
done

# 3. Deploy the example API --------------------------------------------------
MGMT="http://localhost:8083/management/v2/organizations/DEFAULT/environments/DEFAULT"
AUTH="-u admin:admin"
JSON_HDR='-H Content-Type: application/json'

info "Creating API"
api_id=$(curl -fsS $AUTH -H 'Content-Type: application/json' \
  -d @"$(dirname "$0")/apis/health-check-api.json" \
  "$MGMT/apis" | jq -r '.id')
ok "API id=$api_id"

info "Creating + publishing KEY_LESS plan"
plan_id=$(curl -fsS $AUTH -H 'Content-Type: application/json' \
  -d '{"name":"Default Plan","definitionVersion":"V4","security":{"type":"KEY_LESS"}}' \
  "$MGMT/apis/$api_id/plans" | jq -r '.id')
curl -fsS $AUTH -X POST "$MGMT/apis/$api_id/plans/$plan_id/_publish" -d '{}' -H 'Content-Type: application/json' >/dev/null

info "Starting + deploying API"
curl -fsS $AUTH -X POST "$MGMT/apis/$api_id/_start"  -d '{}' -H 'Content-Type: application/json' >/dev/null
curl -fsS $AUTH -X POST "$MGMT/apis/$api_id/deployments" -d '{}' -H 'Content-Type: application/json' >/dev/null
ok "API deployed — gateway will sync within ~10s"

echo
ok "Stack is up. Next: ./verify.sh"
