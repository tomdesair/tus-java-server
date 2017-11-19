package me.desair.tus.server.upload;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.*;

/**
 * Implementation of {@link UploadStorageService} that implements storage on disk
 */
public class DiskUploadStorageService implements UploadStorageService, UploadLockingService {

    private Path storagePath;
    private Long maxUploadSize = null;
    private UploadIdFactory idFactory;

    public DiskUploadStorageService(final UploadIdFactory idFactory, final String storagePath) {
        Validate.notNull(idFactory, "The UploadIdFactory cannot be null");
        Validate.notBlank(storagePath, "The storage path cannot be blank");
        this.idFactory = idFactory;
        this.storagePath = Paths.get(storagePath);
    }

    @Override
    public void setMaxUploadSize(final Long maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }

    @Override
    public Long getMaxUploadSize() {
        return maxUploadSize;
    }

    @Override
    public UploadInfo getUploadInfo(final String uploadUrl) throws IOException {
        return getUploadInfo(idFactory.readUploadId(uploadUrl));
    }

    @Override
    public String getUploadURI() {
        return idFactory.getUploadURI();
    }

    @Override
    public UploadInfo create(final UploadInfo info) throws IOException {
        UUID id = createNewId();

        createUploadDirectory(id);

        Path bytesPath = getBytesPath(id);

        //Create an empty file to storage the bytes of this upload
        Files.createFile(bytesPath);

        //Set starting values
        info.setId(id);
        info.setOffset(0l);

        update(info);

        return info;
    }

    @Override
    public void update(final UploadInfo uploadInfo) throws IOException {
        Path infoPath = getInfoPath(uploadInfo.getId());
        Utils.writeSerializable(uploadInfo, infoPath);
    }

    @Override
    public UploadInfo getUploadInfo(final UUID id) throws IOException {
        Path infoPath = getInfoPath(id);

        return Utils.readSerializable(infoPath, UploadInfo.class);
    }

    @Override
    public long getMaxSizeInBytes() {
        return maxUploadSize == null ? 0 : maxUploadSize;
    }

    @Override
    public UploadInfo append(final UUID id, final Long offset, final InputStream inputStream) throws IOException {
        UploadInfo info = null;
        Path bytesPath = getBytesPath(id);

        if(bytesPath != null) {
            long max = getMaxSizeInBytes() > 0 ? getMaxSizeInBytes() : Long.MAX_VALUE;
            long transferred = 0;
            info = getUploadInfo(id);

            FileLock lock = null;
            try(ReadableByteChannel uploadedBytes = Channels.newChannel(inputStream);
                FileChannel file = FileChannel.open(bytesPath, WRITE)) {
                lock = file.lock();

                //Validate that the given offset is at the end of the file
                if(!offset.equals(file.size())) {
                    throw new IOException("You can only append to the end of an upload");
                }

                //write all bytes in the channel up to the configured maximum
                transferred = file.transferFrom(uploadedBytes, offset, max - offset);
            } finally {
                if(lock != null) {
                    lock.release();
                }
            }

            info.setOffset(offset + transferred);
            update(info);
        }

        return info;
    }

    @Override
    public InputStream getUploadedBytes(final String uploadURI) throws IOException {
        InputStream inputStream = null;

        UUID id = idFactory.readUploadId(uploadURI);
        Path bytesPath = getBytesPath(id);

        //If bytesPath is not null, we know this is a valid Upload URI
        if(bytesPath != null) {
            inputStream = Channels.newInputStream(FileChannel.open(bytesPath, READ));
        }

        return inputStream;
    }

    @Override
    public UploadLock lockUploadByUri(final String requestURI) throws TusException {
        String message = "The upload " + requestURI + " is already locked";
        UUID id = idFactory.readUploadId(requestURI);

        UploadLock lock = null;
        FileChannel fileChannel = null;

        try {
            Path lockPath = getLockPath(id);
            //If lockPath is not null, we know this is a valid Upload URI
            if (lockPath != null) {

                //Try to acquire a lock
                //TODO we might need to find a way to remove this lock on upload deletion
                fileChannel = FileChannel.open(lockPath, CREATE, WRITE);
                FileLock fileLock = fileChannel.tryLock();

                if(fileLock == null) {
                    fileChannel.close();
                    throw new UploadAlreadyLockedException(message);
                } else {
                    lock = new FileBasedLock(requestURI, fileChannel, fileLock);
                }
            }

        } catch (IOException | OverlappingFileLockException e) {
            if(fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException e1) {
                    //Should not happen
                }
            }

            throw new UploadAlreadyLockedException(message);
        }

        return lock;
    }

    private Path getUploadDirectory(final UUID id) throws IOException {
        if(id == null) {
            return null;
        } else {
            return storagePath.resolve(id.toString());
        }
    }

    private Path createUploadDirectory(final UUID id) throws IOException {
        return Files.createDirectories(getUploadDirectory(id));
    }

    private Path getBytesPath(final UUID id) throws IOException {
        return getPathInUploadDir(id, Objects.toString(id));
    }

    private Path getLockPath(final UUID id) throws IOException {
        return getPathInUploadDir(id, "lock");
    }

    private Path getInfoPath(final UUID id) throws IOException {
        return getPathInUploadDir(id, "info");
    }

    private Path getPathInUploadDir(final UUID id, final String fileName) throws IOException {
        //Get the upload directory
        Path uploadDir = getUploadDirectory(id);
        if(uploadDir != null && Files.exists(uploadDir)) {
            return uploadDir.resolve(fileName);
        } else {
            return null;
        }
    }

    private synchronized UUID createNewId() throws IOException {
        UUID id;
        do {
            id = idFactory.createId();
            //For extra safety, double check that this ID is not in use yet
        } while(getUploadInfo(id) == null);
        return id;
    }
}
