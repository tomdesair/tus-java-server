package me.desair.tus.server;

import me.desair.tus.server.exception.InvalidTusResumableException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class TusResumableValidatorTest {

    private MockHttpServletRequest servletRequest;
    private TusResumableValidator validator;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
    }

    @Test(expected = InvalidTusResumableException.class)
    public void validateNoVersion() throws Exception {
        validator = new TusResumableValidator(HttpMethod.POST, servletRequest);
        validator.validate();
    }

    @Test(expected = InvalidTusResumableException.class)
    public void validateInvalidVersion() throws Exception {
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");
        validator = new TusResumableValidator(HttpMethod.POST, servletRequest);
        validator.validate();
    }

    @Test
    public void validateOptions() throws Exception {
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");
        validator = new TusResumableValidator(HttpMethod.OPTIONS, servletRequest);
        validator.validate();
    }

    @Test
    public void validateValid() throws Exception {
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        validator = new TusResumableValidator(HttpMethod.POST, servletRequest);
        validator.validate();
    }
}