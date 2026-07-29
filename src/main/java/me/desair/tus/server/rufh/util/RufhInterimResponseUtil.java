package me.desair.tus.server.rufh.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.rufh.handler.RufhCreationPostRequestHandler;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.Utils;

/**
 * Utility class for formatting raw HTTP 104 interim response frames for IETF Resumable Uploads for
 * HTTP (RUFH).
 */
public final class RufhInterimResponseUtil {

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
      boolean isExisting =
          existingUploadUri != null
              && uploadStorageService.getUploadInfo(existingUploadUri, ownerKey) != null;

      if (isExisting) {
        uploadUri = existingUploadUri;
      } else {
        UploadInfo uploadInfo = new UploadInfo();
        uploadInfo = uploadStorageService.create(uploadInfo, ownerKey);
        uploadUri = Utils.getUploadUriOnCreation(uploadInfo, servletRequest, uploadStorageService);

        servletRequest.setAttribute("me.desair.tus.preCreatedUploadInfo", uploadInfo);
      }
    } catch (Exception e) {
      return null;
    }

    if (!uploadUri.startsWith("http://") && !uploadUri.startsWith("https://")) {
      String scheme = servletRequest.getScheme();
      String host = servletRequest.getHeader("Host");
      if (scheme != null && host != null) {
        uploadUri = scheme + "://" + host + uploadUri;
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
   * Generates the raw HTTP 104 interim response frame string for a given upload URI and offset.
   *
   * @param uploadUri The location URI of the upload
   * @param offset The initial upload offset (typically 0)
   * @return The formatted HTTP 104 response frame string
   */
  public static String getRawInterimResponse(String uploadUri, long offset) {
    if (uploadUri == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 104 Upload Resumption Supported\r\n");
    sb.append("Location: ").append(uploadUri).append("\r\n");
    sb.append("Upload-Offset: ").append(offset).append("\r\n");
    sb.append("\r\n");
    return sb.toString();
  }
}
