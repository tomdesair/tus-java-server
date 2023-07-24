package me.desair.tus.server;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/** Interface that represents an extension in the tus protocol. */
public interface TusExtension {

  /**
   * The name of the Tus extension that can be used to disable or enable the extension.
   *
   * @return The name of the extension
   */
  String getName();

  /**
   * Validate the given request.
   *
   * @param method The HTTP method of this request (taking into account overrides)
   * @param servletRequest The HTTP request
   * @param uploadStorageService The current upload storage service
   * @param ownerKey Identifier of the owner of this upload
   * @throws TusException When the request is invalid
   * @throws IOException When unable to read upload information
   */
  void validate(
      HttpMethod method,
      HttpServletRequest servletRequest,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException;

  /**
   * Process the given request.
   *
   * @param method The HTTP method of this request (taking into account overrides)
   * @param servletRequest The HTTP request
   * @param servletResponse The HTTP response
   * @param uploadStorageService The current upload storage service
   * @param ownerKey Identifier of the owner of this upload
   * @throws TusException When processing the request fails
   * @throws IOException When unable to read upload information
   */
  void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException;

  /**
   * If a request is invalid, or when processing the request fails, it might be necessary to react
   * to this failure. This method allows extensions to react to validation or processing failures.
   *
   * @param method The HTTP method of this request (taking into account overrides)
   * @param servletRequest The HTTP request
   * @param servletResponse The HTTP response
   * @param uploadStorageService The current upload storage service
   * @param ownerKey Identifier of the owner of this upload
   * @throws TusException When handling the error fails
   * @throws IOException When unable to read upload information
   */
  void handleError(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException;

  /**
   * The minimal list of HTTP methods that this extension needs to function properly.
   *
   * @return The list of HTTP methods required by this extension
   */
  Collection<HttpMethod> getMinimalSupportedHttpMethods();
}
