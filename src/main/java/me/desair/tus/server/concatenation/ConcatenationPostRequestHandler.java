package me.desair.tus.server.concatenation;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Server MUST acknowledge a successful upload creation with the 201 Created status.
 * The Server MUST set the Location header to the URL of the created resource. This URL MAY be absolute or relative.
 */
public class ConcatenationPostRequestHandler extends AbstractRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ConcatenationPostRequestHandler.class);

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse,
                        final UploadStorageService uploadStorageService, final String ownerKey) throws IOException, TusException {

        //For post requests, the upload URI is part of the response
        String uploadUri = servletResponse.getHeader(HttpHeader.LOCATION);
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);

        if(uploadInfo != null) {

            String uploadConcatValue = servletRequest.getHeader(HttpHeader.UPLOAD_CONCAT);
            if (StringUtils.equalsIgnoreCase(uploadConcatValue, "partial")) {
                uploadInfo.setUploadType(UploadType.PARTIAL);

            } else if (StringUtils.startsWithIgnoreCase(uploadConcatValue, "final")) {
                uploadInfo.setUploadType(UploadType.CONCATENATED);
                uploadInfo.setConcatenationParts(parseConcatenationIDs(uploadConcatValue));

                //TODO uploadService.getConcatenationService().merge(uploadInfo);
            } else {
                uploadInfo.setUploadType(UploadType.REGULAR);
            }
            uploadInfo.setUploadConcatHeaderValue(uploadConcatValue);

            uploadStorageService.update(uploadInfo);
        }
    }

    private List<UUID> parseConcatenationIDs(String uploadConcatValue) {
        List<UUID> output = new LinkedList<>();

        String idString = StringUtils.substringAfter(uploadConcatValue, ";");
        for (String id : StringUtils.trimToEmpty(idString).split("\\s")) {
            try {
                output.add(UUID.fromString(id));
            } catch(IllegalArgumentException ex) {
                log.warn("The {} header contained an invalid ID {}", HttpHeader.UPLOAD_CONCAT, id);
            }
        }

        return output;
    }
}
