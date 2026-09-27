# Migration Guide: Tus 1.0.0 to IETF Resumable Uploads

This guide provides step-by-step instructions for upgrading `tus-java-server` applications and clients to support the official **IETF Resumable Uploads for HTTP specification** ([draft-ietf-httpbis-resumable-upload](https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.html)).

---

## 1. Overview & Compatibility

`tus-java-server` version 2.0.0 introduces full support for the IETF Resumable Uploads standard while maintaining **100% backward compatibility** with legacy Tus 1.0.0 clients.

- **Zero Breaking Changes for Existing Tus 1.0.0 Clients**: Legacy clients sending `Tus-Resumable: 1.0.0` header fields will continue to operate without modification.
- **Dual-Protocol Support**: By default, `TusFileUploadService` automatically detects the protocol version (`ProtocolVersion.AUTO`) used by incoming requests and responds appropriately.

---

## 2. Server Configuration

### Enabling Protocol Options
You can configure protocol support via the `withSupportedProtocolVersions(...)` method on `TusFileUploadService`:

```java
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TusFileUploadService;

TusFileUploadService tusService = new TusFileUploadService()
    .withUploadUri("/files")
    // Enabled by default: AUTO mode detects TUS_1_0_0 vs RUFH per request
    .withSupportedProtocolVersions(ProtocolVersion.AUTO);
```

To restrict the server to only IETF Resumable Uploads or legacy Tus 1.0.0:
```java
// Force server to only serve IETF Resumable Uploads for HTTP (RUFH)
tusService.withSupportedProtocolVersions(ProtocolVersion.RUFH);

// Force server to only serve legacy Tus 1.0.0
tusService.withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0);
```

---

## 3. Protocol Header Mapping

When updating clients or proxies to use IETF Resumable Uploads, note the following header changes:

| Concept / Header | Tus 1.0.0 | IETF Resumable Uploads |
| :--- | :--- | :--- |
| **Version Header** | `Tus-Resumable: 1.0.0` | *Omitted* (protocol autodetected via `Upload-Complete`) |
| **Upload Offset** | `Upload-Offset: 1234` | `Upload-Offset: 1234` (RFC 9651 Integer) |
| **Upload Length** | `Upload-Length: 5000` | `Upload-Length: 5000` (RFC 9651 Integer) |
| **Completeness** | Implicitly checked by size | `Upload-Complete: ?1` (true) or `Upload-Complete: ?0` (false) |
| **Partial Upload Media Type** | `Content-Type: application/offset+octet-stream` | `Content-Type: application/partial-upload` |
| **Upload Limits** | `Tus-Max-Size: 104857600` | `Upload-Limit: max-size=104857600, max-append-size=5242880` |
| **Capabilities Discovery** | `OPTIONS` returns `Tus-Version`, `Tus-Extension` | `OPTIONS` returns `Accept-Patch: application/partial-upload` and `Upload-Limit` |
| **Error Format** | Plain text | `application/problem+json` (RFC 7807) |
| **Checksum / Data Integrity** | `Upload-Checksum: sha1 ...` | `Content-Digest` and `Repr-Digest` (RFC 9530) |
| **Expiration Handling** | `Upload-Expires: Wed, 25 Jun 2026 16:00:00 GMT` | `Upload-Limit: max-age=3600` |

---

## 4. Key Protocol Changes for Client Integrations

### 4.1 Upload Creation (`POST`, `PUT`, `PATCH`)
Clients can initiate uploads via `POST`, `PUT`, or `PATCH`. The request must contain the `Upload-Complete` header:

- **Optimistic Creation (Sending Data Immediately)**:
  ```http
  POST /files HTTP/1.1
  Host: example.com
  Upload-Length: 123456
  Upload-Complete: ?1
  Content-Length: 123456

  [binary content]
  ```

- **Chunked / Multi-Part Creation**:
  ```http
  POST /files HTTP/1.1
  Host: example.com
  Upload-Length: 123456
  Upload-Complete: ?0

  ```
  *Response*: `201 Created` with `Location: /files/b530ce8ff` and `Upload-Limit: max-size=...`.

### 4.2 Appending Data (`PATCH`)
Clients send chunk appends using `Content-Type: application/partial-upload`:

```http
PATCH /files/b530ce8ff HTTP/1.1
Host: example.com
Upload-Offset: 0
Upload-Complete: ?0
Content-Type: application/partial-upload
Content-Length: 50000

[partial binary content]
```

To send the final chunk:
```http
PATCH /files/b530ce8ff HTTP/1.1
Host: example.com
Upload-Offset: 50000
Upload-Complete: ?1
Content-Type: application/partial-upload
Content-Length: 73456

[final binary content]
```

### 4.3 Error Handling (`application/problem+json`)
IETF Resumable Upload error responses return RFC 7807 problem details JSON:

- **Offset Mismatch (`409 Conflict`)**:
  ```json
  {
    "type": "https://iana.org/assignments/http-problem-types#mismatching-upload-offset",
    "title": "offset from request does not match offset of resource",
    "expected-offset": 12500000,
    "provided-offset": 25000000
  }
  ```

