package me.desair.tus.server.upload.disk;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.InvalidUploadOffsetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.concatenation.VirtualConcatenationService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of {@link UploadStorageService} that implements storage on disk. */
public class DiskStorageService extends AbstractDiskBasedService implements UploadStorageService {

  private static final Logger log = LoggerFactory.getLogger(DiskStorageService.class);

  private static final String UPLOAD_SUB_DIRECTORY = "uploads";
  private static final String INFO_FILE = "info";
  private static final String DATA_FILE = "data";

  private Long maxUploadSize = null;
  private Long uploadExpirationPeriod = null;
  private UploadIdFactory idFactory;
  private UploadConcatenationService uploadConcatenationService;
  private boolean isUploadDeduplicationEnabled = false;

  public DiskStorageService(String storagePath) {
    super(storagePath + File.separator + UPLOAD_SUB_DIRECTORY);
    setUploadConcatenationService(new VirtualConcatenationService(this));
  }

  public DiskStorageService(UploadIdFactory idFactory, String storagePath) {
    this(storagePath);
    java.util.Objects.requireNonNull(idFactory, "The IdFactory cannot be null");
    this.idFactory = idFactory;
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    java.util.Objects.requireNonNull(idFactory, "The IdFactory cannot be null");
    this.idFactory = idFactory;
  }

  @Override
  public void setMaxUploadSize(Long maxUploadSize) {
    this.maxUploadSize = (maxUploadSize != null && maxUploadSize > 0 ? maxUploadSize : 0);
  }

  @Override
  public long getMaxUploadSize() {
    return maxUploadSize == null ? 0 : maxUploadSize;
  }

  @Override
  public void setUploadDeduplicationEnabled(boolean enabled) {
    this.isUploadDeduplicationEnabled = enabled;
  }

  @Override
  public boolean isUploadDeduplicationEnabled() {
    return this.isUploadDeduplicationEnabled;
  }

