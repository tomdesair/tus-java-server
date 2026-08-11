# Azure Blob Storage Support for `tus-java-server`

`tus-java-server` provides native support for storing resumable file uploads in **Azure Blob Storage** using the official Microsoft Azure Storage Blob SDK (`com.azure:azure-storage-blob`).

The implementation consists of four primary components:
- **`AzureBlobStorageService`** (implements `UploadStorageService`) — handles Block Blob uploads via staged block staging (`stageBlock` / `commitBlockList`), streaming appends, sub-threshold `.part` buffering, block list truncation, expiration, and checksum deduplication.
- **`AzureBlobLockingService`** (implements `UploadLockingService`) — provides distributed locking using native Azure Blob Leases (30s duration) on `.lock` target blobs with background renewal, enabling multi-replica container deployments without requiring Redis or external databases.
- **`AzureBlobUploadLock`** (implements `UploadLock`) — encapsulates active Azure Blob Leases with a background daemon thread that periodically renews the lease every 10 seconds.
- **`AzureBlobConcatenationService`** (implements `UploadConcatenationService`) — provides server-side zero-copy concatenation using Azure's native `stageBlockFromUrl` operation.

---

## 1. Quick Start

### Step 1: Add Dependencies

Add the official Azure Storage Blob SDK and Jackson dependencies to your application's `pom.xml`:

```xml
<dependencies>
    <!-- Official Azure Blob Storage Java SDK -->
    <dependency>
        <groupId>com.azure</groupId>
        <artifactId>azure-storage-blob</artifactId>
        <version>12.35.0</version>
    </dependency>

    <!-- Jackson databind & annotations for UploadInfo JSON serialization -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.22.1</version>
    </dependency>
</dependencies>
```

### Step 2: Configure `TusFileUploadService`

```java
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.azure.AzureBlobStorageService;
import me.desair.tus.server.upload.azure.AzureBlobLockingService;

// 1. Option A (Recommended Production Setup): Managed Identity via DefaultAzureCredential
String endpoint = System.getenv("AZURE_STORAGE_BLOB_ENDPOINT"); // e.g. "https://myaccount.blob.core.windows.net"
String containerName = System.getenv().getOrDefault("AZURE_STORAGE_CONTAINER", "uploads");

BlobContainerClient containerClient = new BlobContainerClientBuilder()
    .endpoint(endpoint)
    .credential(new DefaultAzureCredentialBuilder().build())
    .containerName(containerName)
    .buildClient();

// Option B (Alternative Production Setup): Connection String from Secret Manager / Env Var
// String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
// BlobContainerClient containerClient = new BlobContainerClientBuilder()
//     .connectionString(connectionString)
//     .containerName(containerName)
//     .buildClient();

// 2. Instantiate Azure Blob Storage & Distributed Locking services
AzureBlobStorageService azureStorageService = new AzureBlobStorageService(containerClient);
AzureBlobLockingService azureLockingService = new AzureBlobLockingService(containerClient);

// 3. Configure TusFileUploadService with Azure storage and locking
// Note: Automatic JVM shutdown hooks are built-in by default to terminate watchdog threads on pod exit.
// Manual call to tusService.close() or azureLockingService.close() is optional for custom container lifecycles.
TusFileUploadService tusService = new TusFileUploadService()
    .withUploadUri("/files/upload")
    .withUploadStorageService(azureStorageService)
    .withUploadLockingService(azureLockingService);
```

---

## 2. Recommendation: Request Caching with `ThreadLocalCachedStorageAndLockingService`

> [!IMPORTANT]
> **Why `ThreadLocalCachedStorageAndLockingService` is Recommended for Azure**:
> By default, `TusFileUploadService` automatically wraps your custom `UploadStorageService` and `UploadLockingService` in a `ThreadLocalCachedStorageAndLockingService`.
>
> During a single HTTP request lifecycle (POST, PATCH, HEAD, DELETE), the tus server validates request headers, reads upload state, appends data, and constructs response headers. Without caching, retrieving `UploadInfo` and calculating offsets would require multiple redundant network roundtrips to Azure (`downloadContent` on `.info`, `getProperties`).
>
> `ThreadLocalCachedStorageAndLockingService` caches the `UploadInfo` in thread-local memory for the duration of a single HTTP request, releasing the cache automatically when the upload lock is closed at the end of the request. This dramatically reduces Azure network latency and API call cost per request.

