package io.kestra.storage.obs;

import com.obs.services.model.ObjectMetadata;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Round-trips user metadata through OBS / S3-compatible endpoints.
 *
 * <p>Two problems are handled:
 * <ul>
 *   <li><b>Key case.</b> OBS and S3-compatible stores lowercase metadata header names, which would
 *       destroy mixed-case keys. We encode {@code camelCase} keys to {@code snake_case} on write and
 *       decode them back on read. The encoding is reversible: an uppercase letter {@code X} becomes
 *       {@code _x}, and a literal underscore is escaped to {@code __}, so keys that already contain
 *       underscores survive the round-trip.</li>
 *   <li><b>Key prefixes.</b> OBS returns user metadata with an {@code x-obs-meta-} prefix; MinIO may
 *       double-prefix to {@code x-amz-meta-x-obs-meta-}. We strip these before deciding whether a key
 *       is user metadata or a system header.</li>
 * </ul>
 */
final class MetadataUtils {

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
        metadata.forEach((key, value) -> out.put(encodeKey(key), value));
        return out;
    }

    /**
     * Reads user metadata, strips any residual OBS/MinIO key prefix, drops system headers, and decodes
     * {@code snake_case} back to {@code camelCase}. {@link ObjectMetadata#getMetadata()} returns the response
     * headers — both user metadata (prefix usually already stripped by the SDK) and system headers — so the
     * prefix is stripped <em>before</em> the system-header check, otherwise a still-prefixed key (which
     * contains hyphens) would be misclassified as a system header and dropped.
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
            if (value == null) {
                return;
            }
            String stripped = stripMetaPrefix(key);
            if (isSystemHeader(stripped)) {
                return;
            }
            out.put(decodeKey(stripped), String.valueOf(value));
        });
        return out;
    }

    /**
     * Reversible {@code camelCase} -> lowercase {@code snake_case} encoding: each uppercase letter becomes
     * {@code _} + its lowercase form, and a literal {@code _} is escaped to {@code __}.
     */
    private static String encodeKey(String key) {
        StringBuilder out = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_') {
                out.append("__");
            } else if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Inverse of {@link #encodeKey}: {@code __} -> {@code _}, {@code _x} -> {@code X}. */
    private static String decodeKey(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' && i + 1 < key.length()) {
                char next = key.charAt(i + 1);
                if (next == '_') {
                    out.append('_');
                } else {
                    out.append(Character.toUpperCase(next));
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
