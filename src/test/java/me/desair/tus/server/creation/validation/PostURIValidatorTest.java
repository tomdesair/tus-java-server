package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.PostOnInvalidRequestURIException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.class)
public class PostURIValidatorTest {

    private PostURIValidator validator;

    private MockHttpServletRequest servletRequest;

    @Mock
    private UploadStorageService uploadStorageService;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        validator = new PostURIValidator();
    }

    @Test
    public void supports() throws Exception {
        assertThat(validator.supports(HttpMethod.GET), is(false));
        assertThat(validator.supports(HttpMethod.POST), is(true));
        assertThat(validator.supports(HttpMethod.PUT), is(false));
        assertThat(validator.supports(HttpMethod.DELETE), is(false));
        assertThat(validator.supports(HttpMethod.HEAD), is(false));
        assertThat(validator.supports(HttpMethod.OPTIONS), is(false));
        assertThat(validator.supports(HttpMethod.PATCH), is(false));
        assertThat(validator.supports(null), is(false));
    }

    @Test
    public void validateMatchingUrl() throws Exception {
        servletRequest.setRequestURI("/test/upload");
        when(uploadStorageService.getUploadURI()).thenReturn("/test/upload");

        validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

        //No Exception is thrown
    }

    @Test(expected = PostOnInvalidRequestURIException.class)
    public void validateInvalidUrl() throws Exception {
        servletRequest.setRequestURI("/test/upload/12");
        when(uploadStorageService.getUploadURI()).thenReturn("/test/upload");

        validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

        //Expect PostOnInvalidRequestURIException
    }
}