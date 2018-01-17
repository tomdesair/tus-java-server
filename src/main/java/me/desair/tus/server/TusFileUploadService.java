package me.desair.tus.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.checksum.ChecksumExtension;
import me.desair.tus.server.core.CoreProtocol;
import me.desair.tus.server.creation.CreationExtension;
import me.desair.tus.server.download.DownloadExtension;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.expiration.ExpirationExtension;
import me.desair.tus.server.termination.TerminationExtension;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.disk.DiskLockingService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class that implements the server side tus v1.0.0 upload protocol
 */
public class TusFileUploadService {

    public static final String TUS_API_VERSION = "1.0.0";

    private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

    private UploadStorageService uploadStorageService;
    private UploadLockingService uploadLockingService;
    private UploadIdFactory idFactory = new UploadIdFactory();
    private final LinkedHashMap<String, TusFeature> enabledFeatures = new LinkedHashMap<>();
    private final Set<HttpMethod> supportedHttpMethods = EnumSet.noneOf(HttpMethod.class);

    public TusFileUploadService() {
        String storagePath = FileUtils.getTempDirectoryPath() + File.separator + "tus";
        this.uploadStorageService = new DiskStorageService(idFactory, storagePath);
        this.uploadLockingService = new DiskLockingService(idFactory, storagePath);
        initFeatures();
    }

    protected void initFeatures() {
        //The order of the features is important
        addTusFeature(new CoreProtocol());
        addTusFeature(new CreationExtension());
        addTusFeature(new ChecksumExtension());
        addTusFeature(new TerminationExtension());
        addTusFeature(new ExpirationExtension());
    }

    public TusFileUploadService withUploadURI(final String uploadURI) {
        Validate.notBlank(uploadURI, "The upload URI cannot be blank");
        this.idFactory.setUploadURI(uploadURI);
        return this;
    }

    public TusFileUploadService withMaxUploadSize(final Long maxUploadSize) {
        Validate.exclusiveBetween(0, Long.MAX_VALUE, maxUploadSize, "The max upload size must be bigger than 0");
        this.uploadStorageService.setMaxUploadSize(maxUploadSize);
        return this;
    }

    public TusFileUploadService withUploadStorageService(final UploadStorageService uploadStorageService) {
        Validate.notNull(uploadStorageService, "The UploadStorageService cannot be null");
        //Copy over any previous configuration
        uploadStorageService.setMaxUploadSize(this.uploadStorageService.getMaxUploadSize());
        //Update the upload storage service
        this.uploadStorageService = uploadStorageService;
        return this;
    }

    public TusFileUploadService withUploadLockingService(final UploadLockingService uploadLockingService) {
        Validate.notNull(uploadLockingService, "The UploadStorageService cannot be null");
        //Update the upload storage service
        this.uploadLockingService = uploadLockingService;
        return this;
    }

    public TusFileUploadService withStoragePath(final String storagePath) {
        Validate.notBlank(storagePath, "The storage path cannot be blank");
        withUploadStorageService(new DiskStorageService(idFactory, storagePath));
        withUploadLockingService(new DiskLockingService(idFactory, storagePath));
        return this;
    }

    public TusFileUploadService withUploadExpirationPeriod(final long expirationPeriod) {
        uploadStorageService.setUploadExpirationPeriod(expirationPeriod);
        return this;
    }

    public TusFileUploadService withDownloadFeature() {
        addTusFeature(new DownloadExtension());
        return this;
    }

    public TusFileUploadService addTusFeature(final TusFeature feature) {
        Validate.notNull(feature, "A custom feature cannot be null");
        enabledFeatures.put(feature.getName(), feature);
        updateSupportedHttpMethods();
        return this;
    }

    public TusFileUploadService disableTusFeature(final String featureName) {
        Validate.notNull(featureName, "The feature name cannot be null");

        if(StringUtils.equals("core", featureName)) {
            throw new IllegalArgumentException("The core protocol cannot be disabled");
        }

        enabledFeatures.remove(featureName);
        updateSupportedHttpMethods();
        return this;
    }

    public Set<HttpMethod> getSupportedHttpMethods() {
        return EnumSet.copyOf(supportedHttpMethods);
    }

    public Set<String> getEnabledFeatures() {
        return new LinkedHashSet<>(enabledFeatures.keySet());
    }

    public void process(final HttpServletRequest servletRequest, final HttpServletResponse servletResponse) throws IOException {
        process(servletRequest, servletResponse, null);
    }

    public void process(final HttpServletRequest servletRequest, final HttpServletResponse servletResponse, final String ownerKey) throws IOException {
        Validate.notNull(servletRequest, "The HTTP Servlet request cannot be null");
        Validate.notNull(servletResponse, "The HTTP Servlet response cannot be null");

        HttpMethod method = HttpMethod.getMethodIfSupported(servletRequest, supportedHttpMethods);

        log.debug("Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

        try {
            validateRequest(method, servletRequest, ownerKey);

            try(UploadLock lock = uploadLockingService.lockUploadByUri(servletRequest.getRequestURI())) {

                executeProcessingByFeatures(method, new TusServletRequest(servletRequest),
                        new TusServletResponse(servletResponse), ownerKey);
            }

        } catch (TusException e) {
            processTusException(method, servletRequest, servletResponse, e);
        }
    }

    public InputStream getUploadedBytes(final String uploadURI, final String ownerKey) throws IOException, TusException {
        try(UploadLock lock = uploadLockingService.lockUploadByUri(uploadURI)) {

            return uploadStorageService.getUploadedBytes(uploadURI, ownerKey);
        }
    }

    public UploadInfo getUploadInfo(final String uploadURI, final String ownerKey) throws IOException, TusException {
        try(UploadLock lock = uploadLockingService.lockUploadByUri(uploadURI)) {

            return uploadStorageService.getUploadInfo(uploadURI, ownerKey);
        }
    }

    /**
     * This method should be invoked periodically. It will cleanup any expired uploads
     * and stale locks
     * @throws IOException When cleaning fails
     */
    public void cleanup() throws IOException {
        uploadLockingService.cleanupStaleLocks();
        uploadStorageService.cleanupExpiredUploads(uploadLockingService);
    }

    protected void executeProcessingByFeatures(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse, final String ownerKey) throws IOException, TusException {
        for (TusFeature feature : enabledFeatures.values()) {
            feature.process(method, servletRequest, servletResponse, uploadStorageService, ownerKey);
        }
    }

    protected void validateRequest(final HttpMethod method, final HttpServletRequest servletRequest, final String ownerKey) throws TusException, IOException {
        for (TusFeature feature : enabledFeatures.values()) {
            feature.validate(method, servletRequest, uploadStorageService, ownerKey);
        }
    }

    private void processTusException(final HttpMethod method, final HttpServletRequest servletRequest,
                                     final HttpServletResponse servletResponse, final TusException ex) throws IOException {
        int status = ex.getStatus();
        String message = ex.getMessage();
        log.warn("Unable to process request {} {}. Sent response status {} with message \"{}\"", method, servletRequest.getRequestURL(), status, message);
        servletResponse.sendError(status, message);
    }

    private void updateSupportedHttpMethods() {
        supportedHttpMethods.clear();
        for (TusFeature tusFeature : enabledFeatures.values()) {
            supportedHttpMethods.addAll(tusFeature.getMinimalSupportedHttpMethods());
        }
    }

}
