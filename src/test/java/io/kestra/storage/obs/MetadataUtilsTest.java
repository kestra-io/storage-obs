package io.kestra.storage.obs;

import com.obs.services.model.ObjectMetadata;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

/**
 * Behavior of the metadata key encoding.
 *
 * <p>Read path model: on a GET, the OBS SDK populates {@code ObjectMetadata.getMetadata()} (the
 * response-header map) with system headers <em>and</em> user-metadata keys whose {@code x-obs-meta-}
 * prefix the SDK has already stripped. {@link MetadataUtils#toRetrievedMetadata} must therefore drop
 * the system headers and decode the bare {@code snake_case} user keys back to {@code camelCase}.
 */
class MetadataUtilsTest {

    /** Simulates the SDK's read-side ObjectMetadata: user keys are echoed prefix-stripped, as stored. */
    private static ObjectMetadata respondedWith(Map<String, String> storedUserKeys, Map<String, String> systemHeaders) {
        ObjectMetadata meta = new ObjectMetadata();
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.putAll(systemHeaders);
        headers.putAll(storedUserKeys);
        meta.setMetadata(headers);
        return meta;
    }

    private static Map<String, String> roundTrip(Map<String, String> in) {
        return MetadataUtils.toRetrievedMetadata(respondedWith(MetadataUtils.toStoredMetadata(in), Map.of()));
    }

    // ---- toStoredMetadata (write side) -----------------------------------------------------------

    @Test
    void stored_encodesCamelCaseToSnakeCase() {
        assertThat(MetadataUtils.toStoredMetadata(Map.of("createdBy", "kestra")),
            is(Map.of("created_by", "kestra")));
    }

    @Test
    void stored_nullYieldsEmpty() {
        assertThat(MetadataUtils.toStoredMetadata(null), is(Map.of()));
    }

    // ---- round trip (write -> stored -> server echo -> read) -------------------------------------

    @Test
    void camelCaseKeys_roundTrip() {
        Map<String, String> out = roundTrip(Map.of("contentType", "text/plain", "createdBy", "kestra"));
        assertThat(out, hasEntry("contentType", "text/plain"));
        assertThat(out, hasEntry("createdBy", "kestra"));
    }

    @Test
    void acronymAndDigitKeys_roundTrip() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("myURLKey", "v1");
        in.put("key2Name", "v2");
        Map<String, String> out = roundTrip(in);
        assertThat(out, hasEntry("myURLKey", "v1"));
        assertThat(out, hasEntry("key2Name", "v2"));
    }

    /** A key containing a literal underscore is escaped ({@code _} -> {@code __}) and round-trips intact. */
    @Test
    void underscoreInputKey_roundTrips() {
        Map<String, String> out = roundTrip(Map.of("my_key", "v"));
        assertThat(out, hasEntry("my_key", "v"));
        assertThat(out, not(hasKey("myKey")));
    }

    // ---- toRetrievedMetadata (read side) ---------------------------------------------------------

    @Test
    void retrieved_filtersSystemHeaders() {
        ObjectMetadata meta = respondedWith(
            Map.of("created_by", "kestra"),
            Map.of("server", "MinIO", "content-length", "10", "etag", "abc", "x-obs-request-id", "1")
        );
        assertThat(MetadataUtils.toRetrievedMetadata(meta), is(Map.of("createdBy", "kestra")));
    }

    /** A still-prefixed key is stripped (and decoded), not misclassified as a system header and dropped. */
    @Test
    void retrieved_stripsObsMetaPrefix() {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setMetadata(new LinkedHashMap<>(Map.of("x-obs-meta-created_by", "kestra")));
        assertThat(MetadataUtils.toRetrievedMetadata(meta), is(Map.of("createdBy", "kestra")));
    }

    /** MinIO's double prefix is also stripped. */
    @Test
    void retrieved_stripsDoublePrefix() {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setMetadata(new LinkedHashMap<>(Map.of("x-amz-meta-x-obs-meta-created_by", "kestra")));
        assertThat(MetadataUtils.toRetrievedMetadata(meta), is(Map.of("createdBy", "kestra")));
    }

    @Test
    void retrieved_nullYieldsEmpty() {
        assertThat(MetadataUtils.toRetrievedMetadata(null), is(Map.of()));
    }
}
