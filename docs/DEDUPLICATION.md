# File Checksum Deduplication & Lifecycle Management

This document describes the architecture, lifecycle management, and migration options for checksum-based upload deduplication in `tus-java-server`.

---

## 1. Architecture Overview

When upload deduplication is enabled (`withUploadDeduplication(true)`), `tus-java-server` detects identical files across uploads using cryptographic checksums (e.g., SHA-1, SHA-256) supplied via Tus 1.0.0 `Upload-Checksum` or IETF RUFH `Content-Digest` / `Repr-Digest` headers.

### 1.1 Parent and Child Relationships

- **Parent Upload ($P$)**: The first upload completed for a given checksum value. It stores the physical data payload on disk, S3, or Azure Blob storage.
- **Child Upload ($C$)**: Subsequent uploads completing with the identical checksum. The child upload's physical data payload is deleted to save storage, and its metadata (`duplicatesUploadId`) is linked to the parent upload ID.
- **Transparent Reads**: When reading or downloading child uploads (`getUploadedBytes`, `copyUploadTo`), the server dynamically streams the payload from the parent upload.
- **Isolated Writes**: Write operations (`append`, `removeLastNumberOfBytes`) never resolve duplicates recursively, guaranteeing that parent files cannot be mutated by child errors or retries.

---

## 2. Storage Layout & Reference Tracking

To safely track parent/child lifecycles and ensure thread-safe and cluster-safe cleanup without race conditions, `tus-java-server` uses the following layout:

```text
checksums/<algorithm>/<hexChecksum>/parent          # Contains the parent UploadId
checksums/<algorithm>/<hexChecksum>/parent-deleted  # Tombstone marker (present only when parent is deleted)
checksums/<algorithm>/<hexChecksum>/children/<id>   # Marker file/blob per active child upload
```

- `<hexChecksum>`: Canonical lowercase hexadecimal checksum normalized via `ChecksumKey` (protects against path traversal, slashes, or illegal characters across Linux, Windows, S3, and Azure Blob storage).
- `parent`: Created atomically when the first upload with this checksum completes.
- `parent-deleted`: Created if the parent upload is deleted via HTTP `DELETE` or `deleteUpload` while child uploads are still active.
- `children/<childId>`: Created when a child links to the parent; removed when the child is deleted.

---

## 3. Lifecycle & Cleanup Workflows

### 3.1 Parent Upload Deletion

When a parent upload is deleted:
1. **If active children exist** (`hasChildren(key) == true`):
   - A `parent-deleted` tombstone marker is created alongside the existing `parent` pointer file.
   - Parent metadata is deleted, but **parent physical payload bytes and the `parent` pointer are preserved** so children continue downloading without disruption.
   - **Tombstoned Parent Joining**: Any new duplicate upload arriving while a parent is tombstoned and still has active children will join the tombstoned parent and share its retained bytes until all children are deleted.
2. **If no children exist** (`hasChildren(key) == false`):
   - The parent physical data bytes are deleted.
   - The entire `checksums/<algorithm>/<hexChecksum>` directory/prefix is removed.

### 3.2 Child Upload Deletion

When a child upload is deleted:
1. The child marker `children/<childId>` and child metadata are deleted.
2. **Last-Child Purge**: If the parent was previously deleted (`isParentDeleted(key) == true`) and no other children remain, the server acquires the parent lock and purges the parent data payload and the checksum directory.

### 3.3 Self-Healing on Missing Parent Data

If an external lifecycle policy or disk cleanup deletes a parent payload out-of-band:
- Upon the next lookup or deduplication attempt, `DeduplicationService` detects that the parent physical data is missing.
- It deletes the stale checksum directory/prefix immediately and allows the incoming upload to become the new parent, preventing data loss.

---

## 4. Upgrading & Migration Guide (from version 1.x)

In versions prior to 2.0.0, completed uploads were indexed using a flat file structure:
`<storagePath>/checksums/<algorithm>/<checksum>`

Version 2.0.0 introduces directory-based reference tracking (`<hexChecksum>/parent`). When upgrading to 2.0.0 with deduplication enabled, choose one of the following two migration options:

### Option 1: Clear the Existing Checksum Index (Recommended)

Because the checksum index is an optimization for deduplication and does not store actual file payloads, the simplest approach is to delete the `checksums/` directory or object prefix before starting the upgraded server:

- **Local Disk**: `rm -rf <storagePath>/checksums`
- **S3 Object Storage**: Remove all objects under the `checksums/` prefix (e.g. `aws s3 rm s3://<bucket>/checksums/ --recursive`)
- **Azure Blob Storage**: Delete blobs with prefix `checksums/`

New completed uploads will automatically populate the new layout. Existing completed uploads will remain accessible, and new identical files will simply register a fresh parent.

### Option 2: Run a Manual Migration Script

If you wish to retain existing parent deduplication references for pre-existing completed uploads, run a migration script before starting version 2.0.0:

#### Local Disk Migration Script (Bash)

```bash
#!/usr/bin/env bash
set -euo pipefail

STORAGE_DIR="${1:-/tmp/tus}"
CHECKSUMS_DIR="${STORAGE_DIR}/checksums"

if [ ! -d "${CHECKSUMS_DIR}" ]; then
  echo "No checksums directory found at ${CHECKSUMS_DIR}. Nothing to migrate."
  exit 0
fi

echo "Migrating checksum files in ${CHECKSUMS_DIR}..."

for alg_dir in "${CHECKSUMS_DIR}"/*; do
  if [ ! -d "${alg_dir}" ]; then
    continue
  fi
  algorithm=$(basename "${alg_dir}")

  for checksum_file in "${alg_dir}"/*; do
    if [ ! -f "${checksum_file}" ]; then
      continue
    fi

    checksum_name=$(basename "${checksum_file}")
    parent_id=$(cat "${checksum_file}" | tr -d '[:space:]')

    # Create target directory: <checksums>/<algorithm>/<checksum_name>/
    target_dir="${alg_dir}/${checksum_name}_dir"
    mkdir -p "${target_dir}"

    # Write parent file with upload ID
    echo -n "${parent_id}" > "${target_dir}/parent"

    # Replace old flat file with the new directory
    rm -f "${checksum_file}"
    mv "${target_dir}" "${alg_dir}/${checksum_name}"

    echo "Migrated ${algorithm}/${checksum_name} -> parent: ${parent_id}"
  done
done

echo "Checksum migration completed successfully."
```

#### S3 Object Storage Migration

For S3, move each object from `checksums/<algorithm>/<checksum>` to `checksums/<algorithm>/<checksum>/parent` retaining the `UploadId` content.
