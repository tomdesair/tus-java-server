package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.MinUploadLengthNotReachedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class S3StorageServiceTest {

  private S3Client s3Client;
  private S3StorageService storageService;

  @Before
  public void setUp() {
    s3Client = mock(S3Client.class);
    storageService = new S3StorageService(s3Client, "test-bucket");
  }

  @Test
  public void testCreateUpload() throws Exception {
    CreateMultipartUploadResponse response =
        CreateMultipartUploadResponse.builder().uploadId("mp-upload-123").build();
    when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(response);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1024L);

    UploadInfo created = storageService.create(info, "owner-1");

    assertNotNull(created);
    assertEquals("mp-upload-123", created.getStorageUploadId());
    assertEquals("owner-1", created.getOwnerKey());
    assertEquals(
        "tus-uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e", storageService.getS3ObjectKey(created));
  }

  @Test(expected = MaxUploadLengthExceededException.class)
  public void testAppendExceedsMaxUploadSize() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);

    String json = UploadInfoSerializer.serialize(info);
    ResponseInputStream<GetObjectResponse> stream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(json.getBytes())));

    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);

    storageService.setMaxUploadSize(500L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test(expected = MinUploadLengthNotReachedException.class)
  public void testAppendBelowMinSize() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);

    String json = UploadInfoSerializer.serialize(info);
    ResponseInputStream<GetObjectResponse> stream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(json.getBytes())));

    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);

    storageService.setMinSize(2000L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test
  public void testGetUploadInfoReturnsNullForMissingKey() throws Exception {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    UploadInfo result =
        storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertNull(result);
  }
}
