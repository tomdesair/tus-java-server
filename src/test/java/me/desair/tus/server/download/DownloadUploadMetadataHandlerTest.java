package me.desair.tus.server.download;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.util.UUID;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.Silent.class)
public class DownloadUploadMetadataHandlerTest {

  private DownloadUploadMetadataHandler handler;

  private MockHttpServletRequest servletRequest;

  private MockHttpServletResponse servletResponse;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new DownloadUploadMetadataHandler();
  }

  @Test
  public void supports() {
    assertThat(handler.supports(HttpMethod.GET), is(true));
    assertThat(handler.supports(HttpMethod.GET, ProtocolVersion.TUS_1_0_0), is(true));
    assertThat(handler.supports(HttpMethod.GET, ProtocolVersion.RUFH), is(false));
    assertThat(handler.supports(HttpMethod.POST, ProtocolVersion.TUS_1_0_0), is(false));
  }

  @Test
  public void testProcessCompletedUploadWithMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId(UUID.randomUUID()));
    info.setOffset(10L);
    info.setLength(10L);
    info.setEncodedMetadata("name dGVzdC5qcGc=,type aW1hZ2UvanBlZw==");
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.GET,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(
        servletResponse.getHeader(HttpHeader.UPLOAD_METADATA),
        is("name dGVzdC5qcGc=,type aW1hZ2UvanBlZw=="));
  }

  @Test
  public void testProcessCompletedUploadWithoutMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId(UUID.randomUUID()));
    info.setOffset(10L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.GET,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());
  }

  @Test
  public void testProcessUploadInProgress() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId(UUID.randomUUID()));
    info.setOffset(5L);
    info.setLength(10L);
    info.setEncodedMetadata("name dGVzdC5qcGc=");
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.GET,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());
  }
}
