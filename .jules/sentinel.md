## 2026-06-23 - Path Traversal Vulnerability in Disk Storage

**Vulnerability:** The application allowed path traversal in the disk storage component when resolving upload IDs. Specifically, `storagePath.resolve(id.toString())` was used without checking if the resulting path was still within the intended storage directory.

**Learning:** When resolving paths using user-controlled input (like an upload ID), it is insufficient to simply rely on `Path.resolve`. If the input contains `../` or absolute paths, it could escape the intended boundaries.

**Prevention:** Always normalize the resolved path and explicitly check that it starts with the normalized base directory using `uploadPath.normalize().startsWith(storagePath.normalize())`. Throw an exception if the check fails.
