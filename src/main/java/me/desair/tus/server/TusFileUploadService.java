package me.desair.tus.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import me.desair.tus.server.checksum.ChecksumExtension;
import me.desair.tus.server.concatenation.ConcatenationExtension;
import me.desair.tus.server.core.CoreProtocol;
import me.desair.tus.server.cors.CorsExtension;
import me.desair.tus.server.creation.CreationExtension;
import me.desair.tus.server.creationwithupload.CreationWithUploadExtension;
import me.desair.tus.server.digest.HttpDigestsExtension;
import me.desair.tus.server.download.DownloadExtension;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.expiration.ExpirationExtension;
import me.desair.tus.server.rufh.ResumableUploadsForHttpProtocol;
import me.desair.tus.server.rufh.util.RufhInterimResponseUtil;
import me.desair.tus.server.termination.TerminationExtension;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.upload.cache.ThreadLocalCachedStorageAndLockingService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import me.desair.tus.server.upload.disk.LeaseFileLockingService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class that implements the server side tus v1.0.0 upload protocol and the official IETF
 * Resumable Uploads for HTTP (RUFH) specification.
 */
public class TusFileUploadService implements Closeable {

  public static final String TUS_API_VERSION = "1.0.0";

  private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

  public static final int DEFAULT_MAX_LOCK_RETRIES = 40;

  private UploadStorageService uploadStorageService;
  private UploadLockingService uploadLockingService;
  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private final LinkedHashMap<String, TusExtension> enabledFeatures = new LinkedHashMap<>();
  private final Set<HttpMethod> supportedHttpMethods = EnumSet.noneOf(HttpMethod.class);
  private boolean isThreadLocalCacheEnabled = false;
  private boolean isChunkedTransferDecodingEnabled = false;
  private ProtocolVersion supportedProtocolVersion = ProtocolVersion.AUTO;
  private int maxLockRetries = DEFAULT_MAX_LOCK_RETRIES;
  private final List<UploadCompletionListener> uploadCompletionListeners =
      new CopyOnWriteArrayList<>();

  /** Constructor. */
  public TusFileUploadService() {
    String storagePath = FileUtils.getTempDirectoryPath() + File.separator + "tus";
    this.uploadStorageService = new DiskStorageService(idFactory, storagePath);
    this.uploadLockingService = new LeaseFileLockingService(idFactory, storagePath);
    initFeatures();
  }

  protected void initFeatures() {
    // The order of the features is important
    addTusExtension(new CoreProtocol());
    CreationExtension creationExtension = new CreationExtension();
    addTusExtension(creationExtension);
    addTusExtension(new CreationWithUploadExtension(creationExtension));
    addTusExtension(new ResumableUploadsForHttpProtocol());
    addTusExtension(new ChecksumExtension());
    addTusExtension(new TerminationExtension());
    addTusExtension(new ExpirationExtension());
    addTusExtension(new ConcatenationExtension());
    addTusExtension(new CorsExtension());
    addTusExtension(new HttpDigestsExtension());
  }

  /**
   * Register a callback listener that is invoked when an upload successfully reaches completion.
   *
   * @param listener The completion listener to register
   * @return The current service
   */
  public TusFileUploadService withUploadCompletionListener(UploadCompletionListener listener) {
    return addUploadCompletionListener(listener);
  }

  /**
   * Add a callback listener that is invoked when an upload successfully reaches completion.
   *
   * @param listener The completion listener to add
   * @return The current service
   */
  public TusFileUploadService addUploadCompletionListener(UploadCompletionListener listener) {
    if (listener != null) {
      this.uploadCompletionListeners.add(listener);
    }
    return this;
  }

  /**
   * Configure the supported protocol version(s) for this service.
   *
   * @param supportedProtocolVersion ProtocolVersion setting (TUS_1_0_0, RUFH, or AUTO)
   * @return The current service
   */
  public TusFileUploadService withSupportedProtocolVersions(
      ProtocolVersion supportedProtocolVersion) {
    if (supportedProtocolVersion != null) {
      this.supportedProtocolVersion = supportedProtocolVersion;
    }
    return this;
  }

