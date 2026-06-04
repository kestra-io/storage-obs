package io.kestra.storage.obs;

import io.kestra.core.storage.StorageTestSuite;

/**
 * Runs the full Kestra storage contract against the {@code obs} backend.
 *
 * <p>The backend is configured in {@code src/test/resources/application-test.yml} and exercised against a
 * MinIO endpoint (S3-compatible, {@code authType: V2}). Start it with {@code docker compose up -d minio}.
 */
class ObsStorageTest extends StorageTestSuite {
    // All test logic is inherited from StorageTestSuite.
}
