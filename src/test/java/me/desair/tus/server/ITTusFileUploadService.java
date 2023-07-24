package me.desair.tus.server;

import static me.desair.tus.server.util.MapMatcher.hasSize;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Test cases for the {@link TusFileUploadService}. */
public class ITTusFileUploadService {

  protected static final String UPLOAD_URI = "/test/upload";
  protected static final String OWNER_KEY = "JOHN_DOE";

  private static final DateFormat mockDateFormat =
      new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

  protected MockHttpServletRequest servletRequest;
  protected MockHttpServletResponse servletResponse;

  protected TusFileUploadService tusFileUploadService;

  protected static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "data").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Before
  public void setUp() {
    reset();
    tusFileUploadService =
        new TusFileUploadService()
            .withUploadUri(UPLOAD_URI)
            .withStoragePath(storagePath.toAbsolutePath().toString())
            .withMaxUploadSize(1073741824L)
            .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
            .withDownloadFeature()
            .withChunkedTransferDecoding(true);
  }

  protected void reset() {
    servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("192.168.1.1");
    servletRequest.addHeader(HttpHeader.X_FORWARDED_FOR, "10.0.2.1, 123.231.12.4");
    servletResponse = new MockHttpServletResponse();
  }

  @Test
  public void testSupportedHttpMethods() {
    assertThat(
        tusFileUploadService.getSupportedHttpMethods(),
        containsInAnyOrder(
            HttpMethod.HEAD,
            HttpMethod.OPTIONS,
            HttpMethod.PATCH,
            HttpMethod.POST,
            HttpMethod.DELETE,
            HttpMethod.GET));

    assertThat(
        tusFileUploadService.getEnabledFeatures(),
        containsInAnyOrder(
            "core",
            "creation",
            "checksum",
            "termination",
            "download",
            "expiration",
            "concatenation"));
  }

  @Test
  public void testDisableFeature() throws Exception {
    tusFileUploadService.disableTusExtension("download");
    tusFileUploadService.disableTusExtension("termination");

    assertThat(
        tusFileUploadService.getSupportedHttpMethods(),
        containsInAnyOrder(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PATCH, HttpMethod.POST));

    assertThat(
        tusFileUploadService.getEnabledFeatures(),
        containsInAnyOrder("core", "creation", "checksum", "expiration", "concatenation"));

    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);

    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDisableCore() {
    tusFileUploadService.disableTusExtension("core");
  }

  @Test(expected = NullPointerException.class)
  public void testWithFileStoreServiceNull() throws Exception {
    tusFileUploadService.withUploadStorageService(null);
  }

  @Test
  public void testProcessCompleteUpload() throws Exception {
    String uploadContent = "This is my test upload content";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 Mfhm5HaSPUf+pUakdMxARo4rvfQ=");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Check with HEAD request upload is complete
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "" + uploadContent.getBytes().length);
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Get upload info from service
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertFalse(info.isUploadInProgress());
    assertThat(info.getLength(), is((long) uploadContent.getBytes().length));
    assertThat(info.getOffset(), is((long) uploadContent.getBytes().length));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));
    assertThat(info.getCreatorIpAddresses(), is("10.0.2.1, 123.231.12.4, 192.168.1.1"));

    // Try retrieving the uploaded bytes without owner key
    try {
      tusFileUploadService.getUploadedBytes(location);
      fail();
    } catch (TusException ex) {
      assertThat(ex.getStatus(), is(404));
    }

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, OWNER_KEY)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is("This is my test upload content"));
    }

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Download the upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "" + uploadContent.getBytes().length);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is("This is my test upload content"));

    // Pretend that we processed the upload and that we can remove it
    tusFileUploadService.deleteUpload(location, OWNER_KEY);

    // Check that the upload is really gone
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testTerminateViaHttpRequest() throws Exception {
    String uploadContent = "This is my terminated test upload";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Download the upload to make sure it was uploaded correctly
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "" + uploadContent.getBytes().length);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is("This is my terminated test upload"));

    // Terminate the upload so that the server can remove it
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Check that the upload is really gone
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testProcessUploadTwoParts() throws Exception {
    String part1 =
        "29\r\nThis is the first part of my test upload "
            + "\r\n0\r\nUpload-Checksum: sha1 n5RQbRwM6UVAD+9iuHEmnN6HCGQ=";
    String part2 =
        "1C\r\nand this is the second part."
            + "\r\n0\r\nUpload-Checksum: sha1 oNge323kGFKICxp+Me5xJgPvGEM=";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "69");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "41");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "41");

    // Check with service that upload is still in progress
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertTrue(info.isUploadInProgress());
    assertThat(info.getLength(), is(69L));
    assertThat(info.getOffset(), is(41L));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));
    assertThat(info.getCreatorIpAddresses(), is("10.0.2.1, 123.231.12.4, 192.168.1.1"));

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Verify that we cannot download an in-progress upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(422);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertThat(servletResponse.getContentAsString(), is(""));

    // Upload part 2 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "28");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "41");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part2.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "69");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Check with HEAD request upload is complete
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "69");
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "69");
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Get upload info from service
    info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertFalse(info.isUploadInProgress());
    assertThat(info.getLength(), is(69L));
    assertThat(info.getOffset(), is(69L));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));
    assertThat(info.getCreatorIpAddresses(), is("10.0.2.1, 123.231.12.4, 192.168.1.1"));

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, OWNER_KEY)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is("This is the first part of my test upload and this is the second part."));
    }
  }

  @Test
  public void testProcessUploadDeferredLength() throws Exception {
    String part1 = "When sending this part, we don't know the length and ";
    String part2 = "when sending this part, we know the length but the upload is not complete. ";
    String part3 = "Finally when sending the third part, the upload is complete.";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    Long expirationTimestampBefore =
        Long.parseLong(
            String.valueOf(
                mockDateFormat
                    .parse(servletResponse.getHeader(HttpHeader.UPLOAD_EXPIRES))
                    .getTime()));

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part1.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Check with service that upload is still in progress
    UploadInfo info = tusFileUploadService.getUploadInfo(location, null);
    assertTrue(info.isUploadInProgress());
    assertThat(info.getLength(), is(nullValue()));
    assertThat(info.getOffset(), is((long) part1.getBytes().length));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));
    assertThat(info.getCreatorIpAddresses(), is("10.0.2.1, 123.231.12.4, 192.168.1.1"));

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Check with HEAD request length is still not known
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_DEFER_LENGTH, "1");
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Upload part 2 bytes with length
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part2.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, part1.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, (part1 + part2 + part3).getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part2.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1 + part2).getBytes().length);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Check with HEAD request length is known
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1 + part2).getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    info = tusFileUploadService.getUploadInfo(location, null);
    assertTrue(info.isUploadInProgress());
    assertThat(info.getLength(), is((long) (part1 + part2 + part3).getBytes().length));

    // check that expiration timestamp was updated
    assertThat(info.getExpirationTimestamp(), greaterThan(expirationTimestampBefore));

    // Upload part 3 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part3.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, (part1 + part2).getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part3.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Get upload info from service
    info = tusFileUploadService.getUploadInfo(location, null);
    assertFalse(info.isUploadInProgress());
    assertThat(info.getLength(), is((long) (part1 + part2 + part3).getBytes().length));
    assertThat(info.getOffset(), is((long) (part1 + part2 + part3).getBytes().length));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, null)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is(
              "When sending this part, we don't know the length and "
                  + "when sending this part, we know the length but the upload is not complete. "
                  + "Finally when sending the third part, the upload is complete."));
    }

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Download the upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, null);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertThat(
        servletResponse.getContentAsString(),
        is(
            "When sending this part, we don't know the length and "
                + "when sending this part, we know the length but the upload is not complete. "
                + "Finally when sending the third part, the upload is complete."));
  }

  @Test
  public void testProcessUploadInvalidChecksumSecondPart() throws Exception {
    String part1 =
        "29\r\nThis is the first part of my test upload "
            + "\r\n0\r\nUPLOAD-CHECKSUM: sha1 n5RQbRwM6UVAD+9iuHEmnN6HCGQ=";
    String part2 = "1C\r\nand this is the second part." + "\r\n0\r\nupload-checksum: sha1 invalid";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "69");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "41");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "41");

    Long expirationTimestampBefore =
        Long.parseLong(
            String.valueOf(
                mockDateFormat
                    .parse(servletResponse.getHeader(HttpHeader.UPLOAD_EXPIRES))
                    .getTime()));

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Upload part 2 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "28");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "41");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part2.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // We expect the server to return a checksum mismatch error
    assertResponseStatus(460);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");

    // Check that upload info is still from the first patch
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertTrue(info.isUploadInProgress());
    assertThat(info.getLength(), is(69L));
    assertThat(info.getOffset(), is(41L));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));

    // check that expiration timestamp was updated
    assertThat(info.getExpirationTimestamp(), greaterThan(expirationTimestampBefore));

    // We only stored the first valid part
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, OWNER_KEY)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is("This is the first part of my test upload "));
    }

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Terminate our in progress upload
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // We expect the server to return a no content code to indicate successful deletion
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Check that the upload is really gone
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testCleanupExpiredUpload() throws Exception {
    // Set the expiration period to 500 ms
    tusFileUploadService.withUploadExpirationPeriod(500L);

    String part1 = "This is the first part of my test upload";
    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, part1.getBytes().length + 20L);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part1.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);

    // Check with service that upload is still in progress
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertTrue(info.isUploadInProgress());
    assertThat(info.getLength(), is(part1.getBytes().length + 20L));
    assertThat(info.getOffset(), is(Long.valueOf(part1.getBytes().length)));

    // Now wait until the upload expired and run the cleanup
    Utils.sleep(1000L);
    tusFileUploadService.cleanup();

    // Check with HEAD request that the upload is gone
    // If a Client does attempt to resume an upload which has since been removed by the Server,
    // the Server SHOULD respond with the404 Not Found or 410 Gone status.
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  public void testConcatenationCompleted() throws Exception {
    String part1 =
        "29\r\nThis is the first part of my test upload "
            + "\r\n0\r\nUpload-Checksum: sha1 n5RQbRwM6UVAD+9iuHEmnN6HCGQ=";
    String part2 =
        "1C\r\nand this is the second part."
            + "\r\n0\r\nUpload-Checksum: sha1 oNge323kGFKICxp+Me5xJgPvGEM=";

    // Create first upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "41");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location1 =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location1);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "41");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "41");

    // Make sure cleanup does not interfere with this test
    tusFileUploadService.cleanup();

    // Create the second upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "28");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location2 =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload part 2 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location2);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "28");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(part2.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "28");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Create the final concatenated upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "final ; " + location1 + " " + location2);
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA,
        "filename d29ybGRfZG9taW5hdGlvbl9tYXBfY29uY2F0ZW5hdGVkLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Check with HEAD request upload is complete
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "69");
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "69");
    assertResponseHeader(HttpHeader.UPLOAD_CONCAT, "final ; " + location1 + " " + location2);
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA,
        "filename d29ybGRfZG9taW5hdGlvbl9tYXBfY29uY2F0ZW5hdGVkLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Get upload info from service
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertFalse(info.isUploadInProgress());
    assertThat(info.getLength(), is(69L));
    assertThat(info.getOffset(), is(69L));
    assertThat(info.isUploadInProgress(), is(false));
    assertThat(
        info.getMetadata(),
        allOf(hasSize(1), hasEntry("filename", "world_domination_map_concatenated.pdf")));
    assertThat(info.getCreatorIpAddresses(), is("10.0.2.1, 123.231.12.4, 192.168.1.1"));

    // Download the upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "69");
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA,
        "filename d29ybGRfZG9taW5hdGlvbl9tYXBfY29uY2F0ZW5hdGVkLnBkZg==");
    assertThat(
        servletResponse.getContentAsString(),
        is("This is the first part of my test upload and this is the second part."));

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, OWNER_KEY)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is("This is the first part of my test upload and this is the second part."));
    }
  }

  @Test
  public void testConcatenationUnfinished() throws Exception {
    String part1 = "When sending this part, the final upload was already created. ";
    String part2 = "This is the second part of our concatenated upload. ";
    String part3 = "Finally when sending the third part, the final upload is complete.";

    // Create upload part 1
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename cGFydDEucGRm");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location1 =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    reset();
    // Create upload part 2
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename cGFydDIucGRm");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location2 =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    reset();
    // Create upload part 3
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename cGFydDMucGRm");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location3 =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload part 2 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location2);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part2.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, part2.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part2.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part2.getBytes().length);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    reset();
    // Create final upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_CONCAT, "final;" + location1 + " " + location2 + " " + location3);
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename ZmluYWwucGRm");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String locationFinal =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Check with HEAD request that length of final upload is undefined
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(locationFinal);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNull(HttpHeader.UPLOAD_OFFSET);
    assertResponseHeaderNull(HttpHeader.UPLOAD_LENGTH);
    assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename ZmluYWwucGRm");
    assertResponseHeader(
        HttpHeader.UPLOAD_CONCAT, "final;" + location1 + " " + location2 + " " + location3);
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Verify that we cannot download an unfinished final upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(locationFinal);

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseStatus(422);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertThat(servletResponse.getContentAsString(), is(""));

    // Upload part 1 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location1);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part1.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, part1.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part1.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Upload part 3 bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location3);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part3.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, part3.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(part3.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part3.getBytes().length);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Check with HEAD request length of final upload is known
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(locationFinal);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename ZmluYWwucGRm");
    assertResponseHeader(
        HttpHeader.UPLOAD_CONCAT, "final;" + location1 + " " + location2 + " " + location3);
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Download the upload
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(locationFinal);

    tusFileUploadService.process(servletRequest, servletResponse, null);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "" + (part1 + part2 + part3).getBytes().length);
    assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename ZmluYWwucGRm");
    assertThat(
        servletResponse.getContentAsString(),
        is(
            "When sending this part, the final upload was already created. "
                + "This is the second part of our concatenated upload. "
                + "Finally when sending the third part, the final upload is complete."));

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(locationFinal, null)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is(
              "When sending this part, the final upload was already created. "
                  + "This is the second part of our concatenated upload. "
                  + "Finally when sending the third part, the final upload is complete."));
    }
  }

  @Test
  public void testChunkedDecodingDisabledAndRegexUploadUri() throws Exception {
    String chunkedContent =
        "1B;test=value\r\nThis upload looks chunked, \r\n" + "D\r\nbut it's not!\r\n" + "\r\n0\r\n";

    // Create service without chunked decoding
    tusFileUploadService =
        new TusFileUploadService()
            .withUploadUri("/users/[0-9]+/files/upload")
            .withStoragePath(storagePath.toAbsolutePath().toString())
            .withDownloadFeature();

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI("/users/98765/files/upload");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "67");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNull(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location = servletResponse.getHeader(HttpHeader.LOCATION);

    // Upload content
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, "67");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(chunkedContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNull(HttpHeader.UPLOAD_EXPIRES);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "67");

    // Check with HEAD request upload is complete
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "67");
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "67");
    assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
    assertResponseHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Get upload info from service
    UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
    assertFalse(info.isUploadInProgress());
    assertThat(info.getLength(), is(67L));
    assertThat(info.getOffset(), is(67L));
    assertThat(
        info.getMetadata(), allOf(hasSize(1), hasEntry("filename", "world_domination_plan.pdf")));

    // Get uploaded bytes from service
    try (InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, OWNER_KEY)) {
      assertThat(
          IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
          is(
              "1B;test=value\r\nThis upload looks chunked, \r\n"
                  + "D\r\nbut it's not!\r\n"
                  + "\r\n0\r\n"));
    }
  }

  @Test
  public void testOptions() throws Exception {
    // Do options request and check response headers
    servletRequest.setMethod("OPTIONS");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
    assertResponseHeader(HttpHeader.TUS_MAX_SIZE, "1073741824");
    assertResponseHeader(
        HttpHeader.TUS_CHECKSUM_ALGORITHM, "md5", "sha1", "sha256", "sha384", "sha512");
    assertResponseHeader(
        HttpHeader.TUS_EXTENSION,
        "creation",
        "creation-defer-length",
        "checksum",
        "checksum-trailer",
        "termination",
        "download",
        "expiration",
        "concatenation",
        "concatenation-unfinished");
  }

  @Test
  public void testHeadOnNonExistingUpload() throws Exception {
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testInvalidTusResumable() throws Exception {
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_PRECONDITION_FAILED);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testMaxUploadLengthExceeded() throws Exception {
    tusFileUploadService.withMaxUploadSize(10L);

    String uploadContent = "This is upload is too long";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  @Test
  public void testInvalidMethods() throws Exception {
    servletRequest.setMethod("PUT");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);

    reset();
    servletRequest.setMethod("CONNECT");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");

    reset();
    servletRequest.setMethod("TRACE");
    servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
  }

  protected void assertResponseHeader(final String header, final String value) {
    assertThat(servletResponse.getHeader(header), is(value));
  }

  protected void assertResponseHeader(final String header, final String... values) {
    assertThat(
        Arrays.asList(servletResponse.getHeader(header).split(",")), containsInAnyOrder(values));
  }

  protected void assertResponseHeaderNotBlank(final String header) {
    assertTrue(StringUtils.isNotBlank(servletResponse.getHeader(header)));
  }

  protected void assertResponseHeaderNull(final String header) {
    assertNull(servletResponse.getHeader(header));
  }

  protected void assertResponseStatus(final int httpStatus) {
    assertThat(servletResponse.getStatus(), is(httpStatus));
  }
}
