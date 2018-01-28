package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadConcatenationService;
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
    public void merge(UploadInfo uploadInfo) throws IOException {
        if (uploadInfo != null && uploadInfo.isUploadInProgress()
                && uploadInfo.getConcatenationParts() != null) {

            Long totalLength = 0L;

            for (UploadInfo childInfo : getPartialUploads(uploadInfo)) {
                if (childInfo.isUploadInProgress()) {
                    totalLength = null;
                } else if (totalLength != null) {
                    totalLength += uploadInfo.getLength();
                }
            }

            if(totalLength != null && totalLength > 0) {
                uploadInfo.setLength(totalLength);
                uploadInfo.setOffset(totalLength);
                try {
                    uploadStorageService.update(uploadInfo);
                } catch (UploadNotFoundException e) {
                    log.warn("Unexpected exception occurred while saving upload info", e);
                }
            }
        }
    }

    @Override
    public InputStream getConcatenatedBytes(UploadInfo uploadInfo) throws IOException {
        merge(uploadInfo);

        if (uploadInfo == null || uploadInfo.isUploadInProgress()) {
            return null;
        } else {
            List<UploadInfo> uploads = getPartialUploads(uploadInfo);
            return new SequenceInputStream(new UploadInputStreamEnumeration(uploads, uploadStorageService));
        }
    }

    @Override
    public List<UploadInfo> getPartialUploads(UploadInfo info) throws IOException {
        List<UUID> concatenationParts = info.getConcatenationParts();

        if (concatenationParts == null || concatenationParts.size() <= 0) {
            return Collections.singletonList(info);
        } else {
            List<UploadInfo> output = new ArrayList<>(concatenationParts.size());
            for (UUID childId : concatenationParts) {
                output.add(uploadStorageService.getUploadInfo(childId));
            }
            return output;
        }
    }

}
