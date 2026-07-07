# Migration Guide: Tus 1.0.0 to IETF Resumable Uploads

This guide provides step-by-step instructions for upgrading `tus-java-server` applications and clients to support the official **IETF Resumable Uploads for HTTP specification** ([draft-ietf-httpbis-resumable-upload](https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-11.html)).

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
    // Enabled by default: AUTO mode detects TUS_1_0_0 vs IETF per request
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

---

## 5. Reverse Proxies & Load Balancers

Ensure that reverse proxies (Nginx, HAProxy, AWS ALB, Cloudflare):
1. Forward custom headers: `Upload-Offset`, `Upload-Complete`, `Upload-Length`, `Upload-Limit`, `Upload-Draft`.
2. Do not strip or alter `Content-Type: application/partial-upload`.
3. Support HTTP `PATCH` and `DELETE` requests.
