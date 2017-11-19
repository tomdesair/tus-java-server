package me.desair.tus.server.upload;

import me.desair.tus.server.TusFileUploadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class FileBasedLock implements UploadLock {

    private static final Logger log = LoggerFactory.getLogger(FileBasedLock.class);

    private final String uploadUri;
    private final FileChannel fileChannel;
    private final FileLock fileLock;

    public FileBasedLock(final String uploadUri, final FileChannel fileChannel, final FileLock fileLock) throws IOException {
        this.uploadUri = uploadUri;
        this.fileChannel = fileChannel;
        this.fileLock = fileLock;
    }

    @Override
    public String getUploadUri() {
        return uploadUri;
    }

    @Override
    public void release() {
        try {
            fileLock.release();
            fileChannel.close();
        } catch (IOException e) {
            log.warn("Unable to release file lock for URI " + getUploadUri(), e);
        }
    }
}
