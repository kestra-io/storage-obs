package io.kestra.storage.obs;

import io.kestra.core.models.annotations.PluginProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Configuration contract for the OBS internal-storage backend.
 *
 * <p>Bound from {@code kestra.storage.obs.*} at boot. OBS uses AK/SK request signing, so both
 * {@link #getAccessKey()} and {@link #getSecretKey()} are mandatory; IAM token auth is not supported
 * for object-storage operations.
 */
public interface ObsConfig {

    @Schema(
        title = "The OBS bucket where to store internal objects."
    )
    @PluginProperty(group = "advanced")
    @NotNull
    @NotBlank
    String getBucket();

    @Schema(
        title = "Object path prefix within the OBS bucket to store data.",
        description = "If set, all objects will be stored under this prefix (e.g. `bucket/path/`)."
    )
    @PluginProperty(group = "advanced")
    String getPath();

    @Schema(
        title = "The OBS endpoint URL.",
        description = "Explicit endpoint, e.g. `https://obs.cn-north-4.myhuaweicloud.com`, or a MinIO/S3-compatible " +
            "URL such as `http://localhost:9000`. Takes precedence over `region`."
    )
    @PluginProperty(group = "connection")
    String getEndpoint();

    @Schema(
        title = "The Huawei Cloud region, used to derive the OBS endpoint when `endpoint` is not set.",
        description = "Resolves to `https://obs.<region>.myhuaweicloud.com`."
    )
    @PluginProperty(group = "connection")
    String getRegion();

    @Schema(
        title = "The OBS access key (AK)."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @NotBlank
    String getAccessKey();

    @Schema(
        title = "The OBS secret key (SK)."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @NotBlank
    String getSecretKey();

    @Schema(
        title = "Optional security token for temporary AK/SK credentials."
    )
    @PluginProperty(group = "connection")
    String getSecurityToken();

    @Schema(
        title = "Whether to use path-style bucket addressing.",
        description = "Required for MinIO and most S3-compatible endpoints. Defaults to `false`. When enabled, " +
            "the client also switches to S3 v2 request signing, since S3-compatible endpoints do not understand " +
            "OBS-native signing."
    )
    @PluginProperty(group = "advanced")
    Boolean getPathStyleAccess();
}
