package io.kestra.storage.obs;

import io.kestra.core.storages.FileAttributes;
import io.kestra.core.storages.StorageObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Exercises {@link ObsStorage} with a non-empty {@code path} prefix against MinIO — the configuration the
 * inherited {@link io.kestra.core.storage.StorageTestSuite} never sets. Targets the {@code deleteByPrefix}
 * / {@code allByPrefix} URI relativization (the bug fixed in the "relativize deleteByPrefix URIs by prefix
 * length" commit): returned URIs must be kestra-relative — no leaked {@code path} prefix, no doubled slash.
 *
 * <p>Requires MinIO: {@code docker compose up -d minio}.
 */
class ObsStoragePathPrefixTest {

    private static final String TENANT = "qa-tenant";
    private static final String PREFIX = "leading/prefix";

    private ObsStorage storage;

    @BeforeEach
    void setUp() {
        storage = ObsStorage.builder()
            .endpoint("http://localhost:9000")
            .accessKey("minioadmin")
            .secretKey("minioadmin")
            .bucket("unittest")
            .pathStyleAccess(true)
            .path(PREFIX)
            .build();
        storage.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            storage.deleteByPrefix(TENANT, null, URI.create("/qa/"));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        storage.close();
    }

    private void put(String path, String body) throws Exception {
        storage.put(TENANT, null, URI.create(path),
            new StorageObject(Map.of(), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void put_returnsCleanKestraRelativeUri() throws Exception {
        URI returned = storage.put(TENANT, null, URI.create("/qa/sub/f1.txt"),
            new StorageObject(Map.of(), new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
        assertThat(returned, is(URI.create("kestra:///qa/sub/f1.txt")));
        assertThat(returned.toString(), not(matchesPattern(".*leading/prefix.*")));
    }

    @Test
    void allByPrefix_doesNotLeakPathPrefix() throws Exception {
        put("/qa/sub/f1.txt", "a");
        put("/qa/sub/f2.txt", "b");

        List<URI> uris = storage.allByPrefix(TENANT, null, URI.create("/qa/"), false);
        List<String> paths = uris.stream().map(URI::getPath).toList();

        assertThat(paths, everyItem(matchesPattern("^/qa/.*")));
        assertThat(paths, everyItem(not(matchesPattern(".*leading/prefix.*"))));
        assertThat(paths, everyItem(not(matchesPattern(".*//.*"))));
        assertThat(uris, hasItem(URI.create("kestra:///qa/sub/f1.txt")));
        assertThat(uris, hasItem(URI.create("kestra:///qa/sub/f2.txt")));
    }

    @Test
    void list_returnsImmediateChildren() throws Exception {
        put("/qa/sub/f1.txt", "a");

        List<FileAttributes> children = storage.list(TENANT, null, URI.create("/qa/"));
        List<String> names = children.stream().map(FileAttributes::getFileName).toList();
        assertThat(names, contains("sub"));
    }

    @Test
    void deleteByPrefix_returnsCleanUris() throws Exception {
        put("/qa/sub/f1.txt", "a");
        put("/qa/sub/f2.txt", "b");

        List<URI> deleted = storage.deleteByPrefix(TENANT, null, URI.create("/qa/"));
        List<String> paths = deleted.stream().map(URI::getPath).toList();

        assertThat(paths, everyItem(not(matchesPattern(".*leading/prefix.*"))));
        assertThat(paths, everyItem(not(matchesPattern(".*//.*"))));
        assertThat(deleted, containsInAnyOrder(
            URI.create("kestra:///qa/sub/f1.txt"),
            URI.create("kestra:///qa/sub/f2.txt"),
            URI.create("kestra:///qa/sub"),
            URI.create("kestra:///qa")
        ));
        assertThat(storage.exists(TENANT, null, URI.create("/qa/sub/f1.txt")), is(false));
    }

    @Test
    void move_singleFile_underPathPrefix() throws Exception {
        put("/qa/src.txt", "payload");

        storage.move(TENANT, null, URI.create("/qa/src.txt"), URI.create("/qa/dst.txt"));

        assertThat(storage.exists(TENANT, null, URI.create("/qa/src.txt")), is(false));
        assertThat(storage.exists(TENANT, null, URI.create("/qa/dst.txt")), is(true));
        assertThat(new String(storage.get(TENANT, null, URI.create("/qa/dst.txt")).readAllBytes(), StandardCharsets.UTF_8),
            is("payload"));
    }
}