---

## 3. Object Storage Layout

`AzureBlobStorageService` uses a clean, structured blob naming convention:

```
<container>/
├── uploads/<uploadId>                # Final upload data (Block Blob)
├── metadata/<uploadId>.info          # JSON-serialized UploadInfo
├── metadata/<uploadId>.part          # Incomplete sub-threshold buffer blob
├── checksums/<algorithm>/<hex_hash>  # Deduplication checksum index object
├── locks/<uploadId>.lock             # Distributed lock target blob (Blob Lease)
└── locks/<uploadId>.stop             # Cross-replica contention interrupt signal
```

### Key Prefix Defaults

| Setting | Default Value | Description |
|---------|---------------|-------------|
| `uploadPrefix` | `"uploads/"` | Blob name prefix for final completed file objects |
| `metadataPrefix` | `"metadata/"` | Blob name prefix for `.info` JSON and `.part` buffers |
| `checksumsPrefix` | `"checksums/"` | Blob name prefix for deduplication index objects |
| `locksPrefix` | `"locks/"` | Blob name prefix for distributed lock lease objects |

---

## 4. Post-Upload Processing (`getAzureBlobName`)

After an upload completes, downstream services can obtain the direct Azure blob name of the final object using `getAzureBlobName(uploadUri, ownerKey)`:

```java
import com.azure.storage.blob.BlobClient;
import me.desair.tus.server.upload.azure.AzureBlobStorageService;

AzureBlobStorageService azureStorage = (AzureBlobStorageService) tusService.getUploadStorageService();

String uploadUri = "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e";
String ownerKey = "user-123";

// 1. Obtain full Azure blob name after upload completion
String blobName = azureStorage.getAzureBlobName(uploadUri, ownerKey);
// e.g. "uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e"

// 2. Direct Azure SDK access for post-upload processing
BlobClient dataBlob = containerClient.getBlobClient(blobName);
```

---

## 5. Custom Endpoints & Authentication Best Practices

Since `AzureBlobStorageService` accepts a pre-configured `BlobContainerClient`, authentication is fully delegated to the user.

### Production: `DefaultAzureCredential` (Managed Identity / Azure AD)

```java
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;

BlobContainerClient containerClient = new BlobContainerClientBuilder()
    .endpoint("https://<account_name>.blob.core.windows.net")
    .containerName("tus-uploads")
    .credential(new DefaultAzureCredentialBuilder().build())
    .buildClient();
```

### Local Development: Connection String / Azurite Emulator

```java
BlobContainerClient containerClient = new BlobContainerClientBuilder()
    .connectionString("UseDevelopmentStorage=true")
    .containerName("tus-uploads")
    .buildClient();
```

---

## 6. Local Disk Buffer & Block Size Auto-Calibration

`AzureBlobStorageService` streams incoming PATCH payloads in chunks of `optimalBlockSize` into temporary files, staging each block to Azure as it completes. Peak disk usage per upload is capped at `1 × optimalBlockSize` (e.g. 8 MB).

Block sizes auto-calibrate based on total upload size:
- **Baseline Preferred Size**: 8 MB (configurable via constructor)
- **Minimum Block Size**: 4 MB
- **Maximum Block Size**: 4000 MiB (Azure limit)
- **Maximum Blocks per Blob**: 50,000 (Azure limit)

---

## 7. Multi-Replica Container Deployments (Azure Blob Leases & Renewal)

### Lease Renewal Rationale
`AzureBlobLockingService` uses native Azure Blob Leases (30-second duration) for distributed locking. Because large file uploads can stream over several minutes or hours, `AzureBlobUploadLock` runs a background daemon thread that renews the lease every 10 seconds. If an application server crashes unexpectedly, the lease auto-expires after 30 seconds without requiring manual lock cleanup sweeps.

### Lock Contention Resolution
Lock contention resolution operates on two levels:
1. **JVM-local**: Active `InterruptibleInputStream` instances are registered in a concurrent map and interrupted directly if a concurrent lock request arrives in the same JVM.
2. **Cross-replica**: A `.stop` signal blob (`locks/<uploadId>.stop`) is written to Azure Storage. A background watchdog thread polls for `.stop` blobs and interrupts active streams on other cluster nodes.

