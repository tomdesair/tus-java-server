# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0]

### New

- **NFS- & SMB-Safe Lease Locking (`LeaseFileLockingService` & `LeaseFileMutex`)**: Added distributed, container-safe filesystem locking using atomic sibling mutex directories (`<UploadId>.mutex/`), in-place expired lock takeover, and TTL-based JSON lease files with background heartbeat renewal and ownership fencing. Operates reliably across multi-server replicas on NFS (v3/v4), AWS EFS, Azure Files, Windows SMB/CIFS, and local disks without requiring Redis, ZooKeeper, or OS-level `FileLock` daemons. Comprehensive guide and legacy opt-out instructions available in `docs/DISK_BASED_LOCKING.md`.
- **S3-Compatible Storage & Distributed Locking**: Added native S3 storage support via `S3StorageService`, distributed locking via `S3LockingService` (S3 conditional writes with TTL leases and interrupt signals for multi-replica container deployments), S3-native concatenation via `S3ConcatenationService`, configured entirely via standard S3 connection parameters, and complete documentation in `docs/S3_STORAGE.md`.
- **Azure Blob Storage & Distributed Leases**: Added native Azure Blob Storage support via `AzureBlobStorageService` (Block Blob staging with streaming appends, sub-threshold buffering, truncation, and deduplication), distributed locking via `AzureBlobLockingService` (Azure Blob Leases with auto-renewal, JVM interruption, cross-replica `.stop` signals, and clean shutdown), zero-copy server-side concatenation via `AzureBlobConcatenationService` (`stageBlockFromUrl`), and comprehensive documentation in `docs/AZURE_BLOB_STORAGE.md`.
- **IETF Resumable Uploads for HTTP (RUFH) Protocol**: Implemented full support for the official IETF Resumable Uploads for HTTP specification (`draft-ietf-httpbis-resumable-upload-12`).
  - **Dual Protocol Auto-Detection**: Added transparent protocol routing in `TusFileUploadService` supporting both legacy `TUS_1_0_0` (`Tus-Resumable: 1.0.0`) and `RUFH` (`ProtocolVersion.RUFH`) clients concurrently on the same endpoint.
  - **RFC 9651 Structured Header Fields**: Implemented RFC 9651 parsing and serialization for `Upload-Offset`, `Upload-Complete`, `Upload-Length`, and `Upload-Limit` dictionary headers.
  - **RFC 7807 Problem Details JSON**: Added support for standard `application/problem+json` error responses (`mismatching-upload-offset`, `completed-upload`, `inconsistent-upload-length`).
  - **Dedicated Compliance Test Suites**: Added comprehensive, spec-quoted end-to-end tests using a dedicated Python script `scripts/rufh_conformity_test.py` with documentation on how to run the tests in `docs/CONFORMITY_TESTING.md`.
  - **User Migration & Interim Responses Documentation**: Added `docs/MIGRATION.md` and `docs/INTERIM_RESPONSES.md` detailing migration strategies, HTTP 104 status frames under IETF RUFH, Tomcat/Servlet container limitations, cached reflection optimizations, and Spring Boot Tomcat Valve integration.
- **Server-Side Upload Completion Listeners (`UploadCompletionListener`)**: Added a functional interface callback mechanism allowing developers to register post-upload listeners via `withUploadCompletionListener(UploadCompletionListener)` or `addUploadCompletionListener(UploadCompletionListener)`. Listeners receive the completed `UploadInfo` and `TusFileUploadService` instance after lock release, allowing immediate byte streaming and deletion without contention. Added helper overloads `TusFileUploadService.getUploadedBytes(UploadInfo)` and `TusFileUploadService.deleteUpload(UploadInfo)`.
- **Unified Lock Wait & Cloud Drain Timeout**: Added `TusFileUploadService.withLockWaitTimeout(Duration)` (default 60 seconds) to configure the maximum wait duration for requests contending for an active upload lock. Automatically derives the background cloud upload chunk drain timeout (`lockWaitTimeout - 5 seconds`, default 55s) and the retry polling budget (`lockWaitTimeout / 200ms`, default 300 retries).
- **JSON Serialization**: Support storing `UploadInfo` objects as JSON files in the storage backend using `TusFileUploadService.withJsonSerialization(true)`.

