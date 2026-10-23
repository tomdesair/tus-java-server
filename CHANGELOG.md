# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0]

Major release adding the IETF Resumable Uploads for HTTP (RUFH) protocol and cloud storage backends. **Upgrading from `1.0.0-3.x`? Read the [Upgrade Guide](docs/UPGRADING.md).**

### Highlights
- IETF Resumable Uploads for HTTP (`draft-ietf-httpbis-resumable-upload-12`) served next to Tus 1.0.0 on the same endpoint.
- S3-compatible and Azure Blob storage backends with distributed, database-free locking.
- NFS/SMB-safe lease locking is now the default for disk storage.
- Server-side `UploadCompletionListener` and a `process()` method returning the `UploadInfo`.

### Upgrade notes (breaking & behavioural changes)
See [docs/UPGRADING.md](docs/UPGRADING.md) for details and code examples.
- **Java 17 and Jakarta EE**: requires Java 17+ and `jakarta.servlet.*` (Tomcat 10+, Jetty 11+, Spring Boot 3+). `javax.servlet` is no longer supported.
- **Default locking**: `withStoragePath(String)` now uses `LeaseFileLockingService` instead of `DiskLockingService`. Opt out with `withUploadLockingService(new DiskLockingService(path))`, see [docs/DISK_BASED_LOCKING.md](docs/DISK_BASED_LOCKING.md).
- **Lock-holding streams**: `getUploadedBytes(...)` keeps the upload locked until the returned stream is closed. Always use try-with-resources.
- **`process(...)` return value**: now returns the created/updated `UploadInfo` on successful `POST`, `PATCH`, and `HEAD` requests (or `null` on errors, `OPTIONS`, or `DELETE` termination). It also returns a value for progress requests, so check the HTTP method when recording a newly created upload.
- **Download extension (breaking)**: no longer returns `204` for uploads still in progress and no longer sets the `Tus-Resumable` header (needed for dual-protocol support). The `UploadInProgressException` class was removed.
- **Jackson bundled**: `jackson-databind`, `jackson-annotations` and `jackson-core` are now compile-scope dependencies; no separate declaration needed.
- **Protocol detection**: `ProtocolVersion.AUTO` is the default. Restrict with `withSupportedProtocolVersions(...)`, see [docs/PROTOCOLS.md](docs/PROTOCOLS.md).
- **Checksum Deduplication Index**: The checksum index format has been updated to support reference tracking, parent tombstoning, and full cleanup upon upload deletion across Disk, S3, and Azure Blob storage. See [docs/DEDUPLICATION.md](docs/DEDUPLICATION.md) for migration instructions (clearing the index directory or running the migration script).

### New
- **IETF Resumable Uploads for HTTP (RUFH)**: full draft-12 support with automatic protocol detection, RFC 9651 structured headers (`Upload-Offset`, `Upload-Complete`, `Upload-Length`, `Upload-Limit`), RFC 7807 `application/problem+json` errors, RFC 9530 HTTP digests, `104` interim responses and a spec-quoted conformity suite (`scripts/rufh_conformity_test.py`). See [docs/PROTOCOLS.md](docs/PROTOCOLS.md), [docs/MIGRATION.md](docs/MIGRATION.md), [docs/INTERIM_RESPONSES.md](docs/INTERIM_RESPONSES.md) and [docs/CONFORMITY_TESTING.md](docs/CONFORMITY_TESTING.md).
- **NFS- & SMB-safe lease locking (`LeaseFileLockingService`)**: atomic sibling mutex directories, TTL lease files with heartbeat renewal and ownership fencing; safe on NFS v3/v4, AWS EFS, Azure Files, SMB/CIFS and local disks, with no Redis, ZooKeeper or `FileLock` daemons. See [docs/DISK_BASED_LOCKING.md](docs/DISK_BASED_LOCKING.md).
- **S3-compatible storage**: `S3StorageService`, `S3LockingService` (conditional writes, TTL leases, automatic non-CAS fallback for e.g. Backblaze B2 and Ceph RGW, configurable jitter via `withJitter(minMs, maxMs)` and `withS3ConditionalWritesSupported(boolean)`) and `S3ConcatenationService`, including server-side multipart composition that works on AWS S3 and MinIO/Ceph. See [docs/S3_STORAGE.md](docs/S3_STORAGE.md).
- **Azure Blob Storage**: `AzureBlobStorageService`, `AzureBlobLockingService` (Blob Leases with auto-renewal and cross-replica stop signals) and zero-copy `AzureBlobConcatenationService`. See [docs/AZURE_BLOB_STORAGE.md](docs/AZURE_BLOB_STORAGE.md).
- **`UploadCompletionListener`**: register via `withUploadCompletionListener(...)` / `addUploadCompletionListener(...)`. Listeners run after the lock is released, so they can stream and delete the bytes immediately. Added `getUploadedBytes(UploadInfo)` and `deleteUpload(UploadInfo)` overloads.
- **`withLockWaitTimeout(Duration)`** (default 60 s): maximum time a request waits for a contended upload lock; also derives the cloud chunk drain timeout (`lockWaitTimeout - 5 s`) and retry budget. See [docs/LOCKING.md](docs/LOCKING.md).
- **Automatic lock lifecycle cleanup** on release, termination (`DELETE`), and expiration across all locking services, deleting lock artifacts immediately without leaving lingering locks, plus a fallback sweeper for abandoned locks.
- **JSON serialization**: store `UploadInfo` as JSON with `withJsonSerialization(true)`.
- **Absolute base URLs**: `withUploadUri(String)` accepts e.g. `https://upload.example.com/files`, returning full `Location` URLs for Tus and RUFH; relative paths keep working.
- **Multi-Backend Upload Deduplication & Lifecycle Cleanup**: Content-based upload deduplication with `withUploadDeduplication(true)` across Disk, S3, and Azure Blob storage. Features child reference markers, parent tombstoning, and complete index directory cleanup when uploads expire or are deleted. See [docs/DEDUPLICATION.md](docs/DEDUPLICATION.md).
- **`NoOpUploadLockingService`**: Zero-overhead Null Object implementation of `UploadLockingService` for unit tests and custom environments, making locking service usage mandatory and eliminating defensive null checks across all storage backends, request handlers, and deduplication logic.

