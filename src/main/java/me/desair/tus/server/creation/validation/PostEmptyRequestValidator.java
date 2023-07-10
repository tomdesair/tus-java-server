package me.desair.tus.server.creation.validation;

import jakarta.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * An empty POST request is used to create a new upload resource.
 */
public class PostEmptyRequestValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey)
            throws TusException {

        Long contentLength = Utils.getLongHeader(request, HttpHeader.CONTENT_LENGTH);
        if (contentLength != null && contentLength > 0) {
            throw new InvalidContentLengthException("A POST request should have a Content-Length header with value "
                    + "0 and no content");
        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
