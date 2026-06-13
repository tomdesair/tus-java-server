package me.desair.tus.server.upload;

import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import org.junit.Test;

public class UploadLockingServiceTest {

  @Test
  public void testDefaultMethods() {
    UploadLockingService service =
        new UploadLockingService() {
          @Override
          public UploadLock lockUploadByUri(String requestUri) {
            return null;
          }

          @Override
          public void cleanupStaleLocks() {}

          @Override
          public boolean isLocked(UploadId id) {
            return false;
          }

          @Override
          public void setIdFactory(UploadIdFactory idFactory) {}
        };

    // Verify default methods do not throw exceptions and act as no-ops
    service.registerInputStream("/files/test", new ByteArrayInputStream(new byte[0]));
    service.requestLockRelease("/files/test");
    assertNotNull(service);
  }
}
