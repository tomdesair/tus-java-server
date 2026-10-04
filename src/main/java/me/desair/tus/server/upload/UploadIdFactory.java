package me.desair.tus.server.upload;

import java.io.Serializable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;

/**
 * Interface for a factory that can create unique upload IDs. This factory can also parse the upload
 * identifier from a given upload URL.
 */
public abstract class UploadIdFactory {

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private String uploadUri = "/";
  // Read and write operations on uploadUri and uploadUriPattern are guarded by a
  // ReentrantReadWriteLock.
  // This enables concurrent lock-free reads for high performance while ensuring thread-safe lazy
  // pattern compilation and state mutation without triggering Sonar S3077 warnings.
  private Pattern uploadUriPattern = null;

  /**
   * Set the URI or absolute URL under which the main tus upload endpoint is hosted. Optionally,
   * this URI may contain regex parameters in order to support endpoints that contain URL
   * parameters, for example /users/[0-9]+/files/upload or
   * https://upload.example.com/users/[0-9]+/files/upload
   *
   * @param uploadUri The URI or URL of the main tus upload endpoint
   */
  public void setUploadUri(String uploadUri) {
    Validate.notBlank(uploadUri, "The upload URI pattern cannot be blank");
    Validate.isTrue(
        Strings.CS.startsWith(uploadUri, "/")
            || Strings.CS.startsWith(uploadUri, "http://")
            || Strings.CS.startsWith(uploadUri, "https://"),
        "The upload URI should start with /, http://, or https://");
    Validate.isTrue(!Strings.CS.endsWith(uploadUri, "$"), "The upload URI should not end with $");

    lock.writeLock().lock();
    try {
      this.uploadUri = uploadUri;
      this.uploadUriPattern = null;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Return the URI of the main tus upload endpoint. Note that this value possibly contains regex
   * parameters.
   *
   * @return The URI of the main tus upload endpoint.
   */
  public String getUploadUri() {
    lock.readLock().lock();
    try {
      return uploadUri;
    } finally {
      lock.readLock().unlock();
    }
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
    lock.readLock().lock();
    try {
      if (uploadUriPattern != null) {
        return uploadUriPattern;
      }
    } finally {
      lock.readLock().unlock();
    }

    lock.writeLock().lock();
    try {
      if (uploadUriPattern == null) {
        // Extract upload IDs by removing upload URI from start of request URI.
        // Write lock ensures single pattern compilation across concurrent threads.
        String path = Utils.extractUriPath(uploadUri);
        uploadUriPattern =
            Pattern.compile("^.*" + path + (Strings.CS.endsWith(path, "/") ? "" : "/?"));
      }
      return uploadUriPattern;
    } finally {
      lock.writeLock().unlock();
    }
  }
}
