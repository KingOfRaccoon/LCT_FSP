#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Validate the active TLS config with the renewed cert before gracefully reloading.
docker compose -f compose.yml -f infra/compose.proctoring-tls.yml \
  exec -T web sh -ec 'nginx -t && nginx -s reload'
