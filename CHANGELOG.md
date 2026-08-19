# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0]

### New

- **NFS- & SMB-Safe Lease Locking (`LeaseFileLockingService`)**: Added distributed, container-safe filesystem locking using atomic directory creation (`mkdir`) and TTL-based JSON lease files with background heartbeat renewal. Operates reliably across multi-server replicas on NFS (v3/v4), AWS EFS, Azure Files, Windows SMB/CIFS, and local disks without requiring Redis, ZooKeeper, or OS-level `FileLock` daemons. Comprehensive guide and legacy opt-out instructions available in `docs/DISK_BASED_LOCKING.md`.
- **S3-Compatible Storage & Distributed Locking**: Added native S3 storage support via `S3StorageService` (MinIO SDK), distributed locking via `S3LockingService` (S3 conditional writes with TTL leases and interrupt signals for multi-replica container deployments), S3-native concatenation via `S3ConcatenationService`, and complete documentation in `docs/S3_STORAGE.md`.
- **Azure Blob Storage & Distributed Leases**: Added native Azure Blob Storage support via `AzureBlobStorageService` (Block Blob staging with streaming appends, sub-threshold buffering, truncation, and deduplication), distributed locking via `AzureBlobLockingService` (Azure Blob Leases with auto-renewal, JVM interruption, cross-replica `.stop` signals, and clean shutdown), zero-copy server-side concatenation via `AzureBlobConcatenationService` (`stageBlockFromUrl`), and comprehensive documentation in `docs/AZURE_BLOB_STORAGE.md`.
- **IETF Resumable Uploads for HTTP (RUFH) Protocol**: Implemented full support for the official IETF Resumable Uploads for HTTP specification (`draft-ietf-httpbis-resumable-upload-12`).
  - **Dual Protocol Auto-Detection**: Added transparent protocol routing in `TusFileUploadService` supporting both legacy `TUS_1_0_0` (`Tus-Resumable: 1.0.0`) and `RUFH` (`ProtocolVersion.RUFH`) clients concurrently on the same endpoint.
  - **RFC 9651 Structured Header Fields**: Implemented RFC 9651 parsing and serialization for `Upload-Offset`, `Upload-Complete`, `Upload-Length`, and `Upload-Limit` dictionary headers.
  - **RFC 7807 Problem Details JSON**: Added support for standard `application/problem+json` error responses (`mismatching-upload-offset`, `completed-upload`, `inconsistent-upload-length`).
  - **Dedicated Compliance Test Suites**: Added comprehensive, spec-quoted end-to-end tests using a dedicated Python script `scripts/rufh_conformity_test.py` with documentation on how to run the tests in `docs/CONFORMITY_TESTING.md`.
  - **User Migration & Interim Responses Documentation**: Added `docs/MIGRATION.md` and `docs/INTERIM_RESPONSES.md` detailing migration strategies, HTTP 104 status frames under IETF RUFH, Tomcat/Servlet container limitations, cached reflection optimizations, and Spring Boot Tomcat Valve integration.
- **JSON Serialization**: Support storing `UploadInfo` objects as JSON files in the storage backend using `TusFileUploadService.withJsonSerialization(true)`.

### Changed
- **Default Disk-Based Locking**: `TusFileUploadService.withStoragePath(String)` now defaults to `LeaseFileLockingService` instead of `DiskLockingService` for out-of-the-box Kubernetes, container, and shared network storage compatibility. See `docs/DISK_BASED_LOCKING.md` for legacy opt-out instructions.
- **Calibrated Retry Budget**: Extended `TusFileUploadService` lock acquisition retry budget to 8.0 seconds (40 retries x 200ms) to ensure reliable contention resolution over network storage.
- **Absolute Base URL & Location Header Support**: Extended `withUploadUri(String)` to accept absolute base URLs (e.g. `https://upload.example.com/files`), returning full URLs in `Location` response headers for upload creation across both Tus 1.0.0 and RUFH protocols while preserving backward compatibility for relative paths.

### Fixed
- **Clear Content-Length on Error Responses**: Cleared `Content-Length` response header prior to invoking `HttpServletResponse.sendError(...)` during exception handling, resolving buffer conflicts and exceptions in Undertow and other servlet containers ([#40](https://github.com/tomdesair/tus-java-server/issues/40)).

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
