# Upload Locking & Lock Contention Resolution

This document describes how the `tus-java-server` library prevents concurrent modifications to uploads using locks, and how it resolves lock contention when clients resume interrupted uploads.

---

## 1. Why Locking is Needed

In the `tus` protocol, client uploads can be resumed after network interruptions. Multiple concurrent requests targeting the same upload resource must be prevented to avoid data corruption (e.g. out-of-order writes or overlapping file offsets).

### Stalled uploads and resume handling
1. When a client performs an upload via a `PATCH` request, the server acquires an exclusive lock on that upload.
2. If the client's network connection drops, the `PATCH` request connection might remain in a "half-open" state on the server (stalled socket read).
3. The client, recognizing the disconnect, attempts to resume by sending a `HEAD` request to query the current offset.
4. However, the stalled `PATCH` request is still running on the server and holding the lock, preventing the client from resuming.

To solve this, we need a mechanism where a new `HEAD` request can trigger the release of the lock held by the stalled request, allowing immediate resumability.

---

## 2. High-Level Interface (`UploadLockingService`)

The locking behaviour is defined by the `UploadLockingService` interface. To support backwards compatibility and lock contention resolution, the interface exposes the following high-level API:

```java
public interface UploadLockingService {

  // Acquires a lock on an upload resource
  UploadLock lockUploadByUri(String requestUri) throws TusException, IOException;

  // Checks if an upload is currently locked
  boolean isLocked(UploadId id);

  // Cleans up stale locks left on disk
  void cleanupStaleLocks() throws IOException;

  // Registers the input stream of an active request so it can be interrupted later
  default void registerInputStream(String requestUri, InputStream inputStream) {}

  // Requests that any active lock for the URI be released
  default void requestLockRelease(String requestUri) {}
}
```

- **Backward Compatibility**: Both `registerInputStream` and `requestLockRelease` are `default` (no-op) methods, ensuring that third-party custom implementations of `UploadLockingService` (e.g. S3, Redis, or Database backends) do not break.
- **Request Flow**:
  - When a `PATCH` request stream is created, its input stream is wrapped in an `InterruptibleInputStream` and registered via `registerInputStream`.
  - When a `HEAD` request encounters a lock conflict, it invokes `requestLockRelease`, which triggers the watchdog and/or local interruption.

---

## 3. File System Based Implementation (`DiskLockingService`)

### 3.1. General Overview

`DiskLockingService` is the default locking service. It implements locking using Java NIO `FileChannel` and exclusive `FileLock` objects.

- **Lock Files**: For an upload ID `<id>`, it attempts to acquire an exclusive lock on the file `locks/<id>` in the storage directory.
- **Cross-Replica / Multi-Process Signaling**:
  - In a clustered or multi-container setup (e.g. Kubernetes with a shared Persistent Volume Claim (PVC)), different server instances may handle different requests.
  - When `requestLockRelease` is called, it:
    1. Interrupts the local stream if the lock is held in the same JVM.
    2. Writes an empty `.stop` file named `locks/<id>.stop` in the shared locks directory.
  - The JVM instance that currently holds the file lock detects this `.stop` file and terminates its request.

### 3.2. The Watchdog Process

The watchdog is a background daemon thread managed entirely inside `DiskLockingService`.

### Role & Lifecycle
- **Triggered**: Spawns automatically when a request registers its stream in the JVM-local `activeLocks` registry.
- **Polling Loop**: Every 1 second, it scans all active locks. If a `.stop` file exists for a given upload ID, it invokes `stream.interrupt()`, which immediately terminates the stalled connection.
- **Self-Termination**: To conserve resources, the watchdog thread terminates naturally when there are no more active locks in the registry. It will spawn a new thread if a new upload request starts.
- **Safety**:
  - Runs with `Thread.MIN_PRIORITY` to avoid stealing CPU cycles from request handling threads.
  - Set as a daemon thread (`setDaemon(true)`) so it does not block application/JVM shutdown.
  - Uses `WeakReference<InterruptibleInputStream>` for active locks to prevent memory leaks if a request thread terminates unexpectedly without cleaning up its lock.
  - Catches `Throwable` inside the loop to ensure unexpected errors do not crash the daemon silently.
