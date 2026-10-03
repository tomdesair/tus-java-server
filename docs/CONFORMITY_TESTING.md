# Protocol Conformity Testing Guide

This guide describes how to execute the automated conformity test suites for both supported resumable upload protocols:
1. **Tus v1.0.0 Protocol**: [`scripts/tus_conformity_test.py`](../scripts/tus_conformity_test.py) validating the official [Tus v1.0.0 Resumable Upload Protocol](https://tus.io/protocols/resumable-upload).
2. **IETF Resumable Uploads for HTTP (RUFH)**: [`scripts/rufh_conformity_test.py`](../scripts/rufh_conformity_test.py) validating [draft-ietf-httpbis-resumable-upload-12](https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.txt) and [RFC 9530 HTTP Digests](https://www.rfc-editor.org/rfc/rfc9530.html).

Both conformity test suites perform end-to-end, socket-level protocol verification against a running server, ensuring strict adherence to HTTP status codes, structured response headers, error semantics, concurrency guarantees, and storage engine consistency.

---

## 1. Build and Install the Server Library

First, compile and install the core `tus-java-server` library into your local Maven repository:

```bash
# In the root directory of the tus-java-server repository
mvn clean install -DskipTests
```

---

## 2. Start the Demo Server

1. Verify that the dependency in `tus-java-server-spring-demo` project's `spring-boot-rest/pom.xml` references the snapshot version:
   ```xml
   <dependency>
     <groupId>me.desair.tus</groupId>
     <artifactId>tus-java-server</artifactId>
     <version>2.0.0-SNAPSHOT</version>
   </dependency>
   ```

2. Build and start the Spring Boot REST demo server with a `1 KB` maximum upload size limit (`--tus.server.max-upload-size=1024`) to enable full limit discovery & limit enforcement verification:
   ```bash
   cd ../tus-java-server-spring-demo
   mvn clean package -DskipTests
   java -jar spring-boot-rest/target/spring-boot-rest-0.0.1-SNAPSHOT.jar --tus.server.max-upload-size=1024
   ```

   The server will start on port `8080`, exposing upload endpoints for all three storage backends:
   - **Disk Storage (Default)**: `http://localhost:8080/test/api/upload`
   - **S3 Object Storage**: `http://localhost:8080/test-s3/api/upload`
   - **Azure Blob Storage**: `http://localhost:8080/test-azure/api/upload`

---

## 3. Python Prerequisites

Install required Python test packages if not already available in your environment:
```bash
pip install pytest requests
```

---

## 4. Running the Tus v1.0.0 Conformity Test Suite

The Tus v1.0.0 conformity test suite (`scripts/tus_conformity_test.py`) verifies 50 specification rules covering:
- **Core Protocol (§5 & §6)**: OPTIONS feature discovery, Tus-Resumable version handshake (412 Precondition Failed), HEAD offset retrieval, cache control, PATCH data append, Content-Type enforcement, offset mismatch prevention (409 Conflict), length bounds validation, and `X-HTTP-Method-Override`.
- **Creation Extension (§7.1)**: Upload-Length validation, Upload-Defer-Length handling, defer-to-known length transition, and Upload-Metadata base64 decoding.
- **Creation With Upload Extension (§7.2)**: Single-request POST creations with payload body, partial upload chunking, and content-type enforcement.
- **Expiration Extension (§7.3)**: Upload-Expires header validation formatted in RFC 9110 HTTP datetime format.
- **Checksum Extension (§7.4)**: Upload-Checksum verification (SHA-1), 460 Checksum Mismatch handling and payload discarding, and unsupported checksum algorithm rejection (400 Bad Request).
- **Termination Extension (§7.5)**: DELETE cancellation of in-progress and completed uploads and 404/410 verification.
- **Concatenation Extension (§7.6)**: Partial upload creation, final upload creation by merging partials, offset summation, and 403 Forbidden enforcement on final upload PATCH requests.
- **Concurrency & Workflows**: Concurrent PATCH race contention prevention at identical offset and end-to-end multi-chunk upload flows.

### Running Against All Three Storage Backends

The test suite **MUST be run against all three supported storage backend types** (Disk, S3, and Azure Blob) to ensure cross-engine protocol compliance:

#### Option A: Standalone Python Runner (Custom Formatted Summary)

```bash
# 1. Disk Storage Backend
python3 scripts/tus_conformity_test.py --url http://localhost:8080/test/api/upload

# 2. S3 Object Storage Backend
python3 scripts/tus_conformity_test.py --url http://localhost:8080/test-s3/api/upload

# 3. Azure Blob Storage Backend
python3 scripts/tus_conformity_test.py --url http://localhost:8080/test-azure/api/upload
```

Or execute all three backends in a single command loop:
```bash
for endpoint in /test/api/upload /test-s3/api/upload /test-azure/api/upload; do
    echo "======================================================================"
    echo " Running Tus v1.0.0 Conformity Tests: http://localhost:8080$endpoint"
    echo "======================================================================"
    python3 scripts/tus_conformity_test.py --url "http://localhost:8080$endpoint"
done
```

#### Option B: PyTest Runner

```bash
# Execute via pytest
pytest scripts/tus_conformity_test.py --url http://localhost:8080/test/api/upload
pytest scripts/tus_conformity_test.py --url http://localhost:8080/test-s3/api/upload
pytest scripts/tus_conformity_test.py --url http://localhost:8080/test-azure/api/upload
```

---

## 5. Running the IETF RUFH Conformity Test Suite

The RUFH conformity test suite (`scripts/rufh_conformity_test.py`) validates compliance with the official IETF Resumable Uploads for HTTP specification (`draft-ietf-httpbis-resumable-upload-12`) and RFC 9530 HTTP Digests.

### Running Against All Three Storage Backends

```bash
# 1. Disk Storage Backend
python3 scripts/rufh_conformity_test.py --url http://localhost:8080/test/api/upload

# 2. S3 Object Storage Backend
python3 scripts/rufh_conformity_test.py --url http://localhost:8080/test-s3/api/upload

# 3. Azure Blob Storage Backend
python3 scripts/rufh_conformity_test.py --url http://localhost:8080/test-azure/api/upload
```

Or via PyTest:
```bash
for endpoint in /test/api/upload /test-s3/api/upload /test-azure/api/upload; do
    echo "======================================================================"
    echo " Running PyTest RUFH Conformity: http://localhost:8080$endpoint"
    echo "======================================================================"
    pytest scripts/rufh_conformity_test.py --url "http://localhost:8080$endpoint"
done
```

---

## 6. Understanding Test Results & AI Agent Remediation

When executed directly via Python, both conformity test scripts produce a structured summary report detailing:

1. **Total Tests Executed**: Count of total specification compliance tests run.
2. **Passed Tests**: Number of tests matching specification requirements.
3. **Failed Tests**: Detailed list of failing tests including test method names, exact error tracebacks, expected status codes/headers, and corresponding specification section references.

### Example Output:
```
======================================================================
      Tus v1.0.0 Resumable Upload Protocol Conformity Test Suite
      Specification: https://tus.io/protocols/resumable-upload
      Target Endpoint: http://localhost:8080/test/api/upload
======================================================================
..................................................                       [100%]
50 passed in 0.43s

======================================================================
                       CONFORMITY TEST RESULTS
======================================================================
 Total Tests Executed: 50
 Passed:               50
 Failed:               0
======================================================================

[✓] ALL TUS V1.0.0 CONFORMITY TESTS PASSED SUCCESSFULLY!
```

If a test fails, the runner outputs a structured failure breakdown containing:
- Test function name and class
- Verbatim specification quote defining the required behavior
- Exact failure assertion and returned HTTP status or header values

Developers and automated AI agents can use this failure breakdown to immediately diagnose compliance discrepancies and make targeted fixes in the server code.

---

## 7. Running Community (IETF Hackathon) Tests

Alternatively, you can also run the external community test suite from the `ietf-hackathon` repository across all three storage backends:

```bash
git clone https://github.com/tus/ietf-hackathon.git
cd ietf-hackathon/tests
pip install -r requirements.txt

# Run against Disk, S3, and Azure Blob endpoints
pytest --url http://localhost:8080/test/api/upload
pytest --url http://localhost:8080/test-s3/api/upload
pytest --url http://localhost:8080/test-azure/api/upload
```
