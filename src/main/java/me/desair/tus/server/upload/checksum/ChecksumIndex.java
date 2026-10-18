package me.desair.tus.server.upload.checksum;

import java.io.IOException;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;

/**
 * Service Provider Interface (SPI) for storage-backend checksum indexing and deduplication
 * tracking.
 *
 * <p>Each storage backend (disk, S3, Azure Blob) implements this interface to provide atomic
 * registration, parent lookup, tombstoning, and child reference tracking.
 *
 * <p>Storage layout:
 *
 * <ul>
 *   <li>{@code checksums/<algorithm>/<hexChecksum>/parent} - contains the parent upload ID
 *   <li>{@code checksums/<algorithm>/<hexChecksum>/parent-deleted} - tombstone marker indicating
 *       the parent was deleted
 *   <li>{@code checksums/<algorithm>/<hexChecksum>/children/<childId>} - child upload marker files
 * </ul>
 */
public interface ChecksumIndex {

  /**
   * Atomically registers the specified upload as the parent for the given checksum key.
   *
   * @param key the normalized checksum key
   * @param uploadId the upload ID to register as parent
   * @return {@code true} if this upload was atomically registered as the parent; {@code false} if a
   *     parent or tombstone already exists (CAS failure)
   * @throws IOException on storage I/O error
   */
  boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) throws IOException;

  /**
   * Looks up the registered parent upload ID for the given checksum key.
   *
   * @param key the normalized checksum key
   * @return the parent {@link UploadId}, or {@code null} if no parent is registered
   * @throws IOException on storage I/O error
   */
  UploadId findParent(ChecksumKey key) throws IOException;

  /**
   * Atomically marks the parent upload as deleted (tombstone) while preserving the parent marker
   * file until {@link #deleteIndex(ChecksumKey)} is invoked when the last child is removed.
   *
   * @param key the normalized checksum key
   * @throws IOException on storage I/O error
   */
  void markParentDeleted(ChecksumKey key) throws IOException;

  /**
   * Checks whether the parent for the given checksum key has been deleted/tombstoned.
   *
   * @param key the normalized checksum key
   * @return {@code true} if the tombstone marker exists; {@code false} otherwise
   * @throws IOException on storage I/O error
   */
  boolean isParentDeleted(ChecksumKey key) throws IOException;

  /**
   * Registers a child upload ID under the given checksum key.
   *
   * @param key the normalized checksum key
   * @param childId the child upload ID to add
   * @throws IOException on storage I/O error
   */
  void addChild(ChecksumKey key, UploadId childId) throws IOException;

  /**
   * Removes a child upload ID marker from under the given checksum key.
   *
   * @param key the normalized checksum key
   * @param childId the child upload ID to remove
   * @throws IOException on storage I/O error
   */
  void removeChild(ChecksumKey key, UploadId childId) throws IOException;

  /**
   * Checks whether any child upload markers currently exist for the given checksum key.
   *
   * @param key the normalized checksum key
   * @return {@code true} if at least one child marker exists; {@code false} otherwise
   * @throws IOException on storage I/O error
   */
  boolean hasChildren(ChecksumKey key) throws IOException;

  /**
   * Deletes the entire checksum index directory or prefix for the given checksum key, removing all
   * markers and children.
   *
   * @param key the normalized checksum key
   * @throws IOException on storage I/O error
   */
  void deleteIndex(ChecksumKey key) throws IOException;

  /**
   * Retrieves all currently indexed checksum keys present in this checksum index.
   *
   * @return a set of all indexed {@link ChecksumKey} instances
   * @throws IOException on storage I/O error
   */
  Set<ChecksumKey> getAllIndexedKeys() throws IOException;
}
