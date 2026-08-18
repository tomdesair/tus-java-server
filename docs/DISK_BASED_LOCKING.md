# Disk & Shared Network Storage Locking (`LeaseFileLockingService`)

This document provides architectural details, distributed concurrency mechanics, network mount configurations, and migration/opt-out instructions for filesystem-backed locking in `tus-java-server`.

---

## 1. Overview & Why `LeaseFileLockingService`

Starting in version **2.0.0**, `LeaseFileLockingService` is the **default** locking implementation instantiated by `TusFileUploadService.withStoragePath(String)`.

### The Problem with POSIX `FileLock` on Network Storage
The legacy `DiskLockingService` relies on OS kernel-level file locks (`java.nio.channels.FileLock` via POSIX `fcntl` on Linux and `LockFileEx` on Windows). While reliable on local disks, kernel file locks frequently fail on shared network storage (NFS, AWS EFS, Azure Files, SMB/CIFS):

- **Unprivileged Containers & Kubernetes**: Pods running in isolated network namespaces lack `rpc.statd` / NLM daemons, causing `FileChannel.tryLock()` to fail with `IOException: No locks available` (`ENOLCK`).
- **`nolock` Mounts**: Shares mounted with the `nolock` performance option (e.g. AWS EFS defaults) ignore or reject POSIX file locks.
- **Ungraceful Crashes**: Pod crashes (`kill -9`, OOM killer, node eviction) leave locks stuck in NFS server state for minutes or indefinitely.
- **Cross-Pod Coordination**: Kernel file locks are tracked in OS memory and do not coordinate cleanly across multi-replica container clusters.

### The Solution: Application-Level Lease Directories
`LeaseFileLockingService` replaces OS kernel locks with atomic directory creation (`mkdir`) and JSON lease files with background heartbeat renewal. This provides **zero external dependencies** (no Redis, ZooKeeper, or etcd cluster required) and works seamlessly on both local disks and distributed network shares.

---

## 2. Lock Directory Layout & Mechanics

Locks are structured as a dedicated directory containing a JSON lease metadata file:

```
<storagePath>/locks/
├── <UploadId>.lock/                      # Dedicated lock directory (Atomic existence primitive)
│   └── lease.json                        # JSON lease metadata (holderId, expiresAt, acquiredAt)
└── <UploadId>.stop                       # Empty signal file for lock contention interruption
```

### Example `lease.json`:
```json
{
  "holderId": "pod-tus-backend-7d9c6-thread-14",
  "requestUri": "/files/upload/018f3b2a-7140-7e1d-8f92-5cb640d28362",
  "storagePath": "/mnt/uploads/locks/018f3b2a-7140-7e1d-8f92-5cb640d28362.lock",
  "leaseDurationMs": 30000,
  "expiresAt": 1723401234567,
  "acquiredAt": 1723401204567
}
```

### Architectural Rationale:
1. **Universal Atomic Directory Staging & Renames**: Rather than creating an empty directory directly at `<UploadId>.lock` and subsequently writing metadata into it (which creates a window where concurrent nodes observe an empty, uninitialized directory), new locks are staged in a temporary sibling directory (`<UploadId>.lock.stage.<uuid>`) with `lease.json` pre-written, then moved atomically into place (`Files.move` with `StandardCopyOption.ATOMIC_MOVE`). Directory moves map directly to atomic server-side RPCs on both POSIX NFS (`rename(2)`) and Windows SMB (`SetFileInformationByHandle`), ensuring `<UploadId>.lock` is born on disk 100% complete and valid.
2. **Clean Encapsulation**: Placing `lease.json` inside `<UploadId>.lock/` prevents metadata clutter and guarantees that lease updates and watchdog renewals are scoped directly to the lock entity.
3. **Atomic Eviction & Move Isolation**: Stale lock cleanup isolates the target directory by atomically renaming it (`StandardCopyOption.ATOMIC_MOVE` to `.evicting.<uuid>`) before inspecting and deleting its contents. This isolates expired state and allows post-move rollback verification, preventing race collisions between multiple recovering nodes.

---

## 3. Distributed Concurrency & Contention Resolution

### 1. Lock Acquisition Flow (Atomic Directory Staging)
To ensure that an observing process never encounters an empty or partially written lock directory, lock creation uses **Atomic Directory Staging**:
1. Extract `UploadId` from the request URI.
2. Verify that `<storagePath>/locks/<UploadId>.lock` does not already exist. If it exists:
   - If `lease.json` is unexpired: Lock is actively held on another replica $\rightarrow$ throw `UploadAlreadyLockedException`.
   - If `lease.json` is expired: Holder crashed $\rightarrow$ proceed to **Safe Atomic Eviction** and retry acquisition.
