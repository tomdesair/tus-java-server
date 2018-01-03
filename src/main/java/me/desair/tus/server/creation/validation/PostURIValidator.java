package me.desair.tus.server.creation.validation;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.PostOnInvalidRequestURIException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

/**
 * The Client MUST send a POST request against a known upload creation URL to request a new upload resource.
 */
public class PostURIValidator implements RequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final String ownerKey) throws TusException {
        if(!StringUtils.equals(request.getRequestURI(), uploadStorageService.getUploadURI())) {
            throw new PostOnInvalidRequestURIException("POST requests have to be send to " + uploadStorageService.getUploadURI());
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

}
