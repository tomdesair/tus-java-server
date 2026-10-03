# S3-Compatible Storage Support for `tus-java-server`

`tus-java-server` provides native support for storing resumable file uploads in AWS S3 and any S3-compatible object storage service (such as MinIO, RustFS, Cloudflare R2, Ceph, or Google Cloud Storage) using the lightweight MinIO Java SDK.

The implementation consists of three primary components:
- **`S3StorageService`** (implements `UploadStorageService`) — handles server-side object composition (`composeObject`), chunk appends, sub-5MB incomplete part persistence (`.part`), expiration, and checksum deduplication.
- **`S3LockingService`** (implements `UploadLockingService`) — provides distributed locking using S3 object leases (`.lock`) and TTL leases, enabling multi-replica container deployments without requiring Redis or external databases.
- **`S3ConcatenationService`** (implements `UploadConcatenationService`) — provides S3-native concatenation using server-side `composeObject` (for parts $\ge$ 5 MB, with sub-5MB final parts allowed) with a streaming re-upload fallback.

---

## 1. Quick Start

### Step 1: Add Dependencies

Jackson dependencies (`jackson-databind`, `jackson-annotations`, `jackson-core`) are bundled directly with compile scope by `tus-java-server`. Simply add the MinIO Java SDK to your application's `pom.xml`:

```xml
<dependencies>
    <!-- MinIO Java SDK for S3 & S3-compatible storage -->
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>9.0.3</version>
    </dependency>
</dependencies>
```

### Step 2: Configure `TusFileUploadService`

```java
import io.minio.MinioClient;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.s3.S3StorageService;
import me.desair.tus.server.upload.s3.S3LockingService;

// 1. Production Configuration: Load S3 parameters securely from environment variables
String endpoint = System.getenv().getOrDefault("S3_ENDPOINT", "https://s3.eu-central-1.amazonaws.com");
String bucketName = System.getenv().getOrDefault("S3_BUCKET_NAME", "my-upload-bucket");
String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");

MinioClient minioClient = MinioClient.builder()
    .endpoint(endpoint)
    .credentials(accessKey, secretKey)
    .build();

// 2. Instantiate S3 Storage and Distributed Locking services
// Option A: Direct connection parameters (recommended, builds internal client with server-side compose helper)
S3StorageService s3StorageService = new S3StorageService(endpoint, "eu-central-1", accessKey, secretKey, bucketName);
S3LockingService s3LockingService = new S3LockingService(endpoint, "eu-central-1", accessKey, secretKey, bucketName);

// Option B: Using a pre-configured MinIO Client
// S3StorageService s3StorageService = new S3StorageService(minioClient, endpoint, "eu-central-1", accessKey, secretKey, bucketName);
// S3LockingService s3LockingService = new S3LockingService(minioClient, bucketName);

// 3. Configure TusFileUploadService with S3 storage and locking
// Note: Automatic JVM shutdown hooks are built-in by default to terminate watchdog threads on pod exit.
// Manual call to tusService.close() or s3LockingService.close() is optional for custom container lifecycles.
TusFileUploadService tusService = new TusFileUploadService()
    .withUploadUri("/files/upload")
    .withUploadStorageService(s3StorageService)
    .withUploadLockingService(s3LockingService);
```

---

## 2. Recommendation: Request Caching with `ThreadLocalCachedStorageAndLockingService`

> [!IMPORTANT]
> **Why `ThreadLocalCachedStorageAndLockingService` is Recommended for S3**:
> By default, `TusFileUploadService` automatically wraps your custom `UploadStorageService` and `UploadLockingService` in a `ThreadLocalCachedStorageAndLockingService`.
>
> During a single HTTP request lifecycle (POST, PATCH, HEAD, DELETE), the tus server validates request headers, reads upload state, appends data, and constructs response headers. Without caching, retrieving `UploadInfo` and calculating offsets would require multiple redundant network roundtrips to S3 (`GetObject` on `.info`, `ListObjects`, `StatObject`).
>
> `ThreadLocalCachedStorageAndLockingService` caches the `UploadInfo` in thread-local memory for the duration of a single HTTP request, releasing the cache automatically when the upload lock is closed at the end of the request. This dramatically reduces S3 network latency and cost per request.

---

## 3. Object Storage Layout

`S3StorageService` uses a clean, flat object key structure:

