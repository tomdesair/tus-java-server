package me.desair.tus.server.upload;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * Class that contains all metadata on an upload process. This class also holds the metadata
 * provided by the client when creating the upload.
 */
public class UploadInfo implements Serializable {

  private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
  private static List<String> fileNameKeys = Arrays.asList("filename", "name");
  private static List<String> mimeTypeKeys = Arrays.asList("mimetype", "filetype", "type");

  private UploadType uploadType;
  private Long offset;
  private String encodedMetadata;
  private Long length;
  private UploadId id;
  private String ownerKey;
  private Long creationTimestamp;
  private String creatorIpAddresses;
  private Long expirationTimestamp;
  private List<String> concatenationPartIds;
  private String uploadConcatHeaderValue;

  /** Default constructor to use if an upload is created without HTTP request. */
  public UploadInfo() {
    creationTimestamp = getCurrentTime();
    offset = 0L;
    encodedMetadata = null;
    length = null;
  }

  /**
   * Constructor to use if the upload is created using an HTTP request (which is usually the case).
   *
   * @param servletRequest The HTTP request that creates the new upload
   */
  public UploadInfo(HttpServletRequest servletRequest) {
    this();
    creatorIpAddresses = Utils.buildRemoteIpList(servletRequest);
  }

  /**
   * The current byte offset of the bytes that already have been stored for this upload on the
   * server. The offset is the position where the next newly received byte should be stored. This
   * index is zero-based.
   *
   * @return The offset where the next new byte will be written
   */
  public Long getOffset() {
    return offset;
  }

  /**
   * Set the position where the next newly received byte should be stored. This index is zero-based.
   *
   * @param offset The offset where the next new byte should be written
   */
  public void setOffset(Long offset) {
    this.offset = offset;
  }

  /**
   * Get the encoded Tus metadata string as it was provided by the Tus client at creation of the
   * upload. The encoded metadata string consists of one or more comma-separated key-value pairs
   * where the key is ASCII encoded and the value Base64 encoded. See
   * https://tus.io/protocols/resumable-upload.html#upload-metadata
   *
   * @return The encoded metadata string as received from the client
   */
  public String getEncodedMetadata() {
    return encodedMetadata;
  }

  /**
   * Set the encoded Tus metadata string as it was provided by the Tus client at creation of the
   * upload. The encoded metadata string consists of one or more comma-separated key-value pairs
   * where the key is ASCII encoded and the value Base64 encoded. See
   * https://tus.io/protocols/resumable-upload.html#upload-metadata
   */
  public void setEncodedMetadata(String encodedMetadata) {
    this.encodedMetadata = encodedMetadata;
  }

