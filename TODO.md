# Open Implementation Items (`feature/cloud-locking-performance-hardening`)

Below are the remaining items from the Cloud Locking, Performance & Hardening implementation plan that are either partially implemented or missing on the current branch (`cleanupLock(id)` is intentionally retained).

---

## 1. [x] `L-1`: Fail-Closed Split-Brain Protection in `S3LockingService`, `S3UploadLock`, and `LeaseFileUploadLock`

### 1.1 `S3LockingService.isLockExpired(String lockKey)`
- **File**: [`S3LockingService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/s3/S3LockingService.java#L491-L515)
- **Current State**: Catches any non-`NO_SUCH_KEY` `ErrorResponseException` (lines 500–510) and any `Exception` (lines 511–514) and returns `true` ("assuming lock is expired").
- **Required Fix**: Make `isLockExpired` **fail-closed**:
  - Return `true` **only** when S3 explicitly returns `NO_SUCH_KEY` (`404`), when the JSON payload is corrupt/empty (`IllegalArgumentException` / `JsonParseException` or `lock == null`), or when `isLeaseExpired(lock, now)` is `true`.
  - On transient S3/MinIO errors (`503 SlowDown`, `500 InternalError`) or network `IOException` / `SocketTimeoutException`, log a warning and return `false` (assume the lock is still actively held by another node).

### 1.2 `S3UploadLock.doesLockOwnershipMatch(String key)`
- **File**: [`S3UploadLock.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/s3/S3UploadLock.java#L216-L232)
- **Current State**: Catches `ErrorResponseException` and `Exception` and returns `true` (lines 225–231), and also returns `true` if `currentLock == null`.
- **Required Fix**: Make `doesLockOwnershipMatch` **fail-closed**:
  - Return `true` **only** when `currentLock != null && getLeaseData().getOwnerId().equals(currentLock.getOwnerId())`.
  - If the lock object does not exist (`NO_SUCH_KEY`), is `null`, or if a network/S3 error occurs while reading it, log a warning and return `false` so a partitioned node cannot overwrite or delete a lock whose ownership it cannot confirm.

### 1.3 `LeaseFileUploadLock.doesLockOwnershipMatch()`
- **File**: [`LeaseFileUploadLock.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileUploadLock.java#L133-L150)
- **Current State**: Returns `true` when `!Files.exists(leaseFilePath)`, when `currentLease == null`, or when `IOException` / `RuntimeException` is thrown (lines 134–149).
- **Required Fix**: Make `doesLockOwnershipMatch` **fail-closed**:
  - Return `true` **only** when `Files.exists(leaseFilePath)` is true, `currentLease != null`, and `getLeaseData().getOwnerId().equals(currentLease.getOwnerId())`.
  - Return `false` if the file is missing, unreadable, or `null`.

---

## 2. [x] `S-5`: Fast-Path S3 Lock Release in `S3UploadLock.releaseLockResource()`

- **File**: [`S3UploadLock.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/s3/S3UploadLock.java#L118-L133)
- **Current State**: `releaseLockResource()` calls `!doesLockOwnershipMatch(lockKey)` (`GET locks/<id>.lock`) unconditionally on every lock release (line 123).
- **Required Fix**: Guard the remote `doesLockOwnershipMatch(lockKey)` check with `getLeaseData().isExpired(System.currentTimeMillis(), -2_000L)`:
  ```java
  if (lockKey != null
      && getLeaseData().isExpired(System.currentTimeMillis(), -2_000L)
      && !doesLockOwnershipMatch(lockKey)) {
    log.info(
        "Skipping deletion of S3 lock key {}: lock is currently held by another node", lockKey);
    return;
  }
  deleteS3Objects(lockKey, stopKey);
  ```
  This saves 1 S3 `GET` round-trip on every normal request lock release while the local lease is still well within its validity window, matching the fast-path already implemented in [`LeaseFileUploadLock.releaseLockResource()`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileUploadLock.java#L112-L127).

---

## 3. [x] `S-7` & `S-8`: Eliminate Redundant Metadata `GET` & Sequential Double-Deduplication / Double Metadata `PUT`

### 3.1 Reuse Passed `UploadInfo` in `S3StorageService.append(UploadInfo, InputStream)`
- **File**: [`S3StorageService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/s3/S3StorageService.java#L454-L460)
- **Current State**: `append(UploadInfo upload, InputStream inputStream)` calls `UploadInfo info = fetchAndValidateUpload(upload.getId());` on line 457, which unconditionally executes `getUploadInfo(uploadId)` (`GET metadata/<id>.info` from S3) on every `PATCH`, ignoring the `upload` instance already passed in by the caller (and cached by `ThreadLocalCachedStorageAndLockingService`).
- **Required Fix**: Validate and use the passed-in `upload` object directly when non-null (falling back to `getUploadInfo(upload.getId())` only if needed), saving 1 S3 `GET` per `PATCH`.

### 3.2 Remove Sequential Double `deduplicate()` in `S3StorageService`
- **File**: [`S3StorageService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/s3/S3StorageService.java#L1521-L1534)
- **Current State**: `finalizeCompletedUploadIfFinished` calls `deduplicate(info, key)` (lines 1521–1534), and then the caller (`append()` at line 556 or `update()` at lines 600–618) calls `update(info)`, which writes `metadata/<id>.info` and calls `deduplicate(info, key)` a second time sequentially after `inFlightDeduplications.remove(currentId)` has already completed.
- **Required Fix**: Remove the redundant `deduplicate(info, key)` call from `finalizeCompletedUploadIfFinished` so `update(info)` is the single place that persists metadata and runs `deduplicate(info, key)` once per finished upload.

### 3.3 Avoid Redundant `saveUploadInfo(upload)` After Deduplication in `AzureBlobStorageService`
- **File**: [`AzureBlobStorageService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/azure/AzureBlobStorageService.java#L235-L241) and [`AzureBlobStorageService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/azure/AzureBlobStorageService.java#L389-L395)
- **Current State**: In `create()` (lines 235–241) and `append()` (lines 389–395), `checkAndApplyDeduplication(upload)` invokes `deduplicate(upload, dataBlob)` (which calls `update(upload)` $\rightarrow$ `saveUploadInfo(upload)` when a duplicate is found or indexes the checksum), and then the caller unconditionally executes `saveUploadInfo(upload)` a second time.
- **Required Fix**: Persist `saveUploadInfo(upload)` once (or avoid re-saving inside `deduplicate`/`update` when `create()`/`append()` already saves the updated `UploadInfo`).

---

## 4. [x] `P-4`: NFS `.nfsXXXX` Silly-Rename & Empty `.lock` Directory Hardening in Disk Services

### 4.1 Use `Utils.deleteDirectoryQuietly(Path)` Across Disk Services
- **Files**:
  - [`Utils.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/util/Utils.java#L153-L178) (`deleteDirectoryQuietly` is implemented here, but currently unused)
  - [`LeaseFileLockingService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileLockingService.java#L144) (lines 144 and 380)
  - [`LeaseFileMutex.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileMutex.java#L116) (line 116)
  - [`DiskStorageService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/DiskStorageService.java#L503) (line 503)
  - [`DiskChecksumIndex.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/DiskChecksumIndex.java#L155) (line 155)
- **Current State**: Disk services still call `FileUtils.deleteDirectory(path.toFile())`, which throws `DirectoryNotEmptyException` / `IOException` on NFS if an unlinked file is temporarily held open as `.nfsXXXX`.
- **Required Fix**: Replace `FileUtils.deleteDirectory(...)` calls in the classes above with `Utils.deleteDirectoryQuietly(path)` so all regular files (`lease.json`, `stop.signal`, `owner.json`) are deleted first even if `Files.deleteIfExists(dir)` ignores `DirectoryNotEmptyException` on a lingering `.nfsXXXX` handle.

### 4.2 Remove 5-Second Empty-Directory Wait Inside `LeaseFileLockingService.tryAcquireLock`
- **File**: [`LeaseFileLockingService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileLockingService.java#L216-L241)
- **Current State**: Lines 225–238 check `EMPTY_DIR_GRACE_PERIOD_MS` (5,000 ms) when `lockDirPath` exists without `lease.json` and return `null` if the directory `mtime` is under 5 seconds old. On NFS, when a previous lock release deletes `lease.json` (`Utils.deleteDirectoryQuietly`), the directory's `mtime` is updated to `now`, causing the next request for the same upload to be falsely rejected as locked (`409` / `423`) for 5 seconds.
- **Required Fix**: Because `tryAcquireLock` executes **inside** the exclusive sibling `LeaseFileMutex` (`<UploadId>.mutex/`), no other node can be mid-creation of `lease.json`. When `lockDirPath` exists without an active `lease.json`, proceed immediately with in-place lock acquisition instead of waiting for `EMPTY_DIR_GRACE_PERIOD_MS`.

### 4.3 Check Sibling `.mutex` Directory in `LeaseFileLockingService.isLockDirectoryExpired`
- **File**: [`LeaseFileLockingService.java`](file:///Users/tom/projects/tus-java-server/src/main/java/me/desair/tus/server/upload/disk/LeaseFileLockingService.java#L330-L359)
- **Current State**: `isLocked(UploadId)` calls `isLockDirectoryExpired(lockDirPath)` without acquiring the `.mutex`. When `lease.json` is absent (lines 336–358), it checks `lockDirPath`'s `mtime` against `EMPTY_DIR_GRACE_PERIOD_MS` (5s), which treats an empty `.lock` directory left behind by an NFS `.nfsXXXX` silly-rename as "actively locked" for 5 seconds after unlock.
- **Required Fix**: When `lease.json` is missing in `isLockDirectoryExpired(lockDirPath)`, check whether the sibling mutex directory (`LeaseFileMutex.resolveMutexDir(lockDirPath)`) currently exists and has a recent `mtime` within the grace period. Only return `false` (unexpired / mid-creation) if the sibling `.mutex` directory exists; otherwise treat the empty `.lock` directory as expired (`true`) immediately.
