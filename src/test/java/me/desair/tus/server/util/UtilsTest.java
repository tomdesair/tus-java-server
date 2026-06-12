package me.desair.tus.server.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.junit.Test;

public class UtilsTest {

  @Test
  public void testParseUploadChecksumHeaderMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn(null);

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderBlank() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("   ");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderInvalidFormat() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderUnsupportedAlgorithm() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("invalid-algo value");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderNoValue() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 ");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderValid() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 value123");

    Utils.ChecksumInfo info = Utils.parseUploadChecksumHeader(request);
    assertThat(info, is(notNullValue()));
    assertThat(info.getAlgorithm(), is(ChecksumAlgorithm.SHA256));
    assertThat(info.getValue(), is("value123"));
  }
}
