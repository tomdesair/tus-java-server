package me.desair.tus.server.upload;

import java.io.Serializable;
import org.apache.commons.lang3.StringUtils;

/**
 * Alternative {@link UploadIdFactory} implementation that uses the current system time to generate
 * ID's. Since time is not unique, this upload ID factory should not be used in busy, clustered
 * production systems.
 */
public class TimeBasedUploadIdFactory extends UploadIdFactory {

  @Override
  protected Serializable getIdValueIfValid(String extractedUrlId) {
    Long id = null;

    if (StringUtils.isNotBlank(extractedUrlId)) {
      try {
        id = Long.parseLong(extractedUrlId);
      } catch (NumberFormatException ex) {
        id = null;
      }
    }

    return id;
  }

  @Override
  public synchronized UploadId createId() {
    return new UploadId(System.currentTimeMillis());
  }
}
