package me.desair.tus.server.upload;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Implementation of {@link UploadStorageService} that implements storage on disk
 */
public class DiskUploadStorageService implements UploadStorageService {

    private Path storagePath;

    public DiskUploadStorageService(final String storagePath) {
        this.storagePath = Paths.get(storagePath);
    }

    @Override
    public UploadInfo getUploadInfo(final UUID id) {
        //TODO
        return null;
    }

    @Override
    public long getMaxSizeInBytes() {
        //TODO
        return 0;
    }

    @Override
    public UploadInfo append(final UUID id, final Long offset, final InputStream inputStream) {
        //TODO
        return null;
    }

    @Override
    public boolean create(final UploadInfo info) {
        //TODO
        return true;
    }
}
