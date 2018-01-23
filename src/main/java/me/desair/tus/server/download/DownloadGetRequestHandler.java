package me.desair.tus.server.download;

import java.io.IOException;
import java.util.Objects;

import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadInProgressException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Send the uploaded bytes of finished uploads
 */
public class DownloadGetRequestHandler extends AbstractRequestHandler {

    private static final String CONTENT_DISPOSITION_FORMAT = "attachment;filename=\"%s\"";

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.GET.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse,
                        final UploadStorageService uploadStorageService, final String ownerKey) throws IOException, TusException {

        UploadInfo info = uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
        if(info == null || info.isUploadInProgress()) {
            throw new UploadInProgressException("Upload " + servletRequest.getRequestURI() + " is still in progress " +
                    "and cannot be downloaded yet");
        } else {
            servletResponse.setHeader(HttpHeader.CONTENT_LENGTH, Objects.toString(info.getLength()));
            servletResponse.setHeader(HttpHeader.CONTENT_DISPOSITION, String.format(CONTENT_DISPOSITION_FORMAT, info.getFileName()));
            servletResponse.setHeader(HttpHeader.CONTENT_TYPE, info.getFileMimeType());

            if(info.hasMetadata()) {
                servletResponse.setHeader(HttpHeader.UPLOAD_METADATA, info.getEncodedMetadata());
            }

            uploadStorageService.copyUploadTo(info, servletResponse.getOutputStream());
        }

        servletResponse.setStatus(HttpServletResponse.SC_OK);
    }
}
