package me.desair.tus.server.upload;

import java.util.regex.Matcher;

import org.apache.commons.lang3.StringUtils;

/**
 * Alternative {@link UploadIdFactory} implementation that uses the current system time to generate ID's.
 * Since time is not unique, this upload ID factory should not be used in busy, clustered production systems.
 * TODO UNIT TEST
 */
public class TimeBasedUploadIdFactory extends UploadIdFactory {

    @Override
    public UploadId readUploadId(String url) {
        Matcher uploadUriMatcher = getUploadUriPattern().matcher(StringUtils.trimToEmpty(url));
        String pathId = uploadUriMatcher.replaceFirst("");
        Long id = null;

        if (StringUtils.isNotBlank(pathId)) {
            try {
                id = Long.parseLong(pathId);
            } catch (NumberFormatException ex) {
                id = null;
            }
        }

        return id == null ? null : new UploadId(id.toString());
    }

    @Override
    public synchronized UploadId createId() {
        return new UploadId("" + System.currentTimeMillis());
    }

}
