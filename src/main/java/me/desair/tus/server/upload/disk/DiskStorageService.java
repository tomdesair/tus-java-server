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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.InvalidUploadOffsetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.upload.checksum.DeduplicationService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.concatenation.VirtualConcatenationService;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of {@link UploadStorageService} that implements storage on disk. */
public class DiskStorageService extends AbstractDiskBasedService implements UploadStorageService {

  private static final Logger log = LoggerFactory.getLogger(DiskStorageService.class);

  private static final String UPLOAD_SUB_DIRECTORY = "uploads";
  private static final String INFO_FILE = "info";
  private static final String DATA_FILE = "data";

  private Long maxUploadSize = null;
  private Long maxAppendSize = null;
  private Long minAppendSize = null;
  private Long minSize = null;
  private int cloudUploadThreadPoolSize = 10;
  private Duration drainTimeout = Duration.ofSeconds(55);
  private Long uploadExpirationPeriod = null;
  private UploadIdFactory idFactory;
  private UploadConcatenationService uploadConcatenationService;
  private boolean isUploadDeduplicationEnabled = false;
  private boolean jsonSerializationEnabled = false;
  private final DiskChecksumIndex checksumIndex;
  private DeduplicationService deduplicationService;
  private UploadLockingService uploadLockingService;

  public DiskStorageService(String storagePath) {
    super(storagePath + File.separator + UPLOAD_SUB_DIRECTORY);
    setUploadConcatenationService(new VirtualConcatenationService(this));
    this.checksumIndex = new DiskChecksumIndex(storagePath);
    this.deduplicationService = new DeduplicationService(this.checksumIndex, this);
    this.uploadLockingService = new DiskLockingService(storagePath);
    this.deduplicationService.setUploadLockingService(this.uploadLockingService);
  }

