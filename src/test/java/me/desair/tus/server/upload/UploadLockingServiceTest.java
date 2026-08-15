package me.desair.tus.server.upload;

import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import me.desair.tus.server.exception.TusException;
import org.junit.Test;

public class UploadLockingServiceTest {

  @Test
  public void testDefaultMethods() throws Exception {
    UploadLockingService dummyService =
        new UploadLockingService() {
          @Override
          public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {
            return null;
          }

          @Override
          public void cleanupStaleLocks() throws IOException {}

          @Override
          public boolean isLocked(UploadId id) {
            return false;
          }

          @Override
          public void setIdFactory(UploadIdFactory idFactory) {}
        };

    // Test default methods for coverage
    dummyService.registerInputStream("/test/upload/123", new ByteArrayInputStream(new byte[0]));
    dummyService.requestLockRelease("/test/upload/123");
    dummyService.close();

    assertFalse(dummyService.isLocked(new UploadId("123")));
  }
}
