package me.desair.tus.server.util;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

/**
 * Abstract request handler to add tus extension values to the correct header
 */
public abstract class AbstractExtensionRequestHandler extends AbstractRequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.OPTIONS.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final TusServletRequest servletRequest,
                        final TusServletResponse servletResponse, final UploadStorageService uploadStorageService,
                        final String ownerKey) {

        StringBuilder extensionBuilder = new StringBuilder(StringUtils.trimToEmpty(
                servletResponse.getHeader(HttpHeader.TUS_EXTENSION)));

        appendExtensions(extensionBuilder);

        servletResponse.setHeader(HttpHeader.TUS_EXTENSION, extensionBuilder.toString());
    }

    protected abstract void appendExtensions(final StringBuilder extensionBuilder);

    protected void addExtension(final StringBuilder stringBuilder, final String extension) {
        if (stringBuilder.length() > 0) {
            stringBuilder.append(",");
        }
        stringBuilder.append(extension);
    }
}
