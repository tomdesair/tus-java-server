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
import me.desair.tus.server.concatenation.ConcatenationExtension;
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
import me.desair.tus.server.upload.cache.ThreadLocalCachedStorageAndLockingService;
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
    private final LinkedHashMap<String, TusExtension> enabledFeatures = new LinkedHashMap<>();
    private final Set<HttpMethod> supportedHttpMethods = EnumSet.noneOf(HttpMethod.class);
    private boolean isThreadLocalCacheEnabled = false;
    private boolean isChunkedTransferDecodingEnabled = false;

    public TusFileUploadService() {
        String storagePath = FileUtils.getTempDirectoryPath() + File.separator + "tus";
        this.uploadStorageService = new DiskStorageService(idFactory, storagePath);
        this.uploadLockingService = new DiskLockingService(idFactory, storagePath);
        initFeatures();
    }

    protected void initFeatures() {
        //The order of the features is important
        addTusExtension(new CoreProtocol());
        addTusExtension(new CreationExtension());
        addTusExtension(new ChecksumExtension());
        addTusExtension(new TerminationExtension());
        addTusExtension(new ExpirationExtension());
        addTusExtension(new ConcatenationExtension());
    }

    public TusFileUploadService withUploadURI(String uploadURI) {
        Validate.notBlank(uploadURI, "The upload URI cannot be blank");
        this.idFactory.setUploadURI(uploadURI);
        return this;
    }

    public TusFileUploadService withMaxUploadSize(Long maxUploadSize) {
        Validate.exclusiveBetween(0, Long.MAX_VALUE, maxUploadSize, "The max upload size must be bigger than 0");
        this.uploadStorageService.setMaxUploadSize(maxUploadSize);
        return this;
    }

    public TusFileUploadService withUploadStorageService(UploadStorageService uploadStorageService) {
        Validate.notNull(uploadStorageService, "The UploadStorageService cannot be null");
        //Copy over any previous configuration
        uploadStorageService.setMaxUploadSize(this.uploadStorageService.getMaxUploadSize());
        uploadStorageService.setUploadExpirationPeriod(this.uploadStorageService.getUploadExpirationPeriod());
        uploadStorageService.setIdFactory(this.idFactory);
        //Update the upload storage service
        this.uploadStorageService = uploadStorageService;
        prepareCacheIfEnable();
        return this;
    }

    public TusFileUploadService withUploadLockingService(UploadLockingService uploadLockingService) {
        Validate.notNull(uploadLockingService, "The UploadStorageService cannot be null");
        uploadLockingService.setIdFactory(this.idFactory);
        //Update the upload storage service
        this.uploadLockingService = uploadLockingService;
        prepareCacheIfEnable();
        return this;
    }

    public TusFileUploadService withStoragePath(String storagePath) {
        Validate.notBlank(storagePath, "The storage path cannot be blank");
        withUploadStorageService(new DiskStorageService(idFactory, storagePath));
        withUploadLockingService(new DiskLockingService(idFactory, storagePath));
        prepareCacheIfEnable();
        return this;
    }

    public TusFileUploadService withThreadLocalCache(boolean isEnable) {
        this.isThreadLocalCacheEnabled = isEnable;
        prepareCacheIfEnable();
        return this;
    }

    public TusFileUploadService withUploadExpirationPeriod(long expirationPeriod) {
        uploadStorageService.setUploadExpirationPeriod(expirationPeriod);
        return this;
    }

    public TusFileUploadService withDownloadFeature() {
        addTusExtension(new DownloadExtension());
        return this;
    }

    public TusFileUploadService addTusExtension(TusExtension feature) {
        Validate.notNull(feature, "A custom feature cannot be null");
        enabledFeatures.put(feature.getName(), feature);
        updateSupportedHttpMethods();
        return this;
    }

    public TusFileUploadService disableTusExtension(String featureName) {
        Validate.notNull(featureName, "The feature name cannot be null");

        if (StringUtils.equals("core", featureName)) {
            throw new IllegalArgumentException("The core protocol cannot be disabled");
        }

        enabledFeatures.remove(featureName);
        updateSupportedHttpMethods();
        return this;
    }

    /**
     * Instruct this service to decode any requests with Transfer-Encoding value "chunked".
     * Use this method in case the web container in which this service is running does not decode
     * chunked transfers itself.
     *
     * @return The current service
     */
    public TusFileUploadService enableChunkedTransferDecoding() {
        isChunkedTransferDecodingEnabled = true;
        return this;
    }

    public Set<HttpMethod> getSupportedHttpMethods() {
        return EnumSet.copyOf(supportedHttpMethods);
    }

    public Set<String> getEnabledFeatures() {
        return new LinkedHashSet<>(enabledFeatures.keySet());
    }

    public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
            throws IOException {
        process(servletRequest, servletResponse, null);
    }

    public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
                        String ownerKey) throws IOException {
        Validate.notNull(servletRequest, "The HTTP Servlet request cannot be null");
        Validate.notNull(servletResponse, "The HTTP Servlet response cannot be null");

        HttpMethod method = HttpMethod.getMethodIfSupported(servletRequest, supportedHttpMethods);

        log.debug("Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

        TusServletRequest request = new TusServletRequest(servletRequest, isChunkedTransferDecodingEnabled);
        TusServletResponse response = new TusServletResponse(servletResponse);

        try (UploadLock lock = uploadLockingService.lockUploadByUri(request.getRequestURI())) {

            processLockedRequest(method, request, response, ownerKey);

        } catch (TusException e) {
            log.error("Unable to lock upload for request URI " + request.getRequestURI(), e);
        }
    }

    /**
     * Method to retrieve the bytes that were uploaded to a specific upload URI
     *
     * @param uploadURI The URI of the upload
     * @return An {@link InputStream} that will stream the uploaded bytes
     * @throws IOException  When the retreiving the uploaded bytes fails
     * @throws TusException When the upload is still in progress or cannot be found
     */
    public InputStream getUploadedBytes(String uploadURI) throws IOException, TusException {
        return getUploadedBytes(uploadURI, null);
    }

    /**
     * Method to retrieve the bytes that were uploaded to a specific upload URI
     *
     * @param uploadURI The URI of the upload
     * @param ownerKey  The key of the owner of this upload
     * @return An {@link InputStream} that will stream the uploaded bytes
     * @throws IOException  When the retreiving the uploaded bytes fails
     * @throws TusException When the upload is still in progress or cannot be found
     */
    public InputStream getUploadedBytes(String uploadURI, String ownerKey)
            throws IOException, TusException {

        try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadURI)) {

            return uploadStorageService.getUploadedBytes(uploadURI, ownerKey);
        }
    }

    /**
     * Get the information on the upload corresponding to the given upload URI
     *
     * @param uploadURI The URI of the upload
     * @return Information on the upload
     * @throws IOException  When retrieving the upload information fails
     * @throws TusException When the upload is still in progress or cannot be found
     */
    public UploadInfo getUploadInfo(String uploadURI) throws IOException, TusException {
        return getUploadInfo(uploadURI, null);
    }

    /**
     * Get the information on the upload corresponding to the given upload URI
     *
     * @param uploadURI The URI of the upload
     * @param ownerKey  The key of the owner of this upload
     * @return Information on the upload
     * @throws IOException  When retrieving the upload information fails
     * @throws TusException When the upload is still in progress or cannot be found
     */
    public UploadInfo getUploadInfo(String uploadURI, String ownerKey) throws IOException, TusException {
        try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadURI)) {

            return uploadStorageService.getUploadInfo(uploadURI, ownerKey);
        }
    }

    /**
     * Method to delete an upload associated with the given upload URL. Invoke this method if you no longer need
     * the upload.
     *
     * @param uploadURI The upload URI
     */
    public void deleteUpload(String uploadURI) throws IOException, TusException {
        deleteUpload(uploadURI, null);
    }

    /**
     * Method to delete an upload associated with the given upload URL. Invoke this method if you no longer need
     * the upload.
     *
     * @param uploadURI The upload URI
     * @param ownerKey  The key of the owner of this upload
     */
    public void deleteUpload(String uploadURI, String ownerKey) throws IOException, TusException {
        try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadURI)) {
            UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadURI, ownerKey);
            if (uploadInfo != null) {
                uploadStorageService.terminateUpload(uploadInfo);
            }
        }
    }

    /**
     * This method should be invoked periodically. It will cleanup any expired uploads
     * and stale locks
     *
     * @throws IOException When cleaning fails
     */
    public void cleanup() throws IOException {
        uploadLockingService.cleanupStaleLocks();
        uploadStorageService.cleanupExpiredUploads(uploadLockingService);
    }

    protected void processLockedRequest(HttpMethod method, TusServletRequest request,
                                        TusServletResponse response, String ownerKey) throws IOException {
        try {
            validateRequest(method, request, ownerKey);

            executeProcessingByFeatures(method, request, response, ownerKey);

        } catch (TusException e) {
            processTusException(method, request, response, ownerKey, e);
        }
    }

    protected void executeProcessingByFeatures(HttpMethod method, TusServletRequest servletRequest,
                                               TusServletResponse servletResponse, String ownerKey)
            throws IOException, TusException {

        for (TusExtension feature : enabledFeatures.values()) {
            if (!servletRequest.isProcessedBy(feature)) {
                servletRequest.addProcessor(feature);
                feature.process(method, servletRequest, servletResponse, uploadStorageService, ownerKey);
            }
        }
    }

    protected void validateRequest(HttpMethod method, HttpServletRequest servletRequest,
                                   String ownerKey) throws TusException, IOException {

        for (TusExtension feature : enabledFeatures.values()) {
            feature.validate(method, servletRequest, uploadStorageService, ownerKey);
        }
    }

    protected void processTusException(HttpMethod method, TusServletRequest request,
                                       TusServletResponse response, String ownerKey,
                                       TusException exception) throws IOException {

        int status = exception.getStatus();
        String message = exception.getMessage();

        log.warn("Unable to process request {} {}. Sent response status {} with message \"{}\"",
                method, request.getRequestURL(), status, message);

        try {
            for (TusExtension feature : enabledFeatures.values()) {

                if (!request.isProcessedBy(feature)) {
                    request.addProcessor(feature);
                    feature.handleError(method, request, response, uploadStorageService, ownerKey);
                }
            }

            //Since an error occurred, the bytes we have written are probably not valid. So remove them.
            UploadInfo uploadInfo = uploadStorageService.getUploadInfo(request.getRequestURI(), ownerKey);
            uploadStorageService.removeLastNumberOfBytes(uploadInfo, request.getBytesRead());

        } catch (TusException ex) {
            log.warn("An exception occurred while handling another exception", ex);
        }

        response.sendError(status, message);
    }

    private void updateSupportedHttpMethods() {
        supportedHttpMethods.clear();
        for (TusExtension tusFeature : enabledFeatures.values()) {
            supportedHttpMethods.addAll(tusFeature.getMinimalSupportedHttpMethods());
        }
    }

    private void prepareCacheIfEnable() {
        if (isThreadLocalCacheEnabled && uploadStorageService != null && uploadLockingService != null) {
            ThreadLocalCachedStorageAndLockingService service =
                    new ThreadLocalCachedStorageAndLockingService(
                            uploadStorageService,
                            uploadLockingService);
            service.setIdFactory(this.idFactory);
            this.uploadStorageService = service;
            this.uploadLockingService = service;
        }
    }
}
