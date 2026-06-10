package io.kestra.storage.obs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit coverage for endpoint/credential resolution — the production-config code path that the
 * MinIO contract suite never exercises (it always passes an explicit endpoint).
 */
class ObsClientFactoryTest {

    @Test
    void explicitEndpoint_isReturnedAsIs() {
        assertThat(ObsClientFactory.endpoint("https://obs.cn-north-4.myhuaweicloud.com", null),
            is("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void explicitEndpoint_trailingSlashStripped() {
        assertThat(ObsClientFactory.endpoint("http://localhost:9000/", "ignored-region"),
            is("http://localhost:9000"));
    }

    @Test
    void explicitEndpoint_takesPrecedenceOverRegion() {
        assertThat(ObsClientFactory.endpoint("http://localhost:9000", "cn-north-4"),
            is("http://localhost:9000"));
    }

    @Test
    void region_derivesObsEndpoint() {
        assertThat(ObsClientFactory.endpoint(null, "cn-north-4"),
            is("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_isTrimmed() {
        assertThat(ObsClientFactory.endpoint("  ", "  cn-north-4  "),
            is("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void neitherEndpointNorRegion_throws() {
        assertThrows(IllegalArgumentException.class, () -> ObsClientFactory.endpoint(null, null));
        assertThrows(IllegalArgumentException.class, () -> ObsClientFactory.endpoint("  ", "  "));
    }

    @Test
    void missingAccessKeyOrSecretKey_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> ObsClientFactory.of(ObsStorage.builder().endpoint("http://localhost:9000").build()));
        assertThrows(IllegalArgumentException.class,
            () -> ObsClientFactory.of(ObsStorage.builder().endpoint("http://localhost:9000").accessKey("ak").build()));
        assertThrows(IllegalArgumentException.class,
            () -> ObsClientFactory.of(ObsStorage.builder().endpoint("http://localhost:9000").secretKey("sk").build()));
    }
}
