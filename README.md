<p align="center">
  <a href="https://www.kestra.io">
    <img width="460" src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

# Kestra Storage — Huawei OBS

> A storage plugin to leverage a Huawei Cloud OBS bucket as Kestra's internal storage layer.

This plugin registers the `obs` storage type. Once installed, point Kestra at an OBS bucket (or any
S3-compatible endpoint such as MinIO) to store internal objects — flow outputs, execution data and
namespace files.

## Configuration

```yaml
kestra:
  storage:
    type: obs
    obs:
      bucket: my-kestra-bucket
      # Either set an explicit endpoint...
      endpoint: https://obs.cn-north-4.myhuaweicloud.com
      # ...or a region, from which the endpoint is derived
      # region: cn-north-4
      accessKey: "<AK>"
      secretKey: "<SK>"
      # Optional:
      # path: kestra/             # object prefix within the bucket
      # securityToken: "<token>"  # for temporary AK/SK credentials
      # pathStyleAccess: false    # true for MinIO/S3-compatible endpoints
```

| Property          | Required | Default | Description                                                              |
|-------------------|----------|---------|--------------------------------------------------------------------------|
| `bucket`          | yes      |         | OBS bucket holding internal objects.                                     |
| `accessKey`       | yes      |         | OBS access key (AK).                                                      |
| `secretKey`       | yes      |         | OBS secret key (SK).                                                      |
| `endpoint`        | one of   |         | Explicit endpoint URL. Takes precedence over `region`.                   |
| `region`          | one of   |         | Region used to derive `https://obs.<region>.myhuaweicloud.com`.          |
| `path`            | no       |         | Object prefix within the bucket.                                         |
| `securityToken`   | no       |         | Security token for temporary AK/SK credentials.                          |
| `pathStyleAccess` | no       | `false` | Path-style addressing; required for MinIO and most S3-compatible stores. Implies S3 v2 signing. |

## Development

Requires Java 21.

### Tests

The contract test suite (`ObsStorageTest`) runs against a MinIO endpoint using `pathStyleAccess: true`.

```bash
docker compose up -d minio
./gradlew check
```

CI starts the same backend via `docker-compose-ci.yml` (see `.github/setup-unit.sh`).

## Documentation

- Full plugin documentation: https://kestra.io/plugins
- Plugin developer guide: https://kestra.io/docs/plugin-developer-guide

## License

Apache 2.0 © [Kestra Technologies](https://kestra.io)
