package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Disk-backed integration test suite for {@link TusFileUploadService}. Extends {@link
 * AbstractITTusFileUploadService} to run all protocol test use cases against disk storage.
 */
public class ITTusFileUploadService extends AbstractITTusFileUploadService {

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

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    return createTusFileUploadService(UPLOAD_URI);
  }

  @Override
  protected TusFileUploadService createTusFileUploadService(String uploadUri) {
    return new TusFileUploadService()
        .withUploadUri(uploadUri)
        .withStoragePath(storagePath.toAbsolutePath().toString())
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withDownloadFeature()
        .withChunkedTransferDecoding(true);
  }

  // ===============================================================================================
  // DISK-SPECIFIC STORAGE TESTS
  // ===============================================================================================

  /**
   * Disk Storage Specific Test: Verify automatic file deduplication on upload completion,
   * inspecting physical disk file structures, checking child data file non-existence, direct file
   * manipulation on disk, and cleanup on parent deletion.
   */
  @Test
  public void testAutomaticDeduplicationOnUploadCompletion() throws Exception {
    // Step 1: Enable deduplication feature on disk storage
    tusFileUploadService.withUploadDeduplication(true);

    String uploadContent = "Deduplication integration test content";

    // Step 2: Upload parent file
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String parentLocation =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(parentLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 nQPKHXKplOdf9DApoZdrdm0viw4=");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Step 3: Upload identical duplicate child file
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String childLocation =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(childLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 nQPKHXKplOdf9DApoZdrdm0viw4=");
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Step 4: Verify deduplication metadata link (child duplicates parent)
    UploadInfo parentInfo = tusFileUploadService.getUploadInfo(parentLocation, OWNER_KEY);
    UploadInfo childInfo = tusFileUploadService.getUploadInfo(childLocation, OWNER_KEY);

    assertThat(childInfo.getDuplicatesUploadId(), is(parentInfo.getId()));

    // Step 5: Verify child upload physical data file does NOT exist on disk
    Path childDataPath =
        storagePath.resolve("uploads").resolve(childInfo.getId().toString()).resolve("data");
    assertFalse(Files.exists(childDataPath));

    // Step 6: Verify parent upload physical data file DOES exist on disk
    Path parentDataPath =
        storagePath.resolve("uploads").resolve(parentInfo.getId().toString()).resolve("data");
    assertTrue(Files.exists(parentDataPath));

    // Step 7: Verify downloading child retrieves parent's content
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(childLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is(uploadContent));

    // Step 8: Directly modify parent data file on physical disk
    String manipulatedContent = "manipulated content on disk";
    Files.write(
        parentDataPath, manipulatedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // Step 9: Verify parent and child downloads both return the manipulated content
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(parentLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is(manipulatedContent));

    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(childLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is(manipulatedContent));

    // Step 10: Delete parent upload and verify child download returns 404
    tusFileUploadService.deleteUpload(parentLocation, OWNER_KEY);

    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(childLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
  }
}