  public DiskStorageService(UploadIdFactory idFactory, String storagePath) {
    this(storagePath);
    Objects.requireNonNull(idFactory, "The IdFactory cannot be null");
    this.idFactory = idFactory;
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    Objects.requireNonNull(idFactory, "The IdFactory cannot be null");
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
  public void setMaxAppendSize(Long maxAppendSize) {
    this.maxAppendSize = (maxAppendSize != null && maxAppendSize > 0 ? maxAppendSize : null);
  }

  @Override
  public Long getMaxAppendSize() {
    if (maxAppendSize != null && maxAppendSize > 0) {
      return maxAppendSize;
    }
    long maxUpload = getMaxUploadSize();
    return maxUpload > 0 ? maxUpload : null;
  }

  @Override
  public void setMinAppendSize(Long minAppendSize) {
    this.minAppendSize = (minAppendSize != null && minAppendSize > 0 ? minAppendSize : null);
  }

  @Override
  public Long getMinAppendSize() {
    return minAppendSize;
  }

  @Override
  public void setMinSize(Long minSize) {
    this.minSize = (minSize != null && minSize > 0 ? minSize : null);
  }

  @Override
  public Long getMinSize() {
    return minSize;
  }

  @Override
  public void setCloudUploadThreadPoolSize(int size) {
    Validate.isTrue(size > 0, "The cloud upload thread pool size must be greater than 0");
    this.cloudUploadThreadPoolSize = size;
  }

  @Override
  public int getCloudUploadThreadPoolSize() {
    return cloudUploadThreadPoolSize;
  }

  @Override
  public void setDrainTimeout(Duration drainTimeout) {
    if (drainTimeout != null) {
      this.drainTimeout = drainTimeout;
    }
  }

  @Override
  public Duration getDrainTimeout() {
    return drainTimeout;
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
  public void setUploadLockingService(UploadLockingService uploadLockingService) {
    this.uploadLockingService =
        Objects.requireNonNull(uploadLockingService, "uploadLockingService must not be null");
    if (this.deduplicationService != null) {
      this.deduplicationService.setUploadLockingService(uploadLockingService);
    }
  }

  @Override
  public UploadLockingService getUploadLockingService() {
    return this.uploadLockingService;
  }

  @Override
  public UploadInfo deduplicate(UploadInfo uploadInfo, ChecksumKey checksumKey) throws IOException {
    if (!isUploadDeduplicationEnabled() || uploadInfo == null || checksumKey == null) {
      return uploadInfo;
    }
    return deduplicationService.deduplicate(uploadInfo, checksumKey);
  }

  @Override
  public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    if (checksum == null || algorithm == null) {
      return null;
    }
    String normalized = normalizeChecksum(checksum);
    if (!isSafePathComponent(normalized) || !isSafePathComponent(algorithm.toString())) {
      throw new IOException("The checksum contains an unsafe value.");
    }
    try {
      ChecksumKey key = ChecksumKey.of(normalized, algorithm);
      return deduplicationService != null
          ? deduplicationService.getUploadInfoByChecksum(key)
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
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
  public void setJsonSerializationEnabled(boolean enabled) {
    this.jsonSerializationEnabled = enabled;
  }

  @Override
  public boolean isJsonSerializationEnabled() {
    return this.jsonSerializationEnabled;
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    if (id == null) {
      return null;
    }
    try {
      Path infoPath = getInfoPath(id);
      if (infoPath == null || !Files.exists(infoPath)) {
        return null;
      }
      return loadUploadInfo(infoPath);
    } catch (UploadNotFoundException e) {
      return null;
    }
  }

  private void saveUploadInfo(UploadInfo info, Path path) throws IOException {
    // Coarse request locks (via LeaseFileUploadLock or DiskLockingService) already guarantee
    // exclusive single-writer access across cluster replicas. Fine-grained OS FileLock on .info
    // files is redundant and fails on NFS/SMB network mounts and unprivileged containers.
    if (isJsonSerializationEnabled()) {
      UploadInfoJsonSerializer.serializeToPath(info, path);
    } else {
      Utils.writeSerializable(info, path);
    }
  }

  private UploadInfo loadUploadInfo(Path path) throws IOException {
    if (isJsonSerializationEnabled()) {
      UploadInfo info = null;
      try {
        info = UploadInfoJsonSerializer.deserialize(path);
      } catch (Exception e) {
        log.debug(
            "Unable to deserialize upload info as JSON from {}, falling back to Java deserialization: {}",
            path,
            e.getMessage());
        info = null;
      }
      return info != null ? info : Utils.readSerializable(path, UploadInfo.class);
    } else {
      return Utils.readSerializable(path, UploadInfo.class);
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

      if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
        info.updateExpiration(uploadExpirationPeriod);
      }

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
      Path infoPath = getInfoPath(uploadInfo.getId());
      saveUploadInfo(uploadInfo, infoPath);

      if (uploadInfo.getDuplicatesUploadId() != null) {
        // Delete the child's own data file if it exists
        try {
          Path childDataPath = getPathInUploadDir(uploadInfo.getId(), DATA_FILE);
          Utils.deletePathQuietly(childDataPath);
        } catch (UploadNotFoundException e) {
          // It doesn't exist yet, which is fine
        }

        if (deduplicationService != null) {
          deduplicationService.syncParentExpirationIfChild(uploadInfo);
        }
      } else if (isUploadDeduplicationEnabled()
          && !uploadInfo.isUploadInProgress()
          && uploadInfo.getChecksum() != null
          && uploadInfo.getChecksumAlgorithm() != null
          && uploadInfo.getDuplicatesUploadId() == null) {
        String normalized = normalizeChecksum(uploadInfo.getChecksum());
        if (!isSafePathComponent(normalized)
            || !isSafePathComponent(uploadInfo.getChecksumAlgorithm().toString())) {
          throw new IOException("The checksum contains an unsafe value.");
        }
        try {
          ChecksumKey key = ChecksumKey.of(normalized, uploadInfo.getChecksumAlgorithm());
          deduplicate(uploadInfo, key);
        } catch (IllegalArgumentException e) {
          throw new IOException("The checksum contains an unsafe value.", e);
        }
      }
    }
  }

  @Override
  public UploadInfo append(UploadInfo info, InputStream inputStream)
      throws IOException, TusException {
    if (info != null) {
      if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
        info.updateExpiration(uploadExpirationPeriod);
      }

      Path bytesPath = getBytesPath(info.getId());

      long max = getMaxUploadSize() > 0 ? getMaxUploadSize() : Long.MAX_VALUE;
      long transferred = 0;
      Long offset = info.getOffset();
      long newOffset = offset;

      try (ReadableByteChannel uploadedBytes = Channels.newChannel(inputStream);
          FileChannel file = FileChannel.open(bytesPath, WRITE)) {

        try {
          // Note: OS FileChannel.lock() is intentionally omitted here to prevent ENOLCK /
          // "No locks available" errors on NFS mounts and Kubernetes RWX persistent volumes.
          // Single-writer mutual exclusion is already guaranteed by the request-level UploadLock.

          // Validate that the given offset is at the end of the file
          if (!offset.equals(file.size())) {
            throw new InvalidUploadOffsetException(
                "The upload offset does not correspond to the written"
                    + " bytes. You can only append to the end of an upload");
          }

          // write all bytes in the channel up to the configured maximum in a loop to support
          // transfers larger than 2GB (OS syscall limits)
          long bytesTransferred = 0;
          while (offset + bytesTransferred < max) {
            long toTransfer = max - (offset + bytesTransferred);
            long n = file.transferFrom(uploadedBytes, offset + bytesTransferred, toTransfer);
            if (n <= 0) {
              break;
            }
            bytesTransferred += n;
          }
          transferred = bytesTransferred;
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

        // Note: OS FileChannel.lock() is intentionally omitted to avoid ENOLCK on NFS/K8s RWX PVCs.

        // Guard against Math.max(0, file.size() - byteCount) to prevent FileChannel.truncate
        // from throwing IllegalArgumentException on over-truncation during checksum rollback
        file.truncate(Math.max(0L, file.size() - byteCount));
        file.force(true);

        info.setOffset(file.size());
        update(info);
      }
    }
  }

  @Override
  public boolean dataExists(UploadId id) {
    if (id == null) {
      return false;
    }
    try {
      Path bytesPath = getBytesPath(id);
      return bytesPath != null && Files.exists(bytesPath);
    } catch (UploadNotFoundException e) {
      return false;
    }
  }

  @Override
  public void deleteData(UploadId id) throws IOException {
    if (id != null) {
      try {
        Path bytesPath = getBytesPath(id);
        if (bytesPath != null) {
          Files.deleteIfExists(bytesPath);
        }
      } catch (UploadNotFoundException ignored) {
      }
      cleanDirectoryIfEmpty(id);
    }
  }

  private void deleteMetadata(UploadId id) throws IOException {
    if (id != null) {
      try {
        Path infoPath = getInfoPath(id);
        if (infoPath != null) {
          Files.deleteIfExists(infoPath);
        }
      } catch (UploadNotFoundException ignored) {
      }
      cleanDirectoryIfEmpty(id);
    }
  }

  private void cleanDirectoryIfEmpty(UploadId id) {
    Path uploadDir = getPathInStorageDirectory(id);
    if (uploadDir != null && Files.exists(uploadDir)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDir)) {
        if (!stream.iterator().hasNext()) {
          Files.deleteIfExists(uploadDir);
        }
      } catch (IOException ignored) {
      }
    }
  }

