package io.kestra.storage.obs;

import com.obs.services.model.ObjectMetadata;

import io.kestra.core.storages.FileAttributes;

import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.Optional;

@Value
@Builder
public class ObsFileAttributes implements FileAttributes {

    String fileName;
    ObjectMetadata metadata;
    boolean isDirectory;

    @Override
    public long getLastModifiedTime() {
        return Optional.ofNullable(metadata)
            .map(ObjectMetadata::getLastModified)
            .map(java.util.Date::getTime)
            .orElse(0L);
    }

    /**
     * OBS, like S3, keeps only a last-modified date per object; we expose it as the creation time too.
     */
    @Override
    public long getCreationTime() {
        return getLastModifiedTime();
    }

    @Override
    public FileType getType() {
        if (isDirectory || (fileName != null && fileName.endsWith("/"))) {
            return FileType.Directory;
        }
        return FileType.File;
    }

    @Override
    public long getSize() {
        return Optional.ofNullable(metadata)
            .map(ObjectMetadata::getContentLength)
            .orElse(0L);
    }

    @Override
    public Map<String, String> getMetadata() {
        return MetadataUtils.toRetrievedMetadata(metadata);
    }
}