### Changed
- **Default Disk-Based Locking**: `TusFileUploadService.withStoragePath(String)` now defaults to `LeaseFileLockingService` instead of `DiskLockingService` for out-of-the-box Kubernetes, container, and shared network storage compatibility. See `docs/DISK_BASED_LOCKING.md` for legacy opt-out instructions.
- **Unified Lock Wait Budget & Cloud Chunk Drain Calibration**: Replaced unreleased `withMaxLockRetries` with `withLockWaitTimeout(Duration)` (default 60s / 300 retries), synchronizing the lock wait timeout budget across storage backends and automatically deriving the cloud chunk drain timeout (`lockWaitTimeout - 5s`, default 55s) to guarantee in-flight chunks are cleanly flushed to cloud storage before releasing upload locks.
- **Absolute Base URL & Location Header Support**: Extended `withUploadUri(String)` to accept absolute base URLs (e.g. `https://upload.example.com/files`), returning full URLs in `Location` response headers for upload creation across both Tus 1.0.0 and RUFH protocols while preserving backward compatibility for relative paths.
- **`process()` Return Value (`UploadInfo`)**: `TusFileUploadService.process(...)` now returns the created or updated `UploadInfo` instance (or `null` on errors or `OPTIONS` preflight requests), enabling applications to track and store upload IDs directly into user sessions or database repositories.
- **Jackson Bundled in Compile Scope**: Promoted Jackson dependencies (`jackson-databind`, `jackson-annotations`, `jackson-core`) to `compile` scope, eliminating `NoClassDefFoundError` when enabling JSON serialization or using cloud storage.
- **Lock-Holding Stream Lifecycle**: `TusFileUploadService.getUploadedBytes(...)` now retains the upload lock until the returned stream is closed, preventing concurrent modifications or deletions from corrupting data mid-stream.
- **S3 Locking Optimization & Non-CAS Fallback**: Optimized `S3LockingService` and `S3UploadLock` by skipping the redundant `isLockExpired` pre-check on happy-path acquisitions (saving 1 remote GET round-trip) and using S3 Multi-Object Delete to delete `.lock` and `.stop` objects in a single batch round-trip on lock release. Made read-after-write verification jitter bounds configurable via `AbstractLeaseLockingService.setJitter(minMs, maxMs)` and `S3LockingService.withJitter(minMs, maxMs)`. Added automatic non-CAS fallback when `If-None-Match: *` returns `HTTP 501 Not Implemented` (enabling seamless out-of-the-box support for Backblaze B2 and Ceph RGW) along with `withS3ConditionalWritesSupported(boolean)` override. Streamlined `S3LockingService` constructors to minimal required set and documented zero-jitter throughput optimization for pure AWS S3 / Cloudflare R2 deployments in `docs/S3_STORAGE.md`.

