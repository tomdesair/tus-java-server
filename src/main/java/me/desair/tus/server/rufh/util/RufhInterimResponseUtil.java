package me.desair.tus.server.rufh.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.rufh.handler.RufhCreationPostRequestHandler;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for formatting raw HTTP 104 interim response frames for IETF Resumable Uploads for
 * HTTP (RUFH).
 */
public final class RufhInterimResponseUtil {

  public static final String PRE_CREATED_UPLOAD_INFO_ATTR = "me.desair.tus.preCreatedUploadInfo";

  private static final Logger log = LoggerFactory.getLogger(RufhInterimResponseUtil.class);

  private RufhInterimResponseUtil() {
    // Utility class
  }

  /**
   * Generates the raw HTTP 104 interim response frame string for an incoming upload creation
   * request under the IETF Resumable Uploads protocol (RUFH).
   *
   * @param servletRequest The incoming {@link HttpServletRequest}
   * @param uploadStorageService The storage service instance
   * @param ownerKey The owner key identifier for the upload
   * @return The raw HTTP 104 interim response string if applicable, or null if not applicable
   */
  public static String getRawInterimResponse(
      HttpServletRequest servletRequest,
      UploadStorageService uploadStorageService,
      String ownerKey) {

    if (servletRequest == null || uploadStorageService == null) {
      return null;
    }

    HttpMethod method =
        HttpMethod.getMethodIfSupported(servletRequest, EnumSet.allOf(HttpMethod.class));
    RufhCreationPostRequestHandler creationHandler = new RufhCreationPostRequestHandler();
    if (method == null || !creationHandler.supports(method)) {
      return null;
    }

    TusServletRequest tusRequest = new TusServletRequest(servletRequest);
    String existingUploadUri = Utils.getUploadUri(tusRequest, null);

    String uploadUri;
    try {
      UploadInfo uploadInfo =
          existingUploadUri != null
              ? uploadStorageService.getUploadInfo(existingUploadUri, ownerKey)
              : null;

      if (uploadInfo != null) {
        long offset = uploadInfo.getOffset() != null ? uploadInfo.getOffset() : 0L;
        return getRawInterimResponseForAppend(offset);
      }

      // If there is no existing upload, an interim response with Location is only valid for
      // an upload creation request matching this storage service's creation endpoint.
      if (!Utils.isCreationEndpoint(servletRequest, uploadStorageService)) {
        return null;
      }

      uploadInfo = new UploadInfo();
      uploadInfo = uploadStorageService.create(uploadInfo, ownerKey);
      uploadUri = Utils.getUploadUriOnCreation(uploadInfo, servletRequest, uploadStorageService);

      servletRequest.setAttribute(PRE_CREATED_UPLOAD_INFO_ATTR, uploadInfo);

    } catch (Exception e) {
      return null;
    }

    if (!uploadUri.startsWith("http://") && !uploadUri.startsWith("https://")) {
      String scheme = servletRequest.getScheme();
      String host = servletRequest.getHeader("Host");
      if (scheme != null && host != null) {
        // Security (CWE-113): Reject CR and LF characters in Host header to prevent HTTP response
        // splitting
        // when emitting raw HTTP 104 interim response frame strings to the network socket.
        if (host.indexOf('\r') != -1 || host.indexOf('\n') != -1) {
          log.warn("Potential HTTP response splitting attempt rejected in Host header: {}", host);
          return null;
        }
        String sanitizedHost = StructuredHeaderUtil.sanitizeHeaderValue(host);
        uploadUri = scheme + "://" + sanitizedHost + uploadUri;
      }
    }

    return getRawInterimResponse(uploadUri, 0L);
  }

  /**
   * Generates the raw HTTP 104 interim response frame string for a given upload URI, offset, and
   * owner key.
   *
   * @param uploadUri The location URI of the upload
   * @param offset The initial upload offset (typically 0)
   * @param ownerKey The owner key identifier for the upload
   * @return The formatted HTTP 104 response frame string
   */
  public static String getRawInterimResponse(String uploadUri, long offset, String ownerKey) {
    return getRawInterimResponse(uploadUri, offset);
  }

  /**
   * Generates the raw HTTP 104 interim response frame string for an upload creation request.
   *
   * <p>Reference: Section 4.2.2 of draft-ietf-httpbis-resumable-upload-12.
   *
   * @param uploadUri The location URI of the created upload resource
   * @param offset The upload offset
   * @return The formatted HTTP 104 response frame string, or null if uploadUri is null
   */
  public static String getRawInterimResponse(String uploadUri, long offset) {
    if (uploadUri == null) {
      return null;
    }
    // Security (CWE-113): Ensure uploadUri does not contain CR or LF characters before constructing
    // raw status frame
    if (uploadUri.indexOf('\r') != -1 || uploadUri.indexOf('\n') != -1) {
      log.warn("Potential HTTP response splitting attempt rejected in uploadUri: {}", uploadUri);
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 104 Upload Resumption Supported\r\n");
    sb.append("Location: ").append(uploadUri).append("\r\n");
    sb.append("Upload-Offset: ").append(offset).append("\r\n");
    sb.append("\r\n");
    return sb.toString();
  }

  /**
   * Generates the raw HTTP 104 interim response frame string for an upload append request.
   *
   * <p>Reference: Section 4.4.2 of draft-ietf-httpbis-resumable-upload-12 ("These interim responses
   * MUST NOT include the Location header field").
   *
   * @param offset The current upload offset
   * @return The formatted HTTP 104 response frame string for append
   */
  public static String getRawInterimResponseForAppend(long offset) {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 104 Upload Resumption Supported\r\n");
    sb.append("Upload-Offset: ").append(offset).append("\r\n");
    sb.append("\r\n");
    return sb.toString();
  }
}
