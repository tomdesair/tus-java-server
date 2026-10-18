package me.desair.tus.server.upload.disk;

import static java.nio.file.StandardOpenOption.CREATE_NEW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.checksum.ChecksumIndex;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;

/**
 * Disk-based implementation of {@link ChecksumIndex}.
 *
 * <p>Directory and file layout on disk:
 *
 * <ul>
 *   <li>{@code <storagePath>/checksums/<algorithm>/<hexChecksum>/parent} - contains parent upload
 *       ID
 *   <li>{@code <storagePath>/checksums/<algorithm>/<hexChecksum>/parent-deleted} - tombstone marker
 *   <li>{@code <storagePath>/checksums/<algorithm>/<hexChecksum>/children/<childId>} - child upload
 *       marker files
 * </ul>
 */
public class DiskChecksumIndex implements ChecksumIndex {

  private static final String CHECKSUMS_SUB_DIRECTORY = "checksums";
  private static final String PARENT_FILE = "parent";
  private static final String PARENT_DELETED_FILE = "parent-deleted";
  private static final String CHILDREN_DIRECTORY = "children";

  private final Path storagePath;

  /**
   * Constructs a new {@link DiskChecksumIndex}.
   *
   * @param storagePath the base storage directory path
   */
  public DiskChecksumIndex(String storagePath) {
    Objects.requireNonNull(storagePath, "storagePath must not be null");
    this.storagePath = Paths.get(storagePath).resolve(CHECKSUMS_SUB_DIRECTORY);
  }

  @Override
  public boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(uploadId, "uploadId must not be null");

    Path checksumDir = getChecksumDir(key);
    Utils.ensureDirectoryExists(checksumDir);

    Path parentPath = checksumDir.resolve(PARENT_FILE);
    // Atomic creation and write via CREATE_NEW (fails with FileAlreadyExistsException if already
    // present, whether active or tombstoned)
    try {
      Files.write(parentPath, uploadId.toString().getBytes(StandardCharsets.UTF_8), CREATE_NEW);
      return true;
    } catch (FileAlreadyExistsException e) {
      // CAS lost: another thread or process already created the parent marker
      return false;
    }
  }

  @Override
  public UploadId findParent(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Path checksumDir = getChecksumDir(key);
    Path parentPath = checksumDir.resolve(PARENT_FILE);

    if (Files.exists(parentPath)) {
      String idStr = new String(Files.readAllBytes(parentPath), StandardCharsets.UTF_8).trim();
      return idStr.isEmpty() ? null : new UploadId(idStr);
    }
    return null;
  }

  @Override
  public void markParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Path checksumDir = getChecksumDir(key);
    Utils.ensureDirectoryExists(checksumDir);

    Path parentDeletedPath = checksumDir.resolve(PARENT_DELETED_FILE);
    try {
      // Create empty parent-deleted marker while keeping parent pointer file intact
      // until deleteIndex(key) is invoked when the last child upload terminates.
      Files.createFile(parentDeletedPath);
    } catch (FileAlreadyExistsException ignored) {
      // Tombstone already present
    }
  }

  @Override
  public boolean isParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Path checksumDir = getChecksumDir(key);
    return Files.exists(checksumDir.resolve(PARENT_DELETED_FILE));
  }

  @Override
  public void addChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");

    Path childrenDir = getChecksumDir(key).resolve(CHILDREN_DIRECTORY);
    Utils.ensureDirectoryExists(childrenDir);

    Path childMarker = childrenDir.resolve(childId.toString());
    try {
      Files.createFile(childMarker);
    } catch (FileAlreadyExistsException ignored) {
      // Child marker already present
    }
  }

  @Override
  public void removeChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");

    Path childMarker = getChecksumDir(key).resolve(CHILDREN_DIRECTORY).resolve(childId.toString());
    Files.deleteIfExists(childMarker);
  }

  @Override
  public boolean hasChildren(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Path childrenDir = getChecksumDir(key).resolve(CHILDREN_DIRECTORY);
    if (!Files.exists(childrenDir)) {
      return false;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(childrenDir)) {
      return stream.iterator().hasNext();
    }
  }

  @Override
  public void deleteIndex(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Path checksumDir = getChecksumDir(key);
    if (Files.exists(checksumDir)) {
      FileUtils.deleteDirectory(checksumDir.toFile());
    }
  }

  @Override
  public Set<ChecksumKey> getAllIndexedKeys() throws IOException {
    Set<ChecksumKey> keys = new LinkedHashSet<>();
    if (!Files.exists(storagePath)) {
      return keys;
    }
    try (DirectoryStream<Path> algoDirs = Files.newDirectoryStream(storagePath)) {
      for (Path algoDir : algoDirs) {
        if (!Files.isDirectory(algoDir)) {
          continue;
        }
        String algoName = algoDir.getFileName().toString();
        ChecksumAlgorithm algo = ChecksumAlgorithm.forTusName(algoName);
        if (algo == null) {
          continue;
        }
        try (DirectoryStream<Path> hashDirs = Files.newDirectoryStream(algoDir)) {
          for (Path hashDir : hashDirs) {
            if (!Files.isDirectory(hashDir)) {
              continue;
            }
            Path parentFile = hashDir.resolve(PARENT_FILE);
            if (Files.exists(parentFile)) {
              String hash = hashDir.getFileName().toString();
              try {
                keys.add(ChecksumKey.of(hash, algo));
              } catch (IllegalArgumentException e) {
                // Malformed hash directory on disk, skip
              }
            }
          }
        }
      }
    }
    return keys;
  }

  private Path getChecksumDir(ChecksumKey key) {
    return storagePath.resolve(key.toString());
  }
}
