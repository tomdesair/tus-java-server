package me.desair.tus.server.upload;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.tuple.Pair;

public class UploadInfo implements Serializable {

    private Long offset;
    private String encodedMetadata;
    private Long length;
    private UUID id;
    private String ownerKey;

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

    public List<Pair<String, String>> getMetadata() {
        List<Pair<String, String>> metadata = new LinkedList<>();
        for (String valuePair : splitToArray(encodedMetadata, ",")) {
            String[] keyValue = splitToArray(valuePair, "\\s");
            String key = null;
            String value = null;
            if(keyValue.length > 0) {
                key = StringUtils.trimToEmpty(keyValue[0]);
                if(keyValue.length > 1) {
                    value = decode(keyValue[1]);
                }

                metadata.add(Pair.of(key, value));
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
        this.length = length;
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
                .toHashCode();
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
