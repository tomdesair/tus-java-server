package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UnsupportedMethodException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpMethodValidatorTest {

    private MockHttpServletRequest servletRequest;
    private HttpMethodValidator validator;
    private UploadStorageService uploadStorageService;
    private UploadIdFactory idFactory;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        validator = new HttpMethodValidator();
    }

    @Test
    public void validateValid() throws Exception {
        validator.validate(HttpMethod.POST, servletRequest, uploadStorageService);
    }

    @Test(expected = UnsupportedMethodException.class)
    public void validateInvalid() throws Exception {
        validator.validate(null, servletRequest, uploadStorageService);
    }

    @Test
    public void supports() throws Exception {
        assertThat(validator.supports(HttpMethod.GET), is(true));
        assertThat(validator.supports(HttpMethod.POST), is(true));
        assertThat(validator.supports(HttpMethod.PUT), is(true));
        assertThat(validator.supports(HttpMethod.DELETE), is(true));
        assertThat(validator.supports(HttpMethod.HEAD), is(true));
        assertThat(validator.supports(HttpMethod.OPTIONS), is(true));
        assertThat(validator.supports(HttpMethod.PATCH), is(true));
        assertThat(validator.supports(null), is(true));
    }
}