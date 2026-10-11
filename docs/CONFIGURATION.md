# Configuration Reference

All configuration is done through the fluent `with...()` methods of `TusFileUploadService`. Create **one** instance and share it (e.g. as a Spring singleton bean); the service is thread-safe and holds the storage and locking backends.

```java
@Bean
public TusFileUploadService tusFileUploadService() {
    return new TusFileUploadService()
        .withStoragePath("/path/to/uploads")
        .withUploadUri("/api/upload")
        .withUploadDeduplication(true)
        .withThreadLocalCache(true);
}
```

## Dependencies

The library only depends on Jakarta Servlet API 6.0 and some Apache Commons utility libraries, so (in theory) you can use it on any modern Java web application server such as Tomcat, JBoss or Jetty. By default all uploaded data and information is stored on a (shared) file volume of the application server.

Jackson dependencies (`jackson-databind`, `jackson-annotations`, `jackson-core`) are bundled directly with compile scope by `tus-java-server`. When using S3 storage (`S3StorageService`), include the MinIO Java SDK dependency matching the version in `pom.xml`:

```xml
<dependency>
  <groupId>io.minio</groupId>
  <artifactId>minio</artifactId>
  <version>9.0.3</version>
</dependency>
```

For Azure Blob Storage dependencies see the [Azure Blob Storage Guide](AZURE_BLOB_STORAGE.md#1-quick-start).

## Options

### Endpoint & protocol

| Method | Default | Description |
|---|---|---|
| `withUploadUri(String)` | `"/"` | Sets relative path (e.g. `/api/upload`) or absolute base URL (e.g. `https://upload.example.com/api/upload`) under which the upload endpoint is exposed. Supports regex parameters (e.g. `/users/[0-9]+/files/upload`). Use the absolute form when running behind a proxy that rewrites the path or host, so that `Location` headers are correct. |
| `withSupportedProtocolVersions(ProtocolVersion)` | `ProtocolVersion.AUTO` | Configures protocol handling: `AUTO` (header-based auto-detection), `TUS_1_0_0` (Tus 1.0.0 only), or `RUFH` (IETF draft-12 only). See [PROTOCOLS.md](PROTOCOLS.md). |
| `withChunkedTransferDecoding(Boolean)` | `false` | Enables manual chunked HTTP decoding for servlet containers that do not decode chunked requests natively. |
| `addTusExtension(TusExtension)` | All standard enabled | Adds a custom extension (e.g. application authorization checks). |
| `disableTusExtension(String)` | None | Disables a built-in extension (`creation`, `creation-with-upload`, `checksum`, `expiration`, `concatenation`, `termination`, `download`, `cors`, `http-digests`, `resumable-uploads-for-http`). |
| `withDownloadFeature()` | Disabled | Enables the unofficial `download` extension allowing clients to retrieve uploaded bytes via HTTP `GET`. |

### Limits, expiration & integrity

| Method | Default | Description |
|---|---|---|
| `withMaxUploadSize(Long)` | `Long.MAX_VALUE` | Maximum allowed total upload size in bytes per upload resource. |
| `withMaxAppendSize(Long)` / `withMinAppendSize(Long)` / `withMinSize(Long)` | No limit | IETF RUFH only: maximum size of a single append (`PATCH`) request, minimum size of an append or creation request, and minimum total upload size (all in bytes). |
| `withUploadExpirationPeriod(Long)` | `null` (disabled) | Expiration period in **milliseconds** after which incomplete/expired uploads become eligible for cleanup (run `cleanup()` periodically, see the [Integration Guide](INTEGRATION_GUIDE.md#3-cleanup)). |
| `withUploadDeduplication(Boolean)` | `false` | Enables duplicate file detection by checksum, linking new uploads (`duplicatesUploadId`) and skipping redundant storage writes. |

### Identity, events & serialization

| Method | Default | Description |
|---|---|---|
| `withUploadIdFactory(UploadIdFactory)` | `UuidUploadIdFactory` | Custom ID generator for upload resources (e.g., `UuidUploadIdFactory` or `TimeBasedUploadIdFactory`). |
| `withUploadCompletionListener(UploadCompletionListener)` / `addUploadCompletionListener(...)` | None | Registers a callback invoked immediately when an upload finishes transferring all bytes and is completed. Both methods append to the list of active listeners. |
| `withJsonSerialization()` / `withJsonSerialization(boolean)` | Java serialization | Enables or disables JSON serialization for upload metadata (`UploadInfo`). Jackson is bundled by default. |

### Storage, locking & performance

| Method | Default | Description |
|---|---|---|
| `withStoragePath(String)` | `${java.io.tmpdir}/tus` | Path on the filesystem or shared drive where uploaded bytes and metadata are stored when using `DiskStorageService`. Also configures `LeaseFileLockingService`. |
| `withUploadStorageService(UploadStorageService)` | `DiskStorageService` | Configures custom or cloud storage backend (`DiskStorageService`, `S3StorageService`, `AzureBlobStorageService`). Automatically copies over previous limits and configuration. |
| `withUploadLockingService(UploadLockingService)` | `LeaseFileLockingService` | Configures custom or cloud locking backend (`LeaseFileLockingService`, `S3LockingService`, `AzureBlobLockingService`). |
| `withLockWaitTimeout(Duration)` | `Duration.ofSeconds(60)` | Maximum duration a request waits to acquire an upload lock held by an in-flight transfer (retrying every 200ms, resulting in 300 retries). Automatically configures cloud background chunk drain timeout to 5s less than this value (default 55s). See [LOCKING.md](LOCKING.md). |
| `withThreadLocalCache(Boolean)` | `false` | Enables in-memory thread-local caching of upload request data to reduce storage backend I/O load. Strongly recommended for cloud backends (S3, Azure) to eliminate redundant remote metadata lookups per request. |
| `withCloudUploadThreadPoolSize(int)` | `10` | Maximum number of worker threads used for asynchronous background chunk uploading in cloud storage backends (S3, Azure). |

> Backend-specific options (S3 jitter, Azure credentials, key prefixes...) are documented in the respective backend guides below.

## Storage Backend Options

`tus-java-server` provides a pluggable storage architecture supporting multiple backends. Use the table to pick one, then follow the linked guide. See also [DEPLOYMENT.md](DEPLOYMENT.md) for multi-replica considerations.

| Backend | Classes | Best for | Guide |
|---|---|---|---|
| **File Disk & Network Storage** (default) | `DiskStorageService` & `LeaseFileLockingService` | Single node, shared NFS (v3/v4, AWS EFS, Azure Files, SMB/CIFS) or a Kubernetes Persistent Volume (`ReadWriteMany` / `ReadWriteOnce`). | [Disk & Network Storage Locking Guide](DISK_BASED_LOCKING.md) |
| **S3-Compatible Object Storage** | `S3StorageService`, `S3LockingService` & `S3ConcatenationService` | AWS S3, MinIO, RustFS, Cloudflare R2, Ceph or Google Cloud Storage. Uses distributed S3 object locking and TTL leases, so multi-replica container deployments work without Redis or external databases. | [S3 Storage Guide](S3_STORAGE.md) |
| **Azure Blob Storage** | `AzureBlobStorageService`, `AzureBlobLockingService` & `AzureBlobConcatenationService` | Microsoft Azure using the `azure-storage-blob` SDK. Uses native Azure Blob Leases (30s renewable leases) for distributed locking with automatic lock cleanup on upload completion, termination, and expiration. | [Azure Blob Storage Guide](AZURE_BLOB_STORAGE.md) |

Cloud backends feature an asynchronous 3-slot chunk pipeline (`AsyncChunkUploader`) that overlaps client payload streaming with cloud staging in the background. The guides above cover multi-replica deployments in Kubernetes, post-upload processing, and legacy locking opt-out.
