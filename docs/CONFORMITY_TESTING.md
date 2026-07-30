# Conformity Testing Guide (IETF Resumable Uploads for HTTP)

This guide describes how to execute the RUFH (Resumable Uploads for HTTP) conformity tests against a locally running instance of the Spring Boot demo server or any RUFH compliant server endpoint.

The test suite validates compliance with [draft-ietf-httpbis-resumable-upload-12](https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.txt) and [RFC 9530 HTTP Digests](https://www.rfc-editor.org/rfc/rfc9530.html).

---

## 1. Build and Install the Server Library

First, compile and install the core `tus-java-server` library to your local Maven repository:

```bash
# In the root of the tus-java-server repository
mvn clean install -DskipTests
```

---

## 2. Start the Demo Server

1. Verify the dependency in `tus-java-server-spring-demo` project's `spring-boot-rest/pom.xml` points to the snapshot version:
   ```xml
   <dependency>
     <groupId>me.desair.tus</groupId>
     <artifactId>tus-java-server</artifactId>
     <version>2.0.0-SNAPSHOT</version>
   </dependency>
   ```

2. Build and start the Spring Boot REST demo server with a `1 KB` maximum upload size parameter (`--tus.server.max-upload-size=1024`) to enable full limit discovery & limit enforcement verification:
   ```bash
   cd ../tus-java-server-spring-demo
   mvn clean package -DskipTests
   java -jar spring-boot-rest/target/spring-boot-rest-0.0.1-SNAPSHOT.jar --tus.server.max-upload-size=1024
   ```

   The server will start on port `8080` with the upload endpoint exposed at:
   `http://localhost:8080/test/api/upload`

---

## 3. Run the Built-In RUFH Conformity Test Suite

The repository includes its own native Python conformity test suite located at `scripts/rufh_conformity_test.py`. It requires `pytest` and `requests`.

### Prerequisites
Install Python dependencies if not already installed:
```bash
pip install pytest requests
```

### Running the Test Suite

You can execute the test suite using Python directly or via PyTest:

#### Option A: Running directly with Python (Recommended for structured AI / Agent reporting)
```bash
python3 scripts/rufh_conformity_test.py --url http://localhost:8080/test/api/upload
```

#### Option B: Running with PyTest
```bash
pytest scripts/rufh_conformity_test.py --url http://localhost:8080/test/api/upload
```

---

## 4. Understanding Test Results & AI Agent Remediation

When executed, the script produces a structured summary report detailing:

1. **Total Tests Executed**: Count of total specification compliance tests run.
2. **Passed Tests**: Number of tests matching draft-12 specification requirements.
3. **Failed Tests**: Detailed list of failing tests including test method names, exact error tracebacks, expected status codes/headers, and corresponding RFC section references.
4. **104 Interim Responses**: Count of tests where `HTTP/1.1 104 Upload Resumption Supported` interim responses were detected from the server socket.

AI agents and developers can analyze the detailed failure breakdown in the script's console output to pinpoint specific compliance gaps and adjust server logic accordingly.

---

## 5. Running Community (IETF Hackathon) Tests

Alternatively, you can also run the external community test suite from the `ietf-hackathon` repository:

```bash
git clone https://github.com/tus/ietf-hackathon.git
cd ietf-hackathon/tests
pip install -r requirements.txt
pytest --url http://localhost:8080/test/api/upload
```
