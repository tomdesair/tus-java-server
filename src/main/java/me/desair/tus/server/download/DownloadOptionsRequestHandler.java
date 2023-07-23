package me.desair.tus.server.download;

import me.desair.tus.server.util.AbstractExtensionRequestHandler;

/** Add our download extension the Tus-Extension header */
public class DownloadOptionsRequestHandler extends AbstractExtensionRequestHandler {

  @Override
  protected void appendExtensions(StringBuilder extensionBuilder) {
    addExtension(extensionBuilder, "download");
  }
}
