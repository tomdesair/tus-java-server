# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0]

### Added
- **IETF Resumable Uploads for HTTP (RUFH) Protocol Compliance**: Implemented full support for the official IETF Resumable Uploads for HTTP specification (`draft-ietf-httpbis-resumable-upload`).
- **Dual Protocol Auto-Detection**: Added transparent protocol routing in `TusFileUploadService` supporting both legacy `TUS_1_0_0` (`Tus-Resumable: 1.0.0`) and `RUFH` (`ProtocolVersion.RUFH`) clients concurrently on the same endpoint.
- **RFC 9651 Structured Header Fields**: Implemented RFC 9651 parsing and serialization for `Upload-Offset`, `Upload-Complete`, `Upload-Length`, and `Upload-Limit` dictionary headers.
- **RFC 7807 Problem Details JSON**: Added support for standard `application/problem+json` error responses (`mismatching-upload-offset`, `completed-upload`, `inconsistent-upload-length`).
- **Dedicated Compliance & Security Test Suites**: Added comprehensive, spec-quoted unit tests under package `me.desair.tus.server.ietf` and security tests under `me.desair.tus.server.ietf.security` verifying Path Traversal protection, DoS limits, CRLF sanitization, and lock safety.
- **User Migration Documentation**: Added `docs/MIGRATION.md` detailing steps for transitioning from Tus 1.0.0 to IETF RUFH.

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