  /**
   * Generates the raw HTTP 104 interim response frame string for an incoming upload creation
   * request under the IETF Resumable Uploads protocol (RUFH).
   *
   * @param servletRequest The incoming {@link HttpServletRequest}
   * @param ownerKey The owner key identifier for the upload
   * @return The raw HTTP 104 interim response string if applicable, or null if not applicable
   */
  public String getRawInterimResponse(HttpServletRequest servletRequest, String ownerKey) {
    if (detectProtocolVersion(servletRequest) == ProtocolVersion.RUFH) {
      return RufhInterimResponseUtil.getRawInterimResponse(
          servletRequest, uploadStorageService, ownerKey);
    }
    return null;
  }

  /**
   * Get the configured protocol version setting.
   *
   * @return Current ProtocolVersion configuration
   */
  public ProtocolVersion getSupportedProtocolVersion() {
    return supportedProtocolVersion;
  }

  /**
   * Set the URI or absolute URL under which the main tus upload endpoint is hosted. This can be a
   * relative path (for example <code>/files/upload</code>) or an absolute URL (for example <code>
   * https://upload.example.com/files/upload</code>). When an absolute URL is provided, the Location
   * header in creation responses will contain the full URL. Optionally, this URI may contain regex
   * parameters in order to support endpoints that contain URL parameters, for example <code>
   * /users/[0-9]+/files/upload</code> or <code>https://upload.example.com/users/[0-9]+/files/upload
   * </code>.
   *
   * @param uploadUri The URI or URL of the main tus upload endpoint
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
   * Set the maximum size allowed for an individual upload append (PATCH) request in IETF Resumable
   * Uploads.
   *
   * @param maxAppendSize The maximum append size limit in bytes
   * @return The current service
   */
  public TusFileUploadService withMaxAppendSize(Long maxAppendSize) {
    if (maxAppendSize != null) {
      Validate.exclusiveBetween(
          0, Long.MAX_VALUE, maxAppendSize, "The max append size must be bigger than 0");
    }
    this.uploadStorageService.setMaxAppendSize(maxAppendSize);
    return this;
  }

  /**
   * Set the minimum size allowed for an individual upload append or creation request in IETF
   * Resumable Uploads.
   *
   * @param minAppendSize The minimum append size limit in bytes, or null for no limit
   * @return The current service
   */
  public TusFileUploadService withMinAppendSize(Long minAppendSize) {
    if (minAppendSize != null) {
      Validate.exclusiveBetween(
          0, Long.MAX_VALUE, minAppendSize, "The min append size must be bigger than 0");
    }
    this.uploadStorageService.setMinAppendSize(minAppendSize);
    return this;
  }

