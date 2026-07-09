# Changelog

All notable changes to this project will be documented in this file.

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
