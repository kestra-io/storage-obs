#!/usr/bin/env bash
set -uo pipefail

# Mirror of setup-unit.sh: tear down whichever backend that run brought up.
# - Pull requests / local: stop MinIO.
# - Post-merge (real OBS): delete this run's key prefix from the shared bucket. Best-effort — the
#   bucket's 1-day object-expiry lifecycle rule is the backstop for anything this misses.

OBS_ENDPOINT="https://obs.eu-west-101.myhuaweicloud.com"
OBS_BUCKET="kestra-unit-test"

case "${GITHUB_EVENT_NAME:-}" in
  pull_request|"")
    docker compose -f docker-compose-ci.yml down -v || true
    ;;
  *)
    prefix="ci/${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
    echo "Deleting OBS test prefix s3://${OBS_BUCKET}/${prefix} ..."
    # OBS exposes an S3-compatible API; the preinstalled aws CLI works against the OBS endpoint.
    AWS_ACCESS_KEY_ID="${HUAWEI_ACCESS_KEY:-}" \
    AWS_SECRET_ACCESS_KEY="${HUAWEI_SECRET_ACCESS_KEY:-}" \
      aws s3 rm "s3://${OBS_BUCKET}/${prefix}" \
        --recursive \
        --endpoint-url "${OBS_ENDPOINT}" || true
    ;;
esac
