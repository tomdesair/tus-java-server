package me.desair.tus.server.core.validation;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * Validate that the given upload length in combination with the bytes we already received,
 * does not exceed the declared initial length on upload creation.
 */
public class ContentLengthValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey)
            throws TusException, IOException {

        Long contentLength = Utils.getLongHeader(request, HttpHeader.CONTENT_LENGTH);

        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(request.getRequestURI(), ownerKey);

        if (contentLength != null
                && uploadInfo != null
                && uploadInfo.hasLength()
                && (uploadInfo.getOffset() + contentLength > uploadInfo.getLength())) {

            throw new InvalidContentLengthException("The " + HttpHeader.CONTENT_LENGTH + " value " + contentLength
                    + " in combination with the current offset " + uploadInfo.getOffset()
                    + " exceeds the declared upload length " + uploadInfo.getLength());
        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

}
