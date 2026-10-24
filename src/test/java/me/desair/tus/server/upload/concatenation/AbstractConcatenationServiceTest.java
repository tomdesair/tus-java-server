package me.desair.tus.server.upload.concatenation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AbstractConcatenationService}. */
public class AbstractConcatenationServiceTest {

  private UploadStorageService mockStorageService;
  private TestConcatenationService concatService;

  private static class TestConcatenationService extends AbstractConcatenationService {
    public TestConcatenationService(UploadStorageService uploadStorageService) {
      super(uploadStorageService);
    }

    @Override
    public void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {}

    @Override
    public InputStream getConcatenatedBytes(UploadInfo uploadInfo)
        throws IOException, UploadNotFoundException {
      return null;
    }
  }

  @Before
  public void setUp() {
    mockStorageService = mock(UploadStorageService.class);
    concatService = new TestConcatenationService(mockStorageService);
  }

  @Test(expected = NullPointerException.class)
  public void testNullStorageServiceThrowsNpe() {
    new TestConcatenationService(null);
  }

  @Test
  public void testGetPartialUploadsEmptyOrNull() throws Exception {
    assertTrue(concatService.getPartialUploads(null).isEmpty());

    UploadInfo info = new UploadInfo();
    assertTrue(concatService.getPartialUploads(info).isEmpty());

    info.setConcatenationPartIds(Collections.emptyList());
    assertTrue(concatService.getPartialUploads(info).isEmpty());
  }

  @Test
  public void testGetPartialUploadsSuccess() throws Exception {
    UploadInfo parent = new UploadInfo();
    parent.setId(new UploadId("parent-1"));
    parent.setOwnerKey("tenant-A");
    parent.setConcatenationPartIds(Arrays.asList("/files/part-1", "/files/part-2"));

    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId("part-1"));
    child1.setOwnerKey("tenant-A");

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId("part-2"));
    child2.setOwnerKey("tenant-A");

    when(mockStorageService.getUploadInfo("/files/part-1", "tenant-A")).thenReturn(child1);
    when(mockStorageService.getUploadInfo("/files/part-2", "tenant-A")).thenReturn(child2);

    List<UploadInfo> result = concatService.getPartialUploads(parent);
    assertEquals(2, result.size());
    assertEquals(child1, result.get(0));
    assertEquals(child2, result.get(1));
  }

  @Test(expected = UploadNotFoundException.class)
  public void testGetPartialUploadsMissingChildThrows() throws Exception {
    UploadInfo parent = new UploadInfo();
    parent.setOwnerKey("tenant-A");
    parent.setConcatenationPartIds(Collections.singletonList("/files/missing"));

    when(mockStorageService.getUploadInfo("/files/missing", "tenant-A")).thenReturn(null);
    concatService.getPartialUploads(parent);
  }

  @Test(expected = UploadNotFoundException.class)
  public void testGetPartialUploadsOwnerMismatchThrows() throws Exception {
    UploadInfo parent = new UploadInfo();
    parent.setId(new UploadId("parent-1"));
    parent.setOwnerKey("tenant-A");
    parent.setConcatenationPartIds(Collections.singletonList("/files/part-1"));

    UploadInfo child = new UploadInfo();
    child.setId(new UploadId("part-1"));
    child.setOwnerKey("tenant-B");

    when(mockStorageService.getUploadInfo("/files/part-1", "tenant-A")).thenReturn(child);
    concatService.getPartialUploads(parent);
  }

  @Test
  public void testCalculateTotalLength() {
    assertNull(concatService.calculateTotalLength(null));
    assertNull(concatService.calculateTotalLength(Collections.emptyList()));

    UploadInfo part1 = new UploadInfo();
    part1.setLength(100L);
    UploadInfo part2 = new UploadInfo();
    part2.setLength(250L);

    assertEquals(
        Long.valueOf(350L), concatService.calculateTotalLength(Arrays.asList(part1, part2)));

    // Unknown length in part returns null
    UploadInfo partWithoutLength = new UploadInfo();
    assertNull(concatService.calculateTotalLength(Arrays.asList(part1, partWithoutLength)));
  }

  @Test
  public void testCheckAllCompleted() throws Exception {
    assertFalse(concatService.checkAllCompleted(null, null));
    assertFalse(concatService.checkAllCompleted(null, Collections.emptyList()));

    UploadInfo completedPart1 = new UploadInfo();
    completedPart1.setId(new UploadId("p1"));
    completedPart1.setLength(100L);
    completedPart1.setOffset(100L);

    UploadInfo inProgressPart = new UploadInfo();
    inProgressPart.setId(new UploadId("p2"));
    inProgressPart.setLength(200L);
    inProgressPart.setOffset(50L);

    // In-progress part returns false
    assertFalse(
        concatService.checkAllCompleted(null, Arrays.asList(completedPart1, inProgressPart)));

    // All completed without expiration
    assertTrue(concatService.checkAllCompleted(null, Collections.singletonList(completedPart1)));

    // All completed with expiration period updates storage
    assertTrue(
        concatService.checkAllCompleted(3600000L, Collections.singletonList(completedPart1)));
    verify(mockStorageService).update(completedPart1);

    // Storage update exception handled gracefully
    doThrow(new UploadNotFoundException("gone")).when(mockStorageService).update(completedPart1);
    assertTrue(
        concatService.checkAllCompleted(3600000L, Collections.singletonList(completedPart1)));
  }

  @Test
  public void testCloseDoesNotThrow() throws Exception {
    concatService.close();
  }
}
