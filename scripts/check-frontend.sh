#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

find src/main/resources/static/js -name '*.js' -print0 | sort -z | xargs -0 -n 1 node --check
node scripts/validate-frontend-contracts.js
