package me.desair.tus.server.upload;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

/**
 * Interface for a factory that can create unique upload IDs. This factory can also parse the upload
 * identifier from a given upload URL.
 */
public abstract class UploadIdFactory {

  private String uploadUri = "/";
  private Pattern uploadUriPattern = null;

  /**
   * Set the URI under which the main tus upload endpoint is hosted. Optionally, this URI may
   * contain regex parameters in order to support endpoints that contain URL parameters, for example
   * /users/[0-9]+/files/upload
   *
   * @param uploadUri The URI of the main tus upload endpoint
   */
  public void setUploadUri(String uploadUri) {
    Validate.notBlank(uploadUri, "The upload URI pattern cannot be blank");
    Validate.isTrue(StringUtils.startsWith(uploadUri, "/"), "The upload URI should start with /");
    Validate.isTrue(!StringUtils.endsWith(uploadUri, "$"), "The upload URI should not end with $");
    this.uploadUri = uploadUri;
    this.uploadUriPattern = null;
  }

  /**
   * Return the URI of the main tus upload endpoint. Note that this value possibly contains regex
   * parameters.
   *
   * @return The URI of the main tus upload endpoint.
   */
  public String getUploadUri() {
    return uploadUri;
  }

  /**
   * Read the upload identifier from the given URL. <br>
   * Clients will send requests to upload URLs or provided URLs of completed uploads. This method is
   * able to parse those URLs and provide the user with the corresponding upload ID.
   *
   * @param url The URL provided by the client
   * @return The corresponding Upload identifier
   */
  public UploadId readUploadId(String url) {
    Matcher uploadUriMatcher = getUploadUriPattern().matcher(StringUtils.trimToEmpty(url));
    String pathId = uploadUriMatcher.replaceFirst("");

    Serializable idValue = null;
    if (StringUtils.isNotBlank(pathId)) {
      idValue = getIdValueIfValid(pathId);
    }

    return idValue == null ? null : new UploadId(idValue);
  }

  /**
   * Create a new unique upload ID.
   *
   * @return A new unique upload ID
   */
  public abstract UploadId createId();

  /**
   * Transform the extracted path ID value to a value to use for the upload ID object. If the
   * extracted value is not valid, null is returned
   *
   * @param extractedUrlId The ID extracted from the URL
   * @return Value to use in the UploadId object, null if the extracted URL value was not valid
   */
  protected abstract Serializable getIdValueIfValid(String extractedUrlId);

  /**
   * Build and retrieve the Upload URI regex pattern.
   *
   * @return A (cached) Pattern to match upload URI's
   */
  protected Pattern getUploadUriPattern() {
    if (uploadUriPattern == null) {
      // We will extract the upload ID's by removing the upload URI from the start of the
      // request URI
      uploadUriPattern =
          Pattern.compile("^.*" + uploadUri + (StringUtils.endsWith(uploadUri, "/") ? "" : "/?"));
    }
    return uploadUriPattern;
  }
}
