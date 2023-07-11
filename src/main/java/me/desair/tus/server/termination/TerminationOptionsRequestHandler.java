package me.desair.tus.server.termination;

import me.desair.tus.server.util.AbstractExtensionRequestHandler;

/** Add our download extension the Tus-Extension header */
public class TerminationOptionsRequestHandler extends AbstractExtensionRequestHandler {

  @Override
  protected void appendExtensions(StringBuilder extensionBuilder) {
    addExtension(extensionBuilder, "termination");
  }
}