```
<objectPrefix>/<UploadId>                        # Final completed data object
<metadataPrefix>/<UploadId>.info                 # JSON-serialized UploadInfo metadata
<metadataPrefix>/<UploadId>.part                 # Incomplete sub-5MB part buffer
<checksumsPrefix>/<algorithm>/<hex_checksum>     # Deduplication checksum index object
<locksPrefix>/<UploadId>.lock                    # Lock lease object (JSON: holder + expiry)
<locksPrefix>/<UploadId>.stop                    # Cross-pod contention interrupt signal
```

### Key Prefix Defaults

| Setting | Default Value | Description |
|---------|---------------|-------------|
| `objectPrefix` | `"uploads/"` | Key prefix for final completed file objects |
| `metadataPrefix` | `"metadata/"` | Key prefix for `.info` JSON and `.part` buffers |
| `checksumsPrefix` | `"checksums/"` | Key prefix for deduplication index objects |
| `locksPrefix` | `"locks/"` | Key prefix for distributed lock lease objects |

---

## 4. Post-Upload Processing (`getS3ObjectKey`)

After an upload completes, downstream services can obtain the direct S3 key of the final object using `getS3ObjectKey(uploadUri, ownerKey)` (or `getS3ObjectKey(uploadUri)`). This enables zero-download server-side copying (`copyObject`) or direct byte processing with `MinioClient`:

```java
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import java.io.InputStream;

S3StorageService s3Storage = (S3StorageService) tusService.getUploadStorageService();

String uploadUri = "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e";
String ownerKey = "user-123";

// 1. Obtain full S3 key after upload completion using uploadUri and ownerKey
String s3ObjectKey = s3Storage.getS3ObjectKey(uploadUri, ownerKey);
// e.g. "uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e"

// 2. Example: Storage-side processing using MinioClient (server-side object copy)
minioClient.copyObject(
    CopyObjectArgs.builder()
        .bucket("my-archive-bucket")
        .object("archive/processed-file.bin")
        .source(
            CopySource.builder()
                .bucket("my-upload-bucket")
                .object(s3ObjectKey)
                .build())
        .build());

// 3. Example: Direct byte stream reading using MinioClient
try (InputStream stream = minioClient.getObject(
    GetObjectArgs.builder()
        .bucket("my-upload-bucket")
        .object(s3ObjectKey)
        .build())) {
    // Process stream bytes directly on backend
}
```

---

## 5. Configuring Custom S3 Endpoints (MinIO, RustFS, R2, Ceph, GCS)

`S3StorageService` accepts any pre-configured `MinioClient`. To connect to an S3-compatible backend (such as local MinIO, RustFS, or Cloudflare R2), override the endpoint when building the `MinioClient`:

```java
import io.minio.MinioClient;

MinioClient minioClient = MinioClient.builder()
    .endpoint("http://minio.local:9000")
    .credentials("minioadmin", "minioadmin")
    .build();

S3StorageService s3Storage = new S3StorageService(minioClient, "my-bucket");
```

---

## 6. Local Disk Buffer & Multipart Constraints

S3 requires every part chunk of a multipart upload to be at least 5 MB (except the final part).

- **Disk Buffering & Preferred Part Size**: `S3StorageService` buffers incoming bytes to local disk in chunks of **8 MB** (`DEFAULT_PREFERRED_PART_SIZE`), matching Azure Blob Storage's block size. Preferred part size is configurable via `setPreferredPartSize(long)` (between 5 MB and 5 GB).
- **Multi-TB Support & Dynamic Part Size Auto-Calibration**: AWS S3 enforces a maximum ceiling of 10,000 parts per multipart upload. When an upload length exceeds 80 GB ($10,000 \times 8\text{ MB}$), `S3StorageService` automatically scales the part size up proportionally:
  - **1 TB upload**: auto-calibrated to ~105 MB per part.
  - **5 TB upload** (S3 maximum limit): auto-calibrated to ~525 MB per part.
  - Guarantees the entire upload completes within 10,000 parts without exceeding S3's 5 GB maximum part limit.
  - Peak local disk buffer usage remains bounded to at most $2 \times \text{optimalPartSize}$ (one receiving slot + one waiting slot).
- **Asynchronous Chunk Pipelining (`AsyncChunkUploader`)**: Instead of blocking the HTTP client thread while uploading chunks to S3, `S3StorageService` pipelines chunks using a bounded 3-slot model:
  - **Slot 1 (Receiving)**: Streaming incoming bytes from the client into a local temporary file.
  - **Slot 2 (Waiting)**: Holds one ready chunk on local disk buffer.
  - **Slot 3 (Uploading)**: Actively uploading a chunk to S3 on a background daemon worker thread.
  - When the background thread pool is fully utilized, work seamlessly falls back to the client thread without blocking (`ThreadPoolExecutor.CallerRunsPolicy` on a `SynchronousQueue`), ensuring zero queuing overhead and immediate backpressure.
