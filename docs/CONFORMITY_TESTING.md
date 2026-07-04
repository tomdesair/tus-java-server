# Conformity Testing Guide (IETF Resumable Uploads for HTTP)

This guide describes how to manually execute the RUFH conformity tests against a locally running instance of the Spring Boot demo server using the community conformity testing suite.

---

## 1. Build and Install the Server Library

First, compile and install the core `tus-java-server` library to your local Maven repository:

```bash
# In the root of the tus-java-server repository
mvn clean install
```

---

## 2. Start the Demo Server

1. Update the dependency version in the demo project if necessary. In `tus-java-server-spring-demo` project's `spring-boot-rest/pom.xml`, verify it points to the locally built snapshot version:
   ```xml
   <dependency>
     <groupId>me.desair.tus</groupId>
     <artifactId>tus-java-server</artifactId>
     <version>2.0.0-SNAPSHOT</version>
   </dependency>
   ```

2. Build and start the Spring Boot REST demo server:
   ```bash
   cd ../tus-java-server-spring-demo
   mvn clean package
   java -jar spring-boot-rest/target/spring-boot-rest-0.0.1-SNAPSHOT.jar
   ```

   The server will start on port `8080` with the upload endpoint exposed at:
   `http://localhost:8080/test/api/upload`

---

## 3. Clone and Run the Conformity Tester

The conformity tests are written in Python using `pytest` and are maintained by the community under the `ietf-hackathon` repository.

1. Clone the repository and navigate to the tests directory:
   ```bash
   git clone https://github.com/tus/ietf-hackathon.git
   cd ietf-hackathon/tests
   ```

2. Set up a Python virtual environment and activate it:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   ```

3. Install required Python packages:
   ```bash
   pip install -r requirements.txt
   ```

4. Run the conformity tests pointing to your locally running Spring Boot endpoint:
   ```bash
   pytest --url http://localhost:8080/test/api/upload
   ```

   All tests should pass, certifying that the server implementation conforms to the IETF Resumable Uploads for HTTP (RUFH) specification.
