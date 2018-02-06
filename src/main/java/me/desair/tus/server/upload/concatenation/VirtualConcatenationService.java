package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UploadConcatenationService} implementation that uses the file system to keep track
 * of concatenated uploads. The concatenation is executed "virtually" meaning that upload bytes
 * are not duplicated to the final upload but "concatenated" on the fly.
 */
public class VirtualConcatenationService implements UploadConcatenationService {

    private static final Logger log = LoggerFactory.getLogger(VirtualConcatenationService.class);

    private UploadStorageService uploadStorageService;

    public VirtualConcatenationService(final UploadStorageService uploadStorageService) {
        this.uploadStorageService = uploadStorageService;
    }

    @Override
    public void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
        if (uploadInfo != null && uploadInfo.isUploadInProgress()
                && uploadInfo.getConcatenationParts() != null) {

            Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();

            List<UploadInfo> partialUploads = getPartialUploads(uploadInfo);

            Long totalLength = calculateTotalLength(partialUploads);
            boolean completed = checkAllCompleted(expirationPeriod, partialUploads);

            if(totalLength != null && totalLength > 0) {
                uploadInfo.setLength(totalLength);

                if(completed) {
                    uploadInfo.setOffset(totalLength);
                }

                if(expirationPeriod != null) {
                    uploadInfo.updateExpiration(expirationPeriod);
                }

                updateUpload(uploadInfo);
            }
        }
    }

    @Override
    public InputStream getConcatenatedBytes(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
        merge(uploadInfo);

        if (uploadInfo == null || uploadInfo.isUploadInProgress()) {
            return null;
        } else {
            List<UploadInfo> uploads = getPartialUploads(uploadInfo);
            return new SequenceInputStream(new UploadInputStreamEnumeration(uploads, uploadStorageService));
        }
    }

    @Override
    public List<UploadInfo> getPartialUploads(UploadInfo info) throws IOException, UploadNotFoundException {
        List<UUID> concatenationParts = info.getConcatenationParts();

        if (concatenationParts == null || concatenationParts.isEmpty()) {
            return Collections.emptyList();
        } else {
            List<UploadInfo> output = new ArrayList<>(concatenationParts.size());
            for (UUID childId : concatenationParts) {
                UploadInfo childInfo = uploadStorageService.getUploadInfo(childId);
                if(childInfo == null) {
                    throw new UploadNotFoundException("Upload with ID " + childId + " was not found");
                } else {
                    output.add(childInfo);
                }
            }
            return output;
        }
    }

    private Long calculateTotalLength(List<UploadInfo> partialUploads) {
        Long totalLength = 0L;

        for (UploadInfo childInfo : partialUploads) {
            if (childInfo.getLength() == null) {
                //One of our partial uploads does not have a length, we can't calculate the total length yet
                totalLength = null;
            } else if (totalLength != null) {
                totalLength += childInfo.getLength();
            }
        }

        return totalLength;
    }

    private boolean checkAllCompleted(Long expirationPeriod, List<UploadInfo> partialUploads) throws IOException {
        boolean completed = true;

        for (UploadInfo childInfo : partialUploads) {
            if(childInfo.isUploadInProgress()) {
                completed = false;

            } else if (expirationPeriod != null) {
                //Make sure our child uploads do not expire
                //since the partial child upload is complete, it's safe to update it.
                childInfo.updateExpiration(expirationPeriod);
                updateUpload(childInfo);
            }
        }

        return completed;
    }

    private void updateUpload(UploadInfo uploadInfo) throws IOException {
        try {
            uploadStorageService.update(uploadInfo);
        } catch (UploadNotFoundException e) {
            log.warn("Unexpected exception occurred while saving upload info with ID " + uploadInfo.getId(), e);
        }
    }

}
