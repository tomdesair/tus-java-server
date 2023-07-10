package me.desair.tus.server.upload.concatenation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VirtualConcatenationServiceTest {

  @Mock private UploadStorageService uploadStorageService;

  private VirtualConcatenationService concatenationService;

  @Before
  public void setUp() {
    concatenationService = new VirtualConcatenationService(uploadStorageService);
  }

  @Test
  public void merge() throws Exception {
    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength(5L);
    child1.setOffset(5L);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength(10L);
    child2.setOffset(10L);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    concatenationService.merge(infoParent);

    assertThat(infoParent.getLength(), is(15L));
    assertThat(infoParent.getOffset(), is(15L));
    assertThat(infoParent.isUploadInProgress(), is(false));

    verify(uploadStorageService, times(1)).update(infoParent);
  }

  @Test
  public void mergeNotCompleted() throws Exception {
    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength(5L);
    child1.setOffset(5L);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength(10L);
    child2.setOffset(8L);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    concatenationService.merge(infoParent);

    assertThat(infoParent.getLength(), is(15L));
    assertThat(infoParent.getOffset(), is(0L));
    assertThat(infoParent.isUploadInProgress(), is(true));

    verify(uploadStorageService, times(1)).update(infoParent);
  }

  @Test
  public void mergeWithoutLength() throws Exception {
    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength(null);
    child1.setOffset(5L);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength(null);
    child2.setOffset(8L);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    concatenationService.merge(infoParent);

    assertThat(infoParent.getLength(), is(nullValue()));
    assertThat(infoParent.getOffset(), is(0L));
    assertThat(infoParent.isUploadInProgress(), is(true));

    verify(uploadStorageService, never()).update(infoParent);
  }

  @Test(expected = UploadNotFoundException.class)
  public void mergeNotFound() throws Exception {
    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength(5L);
    child1.setOffset(5L);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength(10L);
    child2.setOffset(10L);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(null);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    concatenationService.merge(infoParent);
  }

  @Test
  public void mergeWithExpiration() throws Exception {
    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength(5L);
    child1.setOffset(5L);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength(10L);
    child2.setOffset(8L);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(500L);

    concatenationService.merge(infoParent);

    assertThat(infoParent.getLength(), is(15L));
    assertThat(infoParent.getOffset(), is(0L));
    assertThat(infoParent.isUploadInProgress(), is(true));

    assertThat(infoParent.getExpirationTimestamp(), is(notNullValue()));
    assertThat(child1.getExpirationTimestamp(), is(notNullValue()));
    // We should not update uploads that are still in progress (as they might still being
    // written)
    assertThat(child2.getExpirationTimestamp(), is(nullValue()));

    verify(uploadStorageService, times(1)).update(infoParent);
    verify(uploadStorageService, times(1)).update(child1);
    // We should not update uploads that are still in progress (as they might still being
    // written)
    verify(uploadStorageService, never()).update(child2);
  }

  @Test
  public void getUploadsEmptyFinal() throws Exception {
    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(null);

    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    assertThat(concatenationService.getPartialUploads(infoParent), Matchers.<UploadInfo>empty());

    assertThat(infoParent.getLength(), is(nullValue()));
    assertThat(infoParent.getOffset(), is(0L));
    assertThat(infoParent.isUploadInProgress(), is(true));

    verify(uploadStorageService, never()).update(infoParent);
  }

  @Test
  public void getConcatenatedBytes() throws Exception {
    String upload1 = "This is a ";
    String upload2 = "concatenated upload!";

    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength((long) upload1.getBytes().length);
    child1.setOffset((long) upload1.getBytes().length);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength((long) upload2.getBytes().length);
    child2.setOffset((long) upload2.getBytes().length);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    when(uploadStorageService.getUploadedBytes(child1.getId()))
        .thenReturn(IOUtils.toInputStream(upload1, StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(child2.getId()))
        .thenReturn(IOUtils.toInputStream(upload2, StandardCharsets.UTF_8));

    assertThat(
        IOUtils.toString(
            concatenationService.getConcatenatedBytes(infoParent), StandardCharsets.UTF_8),
        is("This is a concatenated upload!"));
  }

  @Test
  public void getConcatenatedBytesNotComplete() throws Exception {
    String upload1 = "This is a ";
    String upload2 = "concatenated upload!";

    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength((long) upload1.getBytes().length);
    child1.setOffset((long) upload1.getBytes().length - 2);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength((long) upload2.getBytes().length);
    child2.setOffset((long) upload2.getBytes().length - 2);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child2);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    when(uploadStorageService.getUploadedBytes(child1.getId()))
        .thenReturn(IOUtils.toInputStream(upload1, StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(child2.getId()))
        .thenReturn(IOUtils.toInputStream(upload2, StandardCharsets.UTF_8));

    assertThat(concatenationService.getConcatenatedBytes(infoParent), is(nullValue()));
  }

  @Test(expected = UploadNotFoundException.class)
  public void getConcatenatedBytesNotFound() throws Exception {
    String upload1 = "This is a ";
    String upload2 = "concatenated upload!";

    UploadInfo child1 = new UploadInfo();
    child1.setId(new UploadId(UUID.randomUUID()));
    child1.setLength((long) upload1.getBytes().length);
    child1.setOffset((long) upload1.getBytes().length - 2);

    UploadInfo child2 = new UploadInfo();
    child2.setId(new UploadId(UUID.randomUUID()));
    child2.setLength((long) upload2.getBytes().length);
    child2.setOffset((long) upload2.getBytes().length - 2);

    UploadInfo infoParent = new UploadInfo();
    infoParent.setId(new UploadId(UUID.randomUUID()));
    infoParent.setConcatenationPartIds(
        Arrays.asList(child1.getId().toString(), child2.getId().toString()));

    when(uploadStorageService.getUploadInfo(child1.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(child1);
    when(uploadStorageService.getUploadInfo(child2.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(null);
    when(uploadStorageService.getUploadInfo(
            infoParent.getId().toString(), infoParent.getOwnerKey()))
        .thenReturn(infoParent);

    when(uploadStorageService.getUploadedBytes(child1.getId()))
        .thenReturn(IOUtils.toInputStream(upload1, StandardCharsets.UTF_8));
    when(uploadStorageService.getUploadedBytes(child2.getId()))
        .thenReturn(IOUtils.toInputStream(upload2, StandardCharsets.UTF_8));

    concatenationService.getConcatenatedBytes(infoParent);
  }
}
