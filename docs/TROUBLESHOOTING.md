# Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `404` for every request, or `process()` does nothing | `withUploadUri(...)` doesn't match the path the request arrives on (context path, proxy rewrite). | Make the URI match the servlet path, or use an absolute URI behind a proxy. |
| `PATCH` returns `405` / never reaches the library | Servlet overrides `doPost`/`doGet` only, or controller mapping lacks `PATCH`. | Override `service(...)` or map all of `POST, PUT, PATCH, HEAD, DELETE, OPTIONS, GET`. |
| Browser blocked by CORS | A second CORS filter conflicts, or `cors` extension disabled. | Rely on the built-in `cors` extension, or disable it (`disableTusExtension("cors")`) and configure CORS yourself. Preflight `OPTIONS` must reach `process()` unauthenticated. |
| `406 Not Acceptable` | Unexpected `Content-Type`. | Tus `PATCH` needs `application/offset+octet-stream`; RUFH needs `application/partial-upload`. Parameters such as `;charset=UTF-8` are tolerated. |
| `409 Conflict` on `PATCH` | Client `Upload-Offset` differs from the server offset (e.g. a retry after a lost response). | The client should send `HEAD`, read the current offset and resume from there. RUFH returns `application/problem+json` with `expected-offset` / `provided-offset`. |
| `413 Payload Too Large` when creating | `Upload-Length` exceeds `withMaxUploadSize(...)`. | Raise the limit or reduce the file size. |
| `423 Locked` / slow `HEAD` after pause | Another request still holds the upload lock (a stalled `PATCH`). | The library asks the lock holder to release and waits up to `withLockWaitTimeout(...)` (default 60 s). Increase the timeout if you have very slow storage. See [LOCKING.md](LOCKING.md). |
| Locks never expire across replicas | Clock skew between nodes, or storage not shared. | Fix NTP; ensure all replicas use the same backend. |
| `HTTP 500` / error in Undertow when an error is sent | Fixed in 2.0.0 (`Content-Length` conflict). | Upgrade. |
| `NoClassDefFoundError` for Jackson | Old version without bundled Jackson. | Upgrade to 2.0.0 (Jackson is bundled). |
| `ClassNotFoundException: javax.servlet...` | 2.x requires `jakarta.servlet`. | Use Tomcat 10+/Jetty 11+/Spring Boot 3+. See [UPGRADING.md](UPGRADING.md). |
| Uploaded file can't be deleted/read: "locked" | A `getUploadedBytes(...)` stream wasn't closed. | Use try-with-resources. |
| Uploads accumulate on disk or in the bucket | `cleanup()` not scheduled or no expiration period. | Set `withUploadExpirationPeriod(...)` and run `cleanup()` periodically. |
| Upload restarts from 0 behind a proxy | Proxy buffers or times out the request body. | See [DEPLOYMENT.md](DEPLOYMENT.md#3-reverse-proxies--load-balancers). |

Backend-specific problems: [S3 troubleshooting](S3_STORAGE.md#10-troubleshooting-guide) and [Azure troubleshooting](AZURE_BLOB_STORAGE.md#8-troubleshooting-guide).

Still stuck? Run the [conformity tests](CONFORMITY_TESTING.md) against your endpoint or [open an issue](https://github.com/tomdesair/tus-java-server/issues) with the request/response headers.
