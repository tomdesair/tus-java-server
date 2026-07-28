package me.desair.tus.server.rufh;

import me.desair.tus.server.util.TusServletResponse;

/**
 * Default no-op implementation of {@link InterimResponseStrategy} for environments that do not
 * support HTTP 104 interim responses (e.g. standard Servlet containers).
 */
public class NoOpInterimResponseStrategy implements InterimResponseStrategy {

  @Override
  public void sendInterimResponse(TusServletResponse response, String uploadUri, long offset) {
    // Standard servlet containers do not support 104 interim responses; no-op.
  }
}
