package me.desair.tus.server.creation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.*;

import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.AbstractTusFeatureIntegrationTest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidUploadLengthException;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.PostOnInvalidRequestURIException;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ITCreationExtension extends AbstractTusFeatureIntegrationTest {

    private UUID id;

    @Before
    public void setUp() throws Exception {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        tusFeature = new CreationExtension();
        uploadInfo = null;

        id =  UUID.randomUUID();
        servletRequest.setRequestURI("/test/upload");
        when(uploadStorageService.getUploadURI()).thenReturn("/test/upload");
        when(uploadStorageService.create(Matchers.any(UploadInfo.class))).then(new Answer<UploadInfo>() {
            @Override
            public UploadInfo answer(final InvocationOnMock invocation) throws Throwable {
                UploadInfo upload = invocation.getArgumentAt(0, UploadInfo.class);
                upload.setId(id);

                when(uploadStorageService.getUploadInfo("/test/upload/" + id.toString())).thenReturn(upload);
                return upload;
            }
        });
    }

    @Test
    public void testOptions() throws Exception {
        setRequestHeaders();

        executeCall(HttpMethod.OPTIONS);

        //If the Server supports this extension, it MUST add creation to the Tus-Extension header.
        //If the Server supports deferring length, it MUST add creation-defer-length to the Tus-Extension header.
        assertResponseHeader(HttpHeader.TUS_EXTENSION, "creation", "creation-defer-length");
    }

    @Test
    public void testPostWithLength() throws Exception {
        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 9);

        executeCall(HttpMethod.POST);

        verify(uploadStorageService, times(1)).create(notNull(UploadInfo.class));
        assertResponseHeader(HttpHeader.LOCATION, "/test/upload/" + id.toString());
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        //Check data with head request
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.HEAD);

        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));

        //Test Patch request
        servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 9);
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.PATCH);
    }

    @Test
    public void testPostWithDeferredLength() throws Exception {
        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_DEFER_LENGTH, 1);

        executeCall(HttpMethod.POST);

        verify(uploadStorageService, times(1)).create(notNull(UploadInfo.class));
        assertResponseHeader(HttpHeader.LOCATION, "/test/upload/" + id.toString());
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        //Check data with head request
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.HEAD);

        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is("1"));

        //Test Patch request
        servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 9);
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.PATCH);

        //Re-check head request
        servletRequest = new MockHttpServletRequest();
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.HEAD);

        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));
    }

    @Test(expected = InvalidUploadLengthException.class)
    public void testPostWithoutLength() throws Exception {
        //Create upload without any length header
        executeCall(HttpMethod.POST);
    }

    @Test
    public void testPostWithMetadata() throws Exception {
        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 9);
        servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "encoded metadata");

        executeCall(HttpMethod.POST);

        verify(uploadStorageService, times(1)).create(notNull(UploadInfo.class));
        assertResponseHeader(HttpHeader.LOCATION, "/test/upload/" + id.toString());
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        //Check data with head request
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.HEAD);

        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is("encoded metadata"));
        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));
    }

    @Test
    public void testPostWithAllowedMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(100L);

        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 90);
        executeCall(HttpMethod.POST);

        verify(uploadStorageService, times(1)).create(notNull(UploadInfo.class));
        assertResponseHeader(HttpHeader.LOCATION, "/test/upload/" + id.toString());
        assertResponseStatus(HttpServletResponse.SC_CREATED);

        //Check data with head request
        servletRequest.setRequestURI("/test/upload/" + id.toString());
        servletResponse = new MockHttpServletResponse();
        executeCall(HttpMethod.HEAD);

        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), is(nullValue()));
        assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_DEFER_LENGTH), is(nullValue()));
    }

    @Test(expected = MaxUploadLengthExceededException.class)
    public void testPostWithExceededMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(100L);

        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 110);
        executeCall(HttpMethod.POST);
    }

    @Test(expected = PostOnInvalidRequestURIException.class)
    public void testPostOnInvalidUrl() throws Exception {
        //Create upload
        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 9);
        servletRequest.setRequestURI("/test/upload/" + id.toString());

        executeCall(HttpMethod.POST);
    }
}