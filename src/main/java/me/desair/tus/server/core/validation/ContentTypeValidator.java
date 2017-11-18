package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.Utils;
import me.desair.tus.server.exception.InvalidContentTypeException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * All PATCH requests MUST use Content-Type: application/offset+octet-stream.
 */
public class ContentTypeValidator extends AbstractRequestValidator {

    private static final String APPLICATION_OFFSET_OCTET_STREAM = "application/offset+octet-stream";

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException {
        String contentType = Utils.getHeader(request, HttpHeader.CONTENT_TYPE);
        if(!APPLICATION_OFFSET_OCTET_STREAM.equals(contentType)) {
            throw new InvalidContentTypeException("The " + HttpHeader.CONTENT_TYPE + " header must contain value " + APPLICATION_OFFSET_OCTET_STREAM);
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

}
