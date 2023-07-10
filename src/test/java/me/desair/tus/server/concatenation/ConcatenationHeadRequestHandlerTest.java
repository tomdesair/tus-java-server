package me.desair.tus.server.concatenation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
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
public class ConcatenationHeadRequestHandlerTest {

  private ConcatenationHeadRequestHandler handler;

  private MockHttpServletRequest servletRequest;

  private MockHttpServletResponse servletResponse;

  @Mock private UploadStorageService uploadStorageService;

  @Mock private UploadConcatenationService concatenationService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new ConcatenationHeadRequestHandler();
    when(uploadStorageService.getUploadConcatenationService()).thenReturn(concatenationService);
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
  public void testRegularUpload() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadConcatHeaderValue("Impossible");
    info1.setUploadType(UploadType.REGULAR);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    servletRequest.setRequestURI(info1.getId().toString());

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_CONCAT), is(nullValue()));
  }

  @Test
  public void testPartialUpload() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadConcatHeaderValue("partial");
    info1.setUploadType(UploadType.PARTIAL);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    servletRequest.setRequestURI(info1.getId().toString());

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_CONCAT), is("partial"));
  }

  @Test
  public void testConcatenatedUploadWithLength() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadConcatHeaderValue("final; 123 456");
    info1.setLength(10L);
    info1.setOffset(10L);
    info1.setUploadType(UploadType.CONCATENATED);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    servletRequest.setRequestURI(info1.getId().toString());

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_CONCAT), is("final; 123 456"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_LENGTH), is("10"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_OFFSET), is("10"));

    verify(concatenationService, never()).merge(info1);
  }

  @Test
  public void testConcatenatedUploadWithoutLength() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadConcatHeaderValue("final; 123 456");
    info1.setLength(10L);
    info1.setOffset(8L);
    info1.setUploadType(UploadType.CONCATENATED);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    servletRequest.setRequestURI(info1.getId().toString());

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        uploadStorageService,
        null);

    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_CONCAT), is("final; 123 456"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_LENGTH), is("10"));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_OFFSET), is(nullValue()));

    verify(concatenationService, times(1)).merge(info1);
  }
}
