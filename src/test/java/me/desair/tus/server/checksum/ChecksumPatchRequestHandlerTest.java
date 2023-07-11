package me.desair.tus.server.checksum;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.UploadChecksumMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ChecksumPatchRequestHandlerTest {

  private ChecksumPatchRequestHandler handler;

  @Mock private TusServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() throws Exception {
    handler = new ChecksumPatchRequestHandler();

    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
  }

  @Test
  public void supports() throws Exception {
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(false));
    assertThat(handler.supports(HttpMethod.PUT), is(false));
    assertThat(handler.supports(HttpMethod.DELETE), is(false));
    assertThat(handler.supports(HttpMethod.HEAD), is(false));
    assertThat(handler.supports(HttpMethod.OPTIONS), is(false));
    assertThat(handler.supports(HttpMethod.PATCH), is(true));
    assertThat(handler.supports(null), is(false));
  }

  @Test
  public void testValidHeaderAndChecksum() throws Exception {
    when(servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha1 1234567890");
    when(servletRequest.getCalculatedChecksum(ArgumentMatchers.any(ChecksumAlgorithm.class)))
        .thenReturn("1234567890");
    when(servletRequest.hasCalculatedChecksum()).thenReturn(true);

    handler.process(HttpMethod.PATCH, servletRequest, null, uploadStorageService, null);

    verify(servletRequest, times(1)).getCalculatedChecksum(any(ChecksumAlgorithm.class));
  }

  @Test(expected = UploadChecksumMismatchException.class)
  public void testValidHeaderAndInvalidChecksum() throws Exception {
    when(servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha1 1234567890");
    when(servletRequest.getCalculatedChecksum(ArgumentMatchers.any(ChecksumAlgorithm.class)))
        .thenReturn("0123456789");
    when(servletRequest.hasCalculatedChecksum()).thenReturn(true);

    handler.process(HttpMethod.PATCH, servletRequest, null, uploadStorageService, null);
  }

  @Test
  public void testNoHeader() throws Exception {
    when(servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn(null);

    handler.process(HttpMethod.PATCH, servletRequest, null, uploadStorageService, null);

    verify(servletRequest, never()).getCalculatedChecksum(any(ChecksumAlgorithm.class));
  }

  @Test(expected = ChecksumAlgorithmNotSupportedException.class)
  public void testInvalidHeader() throws Exception {
    when(servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("test 1234567890");
    when(servletRequest.hasCalculatedChecksum()).thenReturn(true);

    handler.process(HttpMethod.PATCH, servletRequest, null, uploadStorageService, null);
  }
}
