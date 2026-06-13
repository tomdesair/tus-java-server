# Manual Testing: Upload Lock Contention Resolution

This guide outlines how to manually verify that the upload lock contention resolution works as expected using `curl` and a throttling utility like `pv` (Pipe Viewer).

---

## Prerequisites

Ensure you have the following tools installed:
- **curl**: Standard HTTP client.
- **pv** (Pipe Viewer): Throttling tool used to simulate a slow or stalled upload.
  - *macOS*: `brew install pv`
  - *Debian/Ubuntu*: `sudo apt-get install pv`

You also need a running instance of your application using `tus-java-server` configured with the file locking service. We will assume the server is running locally on `http://localhost:8080/files`.

---

## Step-by-Step Test Procedure

### Step 1: Create a New Upload Resource
Initiate a new upload to the server:
```bash
curl -X POST \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Length: 10000000" \
  -I http://localhost:8080/files
```
Look for the `Location` header in the response and record the upload resource URL (e.g., `http://localhost:8080/files/000003f1-a850-49de-af03-997272d834c9`). We will refer to this as `<UPLOAD_URL>`.

---

### Step 2: Start a Throttled PATCH Request
To simulate an active, ongoing upload that is extremely slow (or stalled), run a `PATCH` request using `/dev/zero` throttled to `50 KB/s` via `pv`:
```bash
dd if=/dev/zero bs=1024 count=10000 | pv -L 50k | curl -X PATCH -T - \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" \
  <UPLOAD_URL>
```
Keep this request running in your first terminal.

---

### Step 3: Send a HEAD Request from a Separate Terminal
While the upload in Step 2 is actively running and holding the file lock, open a **second terminal window** and send a `HEAD` request to query the offset of the same upload resource:
```bash
curl -X HEAD -H "Tus-Resumable: 1.0.0" -I <UPLOAD_URL>
```

---

## Expected Results

- **First Terminal (PATCH)**:
  - The stalled/throttled `PATCH` upload should immediately abort with an I/O error or exit because the server terminated its input stream in response to the lock release request.
- **Second Terminal (HEAD)**:
  - The `HEAD` request should succeed with status `204 No Content` after a short delay (the server retries up to 25 times at 200ms intervals while releasing the lock).
  - The response will contain the `Upload-Offset` header reflecting the number of bytes successfully written to disk before the stream was interrupted (e.g., `Upload-Offset: 153600`).
- **Subsequent Uploads**:
  - You can immediately resume the upload using a new `PATCH` request starting from the offset returned in the `HEAD` response.
