package me.desair.tus.server.concatenation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
public class ConcatenationPostRequestHandlerTest {

  private ConcatenationPostRequestHandler handler;

  private MockHttpServletRequest servletRequest;

  private MockHttpServletResponse servletResponse;

  @Mock private UploadStorageService uploadStorageService;

  @Mock private UploadConcatenationService concatenationService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new ConcatenationPostRequestHandler();
    when(uploadStorageService.getUploadConcatenationService()).thenReturn(concatenationService);
  }

  @Test
  public void supports() throws Exception {
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PUT), is(false));
    assertThat(handler.supports(HttpMethod.DELETE), is(false));
    assertThat(handler.supports(HttpMethod.HEAD), is(false));
    assertThat(handler.supports(HttpMethod.OPTIONS), is(false));
    assertThat(handler.supports(HttpMethod.PATCH), is(false));
    assertThat(handler.supports(null), is(false));
  }

  @Test
  public void testRegularUpload() throws Exception {
    TusServletResponse response = new TusServletResponse(this.servletResponse);

    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    response.setHeader(HttpHeader.LOCATION, info1.getId().toString());

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        response,
        uploadStorageService,
        null);

    assertThat(info1.getUploadType(), is(UploadType.REGULAR));
    assertThat(info1.getUploadConcatHeaderValue(), is(nullValue()));

    verify(uploadStorageService, times(1)).update(info1);
    verify(concatenationService, never()).merge(info1);
  }

  @Test
  public void testPartialUpload() throws Exception {
    TusServletResponse response = new TusServletResponse(this.servletResponse);

    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    response.setHeader(HttpHeader.LOCATION, info1.getId().toString());
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        response,
        uploadStorageService,
        null);

    assertThat(info1.getUploadType(), is(UploadType.PARTIAL));
    assertThat(info1.getUploadConcatHeaderValue(), is("partial"));

    verify(uploadStorageService, times(1)).update(info1);
    verify(concatenationService, never()).merge(info1);
  }

  @Test
  public void testFinalUpload() throws Exception {
    TusServletResponse response = new TusServletResponse(this.servletResponse);

    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    response.setHeader(HttpHeader.LOCATION, info1.getId().toString());
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "final; 123 456");

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        response,
        uploadStorageService,
        null);

    assertThat(info1.getUploadType(), is(UploadType.CONCATENATED));
    assertThat(info1.getUploadConcatHeaderValue(), is("final; 123 456"));

    verify(uploadStorageService, times(1)).update(info1);
    verify(concatenationService, times(1)).merge(info1);
  }

  @Test
  public void testUploadNotFound() throws Exception {
    TusServletResponse response = new TusServletResponse(this.servletResponse);

    response.setHeader(HttpHeader.LOCATION, "/test/upload/1234");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "final; 123 456");

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        response,
        uploadStorageService,
        null);

    verify(uploadStorageService, never()).update(any(UploadInfo.class));
    verify(concatenationService, never()).merge(any(UploadInfo.class));
  }
}
