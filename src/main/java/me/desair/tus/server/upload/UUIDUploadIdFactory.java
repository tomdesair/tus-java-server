package me.desair.tus.server.upload;

import java.util.UUID;
import java.util.regex.Matcher;

import org.apache.commons.lang3.StringUtils;

/**
 * Factory to create unique upload IDs. This factory can also parse the upload identifier
 * from a given upload URL.
 */
public class UUIDUploadIdFactory extends UploadIdFactory {

    @Override
    public UploadId readUploadId(String url) {
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

        return id == null ? null : new UploadId(id.toString());
    }

    @Override
    public synchronized UploadId createId() {
        return new UploadId(UUID.randomUUID().toString());
    }

}
