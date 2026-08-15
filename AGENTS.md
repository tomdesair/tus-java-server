# Agent Instructions

## Project Context
When working on this project, always read the [`README.md`](README.md) file to obtain full context on project architecture, features, configuration options, and dual protocol version support (Tus 1.0.0 & IETF RUFH).

## GitHub CLI (`gh`) & Git Usage
- Always run `git` commands (e.g., `git status`, `git diff`, `git add`, `git commit`, `git push`) and `gh` commands unsandboxed (setting `BypassSandbox: true` when calling `run_command`) to ensure git hooks, local tools, and remote repository authentication work without sandbox errors.
- When running `gh` commands in this project via an automated agent environment, ensure you bypass the default `GITHUB_TOKEN` environment variable. The agent environment may have an invalid `GITHUB_TOKEN` set, which `gh` prioritizes over valid keyring credentials, resulting in an `HTTP 401: Bad credentials` error.

**Workaround:** Prefix `gh` commands with `env -u GITHUB_TOKEN` to force the CLI to use the valid keyring authentication.

Example:
```bash
env -u GITHUB_TOKEN gh pr create --title "..." --body "..."
```

## Git Branching Strategy
Any new feature, bugfix, or improvement must be developed in a separate branch that starts with either `feature/` or `bugfix/` and has a meaningful but short name (e.g., `feature/lock-contention-resolution` or `bugfix/fix-upload-timeout`).

## Releases and Documentation

### README.md
When a new feature is introduced, the `README.md` file must be updated with information on this new feature (e.g. configuration, usage).

### CHANGELOG.md
For any new feature, big improvements, or fixes, the `CHANGELOG.md` file must be updated to describe the changes added in this version. Use a release version header (e.g., `## [1.0.0-3.2]`) instead of `## [Unreleased]`. Derive this next release version from the SNAPSHOT version declared in the `pom.xml` file by removing the `-SNAPSHOT` suffix. Make sure to not add duplicate headers.

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

### 10. Unit Test Coverage & Pragmatic Testing
- Unit test coverage must remain high for all new feature logic, handlers, validators, and core workflows.
- **Mandatory Test Addition Rule**: Whenever any functional change, feature implementation, or protocol fix is added, corresponding unit tests MUST ALWAYS be added automatically to prove the fix/feature. Compliance unit tests MUST contain section references and verbatim specification quotes in method Javadocs based on the official specification.
- Do not use reflection to test private helper methods. Always test code through public API boundaries instead of bypassing encapsulation.
- Compliance unit tests in `me.desair.tus.server.rufh` MUST contain verbatim specification quotes in method Javadocs based on the official specification.
- **Meaningful Assertions Rule**: Every test method in both unit and integration test suites MUST include meaningful assertion statements (`assertEquals`, `assertTrue`, `assertNotNull`, `@Test(expected = ...)`) verifying return values or state mutations. If an assertion is genuinely not possible (e.g. verifying a void cleanup method executes cleanly) and the test only verifies that no exception is thrown, an explicit inline comment (e.g. `// KISS: verifying method executes cleanly without throwing an exception`) MUST be added to document this rationale.
- After finalizing implementation, verify test coverage on updated files using:
  ```bash
  python3 scripts/check-coverage.py --per-file-limit 90
  ```
  Ensure that coverage of all updated files is more than 90% and that all important business logic in those classes is covered.

### 11. Efficient Build Execution & Token Reduction
When running builds, tests, or coverage checks via Maven:
- Always run Maven build commands unsandboxed (e.g., setting `BypassSandbox: true` when calling `run_command`) to allow access to local Maven repository (`~/.m2`) and dependency resolution.
- Use quiet/suppressed flags to minimize token usage from verbose logs:
  - `-q` / `--quiet`: Suppresses standard Maven INFO log noise.
  - `-Dtest=TestClass` / `-Dtest=TestClass#testMethod`: Run only the specific test or method relevant to your changes while iterating.
  - `-Dstyle.color=never`: Suppresses ANSI color codes.