- **Thread Pool Sizing**: Worker threads are managed via a shared executor, default 10 threads, configurable on `TusFileUploadService` via `.withCloudUploadThreadPoolSize(int)` or directly on `S3StorageService.setCloudUploadThreadPoolSize(int)`.
- **Incomplete Parts & Stale Buffer Protection**: If a client upload stream ends before reaching 5 MB and the upload is not complete, the sub-5MB chunk is saved as a `<metadataPrefix>/<UploadId>.part` object in S3. On subsequent requests, an arithmetic budget guard ($\text{existingPartsTotalSize} + \text{size}(.part) == \text{length}$) verifies whether the `.part` represents a legitimate final part or an obsolete buffer from a previous attempt, preventing file corruption and size inflation.
- **Configurable Temp Directory**: The temporary buffer directory can be configured in the constructor or builder:

```java
Path customTempDir = Paths.get("/var/tmp/tus-buffer");

S3StorageService s3Storage = new S3StorageService(
    minioClient,
    "my-bucket",
    "uploads/",
    "metadata/",
    "checksums/",
    "locks/",
    customTempDir
);
```

---

## 7. Multi-Replica Container Deployments

`S3LockingService` uses atomic S3 lock leases and short-lived lock leases (auto-renewed via a background heartbeat daemon).

- When multiple container replicas (e.g. pods in Kubernetes) process requests behind a load balancer, any replica can acquire a lock on an upload resource safely.
- If lock contention occurs across replicas, `S3LockingService` writes a `.stop` signal object in S3, signaling the active request on another pod to interrupt its input stream cleanly.
- No external database or Redis cache is required for distributed locking.

### Why Lease Renewal is Required (`S3Lock` vs `FileBasedLock`)

Understanding the architectural distinction between disk/file locking and S3 distributed locking is essential:

- **File-Based Locks (`FileBasedLock`)**: Uses OS kernel-level file channel locks (`java.nio.channels.FileLock`). When a JVM process crashes or is killed, the OS kernel automatically closes open file descriptors and drops the lock. Because process termination triggers automatic OS lock cleanup, file locks do not need a Time-To-Live (TTL) or lease renewal.
- **S3 Distributed Locks (`S3UploadLock`)**: S3 is a stateless HTTP service with no concept of OS file descriptors or process lifetimes. Locks are represented as S3 metadata objects (`.lock`).
  - **Crash Safety**: To prevent a crashed pod from permanently bricking an upload ID, S3 lock objects use a short Time-To-Live (TTL) lease (e.g. 30 seconds) so crashed locks expire automatically.
  - **Heartbeat Renewal**: Because TUS upload requests stream payload data over HTTP and can last for minutes or hours, a background daemon thread periodically renews the lease (`expiresAt`) while the upload is active.
  - **Why Renewal Cannot Be Removed**:
    - Removing renewal with a short TTL would cause locks to expire mid-upload during long transfers, leading to race conditions and data corruption.
    - Removing renewal with an infinite/static lock would mean a single pod crash (`kill -9`, node OOM) leaves behind an orphaned `.lock` object in S3, permanently deadlocking that upload ID.

### Lock Arbitration & TOCTOU Mitigation in S3

In stateless distributed object storage, acquiring a lock via standard `GetObject` followed by `PutObject` is vulnerable to a classic **Time-of-Check to Time-of-Use (TOCTOU)** race condition:
1. **Time of Check (TOC)**: Two contender nodes (Node A and Node B) concurrently check if a `.lock` object exists or is expired. Both observe it as available.
2. **Blind Overwrite**: Node A writes its lease object. An instant later, Node B (having already checked) writes its lease object, silently overwriting Node A due to S3's *last-write-wins* semantics.
3. **Dual Ownership**: Both Node A and Node B believe they exclusively own the lock.

To guarantee waterproof single-winner lock exclusivity across cloud providers and local emulators, `S3LockingService` implements a **multi-layer defense-in-depth architecture**:

1. **Conditional Writes (`If-None-Match: *`)**:
   - `PutObject` requests include the `If-None-Match: *` header.
   - On Amazon S3 and compliant object storage engines, S3 atomically rejects the second write with `HTTP 412 Precondition Failed` (`PreconditionFailed`), immediately preventing concurrent overwrites in a single network round-trip.