  @Override
  public void terminateUpload(UploadInfo info) throws UploadNotFoundException, IOException {
    if (info != null) {
      if (info.getChecksum() != null && info.getChecksumAlgorithm() != null) {
        String normalized = normalizeChecksum(info.getChecksum());
        if (!isSafePathComponent(normalized)
            || !isSafePathComponent(info.getChecksumAlgorithm().toString())) {
          throw new IOException("The checksum contains an unsafe value.");
        }
      }

      boolean deleteData = true;
      if (isUploadDeduplicationEnabled() && deduplicationService != null) {
        deleteData = deduplicationService.removeUploadFromIndex(info);
      }

      if (deleteData) {
        deleteData(info.getId());
      }
      deleteMetadata(info.getId());

      Path uploadPath = getPathInStorageDirectory(info.getId());
      if (uploadPath != null && deleteData) {
        FileUtils.deleteDirectory(uploadPath.toFile());
      }
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
    Objects.requireNonNull(concatenationService);
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

    if (UploadType.CONCATENATED.equals(uploadInfo.getUploadType())
        && uploadConcatenationService != null) {
      inputStream = uploadConcatenationService.getConcatenatedBytes(uploadInfo);

    } else {
      UploadId targetDataId =
          uploadInfo.getDuplicatesUploadId() != null ? uploadInfo.getDuplicatesUploadId() : id;
      Path bytesPath = getBytesPath(targetDataId);
      // If bytesPath is not null, we know this is a valid Upload URI
      if (bytesPath != null) {
        if (!Files.exists(bytesPath)) {
          throw new UploadNotFoundException(
              "The upload bytes for id " + targetDataId + " could not be found.");
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
            // Efficiently copy the bytes to the output stream in a loop to handle transfers
            // exceeding OS single-syscall limits (e.g. 2GB sendfile limit)
            long position = 0;
            long remaining = upload.getLength() != null ? upload.getLength() : file.size();
            while (remaining > 0) {
              long bytesTransferred = file.transferTo(position, remaining, outputChannel);
              if (bytesTransferred <= 0) {
                break;
              }
              position += bytesTransferred;
              remaining -= bytesTransferred;
            }
          }
        }
      }
    }
  }

