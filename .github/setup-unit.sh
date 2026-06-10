#!/usr/bin/env bash
set -euo pipefail

# Bring up the MinIO backend used by the storage contract test suite.
docker compose -f docker-compose-ci.yml up -d

echo "Waiting for MinIO to become ready on http://localhost:9000 ..."
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:9000/minio/health/live >/dev/null 2>&1; then
    echo "MinIO is ready."
    exit 0
  fi
  sleep 2
done

echo "MinIO did not become ready in time." >&2
docker compose -f docker-compose-ci.yml logs >&2 || true
exit 1