### Fixed
- **Azure Lock Cleanup**: eliminated lingering `.lock` blobs in Azure Blob Storage by having `AzureBlobUploadLock.release()` delete the actively leased blob directly using lease conditions upon lock release, and cleaning up orphaned lock artifacts in `cleanupExpiredUploads()`.
- **Storage Backend Utilities**: introduced dedicated `DiskUtils` and expanded `AzureUtils` alongside `S3Utils`, centralizing safe path resolution, storage directory initialization, path component validation, checksum normalization, blob existence checks, and quiet resource deletion across storage, checksum, and locking backends.
- **S3 Upload Performance**: reduced S3 upload latency by ~70% (~3x speedup in conformity benchmarks) via optimistic conditional writes (`If-None-Match: *`) during lock acquisition, caching part sizes to avoid repeated `statObject` roundtrips, and stream-based byte truncation.
- **Lock Lifecycle & Expired Upload Cleanup**: unified `cleanupExpiredUploads()` across Disk, S3, and Azure backends to clean up orphaned locks and prune stale checksum index entries, while ensuring `terminateUpload()` relies on the active `UploadLock` lifecycle.
- **Async Chunk Uploading**: hardened `AsyncChunkUploader` against servlet thread pool starvation under `CallerRunsPolicy` and fixed race conditions during temporary chunk file deletions.
- **Network Filesystem Locking**: prevented `FileChannel.lock()` `ENOLCK` failures on distributed filesystems (NFS, AWS EFS, Kubernetes PVs) by restricting kernel file locks exclusively to legacy `DiskLockingService`.
- **Deduplication Resilience**: protected deduplication traversal against infinite cyclic recursion (`StackOverflowError`) and handled missing concatenation parts safely.
- **Content-Type matching**: media types are matched per RFC 9110 and tolerate parameters such as `;charset=UTF-8` (added by Spring `CharacterEncodingFilter`, proxies or clients) instead of returning `406`.
- **Undertow & other containers**: `Content-Length` is cleared before `sendError(...)` ([#40](https://github.com/tomdesair/tus-java-server/issues/40)).
- **Disk**: truncate underflow guard in `DiskStorageService`; `FileChannel` leak on lock acquisition errors fixed in `FileBasedLock`.

### Deprecated
- **`UploadStorageService.cleanupExpiredUploads(UploadLockingService)`**: Deprecated in favor of the zero-argument `cleanupExpiredUploads()`, as storage services coordinate locking through `setUploadLockingService(UploadLockingService)`.

### Security
- **Path traversal protection**: hardened `LeaseFileLockingService` against directory traversal via crafted upload identifiers ([#141](https://github.com/tomdesair/tus-java-server/pull/141)).
- **CORS origin validation**: prevented arbitrary `Origin` header reflection in CORS preflight responses ([#132](https://github.com/tomdesair/tus-java-server/pull/132)).
- **Secure temporary files**: replaced legacy `File.createTempFile` with `Files.createTempFile` enforcing owner-only POSIX permissions (`0600`, CWE-378 / SonarQube java:S5443) ([#131](https://github.com/tomdesair/tus-java-server/pull/131)).

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
