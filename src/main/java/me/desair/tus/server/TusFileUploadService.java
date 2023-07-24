package me.desair.tus.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
import me.desair.tus.server.upload.UuidUploadIdFactory;
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

/** Helper class that implements the server side tus v1.0.0 upload protocol */
public class TusFileUploadService {

  public static final String TUS_API_VERSION = "1.0.0";

  private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

  private UploadStorageService uploadStorageService;
  private UploadLockingService uploadLockingService;
  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private final LinkedHashMap<String, TusExtension> enabledFeatures = new LinkedHashMap<>();
  private final Set<HttpMethod> supportedHttpMethods = EnumSet.noneOf(HttpMethod.class);
  private boolean isThreadLocalCacheEnabled = false;
  private boolean isChunkedTransferDecodingEnabled = false;

  /** Constructor. */
  public TusFileUploadService() {
    String storagePath = FileUtils.getTempDirectoryPath() + File.separator + "tus";
    this.uploadStorageService = new DiskStorageService(idFactory, storagePath);
    this.uploadLockingService = new DiskLockingService(idFactory, storagePath);
    initFeatures();
  }

  protected void initFeatures() {
    // The order of the features is important
    addTusExtension(new CoreProtocol());
    addTusExtension(new CreationExtension());
    addTusExtension(new ChecksumExtension());
    addTusExtension(new TerminationExtension());
    addTusExtension(new ExpirationExtension());
    addTusExtension(new ConcatenationExtension());
  }

  /**
   * Set the URI under which the main tus upload endpoint is hosted. Optionally, this URI may
   * contain regex parameters in order to support endpoints that contain URL parameters, for example
   * /users/[0-9]+/files/upload
   *
   * @param uploadUri The URI of the main tus upload endpoint
   * @return The current service
   */
  public TusFileUploadService withUploadUri(String uploadUri) {
    this.idFactory.setUploadUri(uploadUri);
    return this;
  }

  /**
   * Specify the maximum number of bytes that can be uploaded per upload. If you don't call this
   * method, the maximum number of bytes is Long.MAX_VALUE.
   *
   * @param maxUploadSize The maximum upload length that is allowed
   * @return The current service
   */
  public TusFileUploadService withMaxUploadSize(Long maxUploadSize) {
    Validate.exclusiveBetween(
        0, Long.MAX_VALUE, maxUploadSize, "The max upload size must be bigger than 0");
    this.uploadStorageService.setMaxUploadSize(maxUploadSize);
    return this;
  }

  /**
   * Provide a custom {@link UploadIdFactory} implementation that should be used to generate
   * identifiers for the different uploads. Example implementation are {@link
   * me.desair.tus.server.upload.UuidUploadIdFactory} and {@link
   * me.desair.tus.server.upload.TimeBasedUploadIdFactory}.
   *
   * @param uploadIdFactory The custom {@link UploadIdFactory} implementation
   * @return The current service
   */
  public TusFileUploadService withUploadIdFactory(UploadIdFactory uploadIdFactory) {
    Validate.notNull(uploadIdFactory, "The UploadIdFactory cannot be null");
    String previousUploadUri = this.idFactory.getUploadUri();
    this.idFactory = uploadIdFactory;
    this.idFactory.setUploadUri(previousUploadUri);
    this.uploadStorageService.setIdFactory(this.idFactory);
    this.uploadLockingService.setIdFactory(this.idFactory);
    return this;
  }

