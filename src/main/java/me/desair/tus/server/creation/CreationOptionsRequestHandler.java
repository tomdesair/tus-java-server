package me.desair.tus.server.creation;

import me.desair.tus.server.*;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * The Client and the Server SHOULD implement the upload creation extension.
 * If the Server supports this extension, it MUST add creation to the Tus-Extension header.
 *
 * If the Server supports deferring length, it MUST add creation-defer-length to the Tus-Extension header.
 */
public class CreationOptionsRequestHandler extends AbstractExtensionRequestHandler {

    @Override
    protected void appendExtensions(final StringBuilder extensionBuilder) {
        addExtension(extensionBuilder, "creation");
        addExtension(extensionBuilder, "creation-defer-length");
    }

}
