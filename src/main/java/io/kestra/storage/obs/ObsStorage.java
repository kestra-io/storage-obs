package io.kestra.storage.obs;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.CopyObjectRequest;
import com.obs.services.model.DeleteObjectRequest;
import com.obs.services.model.DeleteObjectsRequest;
import com.obs.services.model.GetObjectRequest;
import com.obs.services.model.KeyAndVersion;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.PutObjectRequest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.storages.FileAttributes;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.storages.StorageObject;

import jakarta.annotation.Nullable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Jacksonized
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@Plugin
@Plugin.Id("obs")
public class ObsStorage implements StorageInterface, ObsConfig {
    private static final Logger log = LoggerFactory.getLogger(ObsStorage.class);

    /** OBS / S3 batch-delete caps each request at 1000 keys. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private String bucket;
    private String path;
    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String securityToken;
    private AuthType authType;
    private Boolean pathStyleAccess;

    @JsonIgnore
    @Getter(AccessLevel.PRIVATE)
    private ObsClient client;

    /** {@inheritDoc} **/
    @Override
    public void init() {
        this.client = ObsClientFactory.of(this);
    }

    /** {@inheritDoc} **/
    @Override
    public void close() {
        if (this.client != null) {
            try {
                this.client.close();
            } catch (IOException e) {
                log.warn("Failed to close ObsStorage client", e);
            }
        }
    }

