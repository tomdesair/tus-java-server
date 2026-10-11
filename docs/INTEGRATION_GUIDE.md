# Integration Guide

How to wire `tus-java-server` into your Java application, handle completed uploads and keep storage clean. For configuration options see the [Configuration Reference](CONFIGURATION.md).

## Upload Lifecycle

1. **Create & transfer** — the client talks to the upload endpoint; your code only forwards the request to `TusFileUploadService.process(...)`.
2. **Complete** — when the last byte arrives the upload is complete (an optional `UploadCompletionListener` fires).
3. **Consume** — your application reads the bytes (`getUploadedBytes`) and moves them to their final location.
4. **Clean up** — your application calls `deleteUpload`; a scheduled `cleanup()` removes abandoned or expired uploads.

## 1. Receiving a Resumable Upload

To process an upload request you have to pass the current `jakarta.servlet.http.HttpServletRequest` and `jakarta.servlet.http.HttpServletResponse` objects to `me.desair.tus.server.TusFileUploadService.process()`. Typical places where you can do this are Servlets, Filters or REST API Controllers. The same call handles both Tus 1.0.0 and IETF RUFH clients.

The `process()` method returns the processed `UploadInfo` object on successful `POST`, `PATCH`, and `HEAD` requests (or `null` for `OPTIONS` preflight requests, `DELETE` terminations, or when an error response is generated).

### Spring Boot 3 controller

The Spring snippets below are complete and compile as-is (Spring Boot 3, Java 17+, `spring-boot-starter-web`); only the paths need adjusting.

```java
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.TusFileUploadService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileUploadController {

  private final TusFileUploadService tusFileUploadService;

  public FileUploadController(TusFileUploadService tusFileUploadService) {
    this.tusFileUploadService = tusFileUploadService;
  }

  @RequestMapping(
      value = {"/api/upload", "/api/upload/**"},
      method = {
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.HEAD,
        RequestMethod.DELETE,
        RequestMethod.OPTIONS,
        RequestMethod.GET
      })
  public void processUpload(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    tusFileUploadService.process(request, response);
  }
}
```

> CORS headers and `OPTIONS` preflight handling are built in (the `cors` extension), so no `@CrossOrigin` annotation is needed.

### Plain Jakarta Servlet (Tomcat, Jetty, Undertow, ...)

```java
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.TusFileUploadService;

@WebServlet("/api/upload/*")
public class UploadServlet extends HttpServlet {

  private TusFileUploadService tus;

  @Override
  public void init() {
    tus =
        new TusFileUploadService()
            .withStoragePath("/path/to/uploads")
            .withUploadUri("/api/upload")
            .withUploadDeduplication(true)
            .withThreadLocalCache(true);
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    // Override service() (not doPost/doGet) so that PATCH, HEAD and OPTIONS reach the library
    tus.process(req, resp);
  }

  @Override
  public void destroy() {
    try {
      tus.close();
    } catch (IOException e) {
      log("Failed to close TusFileUploadService", e);
    }
  }
}
```

Note that overriding `service(...)` (instead of `doGet`/`doPost`) is required so that `PATCH` requests reach the library.

### Creation vs. progress ambiguity

`process()` returns an `UploadInfo` on both creation (`POST`) and progress updates (`PATCH` / `HEAD`). When associating an upload ID with a user session or database entity on creation, check the method:

```java
UploadInfo info = tusFileUploadService.process(request, response, ownerKey);
if (info != null && "POST".equalsIgnoreCase(request.getMethod())) {
  userSessionRepository.recordUpload(ownerKey, info.getId());
}
```

**Single-request upload completion**: when clients upload the entire file in a single request using the `creation-with-upload` extension or a RUFH single-request `POST`, the upload is already complete when `process()` returns, and any registered `UploadCompletionListener` will have already fired *before* `process()` returns to your controller.

### Multi-tenancy with `ownerKey`

`process(request, response, String ownerKey)` accepts an optional `ownerKey`. The `ownerKey` provides a hard separation between uploads of different users, groups or tenants: an upload created with one key cannot be read, queried or deleted with another. Examples of `ownerKey` values are user ID's, group names, client ID's... Use the same overloads with `ownerKey` on `getUploadInfo`, `getUploadedBytes` and `deleteUpload` when reading the upload back.

## 2. Handling Upload Completion & Retrieving Files

