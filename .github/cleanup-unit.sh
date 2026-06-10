#!/usr/bin/env bash
set -uo pipefail

docker compose -f docker-compose-ci.yml down -v || true
