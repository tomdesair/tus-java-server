package me.desair.tus.server.upload;

import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

public class UploadInfo {

    private Long offset;
    private String encodedMetadata;
    private Long length;
    private UUID id;

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
     * - the total length matches the current offset
     * @return true if the upload is still in progress, false otherwise
     */
    public boolean isUploadInProgress() {
        return length == null || offset.equals(length);
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
