package me.desair.tus.server.upload;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

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
    private UUID id;
    private String ownerKey;
    private Long expirationTimestamp;
    private List<UUID> concatenationParts;
    private String uploadConcatHeaderValue;

    public UploadInfo() {
        offset = 0l;
        encodedMetadata = null;
        length = null;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(final Long offset) {
        this.offset = offset;
    }

    public String getEncodedMetadata() {
        return encodedMetadata;
    }

    public void setEncodedMetadata(final String encodedMetadata) {
        this.encodedMetadata = encodedMetadata;
    }

    public Map<String, String> getMetadata() {
        Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String valuePair : splitToArray(encodedMetadata, ",")) {
            String[] keyValue = splitToArray(valuePair, "\\s");
            String key = null;
            String value = null;
            if(keyValue.length > 0) {
                key = StringUtils.trimToEmpty(keyValue[0]);

                //Skip any blank values
                int i = 1;
                while(keyValue.length > i && StringUtils.isBlank(keyValue[i])) {
                    i++;
                }

                if(keyValue.length > i) {
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

    public void setLength(final Long length) {
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

    public void setId(final UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setOwnerKey(final String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public Long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void updateExpiration(final long expirationPeriod) {
        expirationTimestamp = getCurrentTime() + expirationPeriod;
    }

    public UploadType getUploadType() {
        return uploadType;
    }

    public void setUploadType(final UploadType uploadType) {
        this.uploadType = uploadType;
    }

    public void setConcatenationParts(final List<UUID> concatenationParts) {
        this.concatenationParts = concatenationParts;
    }

    public List<UUID> getConcatenationParts() {
        return concatenationParts;
    }

    public void setUploadConcatHeaderValue(final String uploadConcatHeaderValue) {
        this.uploadConcatHeaderValue = uploadConcatHeaderValue;
    }

    public String getUploadConcatHeaderValue() {
        return uploadConcatHeaderValue;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;

        if (o == null || getClass() != o.getClass()) return false;

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
     *
     * NOTE: This is only a guess, there are no guarantees that the return value is correct
     *
     * @return A potential file name
     */
    public String getFileName() {
        Map<String, String> metadata = getMetadata();
        for (String fileNameKey : fileNameKeys) {
            if(metadata.containsKey(fileNameKey)) {
                return metadata.get(fileNameKey);
            }
        }

        return getId().toString();
    }

    /**
     * Try to guess the mime-type of the uploaded data.
     *
     * NOTE: This is only a guess, there are no guarantees that the return value is correct
     *
     * @return A potential file name
     */
    public String getFileMimeType() {
        Map<String, String> metadata = getMetadata();
        for (String fileNameKey : mimeTypeKeys) {
            if(metadata.containsKey(fileNameKey)) {
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

    private String[] splitToArray(final String value, final String separatorRegex) {
        if(StringUtils.isBlank(value)) {
            return new String[0];
        } else {
            return StringUtils.trimToEmpty(value).split(separatorRegex);
        }
    }

    private String decode(final String encodedValue) {
        return org.apache.commons.codec.binary.StringUtils.newStringUtf8(Base64.decodeBase64(encodedValue));
    }
}
