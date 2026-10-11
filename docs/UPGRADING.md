# Upgrade Guide: `1.0.0-3.x` → `2.0.0`

Everything a Java developer needs when upgrading an application from `tus-java-server` `1.0.0-3.3` (or earlier) to `2.0.0`. For the full list of changes see the [CHANGELOG](../CHANGELOG.md). To upgrade your *clients* to the IETF Resumable Uploads protocol see [MIGRATION.md](MIGRATION.md).

## Upgrade Checklist

| # | Change | Action required |
|---|---|---|
| 1 | Java 17+ and `jakarta.servlet` | Run on Java 17+ and a Jakarta container (Tomcat 10+, Jetty 11+, Spring Boot 3+). |
| 2 | Default locking is now `LeaseFileLockingService` | None for most; opt out explicitly if you want kernel file locks (§2). |
| 3 | `getUploadedBytes(...)` holds the lock until the stream is closed | Always use try-with-resources (§4). |
| 4 | `process(...)` now returns `UploadInfo` | None; source compatible. Use the return value if useful. |
| 5 | Download extension no longer returns `204` for unfinished uploads and omits `Tus-Resumable` | Check clients that rely on the old behaviour (§7). |
| 6 | Jackson now bundled (compile scope) | Remove redundant Jackson declarations if they conflict. |
| 7 | Dual protocol (`ProtocolVersion.AUTO` by default) | None for Tus clients; see §8 to restrict. |
| 8 | `UploadInProgressException` removed | Remove any references. |
| 9 | Absolute URLs accepted in `withUploadUri(...)` | Optional. |
| 10 | Security hardening (path traversal, CORS, permissions) | Transparent; verify custom URI handling if applicable (§9). |

## 1. Java 17 & Jakarta EE Baseline
- **Java 17**: `tus-java-server` 2.0.0 targets Java 17+.
- **Jakarta EE**: The library uses `jakarta.servlet.*` package imports instead of legacy `javax.servlet.*` packages, aligning with Spring Boot 3.x, Tomcat 10+, and Jetty 11+. Applications still on `javax.servlet` must stay on `1.0.0-3.3` or migrate.

## 2. Default Distributed Lease Locking
- In `1.0.0-3.3`, `TusFileUploadService.withStoragePath(path)` defaulted to `DiskLockingService`, which relied on OS kernel file locks (`java.nio.channels.FileLock`).
- In `2.0.0`, `withStoragePath(path)` defaults to `LeaseFileLockingService`, which uses atomic sibling mutex directories (`<UploadId>.mutex/`) and JSON lease files with auto-renewal. This provides container-safe, distributed locking across multi-replica pods on Kubernetes, NFS (v3/v4), AWS EFS, Azure Files, and SMB without requiring OS-level lock daemons or external coordination. See [DISK_BASED_LOCKING.md](DISK_BASED_LOCKING.md).
- **Opting Out**: If your application runs exclusively on local disks and you prefer kernel file locks, explicitly configure:
  ```java
  tusService.withUploadLockingService(new DiskLockingService(storagePath));
  ```
- Existing uploads on disk remain readable: the stored `UploadInfo` format is unchanged (the `serialVersionUID` is preserved).

## 3. Jackson Dependencies Bundled in Compile Scope
- Jackson dependencies (`jackson-databind`, `jackson-annotations`, `jackson-core`) are now included with compile scope in `tus-java-server`. You no longer need to declare Jackson separately in your application `pom.xml` when using JSON metadata serialization (`withJsonSerialization()`) or cloud storage.

## 4. Safe Lock-Holding InputStream Lifecycle
- In 2.0.0, `tusService.getUploadedBytes(...)` returns a stream that retains an exclusive upload lock until closed. This prevents concurrent `DELETE` or `PATCH` requests from corrupting data mid-stream.
- **Best Practice**: Always consume uploaded bytes within a `try-with-resources` block:
  ```java
  try (InputStream stream = tusService.getUploadedBytes(uploadUri)) {
      // Process file data safely under lock protection
  } // Lock is automatically released on close
  ```
- A stream that is never closed leaves the upload locked until the lease expires.

## 5. `process()` Return Value
`TusFileUploadService.process(...)` now returns the created or updated `UploadInfo` (or `null` on errors or `OPTIONS` preflight requests), enabling applications to track upload IDs. Note that it returns a value for both creation and progress requests, see [Creation vs. progress ambiguity](INTEGRATION_GUIDE.md#creation-vs-progress-ambiguity).

## 6. New Features You May Want to Adopt
- **Pluggable Cloud Storage Backends**
  - **S3 Storage**: `S3StorageService`, `S3LockingService`, and `S3ConcatenationService` (AWS S3, MinIO, RustFS, Cloudflare R2, Ceph, GCS). See [S3_STORAGE.md](S3_STORAGE.md).
  - **Azure Blob Storage**: `AzureBlobStorageService`, `AzureBlobLockingService`, and `AzureBlobConcatenationService`. See [AZURE_BLOB_STORAGE.md](AZURE_BLOB_STORAGE.md).
  - **Thread-Local Caching**: Enable `.withThreadLocalCache(true)` when using cloud backends to eliminate redundant remote metadata roundtrips per HTTP request. When enabled, `tusService.getUploadStorageService()` returns a `ThreadLocalCachedStorageAndLockingService` wrapper; inject your concrete storage service bean directly rather than casting the wrapper if you need backend-specific methods like `getS3ObjectKey()` or `getAzureBlobName()`.
- **Post-Upload Listeners (`UploadCompletionListener`)**: register callbacks that fire immediately upon upload completion:
  ```java
  tusService.withUploadCompletionListener((uploadInfo, service) -> {
      log.info("Upload completed: {}", uploadInfo.getId());
  });
  ```
- **`withLockWaitTimeout(Duration)`**: configures how long requests wait for a contended upload lock (default 60 s). See [LOCKING.md](LOCKING.md).
- **`withJsonSerialization()`**: store `UploadInfo` as JSON instead of Java serialization.
- **Absolute upload URIs**: `withUploadUri("https://upload.example.com/files")` returns full `Location` URLs; relative paths keep working.
- **Automatic lock cleanup** on completion, termination and expiration (no action needed).

## 7. Download Extension Behaviour Change
To support both protocols, the unofficial `download` extension no longer returns HTTP `204` for uploads that are still in progress and no longer includes the `Tus-Resumable` response header. The `UploadInProgressException` class was removed.

## 8. Dual Protocol Support
By default `ProtocolVersion.AUTO` detects Tus 1.0.0 vs. IETF RUFH per request, so existing Tus clients keep working unchanged. Restrict it with `withSupportedProtocolVersions(...)`, see [PROTOCOLS.md](PROTOCOLS.md).

## 9. Security Enhancements
- **Path Traversal Protection**: Request URIs and upload IDs are strictly validated to prevent directory traversal attacks.
- **CORS Origin Validation**: Prevents arbitrary Origin reflection when CORS is enabled.
- **Secure File Permissions**: Temporary files are created with restricted owner-only permissions (`0600`) via `Files.createTempFile` instead of legacy world-readable temp files.
