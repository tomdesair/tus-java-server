# HTTP 104 Interim Responses (IETF Resumable Uploads for HTTP)

This document describes how preliminary **HTTP 104 (Upload Resumption Supported)** interim responses work under the IETF Resumable Uploads for HTTP (RUFH) specification, how support is provided in `tus-java-server`, the limitations within standard Servlet containers and Tomcat, and how to integrate an interim response Tomcat Valve in a Spring Boot application.

---

## 1. Specification Overview

According to Section 4.2 and 4.2.2 of [draft-ietf-httpbis-resumable-upload-12](https://www.ietf.org/archive/id/draft-ietf-httpbis-resumable-upload-12.txt):

* When an upload creation request (`POST`, `PUT`, or `PATCH`) is received, the server **MAY** send an informational **`104 (Upload Resumption Supported)`** interim response frame before receiving or processing the entire request payload body.
* The 104 response informs the client of the assigned resource URL (`Location`) and current offset (`Upload-Offset: 0`) early in the transmission cycle.
* Example raw HTTP 104 interim response frame:
  ```http
  HTTP/1.1 104 Upload Resumption Supported
  Location: https://upload.example.com/files/74384a29-d5c2-4916-b8c1-123456789abc
  Upload-Offset: 0

  ```

---

## 2. Support in `tus-java-server`

The `tus-java-server` library decoupled protocol logic from web container socket I/O by providing utility methods to generate formatted raw HTTP 104 response string frames:

* **`TusFileUploadService#getRawInterimResponse(HttpServletRequest, String)`**:
  Inspects an incoming HTTP request, determines if it is an upload creation under the RUFH protocol, pre-creates or resolves the target `UploadId`, and returns the raw HTTP 104 header frame string.

* **`RufhInterimResponseUtil#getRawInterimResponse(...)`**:
  Underlying utility class that formats the status line (`HTTP/1.1 104 Upload Resumption Supported`) and headers (`Location`, `Upload-Offset: 0`).

---

## 3. Container & Framework Limitations

### Jakarta Servlet Specification (Servlet 6.0)
The standard Servlet API enforces a strict response lifecycle:
$$\text{Status Line} \longrightarrow \text{Headers} \longrightarrow \text{Response Body}$$
Calling `HttpServletResponse#getOutputStream()` or `getWriter()` writes data as part of the HTTP response body *after* committing a final status code (e.g. `200 OK` or `201 Created`). The Servlet API does not provide a standard mechanism to emit preliminary 1xx status lines prior to final response completion.

### Apache Tomcat 10 & Coyote Connector
Tomcat encapsulates its low-level TCP connection object (`SocketWrapperBase`) deep inside internal Coyote output buffers (e.g., `Http11OutputBuffer`). While Tomcat provides internal mechanisms like `Response#sendAcknowledgement()` (`ActionCode.ACK`), that feature is hardcoded exclusively for `100 Continue` expectations. Tomcat 10 does not expose a public, non-reflective API for writing arbitrary 1xx status frames to the network socket.

### Spring Boot 3.x
Spring Boot controllers process HTTP requests inside Spring's `DispatcherServlet` pipeline after Tomcat has allocated a worker thread. Standard Spring controllers cannot output uncommitted 1xx preliminary headers directly. To emit a raw 104 frame before servlet execution, a container-level interceptor—such as a **Tomcat Valve**—is required.

---

## 4. Tomcat Valve Implementation & Spring Boot Integration

To write raw 104 frames directly to Tomcat's underlying TCP socket before servlet execution, a custom Tomcat Valve inspects the request and writes bytes directly to Tomcat's `SocketWrapperBase` using cached reflection.

### Reference Implementation
A complete reference implementation is available in the [tus-java-server-spring-demo](https://github.com/tomdesair/tus-java-server-spring-demo) repository:
* **Class**: [`TusInterimResponseTomcatValve`](https://github.com/tomdesair/tus-java-server-spring-demo/blob/main/spring-boot-rest/src/main/java/me/desair/spring/tus/TusInterimResponseTomcatValve.java)

### Example Tomcat Valve Code
```java
public class TusInterimResponseTomcatValve extends ValveBase {

  private static final Logger LOG = LoggerFactory.getLogger(TusInterimResponseTomcatValve.class);

  // Cached reflection fields and methods to avoid per-request lookup overhead
  private static Field outputBufferField;
  private static Field socketWrapperField;
  private static Method writeMethod;
  private static Method flushMethod;

  private final TusFileUploadService tusFileUploadService;

  public TusInterimResponseTomcatValve(TusFileUploadService tusFileUploadService) {
    this.tusFileUploadService = tusFileUploadService;
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    if (tusFileUploadService != null) {
      try {
        // Step 1: Inspect the incoming request to check if a 104 interim response frame is needed
        String rawInterimResponse =
            tusFileUploadService.getRawInterimResponse(request.getRequest(), null);

        if (rawInterimResponse != null) {
          byte[] bytes = rawInterimResponse.getBytes(StandardCharsets.UTF_8);

          // Step 2: Write raw HTTP 104 bytes directly to Tomcat's underlying SocketWrapperBase
          boolean written = writeToSocketWrapper(response, bytes);
          if (written) {
            LOG.debug(
                "Emitted raw HTTP 104 Interim Response via Tomcat SocketWrapper for request URI: {}",
                request.getRequestURI());
          } else {
            LOG.warn("Could not obtain Tomcat SocketWrapper to emit 104 interim response");
          }
        }
      } catch (Exception e) {
        LOG.warn("Error emitting HTTP 104 interim response in Tomcat Valve", e);
      }
    }

    // Step 3: Continue normal request processing down Tomcat's pipeline to the target servlet
    getNext().invoke(request, response);
  }

  private boolean writeToSocketWrapper(Response response, byte[] bytes) {
    try {
      org.apache.coyote.Response coyoteResponse = response.getCoyoteResponse();
      Object outputBuffer = getOutputBuffer(coyoteResponse);
      if (outputBuffer != null) {
        Object socketWrapper = getSocketWrapper(outputBuffer);
        if (socketWrapper != null) {
          return invokeWriteAndFlush(socketWrapper, bytes);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to write to Tomcat SocketWrapperBase via reflection", e);
    }
    return false;
  }

  private Object getOutputBuffer(org.apache.coyote.Response coyoteResponse) throws Exception {
    if (outputBufferField == null) {
      Field field = org.apache.coyote.Response.class.getDeclaredField("outputBuffer");
      field.setAccessible(true);
      outputBufferField = field;
    }
    return outputBufferField.get(coyoteResponse);
  }

  private Object getSocketWrapper(Object outputBuffer) throws Exception {
    if (socketWrapperField == null
        || !socketWrapperField.getDeclaringClass().isAssignableFrom(outputBuffer.getClass())) {
      Field field = findDeclaredField(outputBuffer.getClass(), "socketWrapper");
      if (field != null) {
        field.setAccessible(true);
        socketWrapperField = field;
      }
    }
    return socketWrapperField != null ? socketWrapperField.get(outputBuffer) : null;
  }

  private boolean invokeWriteAndFlush(Object socketWrapper, byte[] bytes) throws Exception {
    if (writeMethod == null) {
      writeMethod =
          socketWrapper
              .getClass()
              .getMethod("write", boolean.class, byte[].class, int.class, int.class);
    }
    if (flushMethod == null) {
      flushMethod = socketWrapper.getClass().getMethod("flush", boolean.class);
    }
    writeMethod.invoke(socketWrapper, true, bytes, 0, bytes.length);
    flushMethod.invoke(socketWrapper, true);
    return true;
  }

  private static Field findDeclaredField(Class<?> clazz, String fieldName) {
    Class<?> current = clazz;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    return null;
  }
}
```

### Registering in Spring Boot
Register the valve in your Spring Boot application configuration using `TomcatServletWebServerFactory`:

```java
@Bean
public TomcatServletWebServerFactory tomcatFactory(TusFileUploadService tusFileUploadService) {
  TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
  factory.addContextValves(new TusInterimResponseTomcatValve(tusFileUploadService));
  return factory;
}
```

---

## 5. Production Best Practices & Upgrade Path

1. **Edge Reverse Proxies / API Gateways**:
   In production environments, preliminary 1xx informational responses (such as 103 Early Hints or 104 Upload Resumption) are typically emitted at the Edge Proxy or API Gateway layer (e.g. Nginx, HAProxy, Envoy, Cloudflare). Reverse proxies operate directly on raw TCP streams without requiring reflection inside Java application servers.

2. **Future Servlet Specifications (Servlet 6.2 / Tomcat 12+)**:
   Standardized early hint APIs (such as `HttpServletResponse#sendEarlyHints()`) in future Servlet revisions will allow reflection-free emission of preliminary status frames directly through the standard Java web container API.