    @Override
    public String getPath(String tenantId, URI uri) {
        String basePath = StorageInterface.super.getPath(tenantId, uri);
        if (path == null) {
            return basePath;
        }
        return path + (path.endsWith("/") ? basePath : "/" + basePath);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // GET
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream get(String tenantId, @Nullable String namespace, URI uri) throws IOException {
        return getWithMetadata(tenantId, namespace, uri).inputStream();
    }

    @Override
    public InputStream getInstanceResource(@Nullable String namespace, URI uri) throws IOException {
        return getObject(uri, getPath(uri)).inputStream();
    }

    @Override
    public StorageObject getWithMetadata(String tenantId, @Nullable String namespace, URI uri) throws IOException {
        return getObject(uri, getPath(tenantId, URI.create(uri.getPath())));
    }

    private StorageObject getObject(URI uri, String key) throws IOException {
        try {
            com.obs.services.model.ObsObject obsObject = this.client.getObject(new GetObjectRequest(bucket, key));
            return new StorageObject(
                MetadataUtils.toRetrievedMetadata(obsObject.getMetadata()),
                obsObject.getObjectContent()
            );
        } catch (ObsException e) {
            if (e.getResponseCode() == 404) {
                throw new FileNotFoundException(uri + " (File not found)");
            }
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // LIST
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public List<URI> allByPrefix(String tenantId, @Nullable String namespace, URI prefix, boolean includeDirectories) {
        String key = getPath(tenantId, prefix);
        List<URI> result = new ArrayList<>();
        for (com.obs.services.model.ObsObject o : listAllObjects(key)) {
            String objectKey = o.getObjectKey();
            if (objectKey.equals(key) || objectKey.equals(key + "/")) {
                continue;
            }
            if (!includeDirectories && objectKey.endsWith("/")) {
                continue;
            }
            result.add(URI.create("kestra://" + prefix.getPath() + objectKey.substring(key.length())));
        }
        return result;
    }

    @Override
    public List<FileAttributes> list(String tenantId, @Nullable String namespace, URI uri) throws IOException {
        String key = getPath(tenantId, uri);
        String prefix = key.endsWith("/") ? key : key + "/";

        List<FileAttributes> list = listChildren(prefix);
        if (list.isEmpty()) {
            // throws FileNotFoundException if the directory itself does not exist
            this.getAttributes(tenantId, namespace, uri);
        }
        return list;
    }

    @Override
    public List<FileAttributes> listInstanceResource(@Nullable String namespace, URI uri) throws IOException {
        String key = getPath(uri);
        String prefix = key.endsWith("/") ? key : key + "/";
        prefix = prefix.equals("/") ? "" : prefix;

        List<FileAttributes> list = listChildren(prefix);
        if (list.isEmpty()) {
            this.getInstanceAttributes(namespace, uri);
        }
        return list;
    }

    /**
     * Lists the immediate children of {@code prefix} (non-recursive). OBS / S3-compatible endpoints do not
     * return user metadata in a listing, so each child is fetched with a HEAD via {@link #getFileAttributes}.
     */
    private List<FileAttributes> listChildren(String prefix) throws IOException {
        List<FileAttributes> out = new ArrayList<>();
        for (String key : keysForPrefix(prefix, false, true)) {
            out.add(getFileAttributes(key));
        }
        return out;
    }

    /**
     * Collects object keys under {@code prefix}. When {@code recursive} is false, only direct children are
     * kept (mirrors the {@code Path.getParent() == null} filter used by the S3 storage backend). Directory
     * markers — zero-byte objects whose key ends in {@code "/"} — are kept when {@code includeDirectories}.
     */
    private List<String> keysForPrefix(String prefix, boolean recursive, boolean includeDirectories) {
        List<String> keys = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest req = new ListObjectsRequest(bucket);
            req.setPrefix(prefix);
            req.setMaxKeys(DELETE_BATCH_SIZE);
            if (marker != null) {
                req.setMarker(marker);
            }

            ObjectListing listing = this.client.listObjects(req);
            for (com.obs.services.model.ObsObject o : listing.getObjects()) {
                String key = o.getObjectKey();
                String relativeKey = key.substring(prefix.length());
                if (relativeKey.isEmpty() || key.equals(prefix) || relativeKey.equals("/")) {
                    continue;
                }
                if (!recursive && Path.of(relativeKey).getParent() != null) {
                    continue;
                }
                if (!includeDirectories && relativeKey.endsWith("/")) {
                    continue;
                }
                keys.add(key);
            }

            if (listing.isTruncated()) {
                marker = listing.getNextMarker();
            } else {
                break;
            }
        } while (true);
        return keys;
    }

    /**
     * Lists every object under {@code prefix} recursively (no delimiter), paginating until exhausted.
     * Directory-marker objects (keys ending in {@code "/"}) — including the {@code prefix} marker itself —
     * are included, since callers (delete/move) need the full object set.
     */
    private List<com.obs.services.model.ObsObject> listAllObjects(String prefix) {
        List<com.obs.services.model.ObsObject> out = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest req = new ListObjectsRequest(bucket);
            req.setPrefix(prefix);
            req.setMaxKeys(DELETE_BATCH_SIZE);
            if (marker != null) {
                req.setMarker(marker);
            }

            ObjectListing listing = this.client.listObjects(req);
            out.addAll(listing.getObjects());

            if (listing.isTruncated()) {
                marker = listing.getNextMarker();
            } else {
                break;
            }
        } while (true);
        return out;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // EXISTS / ATTRIBUTES
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public boolean exists(String tenantId, @Nullable String namespace, URI uri) {
        return exists(getPath(tenantId, URI.create(uri.getPath())));
    }

    @Override
    public boolean existsInstanceResource(@Nullable String namespace, URI uri) {
        return exists(getPath(URI.create(uri.getPath())));
    }

    private boolean exists(String key) {
        try {
            this.client.getObjectMetadata(bucket, key);
            return true;
        } catch (ObsException e) {
            // 404, or MinIO's 400 when probing a trailing-slash key, mean "absent". Any other code
            // (403, 5xx, ...) is a real error — propagate it rather than reporting "not found", so it
            // isn't silently swallowed here or mistaken for "directory not yet created" by mkdirs.
            if (e.getResponseCode() == 404 || e.getResponseCode() == 400) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public FileAttributes getAttributes(String tenantId, @Nullable String namespace, URI uri) throws IOException {
        return getAttributes(getPath(tenantId, uri));
    }

    @Override
    public FileAttributes getInstanceAttributes(@Nullable String namespace, URI uri) throws IOException {
        return getAttributes(getPath(uri));
    }

    /**
     * Resolves attributes for a key, falling back to the directory-marker form ({@code key + "/"}) when the
     * plain key is absent — so a directory can be queried without a trailing slash.
     */
    private FileAttributes getAttributes(String key) throws IOException {
        try {
            return getFileAttributes(key);
        } catch (FileNotFoundException e) {
            if (key.endsWith("/")) {
                throw e;
            }
            return getFileAttributes(key + "/");
        }
    }

    private FileAttributes getFileAttributes(String key) throws IOException {
        try {
            ObjectMetadata metadata = this.client.getObjectMetadata(bucket, key);
            return ObsFileAttributes.builder()
                .fileName(Optional.ofNullable(Path.of(key).getFileName()).map(Path::toString).orElse("/"))
                .metadata(metadata)
                .isDirectory(key.endsWith("/"))
                .build();
        } catch (ObsException e) {
            // MinIO returns 400 (not 404) when probing a non-existent trailing-slash key; treat both as not-found.
            if (e.getResponseCode() == 404 || e.getResponseCode() == 400) {
                throw new FileNotFoundException("%s (File not found)".formatted(key));
            }
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // PUT
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public URI put(String tenantId, @Nullable String namespace, URI uri, StorageObject storageObject) throws IOException {
        return put(uri, getPath(tenantId, uri), storageObject);
    }

    @Override
    public URI putInstanceResource(@Nullable String namespace, URI uri, StorageObject storageObject) throws IOException {
        return put(uri, getPath(uri), storageObject);
    }

    private URI put(URI uri, String key, StorageObject storageObject) throws IOException {
        mkdirs(key);

        // Buffer to a temp file so the upload carries an explicit Content-Length — OBS and MinIO both
        // reject stream uploads of unknown length with HTTP 411.
        File tmp = File.createTempFile("obs-storage-", ".tmp");
        try {
            try (InputStream in = storageObject.inputStream()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(tmp.length());
            MetadataUtils.toStoredMetadata(storageObject.metadata()).forEach(metadata::addUserMetadata);

            PutObjectRequest req = new PutObjectRequest(bucket, key, tmp);
            req.setMetadata(metadata);
            this.client.putObject(req);

            return URI.create("kestra://" + uri.getPath());
        } catch (ObsException e) {
            throw new IOException(e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Ensures a zero-byte marker exists for every ancestor directory of {@code key}.
     *
     * <p>Guarded by a HEAD against the store (the deepest directory marker) rather than an in-memory
     * cache: this stays correct after a directory is deleted and re-created, and keeps no unbounded
     * per-instance state. Mirrors the {@code storage-s3} backend.
     */
    private void mkdirs(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }

        String dirPath = key.endsWith("/") ? key : key.substring(0, key.lastIndexOf('/') + 1);
        if (dirPath.isEmpty() || exists(dirPath)) {
            return;
        }

        String[] parts = dirPath.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            currentPath.append(part).append("/");
            String dir = currentPath.toString();
            try {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(0L);
                PutObjectRequest req = new PutObjectRequest(bucket, dir, new ByteArrayInputStream(new byte[0]));
                req.setMetadata(metadata);
                this.client.putObject(req);
            } catch (ObsException e) {
                log.warn("Failed to create directory: {}", dir, e);
            }
        }
    }

    @Override
    public URI createDirectory(String tenantId, @Nullable String namespace, URI uri) {
        return createDirectory(uri, getPath(tenantId, uri));
    }

    @Override
    public URI createInstanceDirectory(String namespace, URI uri) {
        return createDirectory(uri, getPath(uri));
    }

    private URI createDirectory(URI uri, String key) {
        if (!key.endsWith("/")) {
            key = key + "/";
        }
        mkdirs(key);
        return createUri(uri.getPath());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // DELETE / MOVE
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public boolean delete(String tenantId, @Nullable String namespace, URI uri) throws IOException {
        FileAttributes fileAttributes;
        try {
            fileAttributes = getAttributes(tenantId, namespace, uri);
        } catch (FileNotFoundException e) {
            return false;
        }

        if (fileAttributes.getType() == FileAttributes.FileType.Directory) {
            return !this.deleteByPrefix(
                tenantId,
                namespace,
                uri.getPath().endsWith("/") ? uri : URI.create(uri.getPath() + "/")
            ).isEmpty();
        }

        this.client.deleteObject(new DeleteObjectRequest(bucket, getPath(tenantId, uri)));
        return true;
    }

    @Override
    public boolean deleteInstanceResource(@Nullable String namespace, URI uri) throws IOException {
        FileAttributes fileAttributes;
        try {
            fileAttributes = getInstanceAttributes(namespace, uri);
        } catch (FileNotFoundException e) {
            return false;
        }

        if (fileAttributes.getType() == FileAttributes.FileType.Directory) {
            return !this.deleteByPrefix(
                uri.getPath().endsWith("/") ? uri : URI.create(uri.getPath() + "/")
            ).isEmpty();
        }

        this.client.deleteObject(new DeleteObjectRequest(bucket, getPath(uri)));
        return true;
    }

    @Override
    public URI move(String tenantId, @Nullable String namespace, URI from, URI to) throws IOException {
        String fromKey = getPath(tenantId, from);

        if (getAttributes(tenantId, namespace, from).getType() == FileAttributes.FileType.File) {
            copy(fromKey, getPath(tenantId, to));
            this.client.deleteObject(new DeleteObjectRequest(bucket, fromKey));
        } else {
            String prefix = fromKey.endsWith("/") ? fromKey : fromKey + "/";
            String toKey = getPath(tenantId, to);
            for (com.obs.services.model.ObsObject o : listAllObjects(prefix)) {
                String source = o.getObjectKey();
                String target = toKey + "/" + source.substring(prefix.length());
                copy(source, target);
                this.client.deleteObject(new DeleteObjectRequest(bucket, source));
            }
        }
        return createUri(to.getPath());
    }

    private void copy(String source, String target) {
        this.client.copyObject(new CopyObjectRequest(bucket, source, bucket, target));
    }

    @Override
    public List<URI> deleteByPrefix(String tenantId, @Nullable String namespace, URI storagePrefix) throws IOException {
        return deleteByPrefix(storagePrefix, getPath(tenantId, storagePrefix));
    }

    private List<URI> deleteByPrefix(URI storagePrefix) throws IOException {
        return deleteByPrefix(storagePrefix, getPath(storagePrefix));
    }

    private List<URI> deleteByPrefix(URI storagePrefix, String prefix) throws IOException {
        try {
            List<String> keys = listAllObjects(prefix).stream()
                .map(com.obs.services.model.ObsObject::getObjectKey)
                .toList();

            if (keys.isEmpty()) {
                return List.of();
            }

            for (int i = 0; i < keys.size(); i += DELETE_BATCH_SIZE) {
                List<String> chunk = keys.subList(i, Math.min(i + DELETE_BATCH_SIZE, keys.size()));
                KeyAndVersion[] kvs = chunk.stream()
                    .map(KeyAndVersion::new)
                    .toArray(KeyAndVersion[]::new);
                this.client.deleteObjects(new DeleteObjectsRequest(bucket, false, kvs));
            }

            // Relativize by stripping the requested prefix by length and re-prepending the original
            // kestra-relative path — same scheme as allByPrefix/list. Handles a configured `path`
            // prefix and the tenant segment without parsing them back out of the key.
            String base = storagePrefix.getPath();
            List<URI> deleted = new ArrayList<>(keys.size());
            for (String key : keys) {
                String relative = (base + key.substring(prefix.length())).replaceAll("/$", "");
                deleted.add(URI.create("kestra://" + relative));
            }
            return deleted;
        } catch (ObsException e) {
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------------------------------

    private static URI createUri(String key) {
        return URI.create("kestra://%s".formatted(key));
    }
}
