#!/usr/bin/env bash
# Tears down the example stack and removes containers + volumes.
set -euo pipefail
docker compose -f "$(dirname "$0")/docker-compose.yml" down -v --remove-orphans