- Example:
  ```bash
  mvn test -Dtest=RufhProtocolCreationTest -q
  ```

### 12. Mandatory Javadocs & Code Formatting
- Always write thorough Javadoc comments for all new and modified public/protected classes, interfaces, and methods.
- Always remove unused imports across all modified and newly created Java source files.
- Run code formatting before committing:
  ```bash
  mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:format -q
  ```

### 13. String Comparisons & Avoiding Deprecated StringUtils
- Do not use deprecated `StringUtils` comparison methods such as `StringUtils.equals(...)` or `StringUtils.equalsIgnoreCase(...)`.
- Always use `org.apache.commons.lang3.Strings.CS` for case-sensitive operations (e.g., `Strings.CS.equals(...)`, `Strings.CS.startsWith(...)`) and `org.apache.commons.lang3.Strings.CI` for case-insensitive operations (e.g., `Strings.CI.equals(...)`, `Strings.CI.startsWith(...)`).

### 14. UploadStorageService Configuration & Builder Synchronization
Whenever a new setter or configuration property (such as `setMinAppendSize`, `setMinSize`, `setMaxAppendSize`) is added to `UploadStorageService`:
- A corresponding `with...` builder method (e.g. `withMinAppendSize`, `withMinSize`) MUST be added to `TusFileUploadService` with thorough Javadoc comments.
- `TusFileUploadService.withUploadStorageService(...)` MUST be updated to copy the setting from the old `UploadStorageService` instance to the new one.
- `ThreadLocalCachedStorageAndLockingService` MUST delegate the setter and getter methods to `storageServiceDelegate`.

### 15. Typed Exceptions & HttpServletResponse Status Codes
- Do NOT throw generic `TusException` directly when throwing protocol errors or request validation failures.
- Always throw specific typed exceptions from the `me.desair.tus.server.exception` package (e.g., `UploadNotFoundException`, `InvalidUploadMetadataException`, `UploadLengthExceededException`, `InvalidHttpDigestException`).
- If a new error condition is introduced, create a new typed exception class in `me.desair.tus.server.exception` that extends `TusException`.
- Typed exception constructors MUST use `jakarta.servlet.http.HttpServletResponse` HTTP status code constants (e.g., `HttpServletResponse.SC_BAD_REQUEST`, `HttpServletResponse.SC_CONFLICT`, `HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE`) when calling `super(status, message)`.

### 16. Multi-Backend Integration Test Hierarchy
To avoid duplicate test code and ensure all protocol integration tests run consistently across all storage backends (Disk, S3, Azure Blob, etc.):
- **Abstract Base Classes**: End-to-end integration test suites (e.g. for RUFH protocol or Tus 1.0.0 `TusFileUploadService`) MUST be written as abstract base classes (`AbstractITRufhProtocol`, `AbstractITTusFileUploadService`).
- **Template Factory Method**: Base test classes declare an abstract method `protected abstract TusFileUploadService createTusFileUploadService() throws Exception;` which subclasses implement to supply the backend-configured service instance.
- **Backend Subclasses**: Create concrete test subclasses per storage backend (e.g., `ITRufhProtocol` / `ITTusFileUploadService` for Disk, `ITS3RufhProtocol` / `ITS3TusFileUploadService` for S3, `ITAzureBlobRufhProtocol` / `ITAzureBlobTusFileUploadService` for Azure Blob). Subclasses handle backend-specific `@BeforeClass` / `@AfterClass` setup (such as starting Testcontainers) and storage-specific assertion tests.

### 17. Mandatory Inline Comments & Code Readability
- Always write and preserve thorough inline comments across all main and test Java source files to explain non-obvious algorithms, multi-step operations, and complex logic.
- Ensure all function implementations remain short, clean, well-documented, and stick to the same level of abstraction.

