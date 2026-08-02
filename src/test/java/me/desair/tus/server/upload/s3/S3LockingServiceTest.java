package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadLock;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3LockingServiceTest {

  private S3Client s3Client;
  private S3LockingService lockingService;

  @Before
  public void setUp() {
    s3Client = Mockito.mock(S3Client.class);
    lockingService = new S3LockingService(s3Client, "test-bucket");
  }

  @Test
  public void testLockUploadByUriSuccess() throws Exception {
    Mockito.when(
            s3Client.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    UploadLock lock =
        lockingService.lockUploadByUri("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    assertNotNull(lock);
    lock.close();
  }

  @Test
  public void testLockUploadByUriInvalidUri() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/invalid-uri");
    assertNull(lock);
  }

  @Test
  public void testIsLockedReturnsFalseWhenMissing() {
    Mockito.when(
            s3Client.getObject(
                Mockito.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    boolean locked = lockingService.isLocked(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertFalse(locked);
  }
}
