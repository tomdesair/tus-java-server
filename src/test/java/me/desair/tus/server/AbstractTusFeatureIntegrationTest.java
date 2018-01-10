package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractTusFeature;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.io.IOUtils;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.class)
public abstract class AbstractTusFeatureIntegrationTest {

    protected AbstractTusFeature tusFeature;

    protected MockHttpServletRequest servletRequest;

    protected MockHttpServletResponse servletResponse;

    @Mock
    protected UploadStorageService uploadStorageService;

    protected UploadInfo uploadInfo;

    protected void prepareUploadInfo(final Long offset, final Long length) throws IOException, TusException {
        uploadInfo = new UploadInfo();
        uploadInfo.setOffset(offset);
        uploadInfo.setLength(length);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(uploadInfo);
        when(uploadStorageService.append(any(UploadInfo.class), any(InputStream.class))).thenReturn(uploadInfo);
    }

    protected void setRequestHeaders(String... headers) {
        if(headers != null && headers.length > 0) {
            for (String header : headers) {
                switch (header) {
                    case HttpHeader.TUS_RESUMABLE:
                        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
                        break;
                    case HttpHeader.CONTENT_TYPE:
                        servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
                        break;
                    case HttpHeader.UPLOAD_OFFSET:
                        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, uploadInfo.getOffset());
                        break;
                    case HttpHeader.CONTENT_LENGTH:
                        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadInfo.getLength() - uploadInfo.getOffset());
                        break;
                }
            }
        }
    }

    protected void executeCall(final HttpMethod method, final boolean readContent) throws TusException, IOException {
        tusFeature.validate(method, servletRequest, uploadStorageService, null);
        TusServletRequest tusServletRequest = new TusServletRequest(this.servletRequest);

        if(readContent) {
            StringWriter writer = new StringWriter();
            IOUtils.copy(tusServletRequest.getContentInputStream(), writer, StandardCharsets.UTF_8);
        }

        tusFeature.process(method, tusServletRequest, new TusServletResponse(servletResponse), uploadStorageService, null);
    }

    protected void assertResponseHeader(final String header, final String value) {
        assertThat(servletResponse.getHeader(header), is(value));
    }

    protected void assertResponseHeader(final String header, final String... values) {
        assertThat(Arrays.asList(servletResponse.getHeader(header).split(",")),
                containsInAnyOrder(values));
    }

    protected void assertResponseStatus(final int httpStatus) {
        assertThat(servletResponse.getStatus(), is(httpStatus));
    }

}
