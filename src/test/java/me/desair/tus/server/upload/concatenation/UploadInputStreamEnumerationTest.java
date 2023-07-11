package me.desair.tus.server.upload.concatenation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.UUID;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class UploadInputStreamEnumerationTest {

  @Mock private UploadStorageService uploadStorageService;

  @Test
  public void hasMoreElements() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info2 = new UploadInfo();
    info2.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info3 = new UploadInfo();
    info3.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadedBytes(info1.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 1", StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(info2.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 2", StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(info3.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 3", StandardCharsets.UTF_8));

    UploadInputStreamEnumeration uploadInputStreamEnumeration =
        new UploadInputStreamEnumeration(Arrays.asList(info1, info2, info3), uploadStorageService);

    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 1",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 2",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 3",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
  }

  @Test
  public void hasMoreElementsException() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info2 = new UploadInfo();
    info2.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info3 = new UploadInfo();
    info3.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadedBytes(info1.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 1", StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(info2.getId())).thenThrow(new IOException("Test"));
    when(uploadStorageService.getUploadedBytes(info3.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 3", StandardCharsets.UTF_8));

    UploadInputStreamEnumeration uploadInputStreamEnumeration =
        new UploadInputStreamEnumeration(Arrays.asList(info1, info2, info3), uploadStorageService);

    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 1",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(null, uploadInputStreamEnumeration.nextElement());
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
  }

  @Test
  public void hasMoreElementsNotFound() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info2 = new UploadInfo();
    info2.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info3 = new UploadInfo();
    info3.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadedBytes(info1.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 1", StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(info2.getId())).thenReturn(null);
    when(uploadStorageService.getUploadedBytes(info3.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 3", StandardCharsets.UTF_8));

    UploadInputStreamEnumeration uploadInputStreamEnumeration =
        new UploadInputStreamEnumeration(Arrays.asList(info1, info2, info3), uploadStorageService);

    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 1",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(null, uploadInputStreamEnumeration.nextElement());
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
  }

  @Test
  public void hasMoreElementsNullElement() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    UploadInfo info3 = new UploadInfo();
    info3.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadedBytes(info1.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 1", StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(info3.getId()))
        .thenReturn(IOUtils.toInputStream("Upload 3", StandardCharsets.UTF_8));

    UploadInputStreamEnumeration uploadInputStreamEnumeration =
        new UploadInputStreamEnumeration(Arrays.asList(info1, null, info3), uploadStorageService);

    assertTrue(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(
        "Upload 1",
        IOUtils.toString(uploadInputStreamEnumeration.nextElement(), StandardCharsets.UTF_8));
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(null, uploadInputStreamEnumeration.nextElement());
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
  }

  @Test
  public void hasMoreElementsEmptyList() throws Exception {
    UploadInputStreamEnumeration uploadInputStreamEnumeration =
        new UploadInputStreamEnumeration(new LinkedList<UploadInfo>(), uploadStorageService);

    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
    assertEquals(null, uploadInputStreamEnumeration.nextElement());
    assertFalse(uploadInputStreamEnumeration.hasMoreElements());
  }
}
