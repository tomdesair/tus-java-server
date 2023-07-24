package me.desair.tus.server.util;

import me.desair.tus.server.RequestHandler;

/**
 * Abstract {@link me.desair.tus.server.RequestHandler} implementation that contains the common
 * functionality.
 */
public abstract class AbstractRequestHandler implements RequestHandler {

  @Override
  public boolean isErrorHandler() {
    return false;
  }
}
