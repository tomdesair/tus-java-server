package me.desair.tus.server.creationwithupload;

import me.desair.tus.server.util.AbstractExtensionRequestHandler;

/** The options request handler for the creation-with-upload extension. */
public class CreationWithUploadOptionsRequestHandler extends AbstractExtensionRequestHandler {

  @Override
  protected void appendExtensions(StringBuilder extensionBuilder) {
    addExtension(extensionBuilder, "creation-with-upload");
  }
}
