package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.List;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UploadConcatenationService} implementation that uses the file system to keep track of
 * concatenated uploads. The concatenation is executed "virtually" meaning that upload bytes are not
 * duplicated to the upload but "concatenated" on the fly.
 */
public class VirtualConcatenationService extends AbstractConcatenationService {

  private static final Logger log = LoggerFactory.getLogger(VirtualConcatenationService.class);

  public VirtualConcatenationService(UploadStorageService uploadStorageService) {
    super(uploadStorageService);
  }

  @Override
  public void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo != null
        && uploadInfo.isUploadInProgress()
        && uploadInfo.getConcatenationPartIds() != null) {

      Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();

      List<UploadInfo> partialUploads = getPartialUploads(uploadInfo);

      Long totalLength = calculateTotalLength(partialUploads);
      boolean completed = checkAllCompleted(expirationPeriod, partialUploads);

      if (totalLength != null && totalLength > 0) {
        uploadInfo.setLength(totalLength);

        if (completed) {
          uploadInfo.setOffset(totalLength);
        }

        if (expirationPeriod != null) {
          uploadInfo.updateExpiration(expirationPeriod);
        }

        updateUpload(uploadInfo);
      }
    }
  }

  @Override
  public InputStream getConcatenatedBytes(UploadInfo uploadInfo)
      throws IOException, UploadNotFoundException {
    merge(uploadInfo);

    if (uploadInfo == null || uploadInfo.isUploadInProgress()) {
      return null;
    } else {
      List<UploadInfo> uploads = getPartialUploads(uploadInfo);
      return new SequenceInputStream(
          new UploadInputStreamEnumeration(uploads, uploadStorageService));
    }
  }

  private void updateUpload(UploadInfo uploadInfo) throws IOException {
    try {
      uploadStorageService.update(uploadInfo);
    } catch (UploadNotFoundException e) {
      log.warn(
          "Unexpected exception occurred while saving upload info with ID " + uploadInfo.getId(),
          e);
    }
  }
}
