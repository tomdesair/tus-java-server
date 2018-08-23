package me.desair.tus.server.upload;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

public class UploadIdFactory {

    private String uploadURI = "/";
    private Pattern uploadUriPattern = null;

    public void setUploadURI(String uploadURI) {
        Validate.notNull(uploadURI, "The upload URI pattern cannot be null");
        this.uploadURI = uploadURI;
        this.uploadUriPattern = null;
    }

    public UUID readUploadId(String url) {
        Matcher uploadUriMatcher = getUploadUriPattern().matcher(StringUtils.trimToEmpty(url));
        String pathId = uploadUriMatcher.replaceFirst("");
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

    private Pattern getUploadUriPattern() {
        if (uploadUriPattern == null) {
            //We will extract the upload ID's by removing the upload URI from the start of the request URI
            uploadUriPattern = Pattern.compile("^.*"
                    + uploadURI
                    + (StringUtils.endsWith(uploadURI, "/") ? "" : "/?"));
        }
        return uploadUriPattern;
    }
}
