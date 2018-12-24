package me.desair.tus.server.upload;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

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
    private List<String> concatenationParts;
    private String uploadConcatHeaderValue;

    public UploadInfo() {
        creationTimestamp = getCurrentTime();
        offset = 0L;
        encodedMetadata = null;
        length = null;
    }

    public UploadInfo(HttpServletRequest servletRequest) {
        this();
        creatorIpAddresses = Utils.buildRemoteIpList(servletRequest);
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public String getEncodedMetadata() {
        return encodedMetadata;
    }

    public void setEncodedMetadata(String encodedMetadata) {
        this.encodedMetadata = encodedMetadata;
    }

    public Map<String, String> getMetadata() {
        Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String valuePair : splitToArray(encodedMetadata, ",")) {
            String[] keyValue = splitToArray(valuePair, "\\s");
            String key = null;
            String value = null;
            if (keyValue.length > 0) {
                key = StringUtils.trimToEmpty(keyValue[0]);

                //Skip any blank values
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

    public boolean hasMetadata() {
        return StringUtils.isNotBlank(encodedMetadata);
    }

    public Long getLength() {
        return length;
    }

    public void setLength(Long length) {
        this.length = (length != null && length > 0 ? length : null);
    }

    public boolean hasLength() {
        return length != null;
    }

    /**
     * An upload is still in progress:
     * - as long as we did not receive information on the total length
     * - the total length does not match the current offset
     * @return true if the upload is still in progress, false otherwise
     */
    public boolean isUploadInProgress() {
        return length == null || !offset.equals(length);
    }

    public void setId(UploadId id) {
        this.id = id;
    }

    public UploadId getId() {
        return id;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public Long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void updateExpiration(long expirationPeriod) {
        expirationTimestamp = getCurrentTime() + expirationPeriod;
    }

    /**
     * The timestamp this upload was created in number of milliseconds since January 1, 1970, 00:00:00 GMT
     * @return Creation timestamp of this upload object
     */
    public Long getCreationTimestamp() {
        return creationTimestamp;
    }

    /** TODO UNIT TEST
     * Get the ip-addresses that were involved when this upload was created.
     * The returned value is a comma-separated list based on the remote address of the request and the
     * X-Forwareded-For header. The list is constructed as "client, proxy1, proxy2".
     * @return A comma-separated list of ip-addresses
     */
    public String getCreatorIpAddresses() {
        return creatorIpAddresses;
    }

    public UploadType getUploadType() {
        return uploadType;
    }

    public void setUploadType(UploadType uploadType) {
        this.uploadType = uploadType;
    }

    public void setConcatenationParts(List<String> concatenationParts) {
        this.concatenationParts = concatenationParts;
    }

    public List<String> getConcatenationParts() {
        return concatenationParts;
    }

    public void setUploadConcatHeaderValue(String uploadConcatHeaderValue) {
        this.uploadConcatHeaderValue = uploadConcatHeaderValue;
    }

    public String getUploadConcatHeaderValue() {
        return uploadConcatHeaderValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        UploadInfo info = (UploadInfo) o;

        return new EqualsBuilder()
                .append(getOffset(), info.getOffset())
                .append(getEncodedMetadata(), info.getEncodedMetadata())
                .append(getLength(), info.getLength())
                .append(getId(), info.getId())
                .append(getOwnerKey(), info.getOwnerKey())
                .append(getExpirationTimestamp(), info.getExpirationTimestamp())
                .append(getUploadType(), info.getUploadType())
                .append(getUploadConcatHeaderValue(), info.getUploadConcatHeaderValue())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(getOffset())
                .append(getEncodedMetadata())
                .append(getLength())
                .append(getId())
                .append(getOwnerKey())
                .append(getExpirationTimestamp())
                .append(getUploadType())
                .append(getUploadConcatHeaderValue())
                .toHashCode();
    }

    /**
     * Try to guess the filename of the uploaded data. If we cannot guess the name
     * we fall back to the ID.
     * <p/>
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
     * Try to guess the mime-type of the uploaded data.
     * <p/>
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
     * Check if this upload is expired
     * @return True if the upload is expired, false otherwise
     */
    public boolean isExpired() {
        return expirationTimestamp != null && expirationTimestamp < getCurrentTime();
    }

    /**
     * Get the current time in the number of milliseconds since January 1, 1970, 00:00:00 GMT
     */
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
            return new String(Base64.decodeBase64(encodedValue), Charset.forName("UTF-8"));
        }
    }
}
