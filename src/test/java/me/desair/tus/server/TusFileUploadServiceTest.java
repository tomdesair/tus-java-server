package me.desair.tus.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

import java.io.IOException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import org.junit.Test;

public class TusFileUploadServiceTest {

  @Test
  public void testAcquireUploadLockInterrupted() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"));

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    // Interrupt the thread to trigger InterruptedException during sleep
    Thread.currentThread().interrupt();

    try {
      service.acquireUploadLock(HttpMethod.HEAD, "/files/test");
      fail("Expected IOException due to thread interruption");
    } catch (IOException e) {
      // Clear interrupted flag so it doesn't leak to other tests
      Thread.interrupted();
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void testAcquireUploadLockFallback() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);

    // We throw exception 25 times and then succeed.
    // To avoid waiting 5 seconds (25 * 200ms) in the test, we mock Thread.sleep by interrupting
    // inside the mock,
    // but wait, mockLockingService doesn't run sleep. Sleep runs in the service itself.
    // Instead of doing 25 times which takes 5 seconds, let's just do it. 5 seconds is perfectly
    // fine for a fallback test.
    var stubbing = when(mockLockingService.lockUploadByUri(anyString()));
    for (int i = 0; i < 25; i++) {
      stubbing = stubbing.thenThrow(new UploadAlreadyLockedException("Locked"));
    }
    stubbing.thenReturn(mockLock);

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    UploadLock lock = service.acquireUploadLock(HttpMethod.HEAD, "/files/test");
    assertNotNull(lock);
  }
}
