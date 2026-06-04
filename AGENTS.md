# Kestra Storage — Huawei OBS

## What

A Kestra **internal-storage backend** that lets Kestra use a Huawei Cloud OBS bucket (or any
S3-compatible endpoint, e.g. MinIO) as its storage layer. Registers the `obs` storage type.

## Why

Teams running Kestra on Huawei Cloud need their internal storage (flow outputs, execution data,
namespace files) backed by OBS without relying on a generic S3 shim.

## How

### Architecture

Single-module plugin, group `io.kestra.storage`, package `io.kestra.storage.obs`. It implements
`io.kestra.core.storages.StorageInterface` and is selected via `kestra.storage.type: obs`.

This repo is **distinct from `plugin-huawei`**: that repo provides OBS *tasks* (Upload/Download/List…)
acting on OBS at flow runtime; this repo provides OBS as Kestra's *backing store*, configured at boot.
There is intentional, small duplication of the OBS client-construction routine between the two repos
(same pattern as `storage-azure` vs `plugin-azure`) — there is no shared library.

### Key classes

- `ObsStorage` — `implements StorageInterface, ObsConfig`, annotated `@Plugin.Id("obs")`. Maps the
  storage contract onto the OBS SDK (`ObsClient`). Uses zero-byte `prefix/` marker objects for
  directories, since OBS (like S3) has no native directories.
- `ObsConfig` — `@PluginProperty` getters bound from `kestra.storage.obs.*`.
- `ObsClientFactory` — RunContext-free factory building an `ObsClient` from `ObsConfig` (endpoint
  resolution, AK/SK, auth type, path-style).
- `ObsFileAttributes` — `FileAttributes` view over an OBS `ObjectMetadata`.
- `AuthType` — `OBS` / `V2` / `V4` wrapping the SDK enum; use `V2` for MinIO/S3-compatible endpoints.
- `MetadataUtils` — normalises user-metadata keys (strips `x-obs-meta-` / `x-amz-meta-` prefixes).

### Tests

`ObsStorageTest extends StorageTestSuite` runs the full storage contract from `io.kestra:tests`,
configured by `src/test/resources/application-test.yml` against MinIO (`authType: V2`).

```bash
docker compose up -d minio
./gradlew check
```

## Local rules

- OBS SDK: `com.huaweicloud:esdk-obs-java-bundle:3.25.5` (shaded, no BOM conflicts, slf4j excluded).
- `AuthType.V2` + `pathStyleAccess: true` are required for MinIO; real OBS uses `OBS` by default.
- The `@Plugin.Id("obs")` string is the public storage type name — do not change it lightly.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/configuration#storage
