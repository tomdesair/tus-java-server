package me.desair.tus.server;

import me.desair.tus.server.core.CoreProtocol;
import me.desair.tus.server.creation.CreationExtension;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.DiskUploadStorageService;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;

/**
 * Helper class that implements the server side tus v1.0.0 upload protocol
 */
public class TusFileUploadHandler {

    public static final String TUS_API_VERSION = "1.0.0";

    private static final Logger log = LoggerFactory.getLogger(TusFileUploadHandler.class);

    private UploadStorageService uploadStorageService;
    private UploadIdFactory idFactory = new UploadIdFactory();
    private LinkedHashMap<String, TusFeature> enabledFeatures = new LinkedHashMap<>();

    public TusFileUploadHandler() {
        uploadStorageService = new DiskUploadStorageService(idFactory, System.getProperty("java.io.tmpdir"));
        initFeatures();
    }

    protected void initFeatures() {
        addTusFeature(new CoreProtocol());
        addTusFeature(new CreationExtension());
    }

    public TusFileUploadHandler withFileStoreService(final UploadStorageService uploadStorageService) {
        Validate.notNull(uploadStorageService, "The UploadStorageService cannot be null");
        this.uploadStorageService = uploadStorageService;
        return this;
    }

    public TusFileUploadHandler withContextPath(final String contextPath) {
        this.idFactory.setUploadURI(contextPath);
        return this;
    }

    public TusFileUploadHandler addTusFeature(final TusFeature feature) {
        Validate.notNull(feature, "A custom feature cannot be null");
        enabledFeatures.put(feature.getName(), feature);
        return this;
    }

    public void process(final HttpServletRequest servletRequest, final HttpServletResponse servletResponse) throws IOException {
        Validate.notNull(servletRequest, "The HTTP Servlet request cannot be null");
        Validate.notNull(servletResponse, "The HTTP Servlet response cannot be null");

        HttpMethod method = HttpMethod.getMethod(servletRequest);

        log.debug("Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

        try {
            validateRequest(method, servletRequest);

            processByFeatures(method, servletRequest, new TusServletResponse(servletResponse));

        } catch (TusException e) {
            processTusException(method, servletRequest, servletResponse, e);
        }
    }

    public OutputStream getUploadedBytes(final String uploadURI) {
        return uploadStorageService.getUploadedBytes(uploadURI);
    }

    public UploadInfo getUploadInfo(final String uploadURI) {
        return uploadStorageService.getUploadInfo(uploadURI);
    }

    protected void processByFeatures(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse) throws IOException {
        for (TusFeature feature : enabledFeatures.values()) {
            feature.process(method, servletRequest, servletResponse, uploadStorageService, idFactory);
        }
    }

    protected void validateRequest(final HttpMethod method, final HttpServletRequest servletRequest) throws TusException {
        for (TusFeature feature : enabledFeatures.values()) {
            feature.validate(method, servletRequest, uploadStorageService, idFactory);
        }
    }

    private void processTusException(final HttpMethod method, final HttpServletRequest servletRequest,
                                     final HttpServletResponse servletResponse, final TusException ex) throws IOException {
        int status = ex.getStatus();
        String message = ex.getMessage();
        log.warn("Unable to process request {} {}. Sent response status {} with message \"{}\"", method, servletRequest.getRequestURL(), status, message);
        servletResponse.sendError(status, message);
    }

}