---

## 8. Troubleshooting Guide

| Issue / Error | Root Cause | Solution |
|---|---|---|
| **HTTP 409 Conflict** | Another process or cluster pod currently holds an active lease on the lock blob. | Normal behavior during concurrent PATCH/DELETE requests. Retry after lock release. |
| **HTTP 404 BlobNotFound** | The upload metadata `.info` blob does not exist or was expired/deleted. | Verify upload ID validity or upload expiration timestamps (`uploadExpirationPeriod`). |
| **Azurite connection refused** | Azurite emulator is not running or listening on port 10000. | Launch Azurite via Docker (`docker run -p 10000:10000 mcr.microsoft.com/azure-storage/azurite`). |
| **`MaxAppendSizeExceededException`** | Incoming PATCH payload chunk exceeded the configured `maxAppendSize`. | Adjust `withMaxAppendSize()` setting on `TusFileUploadService`. |

---

## 9. Test Suite Structure

| Test Suite Class | Type | Dependencies | Execution Time | Description |
|---|---|---|---|---|
| `AzureBlobStorageServiceTest` | Unit Test | Mockito (Offline) | < 1s | Fast unit tests for storage CRUD, chunk streaming, and deduplication. |
| `AzureBlobLockingServiceTest` | Unit Test | Mockito (Offline) | < 1s | Unit tests for lease acquisition, lock contention, and stream interruption. |
| `AzureBlobUploadLockTest` | Unit Test | Mockito (Offline) | < 1s | Unit tests for lease release and background renewal. |
| `AzureBlobConcatenationServiceTest` | Unit Test | Mockito (Offline) | < 1s | Unit tests for zero-copy concatenation merging. |
| `ITAzureBlobStorageServiceTest` | Integration | Azurite (Docker) | ~ 5s | Live end-to-end storage integration test against Azurite emulator. |
| `ITAzureBlobRufhProtocol` | Integration | Azurite (Docker) | ~ 8s | IETF RUFH protocol integration suite for Azure backend. |
| `ITAzureBlobTusFileUploadService` | Integration | Azurite (Docker) | ~ 8s | Tus 1.0.0 protocol integration suite for Azure backend. |

---

## 10. Security & RBAC Permissions Policy

1. **Authentication**: Use `DefaultAzureCredential` or Managed Identity in production. Never hardcode storage account keys in source code.
2. **RBAC Data-Plane Role**: Assign the **`Storage Blob Data Contributor`** role to the application identity.
3. **Lease Permissions Note**: Note that native Azure Blob Lease operations (`acquireLease`, `renewLease`, `releaseLease`) require the `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write` data action in Azure RBAC policies.

### Minimal RBAC Policy (JSON)

```json
{
  "properties": {
    "roleName": "TusFileUploadServiceBlobDataContributor",
    "description": "Minimum RBAC permissions for tus-java-server Azure Blob Storage integration",
    "assignableScopes": [
      "/subscriptions/<subscription-id>/resourceGroups/<resource-group>/providers/Microsoft.Storage/storageAccounts/<account-name>"
    ],
    "permissions": [
      {
        "actions": [],
        "notActions": [],
        "dataActions": [
          "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read",
          "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write",
          "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete",
          "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/add/action"
        ],
        "notDataActions": []
      }
    ]
  }
}
```

---

## 11. Operational Hardening & Cost Optimization Guidance

1. **Storage Lifecycle Management Policies**: Configure an Azure Lifecycle Management policy to automatically delete uncommitted block blobs or orphaned `.part` buffers older than 7 days.
2. **Container Soft Delete & Versioning**: Enable Azure Container Soft Delete (e.g. 7-day retention) to protect completed upload data from accidental deletion.
3. **API Cost Optimization**: `AzureBlobStorageService` minimizes API costs by combining GET calls, using single `getProperties()` lookups, and caching metadata in `ThreadLocalCachedStorageAndLockingService`.

---

## 12. Running Local Azure Integration Tests (Azurite)

```bash
# Run fast offline unit tests
mvn test -Dtest="*Azure*Test" -q

# Run integration tests against Azurite container
mvn test -Dtest="ITAzureBlob*" -q
```