  /**
   * Get the decoded metadata map provided by the client based on the encoded Tus metadata string
   * received on creation of the upload. The encoded metadata string consists of one or more
   * comma-separated key-value pairs where the key is ASCII encoded and the value Base64 encoded.
   * The key and value MUST be separated by a space. See
   * https://tus.io/protocols/resumable-upload.html#upload-metadata
   *
   * @return The encoded metadata string as received from the client
   */
  public Map<String, String> getMetadata() {
    Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String valuePair : splitToArray(encodedMetadata, ",")) {
      String[] keyValue = splitToArray(valuePair, "\\s");
      String key = null;
      String value = null;
      if (keyValue.length > 0) {
        key = StringUtils.trimToEmpty(keyValue[0]);

        // Skip any blank values
        int i = 1;
        while (keyValue.length > i && StringUtils.isBlank(keyValue[i])) {
          i++;
        }

        if (keyValue.length > i) {
          value = decode(keyValue[i]);
        }

        metadata.put(key, value);
      }
    }
    return metadata;
  }

  /**
   * Check if the client provided any metadata when creating this upload.
   *
   * @return True if metadata is present, false otherwise
   */
  public boolean hasMetadata() {
    return StringUtils.isNotBlank(encodedMetadata);
  }

  /**
   * Get the total length of the byte array that the client wants to upload. This value is provided
   * by the client when creating the upload (POST) or when uploading a new set of bytes (PATCH).
   *
   * @return The number of bytes that the client specified he will upload
   */
  public Long getLength() {
    return length;
  }

  /**
   * Set the total length of the byte array that the client wants to upload. The client can provided
   * this value when creating the upload (POST) or when uploading a new set of bytes (PATCH).
   *
   * @param length The number of bytes that the client specified he will upload
   */
  public void setLength(Long length) {
    this.length = (length != null && length > 0 ? length : null);
  }

  /**
   * Check if the client already provided a total upload length.
   *
   * @return True if the total upload length is known, false otherwise
   */
  public boolean hasLength() {
    return length != null;
  }

  /**
   * An upload is still in progress: - as long as we did not receive information on the total length
   * (see {@link UploadInfo#getLength()}) - the total length does not match the current offset.
   *
   * @return true if the upload is still in progress, false otherwise
   */
  public boolean isUploadInProgress() {
    return length == null || !offset.equals(length);
  }

  /**
   * Set the unique identifier of this upload process The unique identifier is represented by a
   * {@link UploadId} instance.
   *
   * @param id The unique identifier to use
   */
  public void setId(UploadId id) {
    this.id = id;
  }

  /**
   * Get the unique identifier of this upload process The unique identifier is represented by a
   * {@link UploadId} instance.
   *
   * @return The unique upload identifier of this upload
   */
  public UploadId getId() {
    return id;
  }

  /**
   * Set the owner key for this upload. This key uniquely identifies the owner of the uploaded
   * bytes. The user of this library is free to interpret the meaning of "owner". This can be a user
   * ID, a company division, a group of users, a tenant...
   *
   * @param ownerKey The owner key to assign to this upload
   */
  public void setOwnerKey(String ownerKey) {
    this.ownerKey = ownerKey;
  }

  /**
   * Get the owner key for this upload. This key uniquely identifies the owner of the uploaded
   * bytes. The user of this library is free to interpret the meaning of "owner". This can be a user
   * ID, a company division, a group of users, a tenant...
   *
   * @return The unique identifying key of the owner of this upload
   */
  public String getOwnerKey() {
    return ownerKey;
  }

  /**
   * Indicates the timestamp after which the upload expires in milliseconds since January 1, 1970,
   * 00:00:00 GMT.
   *
   * @return The expiration timestamp in milliseconds
   */
  public Long getExpirationTimestamp() {
    return expirationTimestamp;
  }

  /**
   * Calculate the expiration timestamp based on the provided expiration period.
   *
   * @param expirationPeriod The period the upload should remain valid
   */
  public void updateExpiration(long expirationPeriod) {
    expirationTimestamp = getCurrentTime() + expirationPeriod;
  }

  /**
   * The timestamp this upload was created in number of milliseconds since January 1, 1970, 00:00:00
   * GMT.
   *
   * @return Creation timestamp of this upload object
   */
  public Long getCreationTimestamp() {
    return creationTimestamp;
  }

  /**
   * Get the ip-addresses that were involved when this upload was created. The returned value is a
   * comma-separated list based on the remote address of the request and the X-Forwareded-For
   * header. The list is constructed as "client, proxy1, proxy2".
   *
   * @return A comma-separated list of ip-addresses
   */
  public String getCreatorIpAddresses() {
    return creatorIpAddresses;
  }

  /**
   * Return the type of this upload. An upload can have types specified in {@link UploadType}. The
   * type of an upload depends on the Tus concatenation extension:
   * https://tus.io/protocols/resumable-upload.html#concatenation
   *
   * @return The type of this upload as specified in {@link UploadType}
   */
  public UploadType getUploadType() {
    return uploadType;
  }

  /**
   * Set the type of this upload. An upload can have types specified in {@link UploadType}. The type
   * of an upload depends on the Tus concatenation extension:
   * https://tus.io/protocols/resumable-upload.html#concatenation
   *
   * @param uploadType The type to set on this upload
   */
  public void setUploadType(UploadType uploadType) {
    this.uploadType = uploadType;
  }

  /**
   * Set the list of upload identifiers of which this upload is composed of.
   *
   * @param concatenationPartIds The list of child upload identifiers
   */
  public void setConcatenationPartIds(List<String> concatenationPartIds) {
    this.concatenationPartIds = concatenationPartIds;
  }

  /**
   * Get the list of upload identifiers of which this upload is composed of.
   *
   * @return The list of child upload identifiers
   */
  public List<String> getConcatenationPartIds() {
    return concatenationPartIds;
  }

  /**
   * Set the original value of the "Upload-Concat" HTTP header that was provided by the client.
   *
   * @param uploadConcatHeaderValue The original value of the "Upload-Concat" HTTP header
   */
  public void setUploadConcatHeaderValue(String uploadConcatHeaderValue) {
    this.uploadConcatHeaderValue = uploadConcatHeaderValue;
  }

  /**
   * Get the original value of the "Upload-Concat" HTTP header that was provided by the client.
   *
   * @return The original value of the "Upload-Concat" HTTP header
   */
  public String getUploadConcatHeaderValue() {
    return uploadConcatHeaderValue;
  }

  /**
   * Try to guess the filename of the uploaded data. If we cannot guess the name we fall back to the
   * ID. <br>
   * NOTE: This is only a guess, there are no guarantees that the return value is correct
   *
   * @return A potential file name
   */
  public String getFileName() {
    Map<String, String> metadata = getMetadata();
    for (String fileNameKey : fileNameKeys) {
      if (metadata.containsKey(fileNameKey)) {
        return metadata.get(fileNameKey);
      }
    }

    return getId().toString();
  }

  /**
   * Try to guess the mime-type of the uploaded data. <br>
   * NOTE: This is only a guess, there are no guarantees that the return value is correct
   *
   * @return A potential file name
   */
  public String getFileMimeType() {
    Map<String, String> metadata = getMetadata();
    for (String fileNameKey : mimeTypeKeys) {
      if (metadata.containsKey(fileNameKey)) {
        return metadata.get(fileNameKey);
      }
    }

    return APPLICATION_OCTET_STREAM;
  }

  /**
   * Check if this upload is expired.
   *
   * @return True if the upload is expired, false otherwise
   */
  public boolean isExpired() {
    return expirationTimestamp != null && expirationTimestamp < getCurrentTime();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof UploadInfo)) {
      return false;
    }

    UploadInfo that = (UploadInfo) o;

    return new EqualsBuilder()
        .append(getUploadType(), that.getUploadType())
        .append(getOffset(), that.getOffset())
        .append(getEncodedMetadata(), that.getEncodedMetadata())
        .append(getLength(), that.getLength())
        .append(getId(), that.getId())
        .append(getOwnerKey(), that.getOwnerKey())
        .append(getCreatorIpAddresses(), that.getCreatorIpAddresses())
        .append(getExpirationTimestamp(), that.getExpirationTimestamp())
        .append(getConcatenationPartIds(), that.getConcatenationPartIds())
        .append(getUploadConcatHeaderValue(), that.getUploadConcatHeaderValue())
        .isEquals();
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(17, 37)
        .append(getUploadType())
        .append(getOffset())
        .append(getEncodedMetadata())
        .append(getLength())
        .append(getId())
        .append(getOwnerKey())
        .append(getCreatorIpAddresses())
        .append(getExpirationTimestamp())
        .append(getConcatenationPartIds())
        .append(getUploadConcatHeaderValue())
        .toHashCode();
  }

  /** Get the current time in the number of milliseconds since January 1, 1970, 00:00:00 GMT. */
  protected long getCurrentTime() {
    return new Date().getTime();
  }

  private String[] splitToArray(String value, String separatorRegex) {
    if (StringUtils.isBlank(value)) {
      return new String[0];
    } else {
      return StringUtils.trimToEmpty(value).split(separatorRegex);
    }
  }

  private String decode(String encodedValue) {
    if (encodedValue == null) {
      return null;
    } else {
      return new String(Base64.decodeBase64(encodedValue), StandardCharsets.UTF_8);
    }
  }
}
