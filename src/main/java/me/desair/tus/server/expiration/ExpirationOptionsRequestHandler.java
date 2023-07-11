package me.desair.tus.server.expiration;

import me.desair.tus.server.util.AbstractExtensionRequestHandler;

/**
 * The Server MAY remove unfinished uploads once they expire. In order to indicate this behavior to
 * the Client, the Server MUST add expiration to the Tus-Extension header.
 */
public class ExpirationOptionsRequestHandler extends AbstractExtensionRequestHandler {

  @Override
  protected void appendExtensions(StringBuilder extensionBuilder) {
    addExtension(extensionBuilder, "expiration");
  }
}
