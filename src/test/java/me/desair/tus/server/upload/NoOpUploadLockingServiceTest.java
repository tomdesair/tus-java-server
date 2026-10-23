package me.desair.tus.server.upload;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import org.junit.Test;

public class NoOpUploadLockingServiceTest {

  @Test
  public void testLockOperationsReturnNonNullLockThatClosesCleanly() throws Exception {
    NoOpUploadLockingService service = new NoOpUploadLockingService();

    try (UploadLock lockByUri = service.lockUploadByUri("/files/123")) {
      assertNotNull(lockByUri);
      assertNull(lockByUri.getUploadUri());
      lockByUri.release();
    }

    UploadId id = new UploadId("test-upload-id");
    try (UploadLock lockById = service.lockUpload(id)) {
      assertNotNull(lockById);
      assertNull(lockById.getUploadUri());
      lockById.release();
    }

    assertFalse(service.isLocked(id));
  }

  @Test
  public void testNoOpLifecycleAndRegistrationMethodsExecuteCleanly() throws Exception {
    NoOpUploadLockingService service = new NoOpUploadLockingService();
    UploadId id = new UploadId("test-id");

    // KISS: verifying method executes cleanly without throwing an exception
    service.cleanupStaleLocks();
    service.registerInputStream("/files/123", new ByteArrayInputStream(new byte[0]));
    service.requestLockRelease("/files/123");
    service.setIdFactory(new UuidUploadIdFactory());
    service.close();
  }
}
