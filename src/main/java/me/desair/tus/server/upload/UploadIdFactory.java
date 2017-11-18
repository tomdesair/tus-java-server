package me.desair.tus.server.upload;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

public class UploadIdFactory {

    private String uploadURI = "/";

    public void setUploadURI(final String uploadURI) {
        Validate.notNull(uploadURI, "The upload URI cannot be null");
        this.uploadURI = uploadURI;
    }

    public UUID readUploadId(final HttpServletRequest request) {
        String pathId = StringUtils.substringAfter(request.getRequestURI(), uploadURI);
        UUID id = null;

        if(StringUtils.isNotBlank(pathId)) {
            try {
                id = UUID.fromString(pathId);
            } catch (IllegalArgumentException ex) {
                id = null;
            }
        }

        return id;
    }

    public String getUploadURI() {
        return uploadURI;
    }

    public UUID createId() {
        return UUID.randomUUID();
    }
}