  /**
   * Provide a custom {@link UploadStorageService} implementation that should be used to store
   * uploaded bytes and metadata ({@link UploadInfo}).
   *
   * @param uploadStorageService The custom {@link UploadStorageService} implementation
   * @return The current service
   */
  public TusFileUploadService withUploadStorageService(UploadStorageService uploadStorageService) {
    Validate.notNull(uploadStorageService, "The UploadStorageService cannot be null");
    // Copy over any previous configuration
    uploadStorageService.setMaxUploadSize(this.uploadStorageService.getMaxUploadSize());
    uploadStorageService.setUploadExpirationPeriod(
        this.uploadStorageService.getUploadExpirationPeriod());
    uploadStorageService.setIdFactory(this.idFactory);
    // Update the upload storage service
    this.uploadStorageService = uploadStorageService;
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * Provide a custom {@link UploadLockingService} implementation that should be used when
   * processing uploads. The upload locking service is responsible for locking an upload that is
   * being processed so that it cannot be corrupted by simultaneous or delayed requests.
   *
   * @param uploadLockingService The {@link UploadLockingService} implementation to use
   * @return The current service
   */
  public TusFileUploadService withUploadLockingService(UploadLockingService uploadLockingService) {
    Validate.notNull(uploadLockingService, "The UploadStorageService cannot be null");
    uploadLockingService.setIdFactory(this.idFactory);
    // Update the upload storage service
    this.uploadLockingService = uploadLockingService;
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * If you're using the default file system-based storage service, you can use this method to
   * specify the path where to store the uploaded bytes and upload information.
   *
   * @param storagePath The file system path where uploads can be stored (temporarily)
   * @return The current service
   */
  public TusFileUploadService withStoragePath(String storagePath) {
    Validate.notBlank(storagePath, "The storage path cannot be blank");
    withUploadStorageService(new DiskStorageService(storagePath));
    withUploadLockingService(new DiskLockingService(storagePath));
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * Enable or disable a thread-local based cache of upload data. This can reduce the load on the
   * storage backends. By default this cache is disabled.
   *
   * @param isEnabled True if the cache should be enabled, false otherwise
   * @return The current service
   */
  public TusFileUploadService withThreadLocalCache(boolean isEnabled) {
    this.isThreadLocalCacheEnabled = isEnabled;
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * Instruct this service to (not) decode any requests with Transfer-Encoding value "chunked". Use
   * this method in case the web container in which this service is running does not decode chunked
   * transfers itself. By default, chunked decoding is disabled.
   *
   * @param isEnabled True if chunked requests should be decoded, false otherwise.
   * @return The current service
   */
  public TusFileUploadService withChunkedTransferDecoding(boolean isEnabled) {
    isChunkedTransferDecodingEnabled = isEnabled;
    return this;
  }

  /**
   * You can set the number of milliseconds after which an upload is considered as expired and
   * available for cleanup.
   *
   * @param expirationPeriod The number of milliseconds after which an upload expires and can be
   *     removed
   * @return The current service
   */
  public TusFileUploadService withUploadExpirationPeriod(Long expirationPeriod) {
    uploadStorageService.setUploadExpirationPeriod(expirationPeriod);
    return this;
  }

  /**
   * Enable the unofficial `download` extension that also allows you to download uploaded bytes. By
   * default this feature is disabled.
   *
   * @return The current service
   */
  public TusFileUploadService withDownloadFeature() {
    addTusExtension(new DownloadExtension());
    return this;
  }

  /**
   * Add a custom (application-specific) extension that implements the {@link
   * me.desair.tus.server.TusExtension} interface. For example you can add your own extension that
   * checks authentication and authorization policies within your application for the user doing the
   * upload.
   *
   * @param feature The custom extension implementation
   * @return The current service
   */
  public TusFileUploadService addTusExtension(TusExtension feature) {
    Validate.notNull(feature, "A custom feature cannot be null");
    enabledFeatures.put(feature.getName(), feature);
    updateSupportedHttpMethods();
    return this;
  }

  /**
   * Disable the TusExtension for which the getName() method matches the provided string. The
   * default extensions have names "creation", "checksum", "expiration", "concatenation",
   * "termination" and "download". You cannot disable the "core" feature.
   *
   * @param extensionName The name of the extension to disable
   * @return The current service
   */
  public TusFileUploadService disableTusExtension(String extensionName) {
    Validate.notNull(extensionName, "The extension name cannot be null");
    Validate.isTrue(
        !StringUtils.equals("core", extensionName), "The core protocol cannot be disabled");

    enabledFeatures.remove(extensionName);
    updateSupportedHttpMethods();
    return this;
  }

  /**
   * Get all HTTP methods that are supported by this TusUploadService based on the enabled and/or
   * disabled tus extensions.
   *
   * @return The set of enabled HTTP methods
   */
  public Set<HttpMethod> getSupportedHttpMethods() {
    return EnumSet.copyOf(supportedHttpMethods);
  }

  /**
   * Get the set of enabled Tus extensions.
   *
   * @return The set of active extensions
   */
  public Set<String> getEnabledFeatures() {
    return new LinkedHashSet<>(enabledFeatures.keySet());
  }

  /**
   * Process a tus upload request. Use this method to process any request made to the main and sub
   * tus upload endpoints. This corresponds to the path specified in the withUploadUri() method and
   * any sub-path of that URI.
   *
   * @param servletRequest The {@link HttpServletRequest} of the request
   * @param servletResponse The {@link HttpServletResponse} of the request
   * @throws IOException When saving bytes or information of this requests fails
   */
  public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws IOException {
    process(servletRequest, servletResponse, null);
  }

  /**
   * Process a tus upload request that belongs to a specific owner. Use this method to process any
   * request made to the main and sub tus upload endpoints. This corresponds to the path specified
   * in the withUploadUri() method and any sub-path of that URI.
   *
   * @param servletRequest The {@link HttpServletRequest} of the request
   * @param servletResponse The {@link HttpServletResponse} of the request
   * @param ownerKey A unique identifier of the owner (group) of this upload
   * @throws IOException When saving bytes or information of this requests fails
   */
  public void process(
      HttpServletRequest servletRequest, HttpServletResponse servletResponse, String ownerKey)
      throws IOException {
    Validate.notNull(servletRequest, "The HTTP Servlet request cannot be null");
    Validate.notNull(servletResponse, "The HTTP Servlet response cannot be null");

    HttpMethod method = HttpMethod.getMethodIfSupported(servletRequest, supportedHttpMethods);

    log.debug(
        "Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

    TusServletRequest request =
        new TusServletRequest(servletRequest, isChunkedTransferDecodingEnabled);
    TusServletResponse response = new TusServletResponse(servletResponse);

    try (UploadLock lock = uploadLockingService.lockUploadByUri(request.getRequestURI())) {

      processLockedRequest(method, request, response, ownerKey);

    } catch (TusException e) {
      log.error("Unable to lock upload for request URI " + request.getRequestURI(), e);
    }
  }

  /**
   * Method to retrieve the bytes that were uploaded to a specific upload URI.
   *
   * @param uploadUri The URI of the upload
   * @return An {@link InputStream} that will stream the uploaded bytes
   * @throws IOException When the retreiving the uploaded bytes fails
   * @throws TusException When the upload is still in progress or cannot be found
   */
  public InputStream getUploadedBytes(String uploadUri) throws IOException, TusException {
    return getUploadedBytes(uploadUri, null);
  }

  /**
   * Method to retrieve the bytes that were uploaded to a specific upload URI.
   *
   * @param uploadUri The URI of the upload
   * @param ownerKey The key of the owner of this upload
   * @return An {@link InputStream} that will stream the uploaded bytes
   * @throws IOException When the retreiving the uploaded bytes fails
   * @throws TusException When the upload is still in progress or cannot be found
   */
  public InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, TusException {

    try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadUri)) {

      return uploadStorageService.getUploadedBytes(uploadUri, ownerKey);
    }
  }

  /**
   * Get the information on the upload corresponding to the given upload URI.
   *
   * @param uploadUri The URI of the upload
   * @return Information on the upload
   * @throws IOException When retrieving the upload information fails
   * @throws TusException When the upload is still in progress or cannot be found
   */
  public UploadInfo getUploadInfo(String uploadUri) throws IOException, TusException {
    return getUploadInfo(uploadUri, null);
  }

  /**
   * Get the information on the upload corresponding to the given upload URI.
   *
   * @param uploadUri The URI of the upload
   * @param ownerKey The key of the owner of this upload
   * @return Information on the upload
   * @throws IOException When retrieving the upload information fails
   * @throws TusException When the upload is still in progress or cannot be found
   */
  public UploadInfo getUploadInfo(String uploadUri, String ownerKey)
      throws IOException, TusException {
    try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadUri)) {

      return uploadStorageService.getUploadInfo(uploadUri, ownerKey);
    }
  }

  /**
   * Method to delete an upload associated with the given upload URL. Invoke this method if you no
   * longer need the upload.
   *
   * @param uploadUri The upload URI
   */
  public void deleteUpload(String uploadUri) throws IOException, TusException {
    deleteUpload(uploadUri, null);
  }

  /**
   * Method to delete an upload associated with the given upload URL. Invoke this method if you no
   * longer need the upload.
   *
   * @param uploadUri The upload URI
   * @param ownerKey The key of the owner of this upload
   */
  public void deleteUpload(String uploadUri, String ownerKey) throws IOException, TusException {
    try (UploadLock lock = uploadLockingService.lockUploadByUri(uploadUri)) {
      UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
      if (uploadInfo != null) {
        uploadStorageService.terminateUpload(uploadInfo);
      }
    }
  }

  /**
   * This method should be invoked periodically. It will cleanup any expired uploads and stale locks
   *
   * @throws IOException When cleaning fails
   */
  public void cleanup() throws IOException {
    uploadLockingService.cleanupStaleLocks();
    uploadStorageService.cleanupExpiredUploads(uploadLockingService);
  }

  protected void processLockedRequest(
      HttpMethod method, TusServletRequest request, TusServletResponse response, String ownerKey)
      throws IOException {
    try {
      validateRequest(method, request, ownerKey);

      executeProcessingByFeatures(method, request, response, ownerKey);

    } catch (TusException e) {
      processTusException(method, request, response, ownerKey, e);
    }
  }

  protected void executeProcessingByFeatures(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      String ownerKey)
      throws IOException, TusException {

    for (TusExtension feature : enabledFeatures.values()) {
      if (!servletRequest.isProcessedBy(feature)) {
        servletRequest.addProcessor(feature);
        feature.process(method, servletRequest, servletResponse, uploadStorageService, ownerKey);
      }
    }
  }

  protected void validateRequest(
      HttpMethod method, HttpServletRequest servletRequest, String ownerKey)
      throws TusException, IOException {

    for (TusExtension feature : enabledFeatures.values()) {
      feature.validate(method, servletRequest, uploadStorageService, ownerKey);
    }
  }

  protected void processTusException(
      HttpMethod method,
      TusServletRequest request,
      TusServletResponse response,
      String ownerKey,
      TusException exception)
      throws IOException {

    int status = exception.getStatus();
    String message = exception.getMessage();

    log.warn(
        "Unable to process request {} {}. Sent response status {} with message \"{}\"",
        method,
        request.getRequestURL(),
        status,
        message);

    try {
      for (TusExtension feature : enabledFeatures.values()) {

        if (!request.isProcessedBy(feature)) {
          request.addProcessor(feature);
          feature.handleError(method, request, response, uploadStorageService, ownerKey);
        }
      }

      // Since an error occurred, the bytes we have written are probably not valid. So remove
      // them.
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

  private void prepareCacheIfEnabled() {
    if (isThreadLocalCacheEnabled && uploadStorageService != null && uploadLockingService != null) {
      ThreadLocalCachedStorageAndLockingService service =
          new ThreadLocalCachedStorageAndLockingService(uploadStorageService, uploadLockingService);
      service.setIdFactory(this.idFactory);
      this.uploadStorageService = service;
      this.uploadLockingService = service;
    }
  }
}