3. Create a unique temporary staging directory: `<storagePath>/locks/<UploadId>.lock.stage.<uuid>`.
4. Write the complete `lease.json` file inside the staging directory.
5. Execute `Files.move(stageDir, lockDirPath, StandardCopyOption.ATOMIC_MOVE)`.
   - **Success**: The lock directory appears on disk atomically with a valid, fully populated `lease.json` already inside it. Start the background heartbeat daemon (renews every $\text{leaseDuration} / 3$) and return `LeaseFileUploadLock`.
   - **Collision (Already Exists)**: `Files.move` fails because another node acquired the lock in the interim. Clean up `stageDir` and throw `UploadAlreadyLockedException`.

### 2. TOCTOU Mitigation in Expired Lock Eviction (Post-Move Verification & Rollback)
When multiple cluster nodes concurrently discover an expired lock left behind by a crashed pod, a **Time-of-Check to Time-of-Use (TOCTOU)** race condition can arise:
1. **Time of Check (TOC)**: Node A and Node B both inspect `<UploadId>.lock` and observe that its lease has expired.
2. **Node A Wins**: Node A renames the expired directory to `.evicting.<uuid-a>`, deletes it, and stages/moves a brand-new active lock.
3. **Time of Use (TOU) Hazard**: Node B (having verified expiration in Step 1) executes eviction on Node A's **active** directory. Without post-move verification, Node B destroys Node A's directory and acquires a second lock handle, causing dual ownership.

**The Solution: Post-Move Verification & Rollback**:
- When Node B isolates the directory via `Files.move(lockDirPath, evictPath, ATOMIC_MOVE)`, it immediately re-inspects `evictPath` post-move.
- If `evictPath` contains an active lease (created by Node A right before Node B's move), Node B recognizes that it lost the race.
- Node B immediately rolls back the move via `Files.move(evictPath, lockDirPath, ATOMIC_MOVE)` and aborts eviction.
- Exactly one node wins the eviction and acquisition, preserving single-owner lock exclusivity.

### 3. Heartbeat Lease Auto-Renewal
Active streaming uploads periodically renew their lease by updating `expiresAt` in `lease.json` every $\text{leaseDuration} / 3$ (default: every 10 seconds for a 30s lease). When the request completes, `lock.close()` stops the daemon and removes the lock directory.

### 4. Lock Contention & `.stop` Signal Files
When a client sends a `HEAD` or `DELETE` request to resume or cancel an upload while a stalled `PATCH` stream holds the lock:
1. The resuming server catches `UploadAlreadyLockedException` and calls `requestLockRelease(requestUri)`.
2. It interrupts any JVM-local stream and writes `<storagePath>/locks/<UploadId>.stop`.
3. A background watchdog thread on the holding replica (polling every 1.5 seconds) detects `.stop` and calls `stream.interrupt()`.
4. The stalled `PATCH` stream aborts and releases its lock. The server's 8.0-second retry budget ($40 \times 200\text{ms}$) allows the `HEAD` or `DELETE` request to acquire the lock and succeed seamlessly.

---

## 4. Production Network Mount Configuration Guide

### Linux / NFSv4 Mount Recommendations
For multi-replica deployments connecting to shared NFS storage (e.g. AWS EFS, Azure NetApp Files, on-premise NFS):

```bash
mount -t nfs4 -o rw,hard,intr,rsize=1048576,wsize=1048576,actimeo=3 nfs-server:/data/uploads /mnt/tus-uploads
```

- `hard,intr`: Prevents silent I/O failure on transient network disconnects and allows graceful thread interruption.
- `actimeo=3`: Attribute caching timeout of 3s ensures `.stop` signal files and lease renewals propagate rapidly across pods.
- `rsize=1048576,wsize=1048576`: 1MB I/O buffers for maximum streaming append throughput.

### Windows / SMB 3.x Configuration
For Windows Server SMB shares or Azure Files SMB:

```powershell
New-SmbMapping -RemotePath "\\smb-server\uploads" -LocalPath "Z:" -Persistent $True
```

---

## 5. Opt-Out & Backward Compatibility Guide

If you are running single-node deployments on local disk and specifically require legacy OS-level `FileLock` (`fcntl` / `LockFileEx`), you can easily opt out of `LeaseFileLockingService` and restore `DiskLockingService`.

### 1. Programmatic Java Configuration (Opt-Out):
```java
String storagePath = "/var/data/tus-uploads";

TusFileUploadService tus = new TusFileUploadService()
    .withUploadStorageService(new DiskStorageService(storagePath))
    .withUploadLockingService(new DiskLockingService(storagePath)); // Opt-out to legacy FileLock
```

### 2. Spring Boot Bean Configuration (Opt-Out):
```java
@Configuration
public class TusConfig {

    @Bean
    public TusFileUploadService tusFileUploadService(@Value("${tus.storage.path}") String storagePath) {
        return new TusFileUploadService()
            .withUploadUri("/files/upload")
            .withUploadStorageService(new DiskStorageService(storagePath))
            .withUploadLockingService(new DiskLockingService(storagePath)); // Opt-out to legacy FileLock
    }
}
```
