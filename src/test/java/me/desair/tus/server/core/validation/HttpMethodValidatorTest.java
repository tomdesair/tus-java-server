package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.core.validation.HttpMethodValidator;
import me.desair.tus.server.exception.UnsupportedMethodException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
        validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, idFactory);
    }

    @Test(expected = UnsupportedMethodException.class)
    public void validateInvalid() throws Exception {
        validator.validate(null, servletRequest, uploadStorageService, idFactory);
    }
}