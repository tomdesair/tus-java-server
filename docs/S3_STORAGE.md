# S3-Compatible Storage Support for `tus-java-server`

`tus-java-server` provides native support for storing resumable file uploads in AWS S3 and any S3-compatible object storage service (such as MinIO, Cloudflare R2, Ceph, or Google Cloud Storage).

The implementation consists of three primary components:
- **`S3StorageService`** (implements `UploadStorageService`) — handles multipart upload creation, chunk appends, incomplete part persistence, expiration, and checksum deduplication.
- **`S3LockingService`** (implements `UploadLockingService`) — provides distributed locking using S3 conditional writes (`If-None-Match: "*"`) and TTL leases, enabling multi-replica container deployments without requiring Redis or external databases.
- **`S3ConcatenationService`** (implements `UploadConcatenationService`) — provides S3-native concatenation using server-side `UploadPartCopy` (for parts $\ge$ 5 MB) with a streaming re-upload fallback.

---

## 1. Quick Start

### Step 1: Add Dependencies

Add the AWS SDK v2 for S3 and Jackson `ObjectMapper` to your application's `pom.xml`:

```xml
<dependencies>
    <!-- AWS SDK v2 for Java (S3) -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>s3</artifactId>
        <version>2.30.22</version>
    </dependency>

    <!-- Jackson databind for UploadInfo JSON serialization -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.18.2</version>
    </dependency>
</dependencies>
```

### Step 2: Configure `TusFileUploadService`

```java
import software.amazon.awssdk.services.s3.S3Client;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.s3.S3StorageService;
import me.desair.tus.server.upload.s3.S3LockingService;

// 1. Instantiate S3 client
S3Client s3Client = S3Client.create(); // Uses standard AWS credential chain

// 2. Configure TusFileUploadService with S3 storage and locking
TusFileUploadService tusService = new TusFileUploadService()
    .withUploadUri("/files/upload")
    .withUploadStorageService(new S3StorageService(s3Client, "my-upload-bucket"))
    .withUploadLockingService(new S3LockingService(s3Client, "my-upload-bucket"));
```

---

## 2. Recommendation: Request Caching with `ThreadLocalCachedStorageAndLockingService`

> [!IMPORTANT]
> **Why `ThreadLocalCachedStorageAndLockingService` is Recommended for S3**:
> By default, `TusFileUploadService` automatically wraps your custom `UploadStorageService` and `UploadLockingService` in a `ThreadLocalCachedStorageAndLockingService`.
>
> During a single HTTP request lifecycle (POST, PATCH, HEAD, DELETE), the tus server validates request headers, reads upload state, appends data, and constructs response headers. Without caching, retrieving `UploadInfo` and calculating offsets would require multiple redundant network roundtrips to S3 (`GetObject` on `.info`, `ListParts`, `HeadObject`).
>
> `ThreadLocalCachedStorageAndLockingService` caches the `UploadInfo` in thread-local memory for the duration of a single HTTP request, releasing the cache automatically when the upload lock is closed at the end of the request. This dramatically reduces S3 network latency and cost per request.

---

## 3. Object Storage Layout

`S3StorageService` uses a clean, flat object key structure:

```
<objectPrefix>/<UploadId>                        # Final data object (created upon completion)
<metadataPrefix>/<UploadId>.info                 # JSON-serialized UploadInfo
<metadataPrefix>/<UploadId>.part                 # Incomplete part buffer (< 5 MB)
<checksumsPrefix>/<algorithm>/<hex_checksum>     # Deduplication checksum index
<locksPrefix>/<UploadId>.lock                    # Lock lease object (JSON: holder + expiry)
<locksPrefix>/<UploadId>.stop                    # Cross-pod contention interrupt signal
```

### Key Prefix Defaults

| Setting | Default Value | Description |
|---------|---------------|-------------|
| `objectPrefix` | `"tus-uploads/"` | Key prefix for final completed file objects |
| `metadataPrefix` | `"metadata/"` | Key prefix for `.info` JSON and `.part` buffers |
| `checksumsPrefix` | `"checksums/"` | Key prefix for deduplication index objects |
| `locksPrefix` | `"locks/"` | Key prefix for distributed lock lease objects |

---

## 4. Post-Upload Processing (`getS3ObjectKey`)

After an upload completes, downstream services can obtain the direct S3 key of the final object using `getS3ObjectKey(UploadInfo)`. This enables zero-download server-side copying (`CopyObject`) or triggering asynchronous processing workflows directly in S3:

```java
S3StorageService s3Storage = (S3StorageService) tusService.getUploadStorageService();

// Obtain full S3 key after upload completion
String s3ObjectKey = s3Storage.getS3ObjectKey(uploadInfo);
// e.g. "tus-uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e"

// Server-side S3 copy to an archive bucket (no server data transfer required)
s3Client.copyObject(CopyObjectRequest.builder()
    .sourceBucket("my-upload-bucket")
    .sourceKey(s3ObjectKey)
    .destinationBucket("my-archive-bucket")
    .destinationKey("archive/" + uploadInfo.getFileName())
    .build());
```

---

## 5. Configuring Custom S3 Endpoints (MinIO, R2, Ceph, GCS)

