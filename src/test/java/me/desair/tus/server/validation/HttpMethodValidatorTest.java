package me.desair.tus.server.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UnsupportedMethodException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.Assert.*;

public class HttpMethodValidatorTest {

    private MockHttpServletRequest servletRequest;
    private HttpMethodValidator validator;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        validator = new HttpMethodValidator();
    }

    @Test
    public void validateValid() throws Exception {
        validator.validate(HttpMethod.POST, servletRequest);
    }

    @Test(expected = UnsupportedMethodException.class)
    public void validateInvalid() throws Exception {
        validator.validate(null, servletRequest);
    }
}