## 2024-05-18 - Prevent Path Traversal in AbstractDiskBasedService
**Vulnerability:** A Path Traversal vulnerability existed in `AbstractDiskBasedService`'s `getPathInStorageDirectory` method because it blindly resolved the `UploadId`'s value against the base storage directory.
**Learning:** Even though `UploadId` may attempt to generate safe IDs, user-provided `UploadId`s or corrupted state might cause paths like `../../../etc/passwd` to be resolved, breaking out of the designated directory.
**Prevention:** Always normalize and verify that file paths derived from user input or generic variables remain within their intended boundary directory by using `path.normalize().toAbsolutePath().startsWith(basePath.normalize().toAbsolutePath())`.
