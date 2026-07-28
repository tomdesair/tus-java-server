package me.desair.tus.server.rufh;

import me.desair.tus.server.util.TusServletResponse;

/**
 * Strategy interface to handle 104 (Upload Resumption) interim responses as specified in the IETF
 * Resumable Uploads for HTTP specification.
 */
public interface InterimResponseStrategy {

  /**
   * Send an interim 104 response if supported by the container/environment.
   *
   * @param response the response to write headers/status to
   * @param uploadUri the location URI of the upload
   * @param offset the current upload offset
   */
  void sendInterimResponse(TusServletResponse response, String uploadUri, long offset);
}
