package io.kestra.storage.obs;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;

/**
 * Builds a configured {@link ObsClient} from an {@link ObsConfig}.
 *
 * <p>RunContext-free on purpose: a storage backend is a Micronaut-configured bean, not a task, so the
 * client is created from plain configuration values resolved at boot.
 */
public final class ObsClientFactory {

    /** Default OBS endpoint template; {@code %s} is the region identifier. */
    static final String DEFAULT_OBS_ENDPOINT_TEMPLATE = "https://obs.%s.myhuaweicloud.com";

    private ObsClientFactory() {
    }

    public static ObsClient of(ObsConfig config) {
        if (isBlank(config.getAccessKey()) || isBlank(config.getSecretKey())) {
            throw new IllegalArgumentException(
                "OBS storage requires both `accessKey` (AK) and `secretKey` (SK). OBS uses AK/SK request " +
                "signing; IAM token auth is not supported for object-storage operations."
            );
        }

        String endpoint = endpoint(config.getEndpoint(), config.getRegion());

        ObsConfiguration obsConfig = new ObsConfiguration();
        obsConfig.setEndPoint(endpoint);
        obsConfig.setPathStyle(Boolean.TRUE.equals(config.getPathStyleAccess()));
        obsConfig.setAuthType((config.getAuthType() != null ? config.getAuthType() : AuthType.OBS).toSdkEnum());
        obsConfig.setHttpsOnly(endpoint.startsWith("https://"));

        if (!isBlank(config.getSecurityToken())) {
            return new ObsClient(config.getAccessKey(), config.getSecretKey(), config.getSecurityToken(), obsConfig);
        }
        return new ObsClient(config.getAccessKey(), config.getSecretKey(), obsConfig);
    }

    /**
     * Resolves the OBS endpoint URL.
     *
     * <ol>
     *   <li>Explicit {@code endpoint} — returned as-is (trailing slash stripped).</li>
     *   <li>{@code region} — {@code https://obs.<region>.myhuaweicloud.com}.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}; OBS has no global fallback endpoint.</li>
     * </ol>
     */
    static String endpoint(String endpoint, String region) {
        if (!isBlank(endpoint)) {
            String e = endpoint.trim();
            return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
        }
        if (!isBlank(region)) {
            return String.format(DEFAULT_OBS_ENDPOINT_TEMPLATE, region.trim());
        }
        throw new IllegalArgumentException(
            "OBS storage requires either `endpoint` or `region` to be set. Unlike IAM, OBS has no global " +
            "fallback endpoint."
        );
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
