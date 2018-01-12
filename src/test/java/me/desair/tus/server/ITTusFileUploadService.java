package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ITTusFileUploadService {

    private static final String UPLOAD_URI = "/test/upload";
    private static final String OWNER_KEY = "JOHN_DOE";

    private MockHttpServletRequest servletRequest;
    private MockHttpServletResponse servletResponse;

    private TusFileUploadService tusFileUploadService;

    private static Path storagePath;

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
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        tusFileUploadService = new TusFileUploadService()
            .withUploadURI(UPLOAD_URI)
            .withStoragePath(storagePath.toAbsolutePath().toString());
    }

    @Test(expected = NullPointerException.class)
    public void testWithFileStoreServiceNull() throws Exception {
        tusFileUploadService.withUploadStorageService(null);
    }

    @Test
    public void testProcessCompleteUpload() throws Exception {
        String uploadContent = "This is my test upload content";

        //Create upload
        servletRequest.setMethod("POST");
        servletRequest.setRequestURI(UPLOAD_URI);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseHeaderNotBlank(HttpHeader.LOCATION);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        String location = UPLOAD_URI + StringUtils.substringAfter(servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

        //Upload bytes
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
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Check with HEAD request upload is complete
        reset();
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "" + uploadContent.getBytes().length);
        assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
        assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Get upload info from service
        UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
        assertFalse(info.isUploadInProgress());
        assertThat(info.getLength(), is((long) uploadContent.getBytes().length));
        assertThat(info.getOffset(), is((long) uploadContent.getBytes().length));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //Get uploaded bytes from service
        try(InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, null)) {
            assertThat(IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
                    is("This is my test upload content"));
        }
    }

    @Test
    public void testProcessUploadTwoParts() throws Exception {
        String part1 = "29\r\nThis is the first part of my test upload \r\n0\r\nUpload-Checksum: sha1 n5RQbRwM6UVAD+9iuHEmnN6HCGQ=";
        String part2 = "1C\r\nand this is the second part.\r\n0\r\nUpload-Checksum: sha1 oNge323kGFKICxp+Me5xJgPvGEM=";

        //Create upload
        servletRequest.setMethod("POST");
        servletRequest.setRequestURI(UPLOAD_URI);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "69");
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseHeaderNotBlank(HttpHeader.LOCATION);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        String location = UPLOAD_URI + StringUtils.substringAfter(servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

        //Upload part 1 bytes
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
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "41");

        //Check with service that upload is still in progress
        UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
        assertTrue(info.isUploadInProgress());
        assertThat(info.getLength(), is(69L));
        assertThat(info.getOffset(), is( 41L));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //Upload part 2 bytes
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
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "69");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Check with HEAD request upload is complete
        reset();
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "69");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "69");
        assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
        assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Get upload info from service
        info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
        assertFalse(info.isUploadInProgress());
        assertThat(info.getLength(), is(69L));
        assertThat(info.getOffset(), is(69L));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //Get uploaded bytes from service
        try(InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, null)) {
            assertThat(IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
                    is("This is the first part of my test upload and this is the second part."));
        }
    }

    @Test
    public void testProcessUploadDeferredLength() throws Exception {
        String part1 = "When sending this part, we don't know the length and ";
        String part2 = "when sending this part, we know the length but the upload is not complete. ";
        String part3 = "Finally when sending the third part, the upload is complete.";

        //Create upload
        servletRequest.setMethod("POST");
        servletRequest.setRequestURI(UPLOAD_URI);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
        servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseHeaderNotBlank(HttpHeader.LOCATION);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        String location = UPLOAD_URI + StringUtils.substringAfter(servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

        //Upload part 1 bytes
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
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Check with service that upload is still in progress
        UploadInfo info = tusFileUploadService.getUploadInfo(location, null);
        assertTrue(info.isUploadInProgress());
        assertThat(info.getLength(), is(nullValue()));
        assertThat(info.getOffset(), is((long) part1.getBytes().length));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //Check with HEAD request length is still not known
        reset();
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + part1.getBytes().length);
        assertResponseHeader(HttpHeader.UPLOAD_DEFER_LENGTH, "1");
        assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Upload part 2 bytes with length
        reset();
        servletRequest.setMethod("PATCH");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part2.getBytes().length);
        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, part1.getBytes().length);
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, (part1+part2+part3).getBytes().length);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.setContent(part2.getBytes());

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1+part2).getBytes().length);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Check with HEAD request length is known
        reset();
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1+part2).getBytes().length);
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "" + (part1+part2+part3).getBytes().length);
        assertResponseHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");
        assertResponseHeaderNull(HttpHeader.UPLOAD_DEFER_LENGTH);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        info = tusFileUploadService.getUploadInfo(location, null);
        assertTrue(info.isUploadInProgress());
        assertThat(info.getLength(), is((long) (part1+part2+part3).getBytes().length));

        //Upload part 3 bytes
        reset();
        servletRequest.setMethod("PATCH");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, part3.getBytes().length);
        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, (part1+part2).getBytes().length);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.setContent(part3.getBytes());

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + (part1+part2+part3).getBytes().length);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Get upload info from service
        info = tusFileUploadService.getUploadInfo(location, null);
        assertFalse(info.isUploadInProgress());
        assertThat(info.getLength(), is((long) (part1+part2+part3).getBytes().length));
        assertThat(info.getOffset(), is((long) (part1+part2+part3).getBytes().length));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //Get uploaded bytes from service
        try(InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, null)) {
            assertThat(IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
                    is("When sending this part, we don't know the length and " +
                            "when sending this part, we know the length but the upload is not complete. " +
                            "Finally when sending the third part, the upload is complete."));
        }
    }

    @Test
    public void testProcessUploadInvalidChecksumSecondPart() throws Exception {
        String part1 = "29\r\nThis is the first part of my test upload \r\n0\r\nUPLOAD-CHECKSUM: sha1 n5RQbRwM6UVAD+9iuHEmnN6HCGQ=";
        String part2 = "1C\r\nand this is the second part.\r\n0\r\nupload-checksum: sha1 invalid";

        //Create upload
        servletRequest.setMethod("POST");
        servletRequest.setRequestURI(UPLOAD_URI);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "69");
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseHeaderNotBlank(HttpHeader.LOCATION);
        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        String location = UPLOAD_URI + StringUtils.substringAfter(servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

        //Upload part 1 bytes
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
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "41");

        //Upload part 2 bytes
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

        //We expect the server to return a checksum mismatch error
        assertResponseStatus(460);

        //Check that upload info is still from the first patch
        UploadInfo info = tusFileUploadService.getUploadInfo(location, OWNER_KEY);
        assertTrue(info.isUploadInProgress());
        assertThat(info.getLength(), is(69L));
        assertThat(info.getOffset(), is(41L));
        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );

        //We only stored the first valid part
        try(InputStream uploadedBytes = tusFileUploadService.getUploadedBytes(location, null)) {
            assertThat(IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
                    is("This is the first part of my test upload "));
        }

        //Terminate our in progress upload
        reset();
        servletRequest.setMethod("DELETE");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

        //We expect the server to return a no content code to indicate successful deletion
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

        //Check that the upload is really gone
        reset();
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(location);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse);
        assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    public void testOptions() throws Exception {
        //Do options request and check response headers
        servletRequest.setMethod("OPTIONS");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

        //If the Server supports this extension, it MUST add creation to the Tus-Extension header.
        //If the Server supports deferring length, it MUST add creation-defer-length to the Tus-Extension header.
        assertResponseHeader(HttpHeader.TUS_EXTENSION, "creation", "creation-defer-length",
                "checksum", "checksum-trailer");
    }

    @Test
    public void testHeadOnNonExistingUpload() throws Exception {
        servletRequest.setMethod("HEAD");
        servletRequest.setRequestURI(UPLOAD_URI + "/" + UUID.randomUUID());
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
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
    }

    @Test
    public void testMaxUploadLengthExceeded() throws Exception {
        tusFileUploadService.withMaxUploadSize(10L);

        String uploadContent = "This is upload is too long";

        //Create upload
        servletRequest.setMethod("POST");
        servletRequest.setRequestURI(UPLOAD_URI);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
        assertResponseStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    private void assertResponseHeader(final String header, final String value) {
        assertThat(servletResponse.getHeader(header), is(value));
    }

    private void assertResponseHeader(final String header, final String... values) {
        assertThat(Arrays.asList(servletResponse.getHeader(header).split(",")),
                containsInAnyOrder(values));
    }

    private void assertResponseHeaderNotBlank(final String header) {
        assertTrue(StringUtils.isNotBlank(servletResponse.getHeader(header)));
    }

    private void assertResponseHeaderNull(final String header) {
        assertTrue(servletResponse.getHeader(header) == null);
    }

    private void assertResponseStatus(final int httpStatus) {
        assertThat(servletResponse.getStatus(), is(httpStatus));
    }

    private void reset() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
    }

}