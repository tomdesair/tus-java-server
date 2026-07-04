package me.desair.tus.server;

import java.io.IOException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/** Interface for processing HTTP request logic within protocol extensions. */
public interface RequestHandler {

  /**
   * Test if this request handler supports the given HTTP method.
   *
   * @param method The current HTTP method
   * @return true if supported, false otherwise
   */
  boolean supports(HttpMethod method);

  /**
   * Process the given HTTP request.
   *
   * @param method The HTTP method of this request
   * @param servletRequest The HTTP request
   * @param servletResponse The HTTP response
   * @param uploadStorageService The current upload storage service
   * @param ownerKey Identifier of the owner of this upload
   * @throws IOException When an I/O error occurs
   * @throws TusException When a protocol error occurs
   */
  void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException;

  /**
   * Process the given HTTP request with access to the upload locking service.
   *
   * @param method The HTTP method of this request
   * @param servletRequest The HTTP request
   * @param servletResponse The HTTP response
   * @param uploadStorageService The current upload storage service
   * @param uploadLockingService The upload locking service instance
   * @param ownerKey Identifier of the owner of this upload
   * @throws IOException When an I/O error occurs
   * @throws TusException When a protocol error occurs
   */
  default void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey)
      throws IOException, TusException {
    process(method, servletRequest, servletResponse, uploadStorageService, ownerKey);
  }

  /**
   * Test if this handler is an error handler invoked during exception processing.
   *
   * @return true if this handler processes errors, false otherwise
   */
  boolean isErrorHandler();
}
