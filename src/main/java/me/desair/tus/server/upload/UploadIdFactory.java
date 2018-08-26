package me.desair.tus.server.upload;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

/**
 * Factory to create unique upload IDs. This factory can also parse the upload identifier
 * from a given upload URL.
 */
public class UploadIdFactory {

    private String uploadURI = "/";
    private Pattern uploadUriPattern = null;

    /**
     * Set the URI under which the main tus upload endpoint is hosted.
     * Optionally, this URI may contain regex parameters in order to support endpoints that contain
     * URL parameters, for example /users/[0-9]+/files/upload
     *
     * @param uploadURI The URI of the main tus upload endpoint
     */
    public void setUploadURI(String uploadURI) {
        Validate.notBlank(uploadURI, "The upload URI pattern cannot be blank");
        Validate.isTrue(StringUtils.startsWith(uploadURI, "/"), "The upload URI should start with /");
        Validate.isTrue(!StringUtils.endsWith(uploadURI, "$"), "The upload URI should not end with $");
        this.uploadURI = uploadURI;
        this.uploadUriPattern = null;
    }

    /**
     * Read the upload identifier from the given URL.
     * <p/>
     * Clients will send requests to upload URLs or provided URLs of completed uploads. This method is able to
     * parse those URLs and provide the user with the corresponding upload ID.
     *
     * @param url The URL provided by the client
     * @return The corresponding Upload identifier
     */
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

    /**
     * Return the URI of the main tus upload endpoint. Note that this value possibly contains regex parameters.
     * @return The URI of the main tus upload endpoint.
     */
    public String getUploadURI() {
        return uploadURI;
    }

    /**
     * Create a new unique upload ID
     * @return A new unique upload ID
     */
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
