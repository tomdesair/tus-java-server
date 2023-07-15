package me.desair.tus.server.upload;

import static me.desair.tus.server.util.MapMatcher.hasSize;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.text.ParseException;
import java.util.Stack;
import java.util.UUID;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Test cases for the UploadInfo class. */
public class UploadInfoTest {

  @Test
  public void hasMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata("Encoded Metadata");
    assertTrue(info.hasMetadata());
  }

  @Test
  public void hasMetadataFalse() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata(null);
    assertFalse(info.hasMetadata());
  }

  @Test
  public void testGetMetadataMultipleValues() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata(
        "filename d29ybGRfZG9taW5hdGlvbiBwbGFuLnBkZg==,"
            + "filesize MTEya2I=, "
            + "mimetype \tYXBwbGljYXRpb24vcGRm , "
            + "scanned , ,, "
            + "user\t546L5LqU \t    ");

    assertThat(
        info.getMetadata(),
        allOf(
            hasSize(5),
            hasEntry("filename", "world_domination plan.pdf"),
            hasEntry("filesize", "112kb"),
            hasEntry("mimetype", "application/pdf"),
            hasEntry("scanned", null),
            hasEntry("user", "王五")));
  }

  @Test
  public void testGetMetadataSingleValues() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata("filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));
  }

  @Test
  public void testGetMetadataNull() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata(null);
    assertTrue(info.getMetadata().isEmpty());
  }

  @Test
  public void hasLength() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    assertTrue(info.hasLength());
  }

  @Test
  public void hasLengthFalse() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(null);
    assertFalse(info.hasLength());
  }

  @Test
  public void isUploadInProgressNoLengthNoOffset() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(null);
    info.setOffset(null);
    assertTrue(info.isUploadInProgress());
  }

  @Test
  public void isUploadInProgressNoLengthWithOffset() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(null);
    info.setOffset(10L);
    assertTrue(info.isUploadInProgress());
  }

  @Test
  public void isUploadInProgressOffsetDoesNotMatchLength() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    info.setOffset(8L);
    assertTrue(info.isUploadInProgress());
  }

  @Test
  public void isUploadInProgressOffsetMatchesLength() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    info.setOffset(10L);
    assertFalse(info.isUploadInProgress());
  }

  @Test
  public void testEquals() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setLength(10L);
    info1.setOffset(5L);
    info1.setEncodedMetadata("Encoded-Metadata");
    info1.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info2 = new UploadInfo();
    info2.setLength(10L);
    info2.setOffset(5L);
    info2.setEncodedMetadata("Encoded-Metadata");
    info2.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info3 = new UploadInfo();
    info3.setLength(9L);
    info3.setOffset(5L);
    info3.setEncodedMetadata("Encoded-Metadata");
    info3.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info4 = new UploadInfo();
    info4.setLength(10L);
    info4.setOffset(6L);
    info4.setEncodedMetadata("Encoded-Metadata");
    info4.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info5 = new UploadInfo();
    info5.setLength(10L);
    info5.setOffset(5L);
    info5.setEncodedMetadata("Encoded-Metadatas");
    info5.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info6 = new UploadInfo();
    info6.setLength(10L);
    info6.setOffset(5L);
    info6.setEncodedMetadata("Encoded-Metadata");
    info6.setId(new UploadId("1911e8a4-6939-490c-c58b-a5d70f8d91fb"));

    assertEquals(info1, info1);
    assertEquals(info1, info2);
    assertNotEquals(info1, null);
    assertNotEquals(info1, new Object());
    assertNotEquals(info1, info3);
    assertNotEquals(info1, info4);
    assertNotEquals(info1, info5);
    assertNotEquals(info1, info6);
  }

  @Test
  public void testHashCode() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setLength(10L);
    info1.setOffset(5L);
    info1.setEncodedMetadata("Encoded-Metadata");
    info1.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    UploadInfo info2 = new UploadInfo();
    info2.setLength(10L);
    info2.setOffset(5L);
    info2.setEncodedMetadata("Encoded-Metadata");
    info2.setId(new UploadId("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

    assertEquals(info1.hashCode(), info2.hashCode());
  }

  @Test
  public void testGetNameAndTypeWithMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setEncodedMetadata("name dGVzdC5qcGc=,type aW1hZ2UvanBlZw==");

    assertThat(info.getFileName(), is("test.jpg"));
    assertThat(info.getFileMimeType(), is("image/jpeg"));
  }

  @Test
  public void testGetNameAndTypeWithoutMetadata() throws Exception {
    UploadInfo info = new UploadInfo();
    final UploadId id = new UploadId(UUID.randomUUID());
    info.setId(id);

    assertThat(info.getFileName(), is(id.toString()));
    assertThat(info.getFileMimeType(), is("application/octet-stream"));
  }

  @Test
  public void testExpiration() throws Exception {
    UploadInfo info1 = new UploadInfo();
    assertFalse(info1.isExpired());

    UploadInfo info2 =
        new UploadInfo() {
          @Override
          protected long getCurrentTime() {
            try {
              return DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT
                  .parse("2018-01-20T10:43:11")
                  .getTime();
            } catch (ParseException e) {
              return 0L;
            }
          }
        };
    info2.updateExpiration(172800000L);
    assertFalse(info2.isExpired());

    final Stack<Long> dateStack = new Stack<>();
    // Current time stamp to check expiration
    dateStack.push(
        DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT.parse("2018-01-23T10:43:11").getTime());
    // Current time stamp to calculate expiration
    dateStack.push(
        DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT.parse("2018-01-20T10:43:11").getTime());
    // Creation time stamp
    dateStack.push(
        DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT.parse("2018-01-20T10:40:39").getTime());

    UploadInfo info3 =
        new UploadInfo() {
          @Override
          protected long getCurrentTime() {
            return dateStack.pop();
          }
        };
    info3.updateExpiration(172800000L);
    assertTrue(info3.isExpired());
  }

  @Test
  public void testGetCreationTimestamp() throws Exception {
    UploadInfo info = new UploadInfo();
    Utils.sleep(10);

    assertThat(info.getCreationTimestamp(), greaterThan(System.currentTimeMillis() - 500L));
    assertThat(info.getCreationTimestamp(), lessThan(System.currentTimeMillis()));
  }

  @Test
  public void testGetCreatorIpAddressesNull() throws Exception {
    UploadInfo info = new UploadInfo();
    assertThat(info.getCreatorIpAddresses(), nullValue());
  }

  @Test
  public void testGetCreatorIpAddressesWithoutXforwardedFor() throws Exception {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("10.11.12.13");

    UploadInfo info = new UploadInfo(servletRequest);
    assertThat(info.getCreatorIpAddresses(), is("10.11.12.13"));
  }

  @Test
  public void testGetCreatorIpAddressesWithXforwardedFor() throws Exception {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("10.11.12.13");
    servletRequest.addHeader(HttpHeader.X_FORWARDED_FOR, "24.23.22.21, 192.168.1.1");

    UploadInfo info = new UploadInfo(servletRequest);
    assertThat(info.getCreatorIpAddresses(), is("24.23.22.21, 192.168.1.1, 10.11.12.13"));
  }
}
