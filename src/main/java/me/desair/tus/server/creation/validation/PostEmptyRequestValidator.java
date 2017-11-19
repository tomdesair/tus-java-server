package me.desair.tus.server.creation.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.util.Utils;
import me.desair.tus.server.core.validation.AbstractRequestValidator;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;

/**
 * An empty POST request is used to create a new upload resource.
 */
public class PostEmptyRequestValidator extends AbstractRequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService) throws TusException {
        Long contentLength = Utils.getLongHeader(request, HttpHeader.CONTENT_LENGTH);
        if(contentLength != null && contentLength > 0) {
            throw new InvalidContentLengthException("A POST request should not have content and a Content-Length header with value 0");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
