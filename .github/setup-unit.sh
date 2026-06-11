#!/usr/bin/env bash
set -euo pipefail

# Provisions the backend the storage contract suite (ObsStorageTest) runs against.
#
# - Pull requests (and local runs): bring up MinIO. Zero credentials, works for forks, stays offline.
# - Post-merge (push / schedule / workflow_dispatch): write application-secrets.yml so the `secrets`
#   Micronaut environment overlays the MinIO base config and the suite runs against a real Huawei Cloud
#   OBS bucket. Each run writes under a unique key prefix so concurrent runs never collide.
#
# The OBS bucket + credentials are provisioned out-of-band (flows-engineering) and injected here as the
# GitHub org secrets HUAWEI_ACCESS_KEY / HUAWEI_SECRET_ACCESS_KEY.

OBS_ENDPOINT="https://obs.eu-west-101.myhuaweicloud.com"
OBS_BUCKET="kestra-unit-test"
SECRETS_FILE="src/test/resources/application-secrets.yml"

setup_minio() {
  echo "Bringing up MinIO for the storage contract suite ..."
  docker compose -f docker-compose-ci.yml up -d

  echo "Waiting for MinIO to become ready on http://localhost:9000 ..."
  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:9000/minio/health/live >/dev/null 2>&1; then
      echo "MinIO is ready."
      return 0
    fi
    sleep 2
  done

  echo "MinIO did not become ready in time." >&2
  docker compose -f docker-compose-ci.yml logs >&2 || true
  exit 1
}

setup_obs() {
  : "${HUAWEI_ACCESS_KEY:?HUAWEI_ACCESS_KEY is required for the real-OBS test run}"
  : "${HUAWEI_SECRET_ACCESS_KEY:?HUAWEI_SECRET_ACCESS_KEY is required for the real-OBS test run}"

  # Unique per-run prefix (per run-attempt for re-runs); recomputed identically in cleanup-unit.sh.
  local prefix="ci/${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"

  echo "Pointing the storage contract suite at real OBS (bucket=${OBS_BUCKET}, prefix=${prefix}) ..."

  # pathStyleAccess: false is what selects native OBS signing (AuthTypeEnum.OBS) in ObsClientFactory;
  # this overlays and overrides the MinIO block from application-test.yml.
  cat > "${SECRETS_FILE}" <<EOF
kestra:
  storage:
    type: obs
    obs:
      endpoint: ${OBS_ENDPOINT}
      bucket: ${OBS_BUCKET}
      accessKey: "${HUAWEI_ACCESS_KEY}"
      secretKey: "${HUAWEI_SECRET_ACCESS_KEY}"
      pathStyleAccess: false
      path: "${prefix}"
EOF
}

case "${GITHUB_EVENT_NAME:-}" in
  pull_request|"")
    setup_minio
    ;;
  *)
    setup_obs
    ;;
esac