### Fixed
- **RFC 9110 Media Type Matching & MIME Parameter Tolerance**: Enhanced `Content-Type` header validation in `ContentTypeValidator`, `PostContentTypeValidator`, and `RufhAppendValidator` using RFC 9110 §8.3 compliant media-type parsing (`Utils.isMediaType`). Media type matching now tolerates MIME parameters (such as `;charset=UTF-8` automatically appended by Spring Boot `CharacterEncodingFilter`, servlet wrappers, proxies, or HTTP clients), whitespace variations, and case-insensitivity without incorrectly rejecting valid requests with `406 Not Acceptable`.
- **Clear Content-Length on Error Responses**: Cleared `Content-Length` response header prior to invoking `HttpServletResponse.sendError(...)` during exception handling, resolving buffer conflicts and exceptions in Undertow and other servlet containers ([#40](https://github.com/tomdesair/tus-java-server/issues/40)).
- **Prevent Disk Truncate Underflow**: Guarded file truncate logic in `DiskStorageService` against underflow when removing bytes (`Math.max(0L, file.size() - byteCount)`).
- **File Channel Leak Prevention in FileBasedLock**: Guaranteed `FileChannel` is closed immediately upon lock acquisition errors to avoid file descriptor leaks.
- **Cloud Upload Pause & Drain Timeout Resilience**: Integrated `AsyncChunkUploader` default drain timeout (55 seconds, calibrated to `lockWaitTimeout - 5s`) with error-resilient recovery logic across `AzureBlobStorageService` and `S3StorageService`. Confirmed uploaded chunks and staged Azure blocks are committed and metadata (`UploadInfo` offset) is persisted to storage before throwing stream or drain exceptions, ensuring paused uploads (such as Uppy client pause/resume) preserve their progress and resume from the exact byte offset instead of restarting from 0.
- **Azure Blob Storage Upload Cancellation Lease Safety**: Checked lock blob lease state before calling `deleteIfExists()` in `AzureBlobStorageService.terminateUpload()`. When an active lease is held on the lock blob by an ongoing request (e.g. `DELETE` cancellation), attempting deletion without specifying the lease ID triggered an Azure SDK error log (`HTTP 412 LeaseIdMissing`). Skipping deletion for actively leased blobs prevents this error and allows normal lease release upon request completion.
- **Lock Contention Stream Interruption Handling**: Handled `IOException` from `InterruptibleInputStream` across upload request handlers (`CorePatchRequestHandler`, `RufhAppendPatchRequestHandler`, `RufhCreationPostRequestHandler`, `CreationWithUploadPostRequestHandler`). When an upload stream is interrupted by the locking service watchdog during lock contention (such as concurrent `HEAD` progress checks or `DELETE` cancellation requests), the storage backend commits all buffered bytes received so far and updates the offset in storage. Request handlers now reload the refreshed `UploadInfo` and return a clean HTTP 204/201 response with the updated offset rather than bubbling an unhandled `IOException` / HTTP 500 to the servlet container.
- **S3 Server-Side Part Composition & Native Multipart Copy**: Implemented `S3ServerSideComposeHelper` to resolve AWS S3 header rejection during server-side part composition. MinIO Java SDK 9.0.3's `composeObject` delegates to `UploadPartCopy` with an empty body placeholder that automatically injects `Content-MD5` and `Content-Type` headers, which Amazon AWS S3 strictly forbids on part copy requests and rejects with HTTP 400 (`The specified header is not valid in this context`). `S3ServerSideComposeHelper` executes native S3 multipart copy requests directly with SigV4 signing omitting `Content-MD5`, allowing fast zero-bandwidth server-side composition on both AWS S3 and MinIO/Ceph clusters without external AWS SDK dependencies. Added reflection-free constructors to `S3StorageService` and `S3LockingService` accepting connection parameters directly, with `eu-central-1` as the default fallback region, while preserving multi-tier streaming concatenation fallbacks.
- **S3 & Azure Pause/Resume Lock Contention & Byte Integrity**: Resolved lock contention and data corruption issues when uploads are repeatedly paused and resumed:
  - **Lock Release on Interruption**: Updated `AsyncChunkUploader` with periodic contention polling (`waitForInFlight`) and bounded worker aborts (`500ms`), ensuring background upload threads cleanly terminate when locks are released and prevented locks from lingering until TTL expiry.
  - **Manifest-Tracked Parts (`uploadPartKeys`)**: Replaced non-deterministic S3 prefix listing with an authoritative `uploadPartKeys` manifest in `UploadInfo`. Stale tail buffers are preserved in S3 until new manifests are committed, preventing byte loss or duplicate byte prepends (+8 MB offsets or drops to 0) during multiple pause/resume cycles.
  - **Multi-Object Delete with Resilient Fallback**: Implemented batch Multi-Object Delete (`POST /?delete`) in `S3UploadLock` and `S3StorageService` with automatic per-object `DELETE` fallback when batch deletes are restricted by bucket IAM policies or unsupported by S3-compatible providers.
  - **Transient Network Tolerance in Lease Renewals**: Tolerated transient network blips in `AbstractLeaseLock` and `AzureBlobUploadLock`, preventing unexpired valid locks from being prematurely aborted during heartbeat retries.
  - **Graceful Lock Eviction**: Handled `NO_SUCH_KEY` cleanly in `S3LockingService.evictExpiredLock()` without generating false eviction warnings when locks were already released by their owners.

### Breaking
- **Downloads**: In order to support both the Tus protocol and RUFH protocol, the unofficial download extension will not return a HTTP status code `204` for uploads that are still in progress and will not contain the response header `Tus-Resumable`. Removed the `UploadInProgressException` class.

## [1.0.0-3.3]

### Added
- **Creation-with-Upload Extension**: Implemented the optional `creation-with-upload` extension, allowing clients to combine creation and initial file data upload in a single `POST` request.
- **CORS Extension**: Implemented native, out-of-the-box CORS support as an unofficial extension (`cors`) enabled by default. For backward compatibility, it can be disabled via `disableTusExtension("cors")`.

### Changed
- **Stricter Protocol Validation**:
  - Prevent modifying `Upload-Length` headers in subsequent `PATCH` requests.
  - Enforced format and Base64 validations for `Upload-Metadata` headers in `POST` requests.
  - Enforced that `Upload-Defer-Length` header values must be strictly `"1"`.
  - Reject malformed or invalid `Upload-Checksum` headers instead of silently ignoring them.
  - Enabled checksum verification on `POST` requests when using the `creation-with-upload` extension.

### Fixes
  - Only unfinished uploads can expire.
  - Fix for deduplication feature when base64-encoded checksum contains a slash.

## [1.0.0-3.2]

### Added
- **Lock Contention Resolution**: Allow resuming clients to immediately release upload locks held by stalled upload requests via `HEAD` requests. Supports both single-instance and multi-replica/Kubernetes deployments without breaking backward compatibility of the locking interfaces.
- **File Deduplication by Hash**: Implemented optional, space-saving duplicate file detection and linking based on file checksums.
  - Added `withUploadDeduplication(boolean)` builder method on `TusFileUploadService` (default: `false` for backward compatibility).
  - Introduced index system under `<storagePath>/checksums/<algorithm>/<checksum_value>` for mapping file checksums to their original completed upload IDs.
  - Implemented safe read-only recursion in `DiskStorageService` for child uploads: read operations (`getUploadedBytes`, `copyUploadTo`) recursively resolve to the parent upload, while write/truncate operations (`append`, `removeLastNumberOfBytes`) remain strictly bounded to the child ID to avoid accidental parent modifications.
  - Added parent-child expiration coordination: parent upload's expiration timestamp is automatically updated to be greater than or equal to any linked child upload's expiration.
  - Self-cleaning index system: dangling index entries resulting from parent deletion/expiration are automatically detected and removed on the fly.
  - Added new `duplicatesUploadId`, `checksum`, and `checksumAlgorithm` fields to `UploadInfo`.
- **Backward Compatibility**: Explicitly declared `serialVersionUID = -8751200491586638308L` inside `UploadInfo` to prevent serialization version mismatches for pre-existing upload data on disk.
- **Deduplication of Parsing Logic**: Introduced `Utils.ChecksumInfo` and `Utils.parseUploadChecksumHeader` to completely centralize header validation and parsing.
