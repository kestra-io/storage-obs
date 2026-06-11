package io.kestra.storage.obs;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.storage.StorageTestSuite;

/**
 * Runs the full Kestra storage contract against the {@code obs} backend.
 *
 * <p>The backend is configured in {@code src/test/resources/application-test.yml}, which targets a MinIO
 * endpoint (S3-compatible, {@code pathStyleAccess: true}) for local dev and pull-request CI. Start it with
 * {@code docker compose up -d minio}.
 *
 * <p>The {@code secrets} environment overlays {@code application-secrets.yml} when present. That file is
 * absent locally and on PRs (so the suite falls back to MinIO), and is written by {@code .github/setup-unit.sh}
 * on post-merge runs to point the suite at a real Huawei Cloud OBS bucket — exercising native OBS auth,
 * metadata and error semantics that MinIO cannot reproduce.
 */
@KestraTest(environments = "secrets")
class ObsStorageTest extends StorageTestSuite {
    // All test logic is inherited from StorageTestSuite.
}
