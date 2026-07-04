# Agent Instructions

## Project Context
When working on this project, always read the [`README.md`](README.md) file to obtain full context on project architecture, features, configuration options, and dual protocol version support (Tus 1.0.0 & IETF RUFH).

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

### Release Process
When performing a release, please strictly follow the instructions outlined in the [docs/RELEASE.md](docs/RELEASE.md) documentation file.

## Developer Guidelines & Code Architecture

### 1. Spring Boot & Java Requirements
- **Java Version**: The project is configured for **Java 17** (or newer) to align with Spring Boot 3.x requirements.
- **Jakarta EE / Servlets**: Always use `jakarta.servlet.*` package imports instead of the legacy `javax.servlet.*` packages.

### 2. Extension Architecture & Protocol Applicability
- Every protocol extension MUST extend `AbstractTusExtension` and declare its applicability via `isApplicable(HttpMethod, ProtocolVersion)`.
- `TusFileUploadService` MUST NOT contain protocol-specific conditionals, version branching, or hardcoded error handling; all protocol-specific validation and execution logic belongs inside dedicated `RequestValidator` and `RequestHandler` implementations.

### 3. Explicit Parameter Passing & No Request Attributes
- Do NOT use magic string servlet request attributes (such as `"me.desair.tus.uploadLockingService"` or `"me.desair.tus.protocolVersion"`).
- Pass dependencies such as `UploadLockingService` and `ProtocolVersion` explicitly as typed method parameters through `TusExtension` and `RequestHandler` interface methods. When expanding interfaces, always use Java `default` methods to preserve backward compatibility.

### 4. Serializable UploadInfo & Backward Compatibility
- The `UploadInfo` class is stored on disk serialized. If you modify fields in `UploadInfo`, you **must** preserve the `serialVersionUID = -8751200491586638308L` to ensure pre-existing uploads on disk do not trigger `InvalidClassException` upon deserialization.
- Backward compatibility is paramount for this project. Breaking changes should only be done if all other options lead to ugly code and design. Breaking changes require a new major version.
- **Release Scope for Backward Compatibility**: Only maintain backward compatibility for classes, methods, or public API signatures that are present in the latest official Git release tag. Signatures, classes, or helper methods introduced in unreleased commits or feature branches do not require backward compatibility and should be refactored or deleted directly.

### 5. Lock Contention Resolution & InterruptibleInputStream
- Request handlers that stream payload bytes to storage (`CorePatchRequestHandler`, `RufhCreationPostRequestHandler`, `RufhAppendPatchRequestHandler`) MUST wrap body input streams in `InterruptibleInputStream` and register them via `lockingService.registerInputStream(...)`. This ensures concurrent `HEAD` and `DELETE` requests can interrupt ongoing byte streams cleanly and resolve lock contention.

### 6. Problem Details Value Objects & Structured JSON Serialization
- Model RFC 7807 problem details as immutable domain value objects (`HttpProblemDetails`).
- Do NOT construct JSON strings using manual string concatenation or `StringBuilder` quote-stitching. Model JSON objects using structured maps (`Map<String, Object>`) or value objects and format them safely with proper JSON string escaping (handling quotes, backslashes, and control characters).

### 7. File Deduplication and Read/Write Safety
The deduplication mechanism links duplicate uploads (child) to the original upload (parent) using the `duplicatesUploadId` field in `UploadInfo`.
- **Read Operations**: Methods that read data (e.g., `getUploadedBytes`, `copyUploadTo` in `DiskStorageService`) should dynamically resolve `duplicatesUploadId` to the parent upload ID if it is set.
- **Write/Modify Operations**: Methods that write or truncate data (e.g., `append`, `removeLastNumberOfBytes` in `DiskStorageService`) **must not** resolve `duplicatesUploadId` recursively. They must only operate on the target upload's own physical files to guarantee parent files are never modified or truncated when handling child upload errors.

### 8. Checksum Index Storage & Self-Cleaning
Completed parent uploads are indexed by checksum under the `<storagePath>/checksums/<algorithm>/<checksum_value>` file path containing the target `UploadId`.
- Index lookup includes a self-cleaning check: if the index points to an upload that is null or whose data file is missing (e.g., due to expiration), the index file is deleted on the fly, keeping the file system clean without needing a separate index sweeper.
- Child uploads (duplicates) are never indexed.
- On parent termination, the parent's index entry is explicitly deleted.

