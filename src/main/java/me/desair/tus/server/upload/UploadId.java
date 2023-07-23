package me.desair.tus.server.upload;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Objects;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The unique identifier of an upload process in the tus protocol */
public class UploadId implements Serializable {

  private static final String UPLOAD_ID_CHARSET = "UTF-8";
  private static final Logger log = LoggerFactory.getLogger(UploadId.class);

  private String urlSafeValue;
  private Serializable originalObject;

  /**
   * Create a new {@link UploadId} instance based on the provided object using it's toString method.
   *
   * @param inputObject The object to use for constructing the ID
   */
  public UploadId(Serializable inputObject) {
    String inputValue = (inputObject == null ? null : inputObject.toString());
    Validate.notBlank(inputValue, "The upload ID value cannot be blank");

    this.originalObject = inputObject;
    URLCodec codec = new URLCodec();
    // Check if value is not encoded already
    try {
      if (inputValue != null && inputValue.equals(codec.decode(inputValue, UPLOAD_ID_CHARSET))) {
        this.urlSafeValue = codec.encode(inputValue, UPLOAD_ID_CHARSET);
      } else {
        // value is already encoded, use as is
        this.urlSafeValue = inputValue;
      }
    } catch (DecoderException | UnsupportedEncodingException e) {
      log.warn("Unable to URL encode upload ID value", e);
      this.urlSafeValue = inputValue;
    }
  }

  /**
   * The original input object that was provided when constructing this upload ID
   *
   * @return The original object used to create this ID
   */
  public Serializable getOriginalObject() {
    return this.originalObject;
  }

  @Override
  public String toString() {
    return urlSafeValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof UploadId)) {
      return false;
    }

    UploadId uploadId = (UploadId) o;
    // Upload IDs with the same URL-safe value should be considered equal
    return Objects.equals(urlSafeValue, uploadId.urlSafeValue);
  }

  @Override
  public int hashCode() {
    // Upload IDs with the same URL-safe value should be considered equal
    return Objects.hash(urlSafeValue);
  }
}
