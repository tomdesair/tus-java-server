package me.desair.tus.server;

/** Class that will hold constants for all HTTP headers relevant to the tus v1.0.0 protocol */
public class HttpHeader {
  /**
   * The X-HTTP-Method-Override request header MUST be a string which MUST be interpreted as the
   * request’s method by the Server, if the header is presented. The actual method of the request
   * MUST be ignored. The Client SHOULD use this header if its environment does not support the
   * PATCH or DELETE methods.
   */
  public static final String METHOD_OVERRIDE = "X-HTTP-Method-Override";

  public static final String CACHE_CONTROL = "Cache-Control";
  public static final String CONTENT_TYPE = "Content-Type";
  public static final String CONTENT_LENGTH = "Content-Length";
  public static final String CONTENT_DISPOSITION = "Content-Disposition";
  public static final String LOCATION = "Location";

  /**
   * The Transfer-Encoding header specifies the form of encoding used to safely transfer the entity
   * to the user.
   */
  public static final String TRANSFER_ENCODING = "Transfer-Encoding";

  /**
   * The Upload-Offset request and response header indicates a byte offset within a resource. The
   * value MUST be a non-negative integer.
   */
  public static final String UPLOAD_OFFSET = "Upload-Offset";

  public static final String UPLOAD_METADATA = "Upload-Metadata";

  /**
   * The Upload-Checksum request header contains information about the checksum of the current body
   * payload. The header MUST consist of the name of the used checksum algorithm and the Base64
   * encoded checksum separated by a space.
   */
  public static final String UPLOAD_CHECKSUM = "Upload-Checksum";

  /**
   * The Upload-Length request and response header indicates the size of the entire upload in bytes.
   * The value MUST be a non-negative integer.
   */
  public static final String UPLOAD_LENGTH = "Upload-Length";

  /**
   * The Upload-Expires response header indicates the time after which the unfinished upload
   * expires. The value of the Upload-Expires header MUST be in RFC 7231
   * (https://tools.ietf.org/html/rfc7231#section-7.1.1.1) datetime format.
   */
  public static final String UPLOAD_EXPIRES = "Upload-Expires";

  /**
   * The Upload-Defer-Length request and response header indicates that the size of the upload is
   * not known currently and will be transferred later. Its value MUST be 1. If the length of an
   * upload is not deferred, this header MUST be omitted.
   */
  public static final String UPLOAD_DEFER_LENGTH = "Upload-Defer-Length";

  /**
   * The Upload-Concat request and response header MUST be set in both partial and upload creation
   * requests. It indicates whether the upload is either a partial or upload.
   */
  public static final String UPLOAD_CONCAT = "Upload-Concat";

  /**
   * The Tus-Version response header MUST be a comma-separated list of protocol versions supported
   * by the Server. The list MUST be sorted by Server’s preference where the first one is the most
   * preferred one.
   */
  public static final String TUS_VERSION = "Tus-Version";

  /**
   * The Tus-Resumable header MUST be included in every request and response except for OPTIONS
   * requests. The value MUST be the version of the protocol used by the Client or the Server.
   */
  public static final String TUS_RESUMABLE = "Tus-Resumable";

  /**
   * The Tus-Extension response header MUST be a comma-separated list of the extensions supported by
   * the Server. If no extensions are supported, the Tus-Extension header MUST be omitted.
   */
  public static final String TUS_EXTENSION = "Tus-Extension";

  /**
   * The Tus-Max-Size response header MUST be a non-negative integer indicating the maximum allowed
   * size of an entire upload in bytes. The Server SHOULD set this header if there is a known hard
   * limit.
   */
  public static final String TUS_MAX_SIZE = "Tus-Max-Size";

  /**
   * The Tus-Checksum-Algorithm response header MUST be a comma-separated list of the checksum
   * algorithms supported by the server.
   */
  public static final String TUS_CHECKSUM_ALGORITHM = "Tus-Checksum-Algorithm";

  /**
   * The X-Forwarded-For (XFF) HTTP header field is a common method for identifying the originating
   * IP address of a client connecting to a web server through an HTTP proxy or load balancer.
   */
  public static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private HttpHeader() {
    // This is an utility class to hold constants
  }
}