  @Override
  public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    if (checksum == null || algorithm == null) {
      return null;
    }
    Path checksumFile = getChecksumPath(checksum, algorithm);
    if (Files.exists(checksumFile)) {
      String uploadIdStr =
          new String(Files.readAllBytes(checksumFile), StandardCharsets.UTF_8).trim();
      UploadId id = new UploadId(uploadIdStr);
      UploadInfo info = getUploadInfo(id);
      if (info == null) {
        Files.deleteIfExists(checksumFile);
        return null;
      }
      Path bytesPath = null;
      try {
        bytesPath = getPathInUploadDir(id, DATA_FILE);
      } catch (UploadNotFoundException e) {
        // file doesn't exist
      }
      if (bytesPath == null || !Files.exists(bytesPath)) {
        Files.deleteIfExists(checksumFile);
        return null;
      }
      return info;
    }
    return null;
  }

  @Override
  public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
    UploadInfo uploadInfo = getUploadInfo(idFactory.readUploadId(uploadUrl));
    if (uploadInfo == null || !Objects.equals(uploadInfo.getOwnerKey(), ownerKey)) {
      return null;
    } else {
      return uploadInfo;
    }
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    try {
      Path infoPath = getInfoPath(id);
      if (infoPath == null || !Files.exists(infoPath)) {
        return null;
      }
      return Utils.readSerializable(infoPath, UploadInfo.class);
    } catch (UploadNotFoundException e) {
      return null;
    }
  }

  @Override
  public String getUploadUri() {
    return idFactory.getUploadUri();
  }

  @Override
  public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
    UploadId id = createNewId();

    createUploadDirectory(id);

    try {
      Path bytesPath = getBytesPath(id);

      // Create an empty file to storage the bytes of this upload
      Files.createFile(bytesPath);

      // Set starting values
      info.setId(id);
      info.setOffset(0L);
      info.setOwnerKey(ownerKey);

      update(info);

      return info;
    } catch (UploadNotFoundException e) {
      // Normally this cannot happen
      log.error("Unable to create UploadInfo because of an upload not found exception", e);
      return null;
    }
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo != null) {
      if (uploadInfo.getDuplicatesUploadId() != null) {
        // Delete the child's own data file if it exists
        try {
          Path childDataPath = getPathInUploadDir(uploadInfo.getId(), DATA_FILE);
          Files.deleteIfExists(childDataPath);
        } catch (UploadNotFoundException e) {
          // It doesn't exist yet, which is fine
        }

        // Retrieve the parent UploadInfo
        UploadId parentId = uploadInfo.getDuplicatesUploadId();
        UploadInfo parentInfo = getUploadInfo(parentId);
        if (parentInfo != null) {
          Path parentBytesPath = null;
          try {
            parentBytesPath = getPathInUploadDir(parentId, DATA_FILE);
          } catch (UploadNotFoundException e) {
            // Parent file is not found
          }

          if (parentBytesPath != null && Files.exists(parentBytesPath)) {
            // Ensure parent's expiration timestamp is >= child's expiration timestamp
            Long childExpire = uploadInfo.getExpirationTimestamp();
            Long parentExpire = parentInfo.getExpirationTimestamp();
            if (childExpire == null) {
              if (parentExpire != null) {
                parentInfo.setExpirationTimestamp(null);
                Path parentInfoPath = getInfoPath(parentId);
                Utils.writeSerializable(parentInfo, parentInfoPath);
              }
            } else {
              if (parentExpire != null && parentExpire < childExpire) {
                parentInfo.setExpirationTimestamp(childExpire);
                Path parentInfoPath = getInfoPath(parentId);
                Utils.writeSerializable(parentInfo, parentInfoPath);
              }
            }
          }
        }
      } else if (isUploadDeduplicationEnabled()
          && !uploadInfo.isUploadInProgress()
          && uploadInfo.getChecksum() != null
          && uploadInfo.getChecksumAlgorithm() != null) {
        // Index the checksum
        Path checksumFile =
            getChecksumPath(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm());
        Files.createDirectories(checksumFile.getParent());
        Files.write(checksumFile, uploadInfo.getId().toString().getBytes(StandardCharsets.UTF_8));
      }

      Path infoPath = getInfoPath(uploadInfo.getId());
      Utils.writeSerializable(uploadInfo, infoPath);
    }
  }

  @Override
  public UploadInfo append(UploadInfo info, InputStream inputStream)
      throws IOException, TusException {
    if (info != null) {
      Path bytesPath = getBytesPath(info.getId());

      long max = getMaxUploadSize() > 0 ? getMaxUploadSize() : Long.MAX_VALUE;
      long transferred = 0;
      Long offset = info.getOffset();
      long newOffset = offset;

      try (ReadableByteChannel uploadedBytes = Channels.newChannel(inputStream);
          FileChannel file = FileChannel.open(bytesPath, WRITE)) {

        try {
          // Lock will be released when the channel closes
          file.lock();

          // Validate that the given offset is at the end of the file
          if (!offset.equals(file.size())) {
            throw new InvalidUploadOffsetException(
                "The upload offset does not correspond to the written"
                    + " bytes. You can only append to the end of an upload");
          }

          // write all bytes in the channel up to the configured maximum
          transferred = file.transferFrom(uploadedBytes, offset, max - offset);
          file.force(true);
          newOffset = offset + transferred;

        } catch (Exception ex) {
          // An error occurred, try to write as much data as possible
          newOffset = writeAsMuchAsPossible(file);
          throw ex;
        }

      } finally {
        info.setOffset(newOffset);
        update(info);
      }
    }

    return info;
  }

  @Override
  public void removeLastNumberOfBytes(UploadInfo info, long byteCount)
      throws UploadNotFoundException, IOException {

    if (info != null && byteCount > 0) {
      Path bytesPath = getBytesPath(info.getId());

      try (FileChannel file = FileChannel.open(bytesPath, WRITE)) {

        // Lock will be released when the channel closes
        file.lock();

        file.truncate(file.size() - byteCount);
        file.force(true);

        info.setOffset(file.size());
        update(info);
      }
    }
  }

  @Override
  public void terminateUpload(UploadInfo info) throws UploadNotFoundException, IOException {
    if (info != null) {
      if (info.getDuplicatesUploadId() == null
          && info.getChecksum() != null
          && info.getChecksumAlgorithm() != null) {
        Path checksumFile = getChecksumPath(info.getChecksum(), info.getChecksumAlgorithm());
        Files.deleteIfExists(checksumFile);
      }
      Path uploadPath = getPathInStorageDirectory(info.getId());
      FileUtils.deleteDirectory(uploadPath.toFile());
    }
  }

  @Override
  public Long getUploadExpirationPeriod() {
    return uploadExpirationPeriod;
  }

  @Override
  public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {
    this.uploadExpirationPeriod = uploadExpirationPeriod;
  }

  @Override
  public void setUploadConcatenationService(UploadConcatenationService concatenationService) {
    java.util.Objects.requireNonNull(concatenationService);
    this.uploadConcatenationService = concatenationService;
  }

  @Override
  public UploadConcatenationService getUploadConcatenationService() {
    return uploadConcatenationService;
  }

  @Override
  public InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, UploadNotFoundException {

    UploadId id = idFactory.readUploadId(uploadUri);

    UploadInfo uploadInfo = getUploadInfo(id);
    if (uploadInfo == null || !Objects.equals(uploadInfo.getOwnerKey(), ownerKey)) {
      throw new UploadNotFoundException(
          "The upload with id " + id + " could not be found for owner " + ownerKey);
    } else {
      return getUploadedBytes(id);
    }
  }

  @Override
  public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
    InputStream inputStream = null;
    UploadInfo uploadInfo = getUploadInfo(id);
    if (uploadInfo == null) {
      throw new UploadNotFoundException("The upload with id " + id + " was not found.");
    }

    if (uploadInfo.getDuplicatesUploadId() != null) {
      return getUploadedBytes(uploadInfo.getDuplicatesUploadId());
    }

    if (UploadType.CONCATENATED.equals(uploadInfo.getUploadType())
        && uploadConcatenationService != null) {
      inputStream = uploadConcatenationService.getConcatenatedBytes(uploadInfo);

    } else {
      Path bytesPath = getBytesPath(id);
      // If bytesPath is not null, we know this is a valid Upload URI
      if (bytesPath != null) {
        if (!Files.exists(bytesPath)) {
          throw new UploadNotFoundException(
              "The upload bytes for id " + id + " could not be found.");
        }
        inputStream = Channels.newInputStream(FileChannel.open(bytesPath, READ));
      }
    }

    return inputStream;
  }

  @Override
  public void copyUploadTo(UploadInfo info, OutputStream outputStream)
      throws UploadNotFoundException, IOException {

    List<UploadInfo> uploads = getUploads(info);

    try (WritableByteChannel outputChannel = Channels.newChannel(outputStream)) {

      for (UploadInfo upload : uploads) {
        if (upload == null) {
          log.warn("We cannot copy the bytes of an upload that does not exist");

        } else if (upload.isUploadInProgress()) {
          log.warn(
              "We cannot copy the bytes of upload {} because it is still in progress",
              upload.getId());

        } else {
          UploadId readId =
              upload.getDuplicatesUploadId() != null
                  ? upload.getDuplicatesUploadId()
                  : upload.getId();
          Path bytesPath = getBytesPath(readId);
          if (!Files.exists(bytesPath)) {
            throw new UploadNotFoundException(
                "The upload bytes for id " + readId + " could not be found.");
          }
          try (FileChannel file = FileChannel.open(bytesPath, READ)) {
            // Efficiently copy the bytes to the output stream
            file.transferTo(0, upload.getLength(), outputChannel);
          }
        }
      }
    }
  }

  @Override
  public void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException {
    try (DirectoryStream<Path> expiredUploadsStream =
        Files.newDirectoryStream(
            getStoragePath(), new ExpiredUploadFilter(this, uploadLockingService))) {

      for (Path path : expiredUploadsStream) {
        FileUtils.deleteDirectory(path.toFile());
      }
    }
  }

  private List<UploadInfo> getUploads(UploadInfo info) throws IOException, UploadNotFoundException {
    List<UploadInfo> uploads;

    if (info != null
        && UploadType.CONCATENATED.equals(info.getUploadType())
        && uploadConcatenationService != null) {
      uploadConcatenationService.merge(info);
      uploads = uploadConcatenationService.getPartialUploads(info);
    } else {
      uploads = Collections.singletonList(info);
    }
    return uploads;
  }

  private Path getBytesPath(UploadId id) throws UploadNotFoundException {
    return getPathInUploadDir(id, DATA_FILE);
  }

  private Path getInfoPath(UploadId id) throws UploadNotFoundException {
    return getPathInUploadDir(id, INFO_FILE);
  }

  private Path getChecksumPath(String checksum, ChecksumAlgorithm algorithm) {
    return getStoragePath()
        .getParent()
        .resolve("checksums")
        .resolve(algorithm.toString())
        .resolve(checksum);
  }

  private Path createUploadDirectory(UploadId id) throws IOException {
    return Files.createDirectories(getPathInStorageDirectory(id));
  }

  private Path getPathInUploadDir(UploadId id, String fileName) throws UploadNotFoundException {
    // Get the upload directory
    Path uploadDir = getPathInStorageDirectory(id);
    if (uploadDir != null && Files.exists(uploadDir)) {
      return uploadDir.resolve(fileName);
    } else {
      throw new UploadNotFoundException("The upload for id " + id + " was not found.");
    }
  }

  private synchronized UploadId createNewId() throws IOException {
    UploadId id;
    do {
      id = idFactory.createId();
      // For extra safety, double check that this ID is not in use yet
    } while (getUploadInfo(id) != null);
    return id;
  }

  private long writeAsMuchAsPossible(FileChannel file) throws IOException {
    long offset = 0;
    if (file != null) {
      file.force(true);
      offset = file.size();
    }
    return offset;
  }
}
