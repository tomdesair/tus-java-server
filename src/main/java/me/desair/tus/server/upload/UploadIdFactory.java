package me.desair.tus.server.upload;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

public class UploadIdFactory {

    private String uploadURI = "/";

    public void setUploadURI(String uploadURI) {
        Validate.notNull(uploadURI, "The upload URI cannot be null");
        this.uploadURI = uploadURI;
    }

    public UUID readUploadId(String url) {
        String pathId = StringUtils.substringAfter(url, uploadURI + (StringUtils.endsWith(uploadURI, "/") ? "" : "/"));
        UUID id = null;

        if (StringUtils.isNotBlank(pathId)) {
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

    public synchronized UUID createId() {
        return UUID.randomUUID();
    }
}
