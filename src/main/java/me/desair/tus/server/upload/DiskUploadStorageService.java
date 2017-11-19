package me.desair.tus.server.upload;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Implementation of {@link UploadStorageService} that implements storage on disk
 */
public class DiskUploadStorageService extends AbstractUploadStorageService {

    private Path storagePath;

    public DiskUploadStorageService(final UploadIdFactory idFactory, final String storagePath) {
        super(idFactory);
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
        //TODO only keep writing while under max
        return null;
    }

    @Override
    public UploadInfo create(final UploadInfo info) {
        //TODO
        return null;
    }

    @Override
    public void update(final UploadInfo uploadInfo) {
        //TODO
    }

    @Override
    public OutputStream getUploadedBytes(final String uploadURI) {
        //TODO
        return null;
    }
}
