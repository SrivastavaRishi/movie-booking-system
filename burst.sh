#!/usr/bin/env bash
# One-command on-sale stampede. Usage: ./burst.sh <BASE_URL> [extra options, see --help]
#   ./burst.sh http://localhost:8080
#   ./burst.sh https://my-app.example.com --users 500 --requests 5000 --concurrency 100
set -euo pipefail
if [ $# -lt 1 ]; then
  echo "usage: $0 <BASE_URL> [--users N] [--requests N] [--concurrency N] [--seats N] [--hot-seats N]" >&2
  exit 2
fi
exec python3 "$(dirname "$0")/scripts/burst.py" "$@"
