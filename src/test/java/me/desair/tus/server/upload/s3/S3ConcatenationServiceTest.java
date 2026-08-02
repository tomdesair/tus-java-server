package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertNotNull;

import java.nio.file.Paths;
import java.util.Arrays;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.s3.S3Client;

public class S3ConcatenationServiceTest {

  private S3Client s3Client;
  private UploadStorageService storageService;
  private S3ConcatenationService concatenationService;

  @Before
  public void setUp() {
    s3Client = Mockito.mock(S3Client.class);
    storageService = Mockito.mock(UploadStorageService.class);
    concatenationService =
        new S3ConcatenationService(
            s3Client,
            "test-bucket",
            "tus-uploads/",
            storageService,
            Paths.get(System.getProperty("java.io.tmpdir")));
  }

  @Test
  public void testGetPartialUploads() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);

    UploadInfo p2 = new UploadInfo();
    p2.setId(new UploadId("part-2"));
    p2.setLength(10L * 1024 * 1024);

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);
    Mockito.when(storageService.getUploadInfo("/part-2", "owner-1")).thenReturn(p2);

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1", "/part-2"));

    java.util.List<UploadInfo> partials = concatenationService.getPartialUploads(finalUpload);
    assertNotNull(partials);
    assertEquals(2, partials.size());
  }

  private void assertEquals(int expected, int actual) {
    org.junit.Assert.assertEquals(expected, actual);
  }
}
