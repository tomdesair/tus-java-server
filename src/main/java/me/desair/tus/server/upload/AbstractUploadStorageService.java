package me.desair.tus.server.upload;

import org.apache.commons.lang3.Validate;

public abstract class AbstractUploadStorageService implements UploadStorageService {

    private UploadIdFactory idFactory;

    public AbstractUploadStorageService(final UploadIdFactory idFactory) {
        Validate.notNull(idFactory, "The UploadIdFactory cannot be null");
        this.idFactory = idFactory;
    }

    @Override
    public UploadInfo getUploadInfo(final String uploadUrl) {
        return getUploadInfo(idFactory.readUploadId(uploadUrl));
    }

    @Override
    public String getUploadURI() {
        return idFactory.getUploadURI();
    }
}
