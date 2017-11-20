package me.desair.tus.server.upload;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Upload locking implementation using the file system file locking mechanism.
 * File locking can also apply to shared network drives. This way the framework supports clustering as long as
 * the upload storage directory is mounted as a shared (network) drive.
 *
 * File locks are also automatically released on application (JVM) shutdown. This means the file locking is not
 * persistent and prevents cleanup and stale lock issues.
 */
public class FileBasedLock implements UploadLock {

    private static final Logger log = LoggerFactory.getLogger(FileBasedLock.class);

    private final String uploadUri;
    private final FileChannel fileChannel;
    private final FileLock fileLock;
    private final Path lockPath;

    public FileBasedLock(final String uploadUri, final FileChannel fileChannel, final FileLock fileLock, final Path lockPath) throws IOException {
        Validate.notBlank(uploadUri, "The upload URI cannot be blank");
        Validate.notNull(fileChannel, "The FileChannel cannot be null");
        Validate.notNull(fileLock, "The FileLock cannot be null");
        Validate.notNull(lockPath, "The path to the lock cannot be null");
        this.uploadUri = uploadUri;
        this.fileChannel = fileChannel;
        this.fileLock = fileLock;
        this.lockPath = lockPath;
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
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            log.warn("Unable to release file lock for URI " + getUploadUri(), e);
        }
    }

    @Override
    public void close() throws Exception {
        release();
    }
}
