package io.kestra.storage.obs;

import com.obs.services.model.AuthTypeEnum;

/**
 * OBS request-signing algorithm variants.
 *
 * <ul>
 *   <li>{@code OBS} — native Huawei OBS signing (default for real OBS endpoints).</li>
 *   <li>{@code V2} — S3 v2 HMAC signing, required for MinIO and other S3-compatible endpoints.</li>
 * </ul>
 */
public enum AuthType {
    OBS(AuthTypeEnum.OBS),
    V2(AuthTypeEnum.V2);

    private final AuthTypeEnum sdkValue;

    AuthType(AuthTypeEnum sdkValue) {
        this.sdkValue = sdkValue;
    }

    public AuthTypeEnum toSdkEnum() {
        return sdkValue;
    }
}
