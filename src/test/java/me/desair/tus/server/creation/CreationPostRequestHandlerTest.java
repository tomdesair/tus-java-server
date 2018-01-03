package me.desair.tus.server.creation;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The Server MUST acknowledge a successful upload creation with the 201 Created status.
 * The Server MUST set the Location header to the URL of the created resource. This URL MAY be absolute or relative.
 */
@RunWith(MockitoJUnitRunner.class)
public class CreationPostRequestHandlerTest {

    private CreationPostRequestHandler handler;

    private MockHttpServletRequest servletRequest;

    private MockHttpServletResponse servletResponse;

    @Mock
    private UploadStorageService uploadStorageService;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        handler = new CreationPostRequestHandler();
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
    public void processWithLengthAndMetadata() throws Exception {
        servletRequest.setRequestURI("/test/upload");
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "encoded-metadata");

        final UUID id = UUID.randomUUID();
        when(uploadStorageService.create(Matchers.any(UploadInfo.class), anyString())).then(new Answer<UploadInfo>() {
            @Override
            public UploadInfo answer(final InvocationOnMock invocation) throws Throwable {
                UploadInfo upload = invocation.getArgumentAt(0, UploadInfo.class);
                assertThat(upload.getLength(), is(10L));
                assertThat(upload.getEncodedMetadata(), is("encoded-metadata"));

                upload.setId(id);

                return upload;
            }
        });

        handler.process(HttpMethod.POST, servletRequest, new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, times(1)).create(Matchers.any(UploadInfo.class), anyString());
        assertThat(servletResponse.getHeader(HttpHeader.LOCATION), endsWith("/test/upload/" + id.toString()));
        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    }

    @Test
    public void processWithLengthAndNoMetadata() throws Exception {
        servletRequest.setRequestURI("/test/upload");
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);
        //servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, null);

        final UUID id = UUID.randomUUID();
        when(uploadStorageService.create(Matchers.any(UploadInfo.class), anyString())).then(new Answer<UploadInfo>() {
            @Override
            public UploadInfo answer(final InvocationOnMock invocation) throws Throwable {
                UploadInfo upload = invocation.getArgumentAt(0, UploadInfo.class);
                assertThat(upload.getLength(), is(10L));
                assertThat(upload.getEncodedMetadata(), is(nullValue()));

                upload.setId(id);

                return upload;
            }
        });

        handler.process(HttpMethod.POST, servletRequest, new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, times(1)).create(Matchers.any(UploadInfo.class), anyString());
        assertThat(servletResponse.getHeader(HttpHeader.LOCATION), endsWith("/test/upload/" + id.toString()));
        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    }

    @Test
    public void processWithNoLengthAndMetadata() throws Exception {
        servletRequest.setRequestURI("/test/upload");
        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, null);
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "encoded-metadata");

        final UUID id = UUID.randomUUID();
        when(uploadStorageService.create(Matchers.any(UploadInfo.class), anyString())).then(new Answer<UploadInfo>() {
            @Override
            public UploadInfo answer(final InvocationOnMock invocation) throws Throwable {
                UploadInfo upload = invocation.getArgumentAt(0, UploadInfo.class);
                assertThat(upload.getLength(), is(nullValue()));
                assertThat(upload.getEncodedMetadata(), is("encoded-metadata"));

                upload.setId(id);

                return upload;
            }
        });

        handler.process(HttpMethod.POST, servletRequest, new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, times(1)).create(Matchers.any(UploadInfo.class), anyString());
        assertThat(servletResponse.getHeader(HttpHeader.LOCATION), endsWith("/test/upload/" + id.toString()));
        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    }

    @Test
    public void processWithNoLengthAndNoMetadata() throws Exception {
        servletRequest.setRequestURI("/test/upload");
        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, null);
        //servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, null);

        final UUID id = UUID.randomUUID();
        when(uploadStorageService.create(Matchers.any(UploadInfo.class), anyString())).then(new Answer<UploadInfo>() {
            @Override
            public UploadInfo answer(final InvocationOnMock invocation) throws Throwable {
                UploadInfo upload = invocation.getArgumentAt(0, UploadInfo.class);
                assertThat(upload.getLength(), is(nullValue()));
                assertThat(upload.getEncodedMetadata(), is(nullValue()));

                upload.setId(id);

                return upload;
            }
        });

        handler.process(HttpMethod.POST, servletRequest, new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, times(1)).create(Matchers.any(UploadInfo.class), anyString());
        assertThat(servletResponse.getHeader(HttpHeader.LOCATION), endsWith("/test/upload/" + id.toString()));
        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    }
}