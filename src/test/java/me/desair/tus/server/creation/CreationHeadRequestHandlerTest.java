package me.desair.tus.server.creation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
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
public class CreationHeadRequestHandlerTest {

  private CreationHeadRequestHandler handler;

  private MockHttpServletRequest servletRequest;

  private MockHttpServletResponse servletResponse;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new CreationHeadRequestHandler();
  }

  @Test
  public void supports() throws Exception {
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(false));
    assertThat(handler.supports(HttpMethod.PUT), is(false));
    assertThat(handler.supports(HttpMethod.DELETE), is(false));
    assertThat(handler.supports(HttpMethod.HEAD), is(true));
    assertThat(handler.supports(HttpMethod.OPTIONS), is(false));
    assertThat(handler.supports(HttpMethod.PATCH), is(false));
    assertThat(handler.supports(null), is(false));
  }

  @Test
  public void processWithLengthAndMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    info.setEncodedMetadata("encoded-metadata");
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is("encoded-metadata"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));
  }

  @Test
  public void processWithLengthAndNoMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    info.setEncodedMetadata(null);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));
  }

  @Test
  public void processWithNoLengthAndMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(null);
    info.setEncodedMetadata("encoded-metadata");
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is("encoded-metadata"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is("1"));
  }

  @Test
  public void processWithNoLengthAndNoMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(null);
    info.setEncodedMetadata(null);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is("1"));
  }
}
