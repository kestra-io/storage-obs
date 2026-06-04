package io.kestra.storage.obs;

import com.obs.services.model.ObjectMetadata;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Round-trips user metadata through OBS / S3-compatible endpoints.
 *
 * <p>Two problems are handled:
 * <ul>
 *   <li><b>Key case.</b> OBS and S3-compatible stores lowercase metadata header names, which would
 *       destroy mixed-case keys. We encode {@code camelCase} keys to {@code snake_case} on write and
 *       decode them back on read (same scheme as the AWS S3 storage plugin).</li>
 *   <li><b>Key prefixes.</b> OBS returns user metadata with an {@code x-obs-meta-} prefix; MinIO may
 *       double-prefix to {@code x-amz-meta-x-obs-meta-}. We strip these before decoding.</li>
 * </ul>
 */
final class MetadataUtils {

    private static final Pattern UPPERCASE = Pattern.compile("([A-Z])");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("_([a-z])");

    /**
     * System/HTTP response headers that the SDK mixes into the user-metadata map on a {@code getObject}
     * response. Standard HTTP headers contain a hyphen (filtered separately); these are the hyphen-free ones.
     */
    private static final Set<String> SYSTEM_HEADER_KEYS = Set.of(
        "server", "date", "etag", "connection", "vary", "expires", "age", "via", "allow", "location", "restore"
    );

    private MetadataUtils() {
    }

    /** Encodes {@code camelCase} keys to lowercase {@code snake_case} so they survive header lowercasing. */
    static Map<String, String> toStoredMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        metadata.forEach((key, value) ->
            out.put(UPPERCASE.matcher(key).replaceAll("_$1").toLowerCase(), value)
        );
        return out;
    }

    /**
     * Reads user metadata, strips any residual OBS/MinIO key prefix, and decodes {@code snake_case} back to
     * {@code camelCase}. {@link ObjectMetadata#getMetadata()} returns the user-metadata map (prefix already
     * stripped by the SDK); {@code getAllMetadata()} would also include system headers, so we avoid it.
     */
    static Map<String, String> toRetrievedMetadata(ObjectMetadata meta) {
        if (meta == null) {
            return Map.of();
        }
        Map<String, Object> user = meta.getMetadata();
        if (user == null || user.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        user.forEach((key, value) -> {
            if (value == null || isSystemHeader(key)) {
                return;
            }
            String stored = stripMetaPrefix(key);
            String decoded = WORD_SEPARATOR.matcher(stored).replaceAll(m -> m.group(1).toUpperCase());
            out.put(decoded, String.valueOf(value));
        });
        return out;
    }

    /**
     * True for response headers that are not user metadata. User-metadata keys are encoded to lowercase
     * {@code snake_case} (underscores, never hyphens), so any hyphenated key is a system/HTTP header.
     */
    private static boolean isSystemHeader(String key) {
        String lc = key.toLowerCase();
        return lc.contains("-") || SYSTEM_HEADER_KEYS.contains(lc);
    }

    private static String stripMetaPrefix(String key) {
        String lc = key.toLowerCase();
        if (lc.startsWith("x-amz-meta-x-obs-meta-")) {
            return key.substring("x-amz-meta-x-obs-meta-".length());
        }
        if (lc.startsWith("x-obs-meta-")) {
            return key.substring("x-obs-meta-".length());
        }
        if (lc.startsWith("x-amz-meta-")) {
            return key.substring("x-amz-meta-".length());
        }
        return key;
    }
}