2. **Lock Arbitration on Backends Without Atomic Conditional Writes (Jittered Read-After-Write Verification)**:
   - Several major S3-compatible engines do not support atomic conditional writes:
     | Backend | Conditional Write (`If-None-Match: *`) Behavior | `S3LockingService` Handling |
     | :--- | :--- | :--- |
     | **AWS S3, Cloudflare R2, LocalStack, RustFS** | Returns `HTTP 412 Precondition Failed` | Optimistic CAS (1 network call) |
     | **Backblaze B2, Ceph RGW** | Returns `HTTP 501 Not Implemented` | Auto-downgrades to non-CAS arbitration |
     | **Wasabi** | Silently ignored (last-write-wins overwrite) | Layer 2 jitter arbitration or `withS3ConditionalWritesSupported(false)` |
   - On backends without conditional write support, contender nodes sleep for a brief randomized jitter (default: 20–60ms) to allow competing writes to settle, then re-read `GetObject`.
   - If the remote `holderId` does not match its own, the node detects that it was overtaken, rejects the acquisition, and leaves the winner's lock intact.
3. **Safe Expired Lock Eviction**:
   - When evicting an expired lock, the lock object's expiration is verified again immediately before deletion to prevent evicting a fresh lock created by a winning peer.
4. **Owner-Safe Lock Release**:
   - In `S3UploadLock.close()`, the node verifies that the remote lock is still owned by its own `holderId` before deleting it. If its lease expired while the process was paused and another node took over ownership, the previous node will never delete the new owner's active lock.

### Jitter Configuration & Zero-Jitter Performance Tuning

The jitter window can be customized via `.withJitter(minMs, maxMs)` on `S3LockingService`:

```java
// Option A: Zero-Jitter for AWS S3 & Cloudflare R2 (Maximum Throughput)
// On backends with atomic conditional writes, jitter is not needed.
// Passing (0L, 0L) completely bypasses Thread.sleep for fastest lock acquisition:
S3LockingService s3LockingService =
    new S3LockingService(minioClient, bucketName)
        .withJitter(0L, 0L);

// Option B: High-Latency or Distributed Backends (Ceph, multi-datacenter MinIO)
// High-latency backends or geo-replicated clusters may need a wider window to settle writes:
S3LockingService s3LockingService =
    new S3LockingService(minioClient, bucketName)
        .withJitter(50L, 200L);

// Option C: Explicit Non-CAS Mode (e.g. Wasabi)
// Pre-checks existing locks before writing unconditionally:
S3LockingService s3LockingService =
    new S3LockingService(minioClient, bucketName)
        .withS3ConditionalWritesSupported(false);
```

### Clock Synchronization & NTP Requirement

Distributed TTL lease evaluation relies on wall-clock timestamps (`expiresAt`). While `S3LockingService` incorporates a 2-second safety buffer (`CLOCK_SKEW_SAFETY_MARGIN_MS`) to absorb minor time deviations between pods, all cluster nodes and containers running `tus-java-server` MUST synchronize their system clocks using NTP (Network Time Protocol) or Amazon Time Sync Service (`chrony`). Avoid clock skew exceeding $\pm 1$ second between cluster nodes.

---

## 8. Minimal IAM Permissions Policy

The following minimal AWS IAM policy permissions are required for `S3StorageService` and `S3LockingService`:

```json
{
	"Version": "2012-10-17",
	"Statement": [
		{
			"Sid": "BucketLevelActions",
			"Effect": "Allow",
			"Action": [
				"s3:ListBucket",
				"s3:ListBucketMultipartUploads"
			],
			"Resource": "arn:aws:s3:::my-upload-bucket"
		},
		{
			"Sid": "ObjectLevelActions",
			"Effect": "Allow",
			"Action": [
				"s3:PutObject",
				"s3:GetObject",
				"s3:DeleteObject",
				"s3:AbortMultipartUpload",
				"s3:ListMultipartUploadParts"
			],
			"Resource": "arn:aws:s3:::my-upload-bucket/*"
		}
	]
}
```

> [!IMPORTANT]
> Make sure to change "my-upload-bucket" to the correct bucket name that you created for your uploads.

---

## 9. Developer Instructions: Running Local S3 Integration Tests

This section explains how developers can run the S3 integration test suite locally on their machine using Testcontainers and a RustFS test container (via its S3 API).

### Prerequisites

