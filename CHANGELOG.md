# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0-3.2]

### Added
- **Lock Contention Resolution & Resumability**: Implemented upload lock contention resolution where subsequent `HEAD` requests force-release locks held by stalled `PATCH` requests on half-open sockets, allowing immediate client resumption without waiting for a TCP timeout.
  - Added default methods `registerInputStream(String requestUri, InputStream inputStream)` and `requestLockRelease(String requestUri)` on `UploadLockingService` for backwards compatibility.
  - Implemented JVM-local active lock tracking with weak references to prevent memory leaks.
  - Implemented multi-process / Kubernetes cluster support via file-system signaling (`.stop` files in the shared locks directory).
  - Integrated daemon watchdog thread with `MIN_PRIORITY` and robust exception recovery to safely manage remote lock releases.
- **File Deduplication by Hash**: Implemented optional, space-saving duplicate file detection and linking based on file checksums.
  - Added `withUploadDeduplication(boolean)` builder method on `TusFileUploadService` (default: `false` for backward compatibility).
  - Introduced index system under `<storagePath>/checksums/<algorithm>/<checksum_value>` for mapping file checksums to their original completed upload IDs.
  - Implemented safe read-only recursion in `DiskStorageService` for child uploads: read operations (`getUploadedBytes`, `copyUploadTo`) recursively resolve to the parent upload, while write/truncate operations (`append`, `removeLastNumberOfBytes`) remain strictly bounded to the child ID to avoid accidental parent modifications.
  - Added parent-child expiration coordination: parent upload's expiration timestamp is automatically updated to be greater than or equal to any linked child upload's expiration.
  - Self-cleaning index system: dangling index entries resulting from parent deletion/expiration are automatically detected and removed on the fly.
  - Added new `duplicatesUploadId`, `checksum`, and `checksumAlgorithm` fields to `UploadInfo`.
- **Backward Compatibility**: Explicitly declared `serialVersionUID = -8751200491586638308L` inside `UploadInfo` to prevent serialization version mismatches for pre-existing upload data on disk.
- **Deduplication of Parsing Logic**: Introduced `Utils.ChecksumInfo` and `Utils.parseUploadChecksumHeader` to completely centralize header validation and parsing.