You can handle upload completion in two ways:
1. **Server-Side Callback Listener (`UploadCompletionListener`)**: Register a hook directly on `TusFileUploadService` that triggers automatically when the final chunk is uploaded.
2. **Domain API Endpoint**: Have your frontend notify your application backend domain endpoint (e.g. `POST /api/documents`) once upload completes.

### Option A: Server-Side `UploadCompletionListener`

Register one or more completion listeners on your `TusFileUploadService`. When an upload finishes transferring all bytes, the callback executes with the completed `UploadInfo` and the service instance. The upload lock is released *before* the listener is invoked, allowing you to safely stream or delete uploaded bytes immediately:

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import me.desair.tus.server.TusFileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TusConfig {

  private static final Logger log = LoggerFactory.getLogger(TusConfig.class);

  @Bean(destroyMethod = "close")
  public TusFileUploadService tusFileUploadService() {
    return new TusFileUploadService()
        .withStoragePath("/path/to/uploads")
        .withUploadUri("/api/upload")
        .withUploadDeduplication(true)
        .withThreadLocalCache(true)
        .withUploadCompletionListener(
            (uploadInfo, service) -> {
              // Safely retrieve or guess the file name (preventing path traversal and NPE)
              String fileName =
                  Paths.get(uploadInfo.getFileName()).getFileName().toString();
              try {
                try (InputStream is = service.getUploadedBytes(uploadInfo)) {
                  Files.copy(is, Paths.get("/var/data/documents", fileName));
                } // closing the stream releases the lock, which deleteUpload needs
                service.deleteUpload(uploadInfo);
              } catch (Exception e) {
                log.error("Failed to process completed upload {}", uploadInfo.getId(), e);
              }
            });
  }
}
```

> [!WARNING]
> Never use a client-supplied `filename` directly as a path component without sanitising it (path traversal). The examples above are simplified.

### Option B: Client-Initiated Domain Notification

When an upload completes, the client receives the final `204 No Content` response from the Tus/RUFH protocol endpoint (`/api/upload/...`). Because Tus and RUFH are decoupled file transport protocols, your frontend application can notify your backend domain API (e.g. `POST /api/documents`) that the upload is complete and pass along the `uploadUrl`.

> [!NOTE]
> `POST /api/documents` represents your application's domain REST endpoint, not a protocol endpoint. The Tus server itself handles file transfer (`/api/upload`), while your application endpoint coordinates business logic, database persistence, and final file consumption.

```java
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {

  /** Request body sent by your frontend once the upload finished. */
  public record DocumentUploadRequest(String uploadUrl) {}

  private final TusFileUploadService tusFileUploadService;

  public DocumentController(TusFileUploadService tusFileUploadService) {
    this.tusFileUploadService = tusFileUploadService;
  }

  @PostMapping("/api/documents")
  public ResponseEntity<Void> completeDocumentUpload(@RequestBody DocumentUploadRequest request)
      throws IOException, TusException {
    String uploadUrl = request.uploadUrl();

    // 1. Retrieve upload metadata (file name, length, custom client metadata)
    UploadInfo info = tusFileUploadService.getUploadInfo(uploadUrl);
    String fileName = Paths.get(info.getFileName()).getFileName().toString();

    // 2. Stream the uploaded bytes to their permanent location
    try (InputStream is = tusFileUploadService.getUploadedBytes(uploadUrl)) {
      Files.copy(is, Paths.get("/var/data/documents", fileName));
    }

    // 3. Remove the temporary upload
    tusFileUploadService.deleteUpload(uploadUrl);
    return ResponseEntity.ok().build();
  }
}
```

> `getUploadedBytes(...)` returns a stream that holds the upload lock until it is closed. Always use try-with-resources.

### Upload information

Using `me.desair.tus.server.TusFileUploadService.getUploadInfo(String uploadUrl)` you can retrieve metadata about a specific upload process. This includes metadata provided by the client as well as metadata kept by the library like creation timestamp, creator ip-address list, upload length... The method `UploadInfo.getId()` returns the unique identifier of this upload encapsulated in an `UploadId` instance. The original (custom generated) identifier object of this upload can be retrieved using `UploadId.getOriginalObject()`. A URL safe string representation of the identifier is returned by `UploadId.toString()`. It is highly recommended to consult the [JavaDoc of both classes](https://tus.desair.me/).

### Cloud Storage Native Processing (S3 & Azure Blob)

When using cloud object storage backends, downstream services can directly obtain the raw cloud object key or blob name to perform zero-download server-side copying, background job processing, or direct cloud SDK operations:

* **S3 / MinIO Storage**: Call `s3StorageService.getS3ObjectKey(uploadUrl)` to get the full S3 object key (e.g. `uploads/018f3a...`). For complete server-side `copyObject` examples and MinIO SDK usage, see the **[S3 Storage Guide](S3_STORAGE.md#4-post-upload-processing-gets3objectkey)**.
* **Azure Blob Storage**: Call `azureBlobStorageService.getAzureBlobName(uploadUrl)` to get the full blob name. For complete Azure SDK examples with `BlobClient`, see the **[Azure Blob Storage Guide](AZURE_BLOB_STORAGE.md#4-post-upload-processing-getazureblobname)**.

> [!TIP]
> In Spring Boot applications, inject your `S3StorageService` or `AzureBlobStorageService` bean directly into downstream controllers or completion listeners. Avoid casting `(S3StorageService) tusFileUploadService.getUploadStorageService()` because when `withThreadLocalCache(true)` is enabled, `getUploadStorageService()` returns the `ThreadLocalCachedStorageAndLockingService` wrapper, causing a `ClassCastException`.

## 3. Cleanup

After having processed the uploaded bytes on the server backend (e.g. copy them to their final persistent location), it's important to clean up the (temporary) uploaded bytes. This can be done by calling `me.desair.tus.server.TusFileUploadService.deleteUpload(String uploadUri)` as shown above. This will remove the uploaded bytes and any associated upload information from the storage backend. Alternatively, a client can also remove an (in-progress) upload using the [termination extension](https://tus.io/protocols/resumable-upload.html#termination).

Next to removing uploads after they have been completed and processed by the backend, it is also recommended to schedule a regular maintenance task to clean up any expired uploads or locks, using `me.desair.tus.server.TusFileUploadService.cleanup()` (expiration requires `withUploadExpirationPeriod(...)`):

```java
import java.io.IOException;
import me.desair.tus.server.TusFileUploadService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CleanupJob {

  private final TusFileUploadService tusFileUploadService;

  public CleanupJob(TusFileUploadService tusFileUploadService) {
    this.tusFileUploadService = tusFileUploadService;
  }

  @Scheduled(fixedDelay = 600000) // every 10 minutes; requires @EnableScheduling
  public void cleanupExpiredUploads() throws IOException {
    tusFileUploadService.cleanup();
  }
}
```

## 4. Security Checklist

The library implements the transfer protocols; **authentication and authorization are your responsibility**:

* **Authenticate the endpoint**: protect `/api/upload/**` with your normal security layer (Spring Security, servlet filter, API gateway) *before* calling `process()`. Remember `OPTIONS` preflight requests carry no credentials.
* **Isolate tenants**: pass a user/tenant `ownerKey` to `process()`, `getUploadInfo`, `getUploadedBytes` and `deleteUpload` so uploads can't be accessed across users.
* **Custom checks**: implement a `TusExtension` (see `addTusExtension`) for rules such as allowed file types or per-user quotas.
* **Limit resource use**: set `withMaxUploadSize(...)` and `withUploadExpirationPeriod(...)`, and schedule `cleanup()`.
* **Treat metadata as untrusted**: `Upload-Metadata` (filename, content type) is client-controlled. Sanitise filenames and verify content before use.
* **Prefer HTTPS** and, for integrity, enable checksums (`Upload-Checksum` for Tus, `Content-Digest` / `Repr-Digest` for RUFH).

## 5. Frontend Client Example (Uppy / JavaScript)

Connect any standard Tus client (e.g. [Uppy](https://uppy.io/) or `tus-js-client`) to your backend upload endpoint:

```javascript
import Uppy from '@uppy/core';
import Tus from '@uppy/tus';

const uppy = new Uppy().use(Tus, {
  endpoint: 'http://localhost:8080/api/upload',
  chunkSize: 5 * 1024 * 1024 // 5MB chunk size
});
```

Complete working examples:

* [Resumable and asynchronous file upload in Spring Boot REST API with Uppy JavaScript client.](https://github.com/tomdesair/tus-java-server-spring-demo)
* [Detailed blog post by Ralph](https://golb.hplar.ch/2019/06/upload-with-tus.html) on how to use this library in [Spring Boot in combination with the Tus JavaScript client](https://github.com/ralscha/blog2019/tree/master/uploadtus).
* [Resumable and asynchronous file upload using Uppy with form submission in Dropwizard (Jetty)](https://github.com/tomdesair/tus-java-server-dropwizard-demo)

For a curl-based walkthrough of both protocols see the [Quick Start in the README](../README.md#try-it-with-curl).
