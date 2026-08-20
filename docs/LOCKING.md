# Upload Locking & Lock Contention Resolution

This document describes why locking is necessary in the `tus-java-server` library, how the core `UploadLockingService` interface is structured, how lock contention resolution works across replicas, and where to find detailed documentation for each concrete locking mechanism implementation.

---

## 1. Why Locking is Needed

In the `tus` protocol (and IETF Resumable Uploads for HTTP specification), client uploads can be interrupted and resumed across multiple HTTP requests. Multiple concurrent requests targeting the same upload resource must be strictly prevented to avoid data corruption (such as out-of-order byte writes or overlapping file offsets).

### Stalled Uploads & Lock Contention Handling

1. **Active Streaming**: When a client sends upload bytes via a `PATCH` (or RUFH `POST`/`PATCH`) request, the server acquires an exclusive lock on that upload.
2. **Network Interruption**: If the client's network drops, the original `PATCH` connection may remain open on the server in a "half-open" state (a stalled socket read waiting for client bytes).
3. **Resume Attempt**: The client, recognizing the disconnect, attempts to resume by sending a `HEAD` request to query the current offset (or a `DELETE` request to terminate the upload).
4. **Lock Conflict**: The stalled `PATCH` request is still running on the server and holding the lock, which would block the client's `HEAD` or `DELETE` request indefinitely if not resolved.

To solve this, `tus-java-server` includes a **lock contention resolution mechanism** where an incoming `HEAD` or `DELETE` request signals the server to interrupt the stalled `PATCH` byte stream cleanly, releasing the lock for immediate resumption.

---

## 2. Core Locking Interfaces

All locking mechanisms in `tus-java-server` implement the `UploadLockingService` interface and return handles implementing `UploadLock`.

### `UploadLockingService` Interface

```java
public interface UploadLockingService {

  // Acquires an exclusive lock on an upload resource
  UploadLock lockUploadByUri(String requestUri) throws TusException, IOException;

  // Checks if an upload is currently locked
  boolean isLocked(UploadId id);

  // Cleans up stale or expired locks
  void cleanupStaleLocks() throws IOException;

  // Injects the UploadIdFactory instance used to parse upload IDs from request URIs
  void setIdFactory(UploadIdFactory idFactory);

  // Registers the active request input stream so it can be interrupted cleanly
  default void registerInputStream(String requestUri, InputStream inputStream) {}

  // Requests that any active lock for the URI be released
  default void requestLockRelease(String requestUri) {}

  // Closes resources, interrupts in-flight streams, and shuts down background daemon threads
  default void close() throws IOException {}
}
```

### `UploadLock` Interface

```java
public interface UploadLock extends Closeable {

  // Gets the request URI associated with this lock
  String getUploadUri();

  // Explicitly releases the lock
  default void release() {
    try {
      close();
    } catch (IOException ignored) {}
  }
}
```

### Request Flow & Contention Resolution

- **Stream Registration**: When a request starts streaming payload bytes, its input stream is wrapped in an `InterruptibleInputStream` and registered via `lockingService.registerInputStream(requestUri, inputStream)`.
- **Release Request**: When a concurrent `HEAD` or `DELETE` request encounters an active lock, `TusFileUploadService` catches `UploadAlreadyLockedException` and calls `lockingService.requestLockRelease(requestUri)`.
- **Stream Interruption**:
  - If the lock is held in the **same JVM**, `requestLockRelease` interrupts the local stream directly.
  - If the lock is held on a **remote replica/pod**, `requestLockRelease` writes a `.stop` signal object or file. A background watchdog thread running on the lock-holding replica detects the `.stop` signal and calls `stream.interrupt()`, causing the stalled `PATCH` stream to abort and release its lock.

---

## 3. Concrete Locking Mechanisms & Storage Providers

`tus-java-server` provides several built-in locking implementations tailored for different deployment topologies and storage backends. Refer to the dedicated documentation files below for full details:

| Storage Backend / Environment | Locking Service Class | Key Characteristics & Architecture | Documentation File |
|---|---|---|---|
| **Disk & Network Filesystems (Default)** | `LeaseFileLockingService` | Atomic directory staging & renames (`mkdir`/`rename`), TTL-based JSON lease files with heartbeat renewal, TOCTOU-safe eviction with rollback, and `.stop` signal files. Fully safe on NFSv3/v4, AWS EFS, SMB/CIFS, Kubernetes containers, and local disks. | [`docs/DISK_BASED_LOCKING.md`](file:///Users/tom/projects/tus-java-server/docs/DISK_BASED_LOCKING.md) |
| **Local File System (Legacy Opt-Out)** | `DiskLockingService` | OS kernel-level exclusive POSIX `FileLock` (`fcntl`) with JVM shutdown hooks and `.stop` signal files. Best for single-node deployments on local disk. | [`docs/DISK_BASED_LOCKING.md`](file:///Users/tom/projects/tus-java-server/docs/DISK_BASED_LOCKING.md) |
| **Amazon S3 / S3-Compatible** | `S3LockingService` | S3 object-backed TTL lease objects (`.lock`), conditional writes (`If-None-Match: *`), jittered read-after-write verification, heartbeat renewal, and cross-pod `.stop` signal object polling watchdog. | [`docs/S3_STORAGE.md`](file:///Users/tom/projects/tus-java-server/docs/S3_STORAGE.md) |
| **Azure Blob Storage** | `AzureBlobLockingService` | Native Azure Blob Storage exclusive 30-second leases (`BlobLeaseClient`), background daemon renewal, and `.stop` signal blob polling watchdog. | [`docs/AZURE_BLOB_STORAGE.md`](file:///Users/tom/projects/tus-java-server/docs/AZURE_BLOB_STORAGE.md) |

---

## 4. Implementing a Custom `UploadLockingService`

Developers extending `tus-java-server` with custom lock providers (such as Redis, ZooKeeper, etcd, or Hazelcast) must implement `UploadLockingService` and `UploadLock`:

1. **Implement `lockUploadByUri`**: Acquire an exclusive lock or throw `UploadAlreadyLockedException` if currently locked by another request.
2. **Implement `registerInputStream` & `requestLockRelease`**: Maintain a registry of active `InterruptibleInputStream` instances. When `requestLockRelease` is invoked, interrupt the active stream to support instant client resumes.
3. **Use Common Watchdog Helpers**: Use `Utils.scheduleWatchdog(...)` and `Utils.shutdownExecutor(...)` in `me.desair.tus.server.util.Utils` for any background daemon threads or heartbeat lease renewals.
4. **Implement `Closeable`**: Register a JVM shutdown hook upon construction and deregister it on `close()` for idempotent resource cleanup.
5. **Guard Against TOCTOU Races**: When building distributed lock services with TTL expiration, ensure acquisition and eviction use atomic operations (e.g., conditional writes, CAS, atomic directory renames, or post-action verification) to prevent race conditions where multiple nodes concurrently evict an expired lock and simultaneously acquire ownership.
