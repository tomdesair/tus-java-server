package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for {@link UploadConcatenationService} implementations, providing shared
 * logic for resolving partial uploads, calculating aggregate lengths, and verifying completion
 * states.
 */
public abstract class AbstractConcatenationService
    implements UploadConcatenationService, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AbstractConcatenationService.class);

  protected final UploadStorageService uploadStorageService;

  /**
   * Constructs an {@link AbstractConcatenationService} with the provided storage service.
   *
   * @param uploadStorageService The backing {@link UploadStorageService}
   */
  public AbstractConcatenationService(UploadStorageService uploadStorageService) {
    this.uploadStorageService =
        Objects.requireNonNull(uploadStorageService, "uploadStorageService must not be null");
  }

  @Override
  public List<UploadInfo> getPartialUploads(UploadInfo info)
      throws IOException, UploadNotFoundException {
    if (info == null || info.getConcatenationPartIds() == null) {
      return Collections.emptyList();
    }
    List<String> concatenationParts = info.getConcatenationPartIds();
    if (concatenationParts.isEmpty()) {
      return Collections.emptyList();
    }

    List<UploadInfo> output = new ArrayList<>(concatenationParts.size());
    for (String childUri : concatenationParts) {
      UploadInfo childInfo = uploadStorageService.getUploadInfo(childUri, info.getOwnerKey());
      if (childInfo == null) {
        throw new UploadNotFoundException(
            "Upload with URI " + childUri + " was not found for owner " + info.getOwnerKey());
      }
      // Ensure only uploads with the same owner key can be merged (either equal or both null)
      if (!Objects.equals(childInfo.getOwnerKey(), info.getOwnerKey())) {
        log.warn(
            "Owner key mismatch during concatenation merge check. Parent upload ID {} has owner"
                + " key '{}', but partial child upload ID {} has owner key '{}'. Merging"
                + " disallowed.",
            info.getId(),
            info.getOwnerKey(),
            childInfo.getId(),
            childInfo.getOwnerKey());
        throw new UploadNotFoundException(
            "Upload with URI " + childUri + " has a mismatching owner key");
      }
      output.add(childInfo);
    }
    return output;
  }

  /**
   * Calculates the combined total length of all partial uploads.
   *
   * @param partialUploads The list of partial uploads
   * @return The combined length, or {@code null} if the list is empty or any upload length is
   *     unknown
   */
  protected Long calculateTotalLength(List<UploadInfo> partialUploads) {
    if (partialUploads == null || partialUploads.isEmpty()) {
      return null;
    }
    long totalLength = 0L;
    for (UploadInfo childInfo : partialUploads) {
      if (childInfo == null || childInfo.getLength() == null) {
        return null;
      }
      totalLength += childInfo.getLength();
    }
    return totalLength;
  }

  /**
   * Verifies that all partial uploads have completed and optionally updates their expiration
   * periods.
   *
   * @param expirationPeriod The expiration period in milliseconds to apply to completed parts
   * @param partialUploads The list of partial uploads to verify
   * @return {@code true} if all partial uploads are completed and not expired, {@code false}
   *     otherwise
   * @throws IOException If updating an upload fails
   */
  protected boolean checkAllCompleted(Long expirationPeriod, List<UploadInfo> partialUploads)
      throws IOException {
    if (partialUploads == null || partialUploads.isEmpty()) {
      return false;
    }
    boolean completed = true;
    for (UploadInfo childInfo : partialUploads) {
      if (childInfo == null || childInfo.isUploadInProgress() || childInfo.isExpired()) {
        completed = false;
      } else if (expirationPeriod != null) {
        childInfo.updateExpiration(expirationPeriod);
        try {
          uploadStorageService.update(childInfo);
        } catch (UploadNotFoundException e) {
          log.debug("Failed to update child upload expiration for ID {}", childInfo.getId(), e);
        }
      }
    }
    return completed;
  }

  @Override
  public void close() throws IOException {
    // Default implementation does nothing
  }
}
