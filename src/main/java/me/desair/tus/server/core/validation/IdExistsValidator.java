package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/**
 * If the resource is not found, the Server SHOULD return either the
 * 404 Not Found, 410 Gone or 403 Forbidden status without the Upload-Offset header.
 */
public class IdExistsValidator extends AbstractRequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException {
        UUID id = idFactory.readUploadId(request);

        if(uploadStorageService.getUploadInfo(id) == null) {
            throw new UploadNotFoundException("The upload with ID " + Objects.toString(id) + " was not found.");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        switch (method) {
            case HEAD:
            case PATCH:
                return true;
            default:
                return false;
        }
    }

}
