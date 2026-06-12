# Agent Instructions

## GitHub CLI (`gh`) Usage
When running `gh` commands in this project via an automated agent environment, ensure you bypass the default `GITHUB_TOKEN` environment variable. The agent environment may have an invalid `GITHUB_TOKEN` set, which `gh` prioritizes over valid keyring credentials, resulting in an `HTTP 401: Bad credentials` error.

**Workaround:** Prefix `gh` commands with `env -u GITHUB_TOKEN` to force the CLI to use the valid keyring authentication.

Example:
```bash
env -u GITHUB_TOKEN gh pr create --title "..." --body "..."
```

## Release Process
When performing a release, please strictly follow the instructions outlined in the [docs/RELEASE.md](docs/RELEASE.md) documentation file.

## Developer Guidelines & Code Architecture

### 1. Serializable UploadInfo & Backward Compatibility
The `UploadInfo` class is stored on disk serialized. If you modify fields in `UploadInfo`, you **must** preserve the `serialVersionUID = -8751200491586638308L` to ensure pre-existing uploads on disk do not trigger `InvalidClassException` upon deserialization.

### 2. File Deduplication and Read/Write Safety
The deduplication mechanism links duplicate uploads (child) to the original upload (parent) using the `duplicatesUploadId` field in `UploadInfo`.
- **Read Operations**: Methods that read data (e.g., `getUploadedBytes`, `copyUploadTo` in `DiskStorageService`) should dynamically resolve `duplicatesUploadId` to the parent upload ID if it is set.
- **Write/Modify Operations**: Methods that write or truncate data (e.g., `append`, `removeLastNumberOfBytes` in `DiskStorageService`) **must not** resolve `duplicatesUploadId` recursively. They must only operate on the target upload's own physical files to guarantee parent files are never modified or truncated when handling child upload errors.

### 3. Checksum Index Storage & Self-Cleaning
Completed parent uploads are indexed by checksum under the `<storagePath>/checksums/<algorithm>/<checksum_value>` file path containing the target `UploadId`.
- Index lookup includes a self-cleaning check: if the index points to an upload that is null or whose data file is missing (e.g., due to expiration), the index file is deleted on the fly, keeping the file system clean without needing a separate index sweeper.
- Child uploads (duplicates) are never indexed.
- On parent termination, the parent's index entry is explicitly deleted.