### 18. Efficient Batch Test & Code Coverage Verification Strategy
To maximize developer velocity and minimize test execution overhead when increasing code coverage:
- **Batch Test Updates**: When addressing missing line/branch coverage reported by JaCoCo, batch multiple test additions across all relevant test classes (`S3StorageServiceTest`, `S3LockingServiceTest`, `S3UploadLockTest`, `S3ConcatenationServiceTest`, `UploadInfoSerializerTest`) at once rather than running test-by-test iterations.
- **Fast Unit Test Execution**: Verify all local unit tests rapidly using target wildcard patterns (e.g. `mvn test -Dtest="S3*" -q` or `mvn test -Dtest="*Test" -q`). Unit tests run in under 2 seconds without launching test containers.
- **Single Verification Gate**: Only run the python coverage script (`python3 scripts/check-coverage.py --per-file-limit 90`) after all batched unit test updates have been applied and locally validated.

### 19. Unit Tests vs. Integration Tests Distinction & Scope
To maintain a clear separation between fast, offline unit tests and containerized integration tests:
- **Pure Offline Unit Tests (`*Test.java`)**:
  - Target specific class/component logic, edge cases, input validation, and boundary conditions in isolation using unit test frameworks and mocks.
  - MUST NOT launch Testcontainers (Docker/Podman) or require external network services.
  - Every primary service and component (e.g., `AzureBlobStorageService`, `AzureBlobLockingService`, `AzureBlobUploadLock`, `AzureBlobConcatenationService`) MUST have corresponding offline unit test classes ending with `Test.java` (e.g. `AzureBlobStorageServiceTest.java`, `AzureBlobLockingServiceTest.java`, `AzureBlobUploadLockTest.java`, `AzureBlobConcatenationServiceTest.java`).
- **End-to-End Integration Tests (`IT*`)**:
  - Focus strictly on business processes, end-to-end user flows, protocol interactions, and backend service capability flows (such as `ITAzureBlobStorageService`, `ITAzureBlobLockingService`, `ITAzureBlobConcatenationService`, `ITAzureBlobRufhProtocol`, `ITAzureBlobTusFileUploadService`).
  - StorageService, LockingService, and ConcatenationService integration tests are maintained because they represent key capability flows that can be combined across different backend types (e.g. Azure storage combined with S3 locking).
  - Internal helper objects or component handles that do NOT represent an independent business process (such as `UploadLock` handles) MUST NOT have dedicated `IT*` integration test classes (e.g. `ITAzureBlobUploadLock` is omitted in favor of testing `AzureBlobUploadLockTest` offline and testing lock lifecycles end-to-end via `ITAzureBlobLockingService` / `ITAzureBlobTusFileUploadService`).
- **Naming & Execution Rules**:
  - Unit test classes MUST end with `Test.java` (executed during `mvn test`).
  - Integration test classes MUST start with `IT` and MUST NOT end with `Test` or `Test.java` (executed during `mvn verify`).
  - When container runtime is unavailable, integration test classes MUST be cleanly skipped via `Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable())`.
- **Consolidated Coverage Script (`scripts/check-coverage.py`)**:
  - Automatically discovers and aggregates coverage across **both** unit tests (`target/site/jacoco-ut/jacoco.xml`) and integration tests (`target/site/jacoco-it/jacoco.xml`).
  - Supports `--filter` (e.g., `--filter azure`), `--per-file-limit` (e.g., `--per-file-limit 90`), `--limit` (overall threshold), and `--compare-branch` (checking diff coverage on modified lines against a base git branch).
  - Always verify that the coverage of all updated files is more than 90% (`python3 scripts/check-coverage.py --per-file-limit 90`) and that all important business logic in those classes is covered.
- **Local Verification Gate**: Before committing or pushing changes to GitHub, always execute:
  1. Clean build and integration verification:
     ```bash
     mvn clean install -q
     ```
  2. Code coverage gate:
     ```bash
     python3 scripts/check-coverage.py --per-file-limit 90
     ```
