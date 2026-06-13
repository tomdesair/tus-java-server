# Manual Testing

## Upload Lock Contention Resolution

This guide outlines how to manually verify that the upload lock contention resolution works as expected using the Spring Boot demo server, `curl`, and `pv` (Pipe Viewer).

---

## Setup & Running the Server

Before performing the manual verification, you need to compile the library and start the Spring Boot demo server.

### 1. Build and install the `tus-java-server` library:
In the root directory of the `tus-java-server` project, run:
```bash
mvn clean install
```

### 2. Build and start the Spring Boot rest demo server:
Navigate to the sibling `tus-java-server-spring-demo` project and build/run the server:
```bash
cd ../tus-java-server-spring-demo
mvn clean package
java -jar spring-boot-rest/target/spring-boot-rest-0.0.1-SNAPSHOT.jar
```
*Note:* The demo server runs locally on port `8080` and exposes the upload endpoint at `http://localhost:8080/api/upload`.

---

## Prerequisites for Client

Ensure you have the following tools installed on your client testing machine:
- **curl**: Standard HTTP client.
- **pv** (Pipe Viewer): Throttling tool used to simulate a slow or stalled upload.
  - *macOS*: `brew install pv`
  - *Debian/Ubuntu*: `sudo apt-get install pv`

---

## Step-by-Step Test Procedure

### Step 1: Create a New Upload Resource
Initiate a new upload to the server:
```bash
curl -X POST \
  -H "Tus-Resumable: 1.0.0" \
  -H "Upload-Length: 10000000" \
  -I http://localhost:8080/api/upload
```
Look for the `Location` header in the response and record the upload resource URL (e.g., `http://localhost:8080/api/upload/000003f1-a850-49de-af03-997272d834c9`). We will refer to this as `<UPLOAD_URL>`.

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

---

## Cleanup

The Spring Boot demo application writes upload metadata and file chunks to your system's temporary directory under a folder named `tus`.
To clean up any files and folders created during testing, delete the temp directory:

- **On macOS/Linux**:
  ```bash
  rm -rf /tmp/tus
  ```
  *(or check your system's `$TMPDIR` / `${java.io.tmpdir}` if configured differently)*
