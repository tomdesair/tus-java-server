package me.desair.tus.server.core.validation;

import jakarta.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidContentTypeException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * All PATCH requests MUST use Content-Type: application/offset+octet-stream.
 */
public class ContentTypeValidator implements RequestValidator {

    static final String APPLICATION_OFFSET_OCTET_STREAM = "application/offset+octet-stream";

    @Override
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey) throws TusException {

        String contentType = Utils.getHeader(request, HttpHeader.CONTENT_TYPE);
        if (!APPLICATION_OFFSET_OCTET_STREAM.equals(contentType)) {
            throw new InvalidContentTypeException("The " + HttpHeader.CONTENT_TYPE + " header must contain value "
                    + APPLICATION_OFFSET_OCTET_STREAM);
        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

}