- **Offline Unit Test Network Isolation**:
  - `*Test.java` unit tests MUST NOT invoke SDK network methods (e.g. `listBlobs()`, `getProperties()`, `downloadContent()`, `releaseLease()`) on dummy or un-mocked clients. Doing so triggers default cloud SDK retry loops (e.g. 3 retries x 60s timeout) against non-existent endpoints, causing test hangs and build delays. All real container interactions belong exclusively in `IT*` integration tests.

### 20. Explicit Top-Level Class Imports
- Always use top-level `import` statements at the top of Java files instead of writing fully qualified package class names inline in method signatures or method bodies (e.g. add `import me.desair.tus.server.util.Utils;` at the top of the file and call `Utils.interruptStream(...)` instead of writing `me.desair.tus.server.util.Utils.interruptStream(...)`).

## IETF Resumable Uploads for HTTP (RUFH) Spec Maintenance & Update Playbook

### 1. Spec Diff Review
When a new draft revision of the IETF Resumable Uploads specification (`draft-ietf-httpbis-resumable-upload`: https://datatracker.ietf.org/doc/draft-ietf-httpbis-resumable-upload/) is published:
- Compare the new draft against the current baseline (draft-12) using the official IETF Author Tools diff:
  `https://author-tools.ietf.org/diff?doc_1=draft-ietf-httpbis-resumable-upload-12&doc_2=draft-ietf-httpbis-resumable-upload-<NEW_REV>`
- Identify any changed header names, structured field syntax changes, response status codes, or problem details schemas.

### 2. Spec-Driven Compliance Test Maintenance
Compliance unit tests located in `src/test/java/me/desair/tus.server.rufh/` (`RufhProtocolCreationTest`, `RufhProtocolAppendTest`, `RufhProtocolHeadTest`, `RufhProtocolCancellationTest`, `HttpProblemDetailsTest`) contain verbatim quotes from the specification in their method Javadocs.
- **Workflow**:
  1. Update the verbatim spec quotes in test method Javadocs to reflect the new draft revision text.
  2. Update test assertions and expected header/status formats.
  3. Run `mvn test -Dtest=me.desair.tus.server.rufh.* -q` to pinpoint which server components need code updates.

### 3. Protocol Update Skill & Execution Procedure
When updating the IETF protocol implementation for a new draft revision, follow this step-by-step procedure:
1. **Branching**: Ensure you are on a feature branch (e.g. `feature/ietf-spec-draft-<REV>`).
2. **Protocol Headers**: Update header definitions in `HttpHeader.java` if structured field keys or parameter names changed.
3. **Structured Header Utility**: Update `StructuredHeaderUtil.java` if RFC 9651 structured field parsing rules or data types changed.
4. **Problem Details**: Update `HttpProblemDetails.java` if RFC 7807 problem json type URIs or field keys changed.
5. **Protocol Logic**: Update `ResumableUploadsForHttpProtocol.java` validation and processing logic.
6. **Coverage Verification**: Verify code coverage and unit tests pass:
   ```bash
   python3 scripts/check-coverage.py --per-file-limit 90
   ```

### 4. Conformity Test Suite Maintenance & Subagent Isolation
Whenever a new draft revision of the RUFH specification is published, the repository's Python conformity test suite (`scripts/rufh_conformity_test.py`) MUST be reviewed and updated by a separate, dedicated subagent.
- **Strict Isolation Rule**: The subagent tasked with updating `scripts/rufh_conformity_test.py` MUST ONLY consult the official IETF specification document (and RFC 9530) and MUST NOT inspect the Java server implementation code under `src/main/java/`. This ensures the conformity test suite remains an independent, unbiased specification benchmark.

### 5. Conformity Test Suite Audit — Repeatable Procedure
Use this procedure to audit `scripts/rufh_conformity_test.py` against the current (or a new) specification revision. The goal is to identify untested MUST/SHOULD/MAY requirements and produce an actionable improvement report.

#### 5.1 Inputs
- **Specification document**: The full text of the target draft revision, e.g.:
  `https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-<REV>.txt`
- **Test suite**: `scripts/rufh_conformity_test.py` (read it in full).
- **Previous audit report** (if any): `CONFORMITY_TEST_IMPROVEMENTS.md` in the project root.

#### 5.2 Isolation Rules
- **Do NOT read any Java source code** under `src/main/java/` during the audit. The audit must be purely spec-vs-test-script.
- The only project files to read are `scripts/rufh_conformity_test.py` and optionally `CONFORMITY_TEST_IMPROVEMENTS.md`.
- You may read the specification document, RFC 9530 (HTTP Digests), RFC 9651 (Structured Fields), and RFC 9457 (Problem Details) for normative context.

#### 5.3 Audit Methodology (Clause-by-Clause)
Walk through every normative section of the specification in order. For each section:

1. **Extract every requirement** containing MUST, MUST NOT, SHOULD, SHOULD NOT, or MAY (per RFC 2119 / RFC 8174 semantics).
2. **For each requirement**, search the test suite for a test that exercises it:
   - Check if the test sends the right request (method, headers, body).
   - Check if the test asserts the correct response behavior (status code, headers, body content).
   - Note whether the test covers both the positive (conformant) and negative (non-conformant input) cases.
3. **Classify the finding**:
   - ✅ **Covered** — a test exists and its assertions match the requirement.
   - ✅ **Partial** — a test exists but assertions are incomplete or only cover one case.
   - ❌ **Missing** — no test covers this requirement.
4. **For partial/missing items**, write a concrete recommendation: test method name, spec section, request/response to send, and assertions to make.

The sections to audit (for draft-12) are:
- §4.1.1 (Offset), §4.1.2 (Completeness), §4.1.3 (Length), §4.1.4 (Limits)
- §4.2 (Upload Creation): §4.2.1 (Client Behavior), §4.2.2 (Server Behavior)
- §4.3 (Offset Retrieval): §4.3.1 (Client Behavior), §4.3.2 (Server Behavior)
- §4.4 (Upload Append): §4.4.1 (Client Behavior), §4.4.2 (Server Behavior)
- §4.5 (Upload Cancellation): §4.5.1, §4.5.2 (Server Behavior)
- §4.6 (Concurrency), §4.7 (Retry)
- §5 (Status Code 104)
- §6 (Media Type application/partial-upload)
- §7.1 (Mismatching Offset problem type), §7.2 (Inconsistent Length problem type)
- §10.1 (Optimistic Upload Creation), §10.1.1 (Upgrading), §10.2 (Careful Upload Creation)

#### 5.4 Output Format
Produce a Markdown report saved as `CONFORMITY_TEST_IMPROVEMENTS.md` in the project root (overwrite the previous version). The report MUST contain:

1. **Executive Summary** — overall coverage assessment.
2. **Critical Gaps** (🔴) — untested MUST-level requirements, with spec quotes and recommended test methods.
3. **Important Gaps** (🟡) — untested SHOULD-level requirements or incomplete assertions.
4. **Minor Improvements** (🔵) — edge cases, test quality improvements, spec alignment.
5. **Existing Test Corrections** — any tests with incorrect or overly permissive assertions.
6. **Recommended New Test Methods** — organized by test class, with spec section, method name, and description.
7. **Summary Matrix** — table with columns: Spec Section, Requirement Level, Currently Tested (✅/✅ Partial/❌), Gap Description.

#### 5.5 How to Invoke This Audit
Request the audit with a prompt like:
> Perform a strict conformity audit of `scripts/rufh_conformity_test.py` against the draft-12 specification at `https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.txt`. Follow the audit procedure in AGENTS.md §5. Do NOT inspect any Java implementation code.

To audit against a newer draft, replace the draft number in the URL.
