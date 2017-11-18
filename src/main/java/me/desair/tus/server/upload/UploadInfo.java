package me.desair.tus.server.upload;

import org.apache.commons.lang3.StringUtils;

public class UploadInfo {

    private Long offset;
    private String metadata;
    private Long length;

    public Long getOffset() {
        return offset;
    }

    public void setOffset(final Long offset) {
        this.offset = offset;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(final String metadata) {
        this.metadata = metadata;
    }

    public boolean hasMetadata() {
        return StringUtils.isNotBlank(metadata);
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
}