Before running the S3 integration tests locally, ensure you have:

1. **Java 17 or higher** installed (`java -version`).
2. **Maven 3.6 or higher** installed (`mvn -version`).
3. **Docker Engine / Docker Desktop or Podman** running on your local machine (`docker info` or `podman info`).

> [!NOTE]
> Testcontainers requires an active local container runtime (Docker or Podman) to spin up the RustFS container. If the container runtime is not running, integration tests will automatically be skipped gracefully.

### Command to Run Local S3 Integration Tests

To run the S3 integration tests locally using Maven, execute:

```bash
mvn test -Dtest="me.desair.tus.server.upload.s3.IT*"
```

Or using Maven Failsafe integration testing phase:

```bash
mvn verify -Dtest="me.desair.tus.server.upload.s3.IT*"
```

### How Testcontainers + RustFS Works

When the test suite executes:

1. **Automatic Container Lifecycle**: Testcontainers automatically pulls the official `rustfs/rustfs:latest` Docker image (if not already cached) and starts a container on dynamic local ports.
2. **Dynamic Endpoint Override**: The base test class queries `rustfs.getHost()` and `rustfs.getMappedPort(9000)` to configure `MinioClient` with `endpoint(...)` using the `rustfsadmin` credentials.
3. **Bucket Setup**: An isolated test bucket is automatically created in RustFS before tests begin.
4. **Execution & Teardown**: The integration tests execute full HTTP request lifecycles (`POST`, `PATCH`, `HEAD`, `DELETE`, deduplication, and locking) against the live local RustFS S3 endpoint. Once tests finish, the container is stopped and cleaned up automatically.

### Test Suite Structure

| Test Class | Purpose | Execution Mode |
|------------|---------|----------------|
| `UploadInfoJsonSerializerTest` | Unit test for Jackson JSON serialization (`me.desair.tus.server.util`) | Mocked / JVM |
| `LeaseDataJsonSerializerTest` | Unit test for lease data JSON serialization (`me.desair.tus.server.util`) | Mocked / JVM |
| `S3StorageServiceTest` | Fast unit test for S3 storage logic | Mocked `MinioClient` |
| `S3LockingServiceTest` | Fast unit test for S3 distributed locking | Mocked `MinioClient` |
| `S3ConcatenationServiceTest` | Fast unit test for S3 concatenation logic | Mocked `MinioClient` |
| `ITS3StorageService` | Integration test for S3 storage | Live RustFS Testcontainer |
| `ITS3LockingService` | Integration test for S3 distributed locking & contention | Live RustFS Testcontainer |
| `ITS3RufhProtocol` | IETF RUFH protocol integration suite for S3 backend | Live RustFS Testcontainer |
| `ITS3TusFileUploadService` | Full end-to-end HTTP protocol lifecycle test | Live RustFS Testcontainer |

### Troubleshooting

- **Test Skipped**: If you see tests reported as skipped, verify that Docker Desktop, Docker Engine, or Podman is running locally.
- **Port Conflicts**: Testcontainers dynamically binds RustFS to random available host ports, preventing port collision with existing local services.

---

## 10. Troubleshooting Guide

| Issue / Error | Root Cause | Solution |
|---------------|------------|----------|
| `UploadAlreadyLockedException` | Concurrent request to an active upload ID | Wait for current request to finish or ensure single-client sequencing |
| `NoSuchKey` / 404 on `.info` | Expiration or upload terminated | Client must re-initiate upload creation via `POST` |
| High S3 Request Costs | Uncached `UploadInfo` lookups | Ensure `ThreadLocalCachedStorageAndLockingService` wrapper is enabled |
| Thread leak on app shutdown | `S3LockingService` watchdog executor active | Call `s3LockingService.close()` on application shutdown |

---

## 11. Operational Hardening & S3 Cost Guidelines

### S3 Bucket Lifecycle Rules
To automatically clean up abandoned partial uploads or lock files in case of sudden server crashes, configure S3 Lifecycle Rules on your bucket:

- **Expire Incomplete Multipart Uploads**: Set rule to abort incomplete multipart uploads after 1–7 days.
- **Expire `metadata/*.part` Objects**: Configure lifecycle expiration for objects under `metadata/` prefix matching `*.part` older than 7 days.
- **Expire `locks/*` Objects**: Configure lifecycle expiration for objects under `locks/` prefix older than 1 day.

### IAM Data Action Summary
Ensure your IAM role or service account is granted `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, and `s3:ListBucket` permissions on your target bucket path.
