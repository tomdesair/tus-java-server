# Agent Instructions

## GitHub CLI (`gh`) Usage
When running `gh` commands in this project via an automated agent environment, ensure you bypass the default `GITHUB_TOKEN` environment variable. The agent environment may have an invalid `GITHUB_TOKEN` set, which `gh` prioritizes over valid keyring credentials, resulting in an `HTTP 401: Bad credentials` error.

**Workaround:** Prefix `gh` commands with `env -u GITHUB_TOKEN` to force the CLI to use the valid keyring authentication.

Example:
```bash
env -u GITHUB_TOKEN gh pr create --title "..." --body "..."
```

## Git Branching Strategy
Any new feature, bugfix, or improvement must be developed in a separate branch that starts with either `feature/` or `bugfix/` and has a meaningful but short name (e.g., `feature/lock-contention-resolution` or `bugfix/fix-upload-timeout`).

## Releases

### CHANGELOG.md
When adding new features or preparing a release, the `CHANGELOG.md` file must be updated to describe the new functionality added in this version. Use a release version header (e.g., `## [1.0.0-3.2]`) instead of `## [Unreleased]`. Derive this next release version from the SNAPSHOT version declared in the `pom.xml` file by removing the `-SNAPSHOT` suffix. Make sure to not add duplicate headers.

## Release Process
When performing a release, please strictly follow the instructions outlined in the [docs/RELEASE.md](docs/RELEASE.md) documentation file.

## Developer Guidelines & Code Architecture

### 1. Spring Boot & Java Requirements
- **Java Version**: The project is configured for **Java 17** (or newer) to align with Spring Boot 3.x requirements.
- **Jakarta EE / Servlets**: Always use `jakarta.servlet.*` package imports instead of the legacy `javax.servlet.*` packages.

### 2. Serializable UploadInfo & Backward Compatibility
- The `UploadInfo` class is stored on disk serialized. If you modify fields in `UploadInfo`, you **must** preserve the `serialVersionUID = -8751200491586638308L` to ensure pre-existing uploads on disk do not trigger `InvalidClassException` upon deserialization.
- Backward compatibility is paramount for this project. Breaking changes should only be done if all other options lead to ugly code and design. Breaking changes require a new major version.
- When expanding interfaces like `UploadLockingService` or `UploadStorageService`, always use Java `default` methods to avoid breaking custom third-party implementations.

### 3. File Deduplication and Read/Write Safety
The deduplication mechanism links duplicate uploads (child) to the original upload (parent) using the `duplicatesUploadId` field in `UploadInfo`.
- **Read Operations**: Methods that read data (e.g., `getUploadedBytes`, `copyUploadTo` in `DiskStorageService`) should dynamically resolve `duplicatesUploadId` to the parent upload ID if it is set.
- **Write/Modify Operations**: Methods that write or truncate data (e.g., `append`, `removeLastNumberOfBytes` in `DiskStorageService`) **must not** resolve `duplicatesUploadId` recursively. They must only operate on the target upload's own physical files to guarantee parent files are never modified or truncated when handling child upload errors.

### 4. Checksum Index Storage & Self-Cleaning
Completed parent uploads are indexed by checksum under the `<storagePath>/checksums/<algorithm>/<checksum_value>` file path containing the target `UploadId`.
- Index lookup includes a self-cleaning check: if the index points to an upload that is null or whose data file is missing (e.g., due to expiration), the index file is deleted on the fly, keeping the file system clean without needing a separate index sweeper.
- Child uploads (duplicates) are never indexed.
- On parent termination, the parent's index entry is explicitly deleted.

### 5. No Thread-Local Contexts
- Do not use `ThreadLocal` variables or thread-local request context to pass state between components. Always pass parameters explicitly or use request wrapping.

### 6. Custom Locking Implementations
- When writing custom `UploadLockingService` implementations, you should implement `registerInputStream` and `requestLockRelease` if the backing store (e.g. Redis, database, S3) needs to support lock contention resolution/interruption across nodes.

### 7. Unit Test Coverage
- Unit test coverage must remain high. All new code (including background watchdog threads, helper methods, stream wrappers, and retry logic) must be thoroughly unit tested.

### 8. No Code Duplication & Reusability
- Avoid code duplication wherever possible. Consolidate repetitive logic (e.g., resolving file-system paths, reading upload IDs, creating/releasing locks) into reusable helper methods or utility functions. Always prioritize code reuse by extracting common logic into reusable helper functions or utilities. Keep file path resolutions consolidated.

### 9. Documentation & Comments
- All new classes, interfaces, and non-obvious code blocks (e.g., watchdog lifecycle, stream wrapping logic, thread-safety mechanisms) must have detailed comments describing their purpose, behavior, and design decisions.

## Release Process & Changelog Guidelines