  @Override
  public void cleanupExpiredUploads() throws IOException {
    try (DirectoryStream<Path> expiredUploadsStream =
        Files.newDirectoryStream(
            getStoragePath(), new ExpiredUploadFilter(this, this.uploadLockingService))) {

      for (Path path : expiredUploadsStream) {
        UploadId id = new UploadId(path.getFileName().toString());
        UploadInfo info = getUploadInfo(id);
        if (info != null) {
          try {
            terminateUpload(info);
          } catch (UploadNotFoundException e) {
            FileUtils.deleteDirectory(path.toFile());
          }
        } else {
          FileUtils.deleteDirectory(path.toFile());
        }
        uploadLockingService.cleanupLock(id);
      }
    }

    if (isUploadDeduplicationEnabled() && deduplicationService != null) {
      deduplicationService.pruneOrphanedChecksumIndices();
    }
  }

  @Override
  @Deprecated
  public void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException {
    setUploadLockingService(Utils.getLockingServiceOrDefault(uploadLockingService));
    cleanupExpiredUploads();
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

  private String normalizeChecksum(String checksum) {
    String filename = checksum;
    if (filename != null) {
      if (!filename.matches("^[0-9a-fA-F]+$")) {
        byte[] bytes = Base64.decodeBase64(filename);
        if (bytes != null && bytes.length > 0) {
          StringBuilder sb = new StringBuilder();
          for (byte b : bytes) {
            sb.append(String.format("%02x", b));
          }
          filename = sb.toString();
        }
      }
    }
    return filename;
  }

  /**
   * Checks whether a given path component string is safe for use as a file or directory name on
   * disk.
   *
   * <p>OS differences in path component restrictions:
   *
   * <ul>
   *   <li><b>POSIX / Linux / macOS:</b> Strict path separators are {@code /} and NUL ({@code \0}).
   *       Characters such as {@code ?}, {@code *}, {@code :}, {@code <}, {@code >}, {@code "}, and
   *       {@code |} are allowed in Linux filenames.
   *   <li><b>Windows (NTFS / FAT32):</b> Reserved characters prohibited in file/directory names are
   *       {@code \}, {@code /}, {@code :}, {@code *}, {@code ?}, {@code "}, {@code <}, {@code >},
   *       {@code |}, and ASCII control characters (0-31). Attempting to resolve paths containing
   *       these characters on Windows triggers an OS-level {@link
   *       java.nio.file.InvalidPathException}.
   * </ul>
   *
   * To guarantee portable, cross-platform safety and prevent OS-dependent runtime exceptions on
   * Windows, this method enforces validation against reserved characters from both Linux and
   * Windows specifications, as well as relative path traversal sequences ({@code ..}).
   *
   * @param component the path component to validate
   * @return {@code true} if the component is safe for cross-platform file system operations; {@code
   *     false} otherwise
   */
  private boolean isSafePathComponent(String component) {
    if (component == null || component.trim().isEmpty() || component.contains("..")) {
      return false;
    }
    for (int i = 0; i < component.length(); i++) {
      char c = component.charAt(i);
      if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
          || c == '<' || c == '>' || c == '|') {
        return false;
      }
    }
    return true;
  }

  private Path createUploadDirectory(UploadId id) throws IOException {
    Path dir = getPathInStorageDirectory(id);
    Utils.ensureDirectoryExists(dir);
    return dir;
  }

  private Path getPathInUploadDir(UploadId id, String fileName) throws UploadNotFoundException {
    // Get the upload directory
    Path uploadDir = getPathInStorageDirectory(id);
    if (uploadDir != null && Files.exists(uploadDir)) {
      if (!isSafePathComponent(fileName)) {
        throw new IllegalArgumentException("The file name contains an unsafe value.");
      }
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