### 4.4 Data Integrity Verification (HTTP Digests)
In Tus 1.0.0, data integrity was verified using the `Upload-Checksum` header from the checksum extension.
In RUFH protocol, integrity verification is achieved using **RFC 9530 HTTP Digests**:
- **Chunk verification**: Use the `Content-Digest` header containing the cryptographic hash of the transmitted chunk (e.g. `Content-Digest: sha-256=:...:`).
- **Full file verification**: Use the `Repr-Digest` header in the creation request or final append request to define the expected digest of the complete file. Alternatively, send `Want-Repr-Digest` in the request to receive the calculated file digest from the server in the response's `Repr-Digest` header.

### 4.5 Download Extension
The unofficial `download` extension is fully supported under both protocols. Once enabled via the `withDownloadFeature()` method:
- Clients can download completed uploads using a standard HTTP `GET` request to the upload's Location URI, regardless of whether it was uploaded via Tus 1.0.0 or RUFH.
- For RUFH download responses, all Tus-specific headers (such as `Upload-Metadata` or `Tus-Extension`) are omitted.

### 4.6 Expiration Handling
The expiration extension works seamlessly across both Tus 1.0.0 and IETF Resumable Uploads protocols when configured via `.withUploadExpirationPeriod(Long milliseconds)`:
- **Tus 1.0.0 Protocol**: Expiration is communicated to clients via the `Upload-Expires` response header field formatted as an HTTP date-time string (RFC 7231, e.g. `Upload-Expires: Wed, 25 Jun 2026 16:00:00 GMT`).
- **IETF Resumable Uploads Protocol**: Expiration is communicated to clients via the `max-age` structured field parameter in the `Upload-Limit` response header field indicating remaining valid seconds (e.g. `Upload-Limit: max-size=1048576, max-age=3600`).

---

## 5. Reverse Proxies & Load Balancers

Ensure that reverse proxies (Nginx, HAProxy, AWS ALB, Cloudflare):
1. Forward custom headers: `Upload-Offset`, `Upload-Complete`, `Upload-Length`, `Upload-Limit`, `Upload-Draft`.
2. Do not strip or alter `Content-Type: application/partial-upload`.
3. Support HTTP `PATCH` and `DELETE` requests.

---

## 6. Java Server API Migration Guide (v1.0.0-3.3 to v2.0.0)

When upgrading backend Java applications from `1.0.0-3.3` to `2.0.0`, note the following changes and recommendations:

### 6.1 Java 17 & Jakarta EE Baseline
- **Java 17**: `tus-java-server` 2.0.0 targets Java 17+.
- **Jakarta EE**: The library uses `jakarta.servlet.*` package imports instead of legacy `javax.servlet.*` packages, aligning with Spring Boot 3.x, Tomcat 10+, and Jetty 11+.

### 6.2 Default Distributed Lease Locking
- In `1.0.0-3.3`, `TusFileUploadService.withStoragePath(path)` defaulted to `DiskLockingService`, which relied on OS kernel file locks (`java.nio.channels.FileLock`).
- In `2.0.0`, `withStoragePath(path)` defaults to `LeaseFileLockingService`, which uses atomic sibling mutex directories (`<UploadId>.mutex/`) and JSON lease files with auto-renewal. This provides container-safe, distributed locking across multi-replica pods on Kubernetes, NFS (v3/v4), AWS EFS, Azure Files, and SMB without requiring OS-level lock daemons or external coordination.
- **Opting Out**: If your application runs exclusively on local disks and you prefer kernel file locks, explicitly configure:
  ```java
  tusService.withUploadLockingService(new DiskLockingService(storagePath));
  ```

### 6.3 Jackson Dependencies Bundled in Compile Scope
- Jackson dependencies (`jackson-databind`, `jackson-annotations`, `jackson-core`) are now included with compile scope in `tus-java-server`. You no longer need to declare Jackson separately in your application `pom.xml` when using JSON metadata serialization (`withJsonSerialization()`) or cloud storage.

### 6.4 Safe Lock-Holding InputStream Lifecycle
- In 2.0.0, `tusService.getUploadedBytes(...)` returns a stream that retains an exclusive upload lock until closed. This prevents concurrent `DELETE` or `PATCH` requests from corrupting data mid-stream.
- **Best Practice**: Always consume uploaded bytes within a `try-with-resources` block:
  ```java
  try (InputStream stream = tusService.getUploadedBytes(uploadUri)) {
      // Process file data safely under lock protection
  } // Lock is automatically released on close
  ```

### 6.5 Pluggable Cloud Storage Backends
- `2.0.0` introduces native cloud object storage backends:
  - **S3 Storage**: `S3StorageService`, `S3LockingService`, and `S3ConcatenationService` (AWS S3, MinIO, RustFS, Cloudflare R2, Ceph, GCS). See [docs/S3_STORAGE.md](S3_STORAGE.md).
  - **Azure Blob Storage**: `AzureBlobStorageService`, `AzureBlobLockingService`, and `AzureBlobConcatenationService`. See [docs/AZURE_BLOB_STORAGE.md](AZURE_BLOB_STORAGE.md).

### 6.6 Post-Upload Listeners (`UploadCompletionListener`)
- You can now register callbacks that fire immediately upon upload completion:
  ```java
  tusService.withUploadCompletionListener((uploadInfo, service) -> {
      log.info("Upload completed: {}", uploadInfo.getId());
  });
  ```