  /**
   * Set the minimum total upload representation size limit in bytes for creation requests in IETF
   * Resumable Uploads.
   *
   * @param minSize The minimum total upload size limit in bytes, or null for no limit
   * @return The current service
   */
  public TusFileUploadService withMinSize(Long minSize) {
    if (minSize != null) {
      Validate.exclusiveBetween(0, Long.MAX_VALUE, minSize, "The min size must be bigger than 0");
    }
    this.uploadStorageService.setMinSize(minSize);
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
    Objects.requireNonNull(uploadIdFactory, "The UploadIdFactory cannot be null");
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
    Objects.requireNonNull(uploadStorageService, "The UploadStorageService cannot be null");
    // Copy over any previous configuration
    uploadStorageService.setMaxUploadSize(this.uploadStorageService.getMaxUploadSize());
    uploadStorageService.setMaxAppendSize(this.uploadStorageService.getMaxAppendSize());
    uploadStorageService.setMinAppendSize(this.uploadStorageService.getMinAppendSize());
    uploadStorageService.setMinSize(this.uploadStorageService.getMinSize());
    uploadStorageService.setUploadExpirationPeriod(
        this.uploadStorageService.getUploadExpirationPeriod());
    uploadStorageService.setUploadDeduplicationEnabled(
        this.uploadStorageService.isUploadDeduplicationEnabled());
    uploadStorageService.setJsonSerializationEnabled(
        this.uploadStorageService.isJsonSerializationEnabled());
    uploadStorageService.setIdFactory(this.idFactory);
    // Update the upload storage service
    this.uploadStorageService = uploadStorageService;
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * Instruct the upload service to use JSON serialization for upload metadata ({@link UploadInfo})
   * instead of default Java object serialization.
   *
   * @return The current service
   */
  public TusFileUploadService withJsonSerialization() {
    return withJsonSerialization(true);
  }

  /**
   * Enable or disable JSON serialization for upload metadata ({@link UploadInfo}).
   *
   * @param enabled True to enable JSON serialization, false otherwise
   * @return The current service
   */
  public TusFileUploadService withJsonSerialization(boolean enabled) {
    this.uploadStorageService.setJsonSerializationEnabled(enabled);
    return this;
  }

  /**
   * Get the current {@link UploadStorageService} configured on this service.
   *
   * @return The current {@link UploadStorageService}
   */
  public UploadStorageService getUploadStorageService() {
    return this.uploadStorageService;
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
    Objects.requireNonNull(uploadLockingService, "The UploadStorageService cannot be null");
    uploadLockingService.setIdFactory(this.idFactory);
    // Update the upload storage service
    this.uploadLockingService = uploadLockingService;
    prepareCacheIfEnabled();
    return this;
  }

  /**
   * Specify the maximum number of retries the service will attempt to acquire an upload lock before
   * failing with an {@link UploadAlreadyLockedException} during lock contention resolution (e.g.
   * for HEAD or DELETE requests). Default is {@value #DEFAULT_MAX_LOCK_RETRIES} retries.
   *
   * @param maxLockRetries The maximum number of lock acquisition retries (must be 0 or greater)
   * @return The current service
   */
  public TusFileUploadService withMaxLockRetries(int maxLockRetries) {
    Validate.isTrue(maxLockRetries >= 0, "The max lock retries must be 0 or greater");
    this.maxLockRetries = maxLockRetries;
    return this;
  }

  /**
   * Get the maximum number of lock acquisition retries.
   *
   * @return The maximum number of lock acquisition retries
   */
  public int getMaxLockRetries() {
    return maxLockRetries;
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
    withUploadLockingService(new LeaseFileLockingService(storagePath));
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
   * Enable or disable duplicate file processing based on checksum hash.
   *
   * @param isEnabled True if duplicate file processing should be enabled, false otherwise
   * @return The current service
   */
  public TusFileUploadService withUploadDeduplication(boolean isEnabled) {
    this.uploadStorageService.setUploadDeduplicationEnabled(isEnabled);
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
    Objects.requireNonNull(feature, "A custom feature cannot be null");
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
    Objects.requireNonNull(extensionName, "The extension name cannot be null");
    Validate.isTrue(
        !Strings.CS.equals("core", extensionName), "The core protocol cannot be disabled");

    enabledFeatures.remove(extensionName);
    updateSupportedHttpMethods();

    if (Strings.CS.equals("creation-with-upload", extensionName)) {
      TusExtension creation = enabledFeatures.get("creation");
      if (creation instanceof CreationExtension) {
        ((CreationExtension) creation).setCreationWithUploadEnabled(false);
      }
    }

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
   * @return The processed {@link UploadInfo} or null if no upload was involved or an error occurred
   * @throws IOException When saving bytes or information of this requests fails
   */
  public UploadInfo process(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws IOException {
    return process(servletRequest, servletResponse, null);
  }

  /**
   * Process a tus upload request that belongs to a specific owner. Use this method to process any
   * request made to the main and sub tus upload endpoints. This corresponds to the path specified
   * in the withUploadUri() method and any sub-path of that URI.
   *
   * @param servletRequest The {@link HttpServletRequest} of the request
   * @param servletResponse The {@link HttpServletResponse} of the request
   * @param ownerKey A unique identifier of the owner (group) of this upload
   * @return The processed {@link UploadInfo} or null if no upload was involved or an error occurred
   * @throws IOException When saving bytes or information of this requests fails
   */
  public UploadInfo process(
      HttpServletRequest servletRequest, HttpServletResponse servletResponse, String ownerKey)
      throws IOException {
    Objects.requireNonNull(servletRequest, "The HTTP Servlet request cannot be null");
    Objects.requireNonNull(servletResponse, "The HTTP Servlet response cannot be null");

    HttpMethod method = HttpMethod.getMethodIfSupported(servletRequest, supportedHttpMethods);

    log.debug(
        "Processing request with method {} and URL {}", method, servletRequest.getRequestURL());

    TusServletRequest request =
        new TusServletRequest(servletRequest, isChunkedTransferDecodingEnabled);
    TusServletResponse response = new TusServletResponse(servletResponse);

    UploadInfo processedUploadInfo = null;
    boolean wasInProgress = checkWasInProgress(request, ownerKey);

    try (UploadLock lock = acquireUploadLock(method, request.getRequestURI())) {

      processedUploadInfo = processLockedRequest(method, request, response, ownerKey);

    } catch (TusException e) {
      log.error("Unable to lock upload for request URI " + request.getRequestURI(), e);
      response.setHeader(HttpHeader.CONTENT_LENGTH, null);
      response.sendError(e.getStatus(), e.getMessage());
    }

    if (wasInProgress && processedUploadInfo != null && !processedUploadInfo.isUploadInProgress()) {
      notifyUploadCompletionListeners(processedUploadInfo);
    }

    return processedUploadInfo;
  }

  protected UploadLock acquireUploadLock(HttpMethod method, String requestUri)
      throws TusException, IOException {
    UploadLock lock = null;
    int retries = 0;
    // Retry budget calibrated by default to 40 retries x 200ms = 8.0 seconds to accommodate
    // NFS/network storage attribute cache propagation (actimeo=3s), watchdog polling
    // interval (1.5s), and socket stream interruption and cleanup overhead.
    while (retries < maxLockRetries) {
      try {
        lock = uploadLockingService.lockUploadByUri(requestUri);
        break;
      } catch (TusException e) {
        if (HttpMethod.HEAD.equals(method) || HttpMethod.DELETE.equals(method)) {
          uploadLockingService.requestLockRelease(requestUri);
          retries++;
          try {
            Thread.sleep(200L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Lock acquisition retry interrupted", ie);
          }
        } else {
          throw e;
        }
      }
    }
    if (lock == null) {
      lock = uploadLockingService.lockUploadByUri(requestUri);
    }
    return lock;
  }

  /**
   * Method to retrieve the bytes that were uploaded to a specific upload represented by {@link
   * UploadInfo}.
   *
   * @param uploadInfo The upload info representing the upload
   * @return An {@link InputStream} that will stream the uploaded bytes, or null if uploadInfo is
   *     null
   * @throws IOException When retrieving the uploaded bytes fails
   * @throws TusException When the upload is still in progress or cannot be found
   */
  public InputStream getUploadedBytes(UploadInfo uploadInfo) throws IOException, TusException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return null;
    }
    return uploadStorageService.getUploadedBytes(uploadInfo.getId());
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
   * Method to delete an upload associated with the given {@link UploadInfo}. Invoke this method if
   * you no longer need the upload.
   *
   * @param uploadInfo The upload info representing the upload to delete
   * @throws IOException When deleting the upload fails
   * @throws TusException When the upload cannot be found or deleted
   */
  public void deleteUpload(UploadInfo uploadInfo) throws IOException, TusException {
    if (uploadInfo != null) {
      uploadStorageService.terminateUpload(uploadInfo);
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

  protected UploadInfo processLockedRequest(
      HttpMethod method, TusServletRequest request, TusServletResponse response, String ownerKey)
      throws IOException {
    ProtocolVersion detectedVersion = detectProtocolVersion(request);

    try {
      validateRequest(method, request, ownerKey, detectedVersion);

      executeProcessingByFeatures(method, request, response, ownerKey, detectedVersion);

      return resolveUploadInfo(request, response, ownerKey);

    } catch (TusException e) {
      processTusException(method, request, response, ownerKey, e, detectedVersion);
      return null;
    }
  }

  private UploadInfo resolveUploadInfo(
      TusServletRequest request, TusServletResponse response, String ownerKey) throws IOException {
    String uploadUri = response != null ? response.getHeader(HttpHeader.LOCATION) : null;
    if (StringUtils.isBlank(uploadUri) && request != null) {
      if (Utils.isCreationEndpoint(request, uploadStorageService)) {
        return null;
      }
      uploadUri = request.getRequestURI();
    }
    if (StringUtils.isNotBlank(uploadUri) && uploadStorageService != null) {
      return uploadStorageService.getUploadInfo(uploadUri, ownerKey);
    }
    return null;
  }

  private boolean checkWasInProgress(TusServletRequest request, String ownerKey) {
    if (request == null || uploadStorageService == null) {
      return true;
    }
    try {
      UploadInfo uploadInfo = resolveUploadInfo(request, null, ownerKey);
      if (uploadInfo != null) {
        return uploadInfo.isUploadInProgress();
      }
    } catch (Exception e) {
      log.debug("Error checking initial upload progress state: {}", e.getMessage());
    }
    return true;
  }

  protected void notifyUploadCompletionListeners(UploadInfo uploadInfo) {
    if (uploadInfo == null || uploadCompletionListeners.isEmpty()) {
      return;
    }
    for (UploadCompletionListener listener : uploadCompletionListeners) {
      try {
        listener.onUploadComplete(uploadInfo, this);
      } catch (Throwable t) {
        log.error(
            "Error executing upload completion listener for upload ID {}: {}",
            uploadInfo.getId(),
            t.getMessage(),
            t);
      }
    }
  }

  public ProtocolVersion detectProtocolVersion(HttpServletRequest request) {
    return Utils.detectProtocolVersion(request, supportedProtocolVersion);
  }

  protected void executeProcessingByFeatures(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      String ownerKey,
      ProtocolVersion version)
      throws IOException, TusException {

    for (TusExtension feature : enabledFeatures.values()) {
      if (!servletRequest.isProcessedBy(feature)) {
        servletRequest.addProcessor(feature);
        feature.process(
            method,
            servletRequest,
            servletResponse,
            uploadStorageService,
            uploadLockingService,
            ownerKey,
            version);
      }
    }
  }

  protected void validateRequest(
      HttpMethod method,
      HttpServletRequest servletRequest,
      String ownerKey,
      ProtocolVersion version)
      throws TusException, IOException {

    for (TusExtension feature : enabledFeatures.values()) {
      feature.validate(
          method, servletRequest, uploadStorageService, uploadLockingService, ownerKey, version);
    }
  }

  protected void processTusException(
      HttpMethod method,
      TusServletRequest request,
      TusServletResponse response,
      String ownerKey,
      TusException exception,
      ProtocolVersion version)
      throws IOException {

    int status = exception.getStatus();
    String message = exception.getMessage();

    log.warn(
        "Unable to process request {} {}. Sent response status {} with message \"{}\"",
        method,
        request.getRequestURL(),
        status,
        message);

    response.setStatus(status);

    HttpProblemDetails problemDetails = null;
    try {
      for (TusExtension feature : enabledFeatures.values()) {
        if (!request.isProcessedBy(feature) || feature.mustReprocessOnError(method, version)) {
          request.addProcessor(feature);
          HttpProblemDetails pd =
              feature.handleError(
                  method,
                  request,
                  response,
                  uploadStorageService,
                  uploadLockingService,
                  ownerKey,
                  version,
                  exception);
          if (pd != null) {
            problemDetails = pd;
          }
        }
      }

      // Since an error occurred, the bytes we have written are probably not valid.
      // So remove them.
      String uploadUri = Utils.getUploadUri(request, response);
      UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
      if (uploadInfo != null) {
        uploadStorageService.removeLastNumberOfBytes(uploadInfo, request.getBytesRead());
      }

    } catch (TusException ex) {
      log.warn("An exception occurred while handling another exception", ex);
    }

    // If one of the features has already committed a response, we don't need to send an error
    // response. Otherwise, we send the error response with the status and message from the
    // exception.
    if (!response.isCommitted()) {
      response.setStatus(status);
      if (problemDetails != null) {
        problemDetails.writeTo(response);
      } else {
        response.setHeader(HttpHeader.CONTENT_LENGTH, null);
        response.sendError(status, message);
      }
    }
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

  /**
   * Closes underlying storage and locking services, releasing background threads and resources.
   *
   * @throws IOException If closing fails
   */
  @Override
  public void close() throws IOException {
    if (uploadLockingService != null) {
      uploadLockingService.close();
    }
    if (uploadStorageService != null) {
      uploadStorageService.close();
    }
  }
}
