# Deployment Guide

Checklist for running `tus-java-server` in production: choosing a backend, running several replicas and configuring proxies.

## 1. Choose a Backend

| Situation | Recommended setup |
|---|---|
| Single application instance, local disk | Defaults: `withStoragePath(...)` (`DiskStorageService` + `LeaseFileLockingService`). Optionally the legacy `DiskLockingService`, see [DISK_BASED_LOCKING.md](DISK_BASED_LOCKING.md#5-opt-out--backward-compatibility-guide). |
| Several replicas sharing a volume (NFS, EFS, Azure Files, SMB, Kubernetes `ReadWriteMany`) | Defaults; read the mount recommendations in [DISK_BASED_LOCKING.md](DISK_BASED_LOCKING.md#4-production-network-mount-configuration-guide). |
| Several replicas, no shared volume, AWS / S3-compatible | `S3StorageService` + `S3LockingService`, see [S3_STORAGE.md](S3_STORAGE.md). |
| Several replicas on Azure | `AzureBlobStorageService` + `AzureBlobLockingService`, see [AZURE_BLOB_STORAGE.md](AZURE_BLOB_STORAGE.md). |

All built-in backends are lease-based and need **no Redis or database**. Custom lock providers can be added, see [LOCKING.md](LOCKING.md#4-implementing-a-custom-uploadlockingservice).

## 2. Multi-Replica Checklist

- Every replica must use the **same** storage and locking backend and `withUploadUri(...)`.
- Keep clocks synchronised with NTP (lease expiry depends on it). See the clock notes in [DISK_BASED_LOCKING.md](DISK_BASED_LOCKING.md#35-clock-synchronization--ntp-requirement) and [S3_STORAGE.md](S3_STORAGE.md#3-clock-synchronization--ntp-requirement).
- Requests for one upload may land on different replicas: contention between a stalled `PATCH` and a resuming `HEAD`/`DELETE` is resolved across replicas automatically, see [LOCKING.md](LOCKING.md).
- Run `cleanup()` on a schedule (on one or all replicas, it is safe to run concurrently).
- Call `TusFileUploadService.close()` on shutdown so locks are released.
- Cloud backends: configure bucket / container lifecycle rules for abandoned objects (see [S3 lifecycle rules](S3_STORAGE.md#8-operational-hardening-s3-lifecycle-cleanup-rules) and [Azure lifecycle rules](AZURE_BLOB_STORAGE.md#6-operational-hardening-azure-blob-lifecycle-management-rules)).

## 3. Reverse Proxies & Load Balancers

Ensure that reverse proxies (Nginx, HAProxy, AWS ALB, Cloudflare):
1. Forward the protocol headers: Tus (`Tus-Resumable`, `Upload-Offset`, `Upload-Length`, `Upload-Metadata`, `Upload-Checksum`, `Upload-Defer-Length`, `Upload-Concat`) and RUFH (`Upload-Offset`, `Upload-Complete`, `Upload-Length`, `Upload-Limit`, `Upload-Draft`, `Content-Digest`, `Repr-Digest`).
2. Do not strip or alter `Content-Type: application/partial-upload` or `application/offset+octet-stream`.
3. Allow the HTTP methods `PATCH`, `HEAD` and `DELETE`.
4. Do not buffer whole request bodies and allow large bodies and long read timeouts. For Nginx, for example:

```nginx
location /api/upload {
    proxy_pass http://app;
    proxy_request_buffering off;   # stream chunks straight to the app
    client_max_body_size 0;        # size limits are enforced by withMaxUploadSize(...)
    proxy_read_timeout 300s;
    proxy_http_version 1.1;
}
```

5. If the proxy changes the public host or path, set an **absolute** `withUploadUri("https://upload.example.com/api/upload")` so that `Location` headers contain the public URL.

## 4. Servlet Container Notes

- Containers that do not decode `Transfer-Encoding: chunked` natively need `withChunkedTransferDecoding(true)`.
- HTTP 104 interim responses (RUFH) need container-specific support, see [INTERIM_RESPONSES.md](INTERIM_RESPONSES.md).
- Use `jakarta.servlet` containers (Tomcat 10+, Jetty 11+, Spring Boot 3+). The library no longer supports `javax.servlet`.