`S3StorageService` accepts any pre-configured `S3Client`. To connect to an S3-compatible backend (such as MinIO or Cloudflare R2), override the endpoint and enable path-style access on the `S3Client`:

```java
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

S3Client minioClient = S3Client.builder()
    .endpointOverride(URI.create("http://minio.local:9000"))
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("minioadmin", "minioadmin")))
    .region(Region.US_EAST_1)
    .forcePathStyle(true)
    .build();

S3StorageService s3Storage = new S3StorageService(minioClient, "my-bucket");
```

---

## 6. Local Disk Buffer & Multipart Constraints

S3 requires every part of a multipart upload to be at least 5 MB (except the final part).

- **Disk Buffering**: `S3StorageService` buffers incoming bytes to local disk in chunks (default 50 MB) before uploading them to S3 via `UploadPart`.
- **Incomplete Parts**: If a client upload stream ends before reaching 5 MB and the upload is not complete, the sub-5MB chunk is saved as a `<metadataPrefix>/<UploadId>.part` object in S3. On the next `PATCH` request, this chunk is downloaded, prepended to the incoming stream, and upload proceeds seamlessly.
- **Configurable Temp Directory**: The temporary buffer directory can be configured in the constructor or builder:

```java
Path customTempDir = Paths.get("/var/tmp/tus-buffer");

S3StorageService s3Storage = new S3StorageService(
    s3Client,
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

`S3LockingService` uses atomic S3 conditional writes (`If-None-Match: "*"`) and short-lived lock leases (auto-renewed via a background heartbeat daemon).

- When multiple container replicas (e.g. pods in Kubernetes) process requests behind a load balancer, any replica can acquire a lock on an upload resource safely.
- If lock contention occurs across replicas, `S3LockingService` writes a `.stop` signal object in S3, signaling the active request on another pod to interrupt its input stream cleanly.
- No external database or Redis cache is required for distributed locking.

---

## 8. Minimal IAM Permissions Policy

The following minimal AWS IAM policy permissions are required for `S3StorageService` and `S3LockingService`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:CreateMultipartUpload",
        "s3:UploadPart",
        "s3:UploadPartCopy",
        "s3:CompleteMultipartUpload",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts",
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::my-upload-bucket/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::my-upload-bucket"
    }
  ]
}
```

---

## 9. Developer Instructions: Running Local S3 Integration Tests

This section explains how developers can run the S3 integration test suite locally on their machine using Testcontainers and a MinIO test container.

### Prerequisites

Before running the S3 integration tests locally, ensure you have:

1. **Java 17 or higher** installed (`java -version`).
2. **Maven 3.6 or higher** installed (`mvn -version`).
3. **Docker Engine / Docker Desktop** running on your local machine (`docker info`).

> [!NOTE]
> Testcontainers requires an active local Docker daemon to spin up the MinIO container. If Docker is not running, integration tests will automatically be skipped gracefully.

### Command to Run Local S3 Integration Tests

To run the S3 integration tests locally using Maven, execute:

```bash
mvn test -Dtest="me.desair.tus.server.upload.s3.IT*"
```

Or using Maven Failsafe integration testing phase:

```bash
mvn verify -Dtest="me.desair.tus.server.upload.s3.IT*"
```

### How Testcontainers + MinIO Works

When the test suite executes:

1. **Automatic Container Lifecycle**: Testcontainers automatically pulls the official `minio/minio` Docker image (if not already cached) and starts a container on a dynamic local port.
2. **Dynamic Endpoint Override**: The base test class queries `minio.getHost()` and `minio.getMappedPort(9000)` to configure the AWS S3 SDK v2 client (`S3Client`) with `endpointOverride(...)` and path-style access (`forcePathStyle(true)`).
3. **Bucket Setup**: An isolated test bucket (`test-tus-bucket`) is automatically created in MinIO before tests begin.
4. **Execution & Teardown**: The integration tests execute full HTTP request lifecycles (`POST`, `PATCH`, `HEAD`, `DELETE`, deduplication, and locking) against the live local MinIO container. Once tests finish, the container is stopped and cleaned up automatically.

### Test Suite Structure

| Test Class | Purpose | Execution Mode |
|------------|---------|----------------|
| `UploadInfoSerializerTest` | Unit test for Jackson JSON serialization | Mocked / JVM |
| `S3StorageServiceTest` | Fast unit test for S3 storage logic | Mocked `S3Client` |
| `S3LockingServiceTest` | Fast unit test for S3 distributed locking | Mocked `S3Client` |
| `S3ConcatenationServiceTest` | Fast unit test for S3 concatenation logic | Mocked `S3Client` |
| `ITS3StorageServiceTest` | Integration test for S3 storage | Live MinIO Testcontainer |
| `ITS3LockingServiceTest` | Integration test for S3 distributed locking & contention | Live MinIO Testcontainer |
| `ITS3TusFileUploadServiceTest` | Full end-to-end HTTP protocol lifecycle test | Live MinIO Testcontainer |

### Troubleshooting

- **Test Skipped**: If you see tests reported as skipped, verify that Docker Desktop or Docker Engine is running locally.
- **Port Conflicts**: Testcontainers dynamically binds MinIO to random available host ports, preventing port collision with existing local services.