### 9. No Thread-Local Contexts
- Do not use `ThreadLocal` variables or thread-local request context to pass state between components. Always pass parameters explicitly or use request wrapping.

### 10. Unit Test Coverage & Verbatim Spec Quotes
- Unit test coverage must remain high. All new code (including background watchdog threads, helper methods, stream wrappers, and retry logic) must be thoroughly unit tested.
- Do not use reflection to test private helper methods. Always test code through public API boundaries instead of bypassing encapsulation.
- Compliance unit tests in `me.desair.tus.server.rufh` MUST contain verbatim specification quotes in method Javadocs based on the official specification.
- After finalizing any implementation, you MUST check the unit test coverage on new/modified lines by running:
  ```bash
  mvn verify -Pcheck-coverage -Djacoco.compare.branch=master
  ```
  If any added or modified lines are reported as uncovered (❌) or partially covered (⚠️), you must add extra unit tests to cover them before submitting.

### 11. Mandatory Javadocs & Code Formatting
- Always write thorough Javadoc comments for all new and modified public/protected classes, interfaces, and methods.
- Always remove unused imports across all modified and newly created Java source files.
- Run code formatting before committing:
  ```bash
  mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:format
  ```

### 12. String Comparisons & Avoiding Deprecated StringUtils
- Do not use deprecated `StringUtils` comparison methods such as `StringUtils.equals(...)` or `StringUtils.equalsIgnoreCase(...)`.
- Always use `org.apache.commons.lang3.Strings.CS` for case-sensitive operations (e.g., `Strings.CS.equals(...)`, `Strings.CS.startsWith(...)`) and `org.apache.commons.lang3.Strings.CI` for case-insensitive operations (e.g., `Strings.CI.equals(...)`, `Strings.CI.startsWith(...)`).

## IETF Resumable Uploads for HTTP (RUFH) Spec Maintenance & Update Playbook

### 1. Spec Diff Review
When a new draft revision of the IETF Resumable Uploads specification (`draft-ietf-httpbis-resumable-upload`: https://datatracker.ietf.org/doc/draft-ietf-httpbis-resumable-upload/) is published:
- Compare the new draft against the current baseline (draft-11) using the official IETF Author Tools diff:
  `https://author-tools.ietf.org/diff?doc_1=draft-ietf-httpbis-resumable-upload-11&doc_2=draft-ietf-httpbis-resumable-upload-<NEW_REV>`
- Identify any changed header names, structured field syntax changes, response status codes, or problem details schemas.

### 2. Spec-Driven Compliance Test Maintenance
Compliance unit tests located in `src/test/java/me/desair/tus/server/rufh/` (`RufhProtocolCreationTest`, `RufhProtocolAppendTest`, `RufhProtocolHeadTest`, `RufhProtocolCancellationTest`, `HttpProblemDetailsTest`) contain verbatim quotes from the specification in their method Javadocs.
- **Workflow**:
  1. Update the verbatim spec quotes in test method Javadocs to reflect the new draft revision text.
  2. Update test assertions and expected header/status formats.
  3. Run `mvn test` to pinpoint which server components need code updates.

### 3. Protocol Update Skill & Execution Procedure
When updating the IETF protocol implementation for a new draft revision, follow this step-by-step procedure:
1. **Branching**: Ensure you are on a feature branch (e.g. `feature/ietf-spec-draft-<REV>`).
2. **Protocol Headers**: Update header definitions in `HttpHeader.java` if structured field keys or parameter names changed.
3. **Structured Header Utility**: Update `StructuredHeaderUtil.java` if RFC 9651 structured field parsing rules or data types changed.
4. **Problem Details**: Update `HttpProblemDetails.java` if RFC 7807 problem json type URIs or field keys changed.
5. **Protocol Logic**: Update `ResumableUploadsForHttpProtocol.java` validation and processing logic.
6. **Coverage Verification**: Verify code coverage and unit tests pass:
   ```bash
   mvn verify -Pcheck-coverage -Djacoco.compare.branch=master
   ```
