package me.desair.tus.server;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.file.FileStorageService;
import me.desair.tus.server.validation.HttpMethodValidator;
import me.desair.tus.server.validation.RequestValidator;
import me.desair.tus.server.validation.TusResumableValidator;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class that implements the server side tus v1.0.0 upload protocol
 */
public class TusFileUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(TusFileUploadHandler.class);

    private HttpServletRequest servletRequest;
    private HttpServletResponse servletResponse;
    private FileStorageService fileStorageService;
    private List<RequestValidator> requestValidators;

    public TusFileUploadHandler(final HttpServletRequest servletRequest, final HttpServletResponse servletResponse) {
        Validate.notNull(servletRequest, "The HTTP Servlet request cannot be null");
        Validate.notNull(servletResponse, "The HTTP Servlet response cannot be null");
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;

        requestValidators = Arrays.asList(
                new HttpMethodValidator(),
                new TusResumableValidator());
    }

    public TusFileUploadHandler withFileStoreService(final FileStorageService fileStorageService) {
        Validate.notNull(fileStorageService, "The FileStorageService cannot be null");
        this.fileStorageService = fileStorageService;
        return this;
    }

    public TusFileUploadHandler addRequestValidator(final RequestValidator requestValidator) {
        Validate.notNull(requestValidator, "A request validator cannot be null");
        requestValidators.add(requestValidator);
        return this;
    }

    public void process() throws IOException {
        HttpMethod method = HttpMethod.getMethod(servletRequest);
        log.debug("Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

        try {
            validateRequest(method);

            //TODO process tus upload

        } catch (TusException e) {
            processTusException(method, e);
        }
    }

    protected void validateRequest(final HttpMethod method) throws TusException {
        for (RequestValidator requestValidator : requestValidators) {
            requestValidator.validate(method, servletRequest);
        }
    }

    private void processTusException(final HttpMethod method, final TusException ex) throws IOException {
        int status = ex.getStatus();
        String message = ex.getMessage();
        log.warn("Unable to process request {} {}. Sent response status {} with message \"{}\"", method, servletRequest.getRequestURL(), status, message);
        servletResponse.sendError(status, message);
    }

}
