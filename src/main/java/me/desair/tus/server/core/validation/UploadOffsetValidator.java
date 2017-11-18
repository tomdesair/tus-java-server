package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.util.Utils;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadOffsetMismatchException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/**
 * The Upload-Offset header’s value MUST be equal to the current offset of the resource.
 * If the offsets do not match, the Server MUST respond with the 409 Conflict status without modifying the upload resource.
 */
public class UploadOffsetValidator extends AbstractRequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException {
        String uploadOffset = Utils.getHeader(request, HttpHeader.UPLOAD_OFFSET);

        UUID id = idFactory.readUploadId(request);
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(id);

        if(uploadInfo != null) {
            String expectedOffset = Objects.toString(uploadInfo.getOffset());
            if(!StringUtils.equals(expectedOffset, uploadOffset)) {
                throw new UploadOffsetMismatchException("The Upload-Offset was " + uploadOffset + " but expected " + expectedOffset);
            }
        }

    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

}
