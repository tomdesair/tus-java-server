package me.desair.tus.server.upload.checksum;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator service responsible for thread-safe and cluster-safe upload deduplication lifecycle
 * management.
 *
 * <p>Handles parent registration, child deduplication linking, CAS race resolution, missing-parent
 * detection, and reference-counted cleanup upon upload deletion (via DELETE request or expiration).
 */
public class DeduplicationService {

  private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);

  private final ChecksumIndex checksumIndex;
  private final UploadStorageService storageService;
  private final Set<UploadId> inFlightDeduplications = ConcurrentHashMap.newKeySet();
  private UploadLockingService lockingService;

  /**
   * Constructs a new {@link DeduplicationService}.
   *
   * @param checksumIndex the backend checksum index implementation
   * @param storageService the underlying upload storage service
   */
  public DeduplicationService(ChecksumIndex checksumIndex, UploadStorageService storageService) {
    this(checksumIndex, storageService, Utils.getLockingServiceOrDefault(storageService));
  }

  /**
   * Constructs a {@link DeduplicationService} with mandatory {@link UploadLockingService}.
   *
   * @param checksumIndex the checksum index
   * @param storageService the storage service
   * @param lockingService the locking service used for inter-upload synchronization
   */
  public DeduplicationService(
      ChecksumIndex checksumIndex,
      UploadStorageService storageService,
      UploadLockingService lockingService) {
    this.checksumIndex = Objects.requireNonNull(checksumIndex, "checksumIndex must not be null");
    this.storageService = Objects.requireNonNull(storageService, "storageService must not be null");
    this.lockingService = Objects.requireNonNull(lockingService, "lockingService must not be null");
  }

  /**
   * Sets the mandatory {@link UploadLockingService} used for inter-upload synchronization.
   *
   * @param lockingService the locking service (must not be {@code null})
   */
  public void setUploadLockingService(UploadLockingService lockingService) {
    this.lockingService = Objects.requireNonNull(lockingService, "lockingService must not be null");
  }

  /**
   * Attempts to deduplicate the given completed upload $U$ against an existing parent with the same
   * checksum, or registers $U$ as the parent if none exists.
   *
   * @param uploadInfo the upload info for the completed upload
   * @param key the normalized checksum key
   * @return the updated {@link UploadInfo} (either linked to parent or registered as parent)
   * @throws IOException on storage I/O error
   */
  public UploadInfo deduplicate(UploadInfo uploadInfo, ChecksumKey key) throws IOException {
    if (uploadInfo == null || key == null) {
      return uploadInfo;
    }

    UploadId currentId = uploadInfo.getId();
    if (currentId == null || !inFlightDeduplications.add(currentId)) {
      // Re-entrant call detected: updateUploadInfo() calls storageService.update(), which invokes
      // deduplicate() again. Guarding with inFlightDeduplications prevents infinite recursion
      // (StackOverflowError) when duplicatesUploadId is still null.
      return uploadInfo;
    }

    try {
      // 1. Check if a parent is currently registered for this checksum
      UploadId parentId = checksumIndex.findParent(key);

      if (parentId == null) {
        // 2. No parent registered. Attempt CAS registration of current upload as parent.
        return registerAsParent(uploadInfo, key);
      }

      // Guard: Do not deduplicate an upload against itself
      if (Objects.equals(parentId, currentId)) {
        return uploadInfo;
      }

      // 3. Parent found ($P$). Acquire lock on $P$ to inspect and join.
      return joinParentUnderLock(uploadInfo, key, parentId, true);
    } finally {
      inFlightDeduplications.remove(currentId);
    }
  }

  /** Registers the given upload as the parent for the specified checksum key. */
  private UploadInfo registerAsParent(UploadInfo uploadInfo, ChecksumKey key) throws IOException {
    UploadId currentId = uploadInfo.getId();
    boolean registered = checksumIndex.tryRegisterParent(key, currentId);
    if (registered) {
      log.debug("Registered upload {} as parent for checksum {}", currentId, key);
      uploadInfo.setChecksum(key.getHexChecksum());
      uploadInfo.setChecksumAlgorithm(key.getAlgorithm());
      updateUploadInfo(uploadInfo);
      return uploadInfo;
    }

    // CAS failed (lost race to concurrent upload). Re-lookup the winning parent.
    UploadId winner = checksumIndex.findParent(key);
    if (winner != null && !Objects.equals(winner, currentId)) {
      return joinParentUnderLock(uploadInfo, key, winner, true);
    }

    uploadInfo.setChecksum(key.getHexChecksum());
    uploadInfo.setChecksumAlgorithm(key.getAlgorithm());
    updateUploadInfo(uploadInfo);
    log.debug(
        "Parent registration failed for upload {} under checksum {}; retaining as standalone upload",
        currentId,
        key);
    return uploadInfo;
  }

  /**
   * Looks up an existing completed parent upload matching the given checksum key.
   *
   * @param key the normalized checksum key
   * @return the parent {@link UploadInfo}, or {@code null} if no valid parent exists or its data is
   *     missing
   * @throws IOException on storage I/O error
   */
  public UploadInfo getUploadInfoByChecksum(ChecksumKey key) throws IOException {
    if (key == null) {
      return null;
    }
    UploadId parentId = checksumIndex.findParent(key);
    if (parentId == null) {
      return null;
    }
    UploadInfo parentInfo = storageService.getUploadInfo(parentId);
    boolean parentTombstoned = checksumIndex.isParentDeleted(key);
    boolean parentBytesExist = storageService.dataExists(parentId);

    if (parentInfo == null) {
      if (parentTombstoned && parentBytesExist) {
        // Parent is legitimately tombstoned and retained for active children.
        // Return null as parent upload is terminated, but preserve index for children.
        return null;
      }
      log.debug(
          "Parent {} referenced by checksum {} is missing or corrupt; self-cleaning index",
          parentId,
          key);
      checksumIndex.deleteIndex(key);
      return null;
    }

    if (!parentBytesExist) {
      log.debug(
          "Parent data for {} referenced by checksum {} is missing; self-cleaning index",
          parentId,
          key);
      checksumIndex.deleteIndex(key);
      return null;
    }

    return parentInfo;
  }

  /**
   * Acquires the upload lock on the specified parent upload with short bounded retries.
   *
   * <p>When a parent completes, registerAsParent publishes the checksum index entry while the
   * parent's request lock is still held by TusFileUploadService for a few milliseconds (running
   * final post-handlers). A concurrent child or subsequent child completing milliseconds later
   * would fail immediately without retry. Retrying up to 10 times with a 50ms pause bridges this
   * transient completion window without deadlocking.
   */
  private UploadLock lockParentWithRetry(UploadId parentId) throws IOException, TusException {
    int retries = 0;
    while (true) {
      try {
        return lockingService.lockUpload(parentId);
      } catch (TusException e) {
        if (++retries >= 10) {
          throw e;
        }
        try {
          Thread.sleep(50L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /**
   * Synchronizes child upload expiration to its parent upload under parent lock.
   *
   * <p>Ensures that the parent upload's expiration timestamp is at least as long as any of its
   * active deduplicated children, preventing the parent data file from being prematurely expired
   * and purged while active children still depend on it.
   *
   * @param childInfo the deduplicated child upload info
   */
  public void syncParentExpirationIfChild(UploadInfo childInfo) {
    if (childInfo == null || childInfo.getDuplicatesUploadId() == null) {
      return;
    }
    UploadId parentId = childInfo.getDuplicatesUploadId();
    if (!inFlightDeduplications.add(parentId)) {
      return;
    }
    try {
      try (UploadLock parentLock = lockParentWithRetry(parentId)) {
        UploadInfo parentInfo = storageService.getUploadInfo(parentId);
        if (parentInfo != null) {
          syncParentExpiration(parentInfo, childInfo);
        }
      } catch (Exception e) {
        log.warn(
            "Failed to sync expiration from child {} to parent {}: {}",
            childInfo.getId(),
            parentId,
            e.getMessage());
      }
    } finally {
      inFlightDeduplications.remove(parentId);
    }
  }

  /**
   * Acquires the lock on parent $P$, re-verifies parent state and physical bytes, and links the
   * child.
   */
  private UploadInfo joinParentUnderLock(
      UploadInfo uploadInfo, ChecksumKey key, UploadId expectedParentId, boolean allowRetry)
      throws IOException {
    try (UploadLock parentLock = lockParentWithRetry(expectedParentId)) {
      return doJoinParentUnderLock(uploadInfo, key, expectedParentId, allowRetry);
    } catch (TusException e) {
      // Parent is currently locked by active request. Per design, skip deduplication
      // and retain current upload as an independent standalone upload.
      log.debug(
          "Parent {} is currently locked; skipping deduplication for upload {}",
          expectedParentId,
          uploadInfo.getId());
      uploadInfo.setChecksum(key.getHexChecksum());
      uploadInfo.setChecksumAlgorithm(key.getAlgorithm());
      updateUploadInfo(uploadInfo);
      return uploadInfo;
    }
  }

  private UploadInfo doJoinParentUnderLock(
      UploadInfo uploadInfo, ChecksumKey key, UploadId expectedParentId, boolean allowRetry)
      throws IOException {
    // Under parent lock: B2 Check - re-read current parent from index
    UploadId currentParent = checksumIndex.findParent(key);
    if (!Objects.equals(expectedParentId, currentParent)) {
      if (allowRetry && currentParent != null) {
        return joinParentUnderLock(uploadInfo, key, currentParent, false);
      }
      return registerAsParent(uploadInfo, key);
    }

    UploadInfo parentInfo = storageService.getUploadInfo(expectedParentId);
    boolean parentTombstoned = (parentInfo == null) && checksumIndex.isParentDeleted(key);

    // B3 Self-healing check: if parent is missing and not legitimately tombstoned,
    // or if parent physical bytes are missing
    if ((parentInfo == null && !parentTombstoned) || !storageService.dataExists(expectedParentId)) {
      log.warn(
          "Parent {} for checksum {} is invalid (exists={}, tombstoned={}, bytesExist={}). Purging index.",
          expectedParentId,
          key,
          parentInfo != null,
          parentTombstoned,
          storageService.dataExists(expectedParentId));
      checksumIndex.deleteIndex(key);
      return registerAsParent(uploadInfo, key);
    }

    // Sync expiration if parent is active (not tombstoned)
    if (parentInfo != null) {
      syncParentExpiration(parentInfo, uploadInfo);
    }

    // 4. Metadata-first child linking:
    // Step 1: Register child in checksum index
    checksumIndex.addChild(key, uploadInfo.getId());

    // Step 2: Persist child metadata referencing parent
    uploadInfo.setDuplicatesUploadId(expectedParentId);
    uploadInfo.setChecksum(key.getHexChecksum());
    uploadInfo.setChecksumAlgorithm(key.getAlgorithm());
    updateUploadInfo(uploadInfo);

    // Step 3: Free local child data bytes (storage is now shared with parent)
    storageService.deleteData(uploadInfo.getId());

    log.debug(
        "Successfully deduplicated child upload {} to parent {}",
        uploadInfo.getId(),
        expectedParentId);
    return uploadInfo;
  }

  private void syncParentExpiration(UploadInfo parentInfo, UploadInfo childInfo)
      throws IOException {
    Long childExpiration = childInfo.getExpirationTimestamp();
    Long parentExpiration = parentInfo.getExpirationTimestamp();
    if (childExpiration == null && parentExpiration != null) {
      parentInfo.setExpirationTimestamp(null);
      updateUploadInfo(parentInfo);
    } else if (childExpiration != null
        && parentExpiration != null
        && childExpiration > parentExpiration) {
      parentInfo.setExpirationTimestamp(childExpiration);
      updateUploadInfo(parentInfo);
    }
  }

  /**
   * Updates the checksum index when an upload is being terminated via DELETE or expiration sweep.
   *
   * @param uploadInfo the upload info being terminated
   * @return {@code true} if the upload's data file can be deleted immediately by the caller; {@code
   *     false} if the upload is a parent with active children whose data must be preserved
   * @throws IOException on storage I/O error
   */
  public boolean removeUploadFromIndex(UploadInfo uploadInfo) throws IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return true;
    }

    UploadId currentId = uploadInfo.getId();
    ChecksumKey key;
    try {
      key = extractChecksumKey(uploadInfo);
    } catch (IllegalArgumentException e) {
      throw new IOException(
          "The checksum contains an unsafe or invalid value: " + e.getMessage(), e);
    }

    UploadId duplicatesId = uploadInfo.getDuplicatesUploadId();
    if (key == null && duplicatesId != null) {
      UploadInfo parentInfo = storageService.getUploadInfo(duplicatesId);
      if (parentInfo != null) {
        key = extractChecksumKey(parentInfo);
      }
    }

    if (key == null) {
      return true;
    }

    if (duplicatesId != null) {
      // Case 3: Terminating a child duplicate upload
      return removeChildFromIndex(currentId, duplicatesId, key);
    } else {
      // Case 2: Terminating a parent upload
      return removeParentFromIndex(currentId, key);
    }
  }

  /**
   * Scans the checksum index and removes orphaned entries (where the parent data file/blob is
   * missing, the parent metadata is missing without a tombstone, or a tombstoned parent has zero
   * remaining children).
   */
  public void pruneOrphanedChecksumIndices() {
    Set<ChecksumKey> allKeys;
    try {
      allKeys = checksumIndex.getAllIndexedKeys();
    } catch (Exception e) {
      log.warn("Failed to retrieve indexed checksum keys for pruning: {}", e.getMessage());
      return;
    }
    for (ChecksumKey key : allKeys) {
      try {
        UploadId parentId = checksumIndex.findParent(key);
        if (parentId == null) {
          continue;
        }

        // Quick unlocked pre-check
        boolean parentDataExists = storageService.dataExists(parentId);
        UploadInfo parentInfo = storageService.getUploadInfo(parentId);
        boolean isTombstoned = checksumIndex.isParentDeleted(key);
        boolean hasChildren = checksumIndex.hasChildren(key);

        boolean needsInspection =
            !parentDataExists
                || (parentInfo == null && !isTombstoned)
                || (isTombstoned && !hasChildren);

        if (!needsInspection) {
          continue;
        }

        // Acquire lock on parent to prevent racing with concurrent children joining parent
        try (UploadLock lock = lockingService.lockUpload(parentId)) {
          // Re-verify under parent lock
          UploadId currentParent = checksumIndex.findParent(key);
          if (!Objects.equals(parentId, currentParent)) {
            continue;
          }
          parentDataExists = storageService.dataExists(parentId);
          parentInfo = storageService.getUploadInfo(parentId);
          isTombstoned = checksumIndex.isParentDeleted(key);
          hasChildren = checksumIndex.hasChildren(key);

          // Repair missing tombstone if parent info is gone but data exists and has children
          if (parentDataExists && parentInfo == null && !isTombstoned && hasChildren) {
            log.info("Repairing missing tombstone for parent {} with active children", parentId);
            checksumIndex.markParentDeleted(key);
            continue;
          }

          boolean orphaned =
              !parentDataExists
                  || (parentInfo == null && !hasChildren)
                  || (isTombstoned && !hasChildren);

          if (orphaned) {
            log.info(
                "Pruning orphaned or abandoned checksum index for key {} (parent {})",
                key,
                parentId);
            checksumIndex.deleteIndex(key);
            if (!hasChildren) {
              storageService.deleteData(parentId);
            }
          }
        } catch (TusException e) {
          // Parent is currently locked; skip this key for this sweep
          log.debug("Parent {} is locked during prune check; skipping", parentId);
        }
      } catch (Exception e) {
        log.warn("Error checking checksum index for key {}: {}", key, e.getMessage());
      }
    }
  }

  /** Handles removal of a parent upload from the index. */
  private boolean removeParentFromIndex(UploadId parentId, ChecksumKey key) throws IOException {
    // B4 Check: Verify whether parentId is the registered parent in the checksum index
    UploadId indexedParent = checksumIndex.findParent(key);
    if (!Objects.equals(parentId, indexedParent)) {
      // Unindexed standalone upload (e.g. deduplication skipped due to lock contention)
      return true;
    }

    if (checksumIndex.hasChildren(key)) {
      // Active children exist: tombstone parent marker, preserve data bytes for children
      checksumIndex.markParentDeleted(key);
      log.debug("Tombstoned parent {} for checksum {} (active children remain)", parentId, key);

      // Post-tombstone race re-check: in case the last child exited while markParentDeleted was
      // executing
      if (!checksumIndex.hasChildren(key)) {
        checksumIndex.deleteIndex(key);
        log.debug("Purged parent index {} after last child exited during tombstoning", parentId);
        return true;
      }
      return false;
    } else {
      // No children: delete checksum index directory
      checksumIndex.deleteIndex(key);
      log.debug("Fully removed unreferenced parent {} and checksum index for {}", parentId, key);
      return true;
    }
  }

  /** Handles removal of a child duplicate upload from the index. */
  private boolean removeChildFromIndex(UploadId childId, UploadId parentId, ChecksumKey key)
      throws IOException {
    // Step 1: B4 Check - if parentId is the registered parent in index, remove child marker
    UploadId indexedParent = checksumIndex.findParent(key);
    if (Objects.equals(parentId, indexedParent)) {
      checksumIndex.removeChild(key, childId);

      // Step 2: Check if parent was tombstoned and no other children remain
      if (checksumIndex.isParentDeleted(key) && !checksumIndex.hasChildren(key)) {
        purgeParentIfUnreferenced(key, parentId);
      }
    }

    return true;
  }

  /**
   * Under parent lock, purges parent data and checksum index if parent is tombstoned and has no
   * active children.
   */
  private void purgeParentIfUnreferenced(ChecksumKey key, UploadId parentId) throws IOException {
    try (UploadLock parentLock = lockParentWithRetry(parentId)) {
      purgeParentUnderLock(key, parentId);
    } catch (TusException e) {
      log.warn(
          "Could not acquire parent lock {} during final child cleanup; skipping immediate purge",
          parentId);
    }
  }

  private void purgeParentUnderLock(ChecksumKey key, UploadId parentId) throws IOException {
    if (Objects.equals(parentId, checksumIndex.findParent(key))
        && checksumIndex.isParentDeleted(key)
        && !checksumIndex.hasChildren(key)) {
      storageService.deleteData(parentId);
      checksumIndex.deleteIndex(key);
      log.debug(
          "Purged parent data {} and checksum index {} after final child deleted", parentId, key);
    }
  }

  private ChecksumKey extractChecksumKey(UploadInfo uploadInfo) {
    if (uploadInfo.getChecksum() != null && uploadInfo.getChecksumAlgorithm() != null) {
      return ChecksumKey.of(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm());
    }
    return null;
  }

  private void updateUploadInfo(UploadInfo info) throws IOException {
    try {
      storageService.update(info);
    } catch (UploadNotFoundException e) {
      throw new IOException("Failed to update upload info: " + e.getMessage(), e);
    }
  }
}
