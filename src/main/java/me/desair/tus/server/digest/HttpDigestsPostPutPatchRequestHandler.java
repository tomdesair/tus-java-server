package me.desair.tus.server.digest;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.digest.validation.HttpDigestsValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadDigestMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Unified request handler for RFC 9530 HTTP Digests verification in RUFH protocol. */
public class HttpDigestsPostPutPatchRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method)
        || HttpMethod.PUT.equals(method)
        || HttpMethod.PATCH.equals(method);
  }

  @Override
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    // 1. Verify Content-Digest for the request chunk
    verifyContentDigest(method, servletRequest);

    // Resolve UploadInfo using centralized helpers
    String uploadUri = Utils.getUploadUri(method, servletRequest, servletResponse);
    UploadInfo uploadInfo = null;
    if (uploadUri != null) {
      try {
        uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
      } catch (Exception e) {
        // Ignored
      }
    }

    if (uploadInfo != null) {
      // 2. Capture client Repr-Digest and Want-Repr-Digest
      captureDigestPreferences(servletRequest, uploadInfo, uploadStorageService);

      // 3. Verify Repr-Digest on upload completion
      verifyReprDigestOnCompletion(uploadInfo, uploadUri, ownerKey, uploadStorageService);

      // 4. Provide Repr-Digest response header if requested
      addReprDigestResponseHeader(
          servletResponse, uploadInfo, uploadUri, ownerKey, uploadStorageService);
    }

    return null;
  }

  private void verifyContentDigest(HttpMethod method, TusServletRequest servletRequest)
      throws TusException, IOException {
    String headerVal = servletRequest.getHeader(HttpHeader.CONTENT_DIGEST);
    if (StringUtils.isBlank(headerVal)) {
      return;
    }

    // Revalidate trailing header syntax
    new HttpDigestsValidator().validate(method, servletRequest, null, null);

    Map<ChecksumAlgorithm, String> expectedDigests = ChecksumAlgorithm.parseDigestHeader(headerVal);
    for (Map.Entry<ChecksumAlgorithm, String> entry : expectedDigests.entrySet()) {
      ChecksumAlgorithm alg = entry.getKey();
      String expectedValue = entry.getValue();
      String calculatedValue = servletRequest.getCalculatedChecksum(alg);

      if (calculatedValue != null && !Strings.CS.equals(expectedValue, calculatedValue)) {
        throw new UploadDigestMismatchException(
            "Content-Digest mismatch for algorithm "
                + alg.getHttpDigestNames().get(0)
                + ". Expected: "
                + expectedValue
                + " but was: "
                + calculatedValue);
      }
    }
  }

  private void captureDigestPreferences(
      TusServletRequest servletRequest,
      UploadInfo uploadInfo,
      UploadStorageService uploadStorageService)
      throws IOException, TusException {
    boolean updated = false;

    // Capture Repr-Digest
    String reprDigestHeader = servletRequest.getHeader(HttpHeader.REPR_DIGEST);
    if (StringUtils.isNotBlank(reprDigestHeader) && uploadInfo.getRepresentationDigest() == null) {
      uploadInfo.setRepresentationDigest(reprDigestHeader);
      updated = true;
    }

    // Capture Want-Repr-Digest
    String wantReprDigestHeader = servletRequest.getHeader(HttpHeader.WANT_REPR_DIGEST);
    if (StringUtils.isNotBlank(wantReprDigestHeader)
        && uploadInfo.getRequestedRepresentationDigests() == null) {
      uploadInfo.setRequestedRepresentationDigests(wantReprDigestHeader);
      updated = true;
    }

    if (updated) {
      uploadStorageService.update(uploadInfo);
    }
  }

  private void verifyReprDigestOnCompletion(
      UploadInfo uploadInfo,
      String uploadUri,
      String ownerKey,
      UploadStorageService uploadStorageService)
      throws IOException, TusException {

    if (!uploadInfo.isUploadInProgress() && uploadInfo.getRepresentationDigest() != null) {
      Map<ChecksumAlgorithm, String> expectedDigests =
          ChecksumAlgorithm.parseDigestHeader(uploadInfo.getRepresentationDigest());

      for (Map.Entry<ChecksumAlgorithm, String> entry : expectedDigests.entrySet()) {
        ChecksumAlgorithm alg = entry.getKey();
        String expectedValue = entry.getValue();
        String calculatedValue =
            calculateEntireFileDigest(uploadUri, ownerKey, alg, uploadStorageService);

        if (calculatedValue != null) {
          if (!Strings.CS.equals(expectedValue, calculatedValue)) {
            throw new UploadDigestMismatchException(
                "Repr-Digest mismatch for algorithm "
                    + alg.getHttpDigestNames().get(0)
                    + ". Expected: "
                    + expectedValue
                    + " but was: "
                    + calculatedValue);
          } else {
            // Deduplication support: If verification succeeds and deduplication is enabled
            if (uploadStorageService.isUploadDeduplicationEnabled()
                && uploadInfo.getDuplicatesUploadId() == null) {
              UploadInfo duplicateInfo =
                  uploadStorageService.getUploadInfoByChecksum(expectedValue, alg);
              if (duplicateInfo != null
                  && !Objects.equals(duplicateInfo.getId(), uploadInfo.getId())) {
                uploadInfo.setDuplicatesUploadId(duplicateInfo.getId());
                uploadStorageService.update(uploadInfo);
              } else {
                // Index this file
                uploadInfo.setChecksum(expectedValue);
                uploadInfo.setChecksumAlgorithm(alg);
                uploadStorageService.update(uploadInfo);
              }
            }
          }
        }
      }
    }
  }

  private void addReprDigestResponseHeader(
      TusServletResponse servletResponse,
      UploadInfo uploadInfo,
      String uploadUri,
      String ownerKey,
      UploadStorageService uploadStorageService)
      throws IOException, TusException {

    String requestedDigests = uploadInfo.getRequestedRepresentationDigests();
    if (StringUtils.isNotBlank(requestedDigests)) {
      ChecksumAlgorithm preferredAlg = ChecksumAlgorithm.selectBestAlgorithm(requestedDigests);
      if (preferredAlg != null) {
        String calculatedValue =
            calculateEntireFileDigest(uploadUri, ownerKey, preferredAlg, uploadStorageService);
        if (calculatedValue != null) {
          servletResponse.setHeader(
              HttpHeader.REPR_DIGEST,
              preferredAlg.getHttpDigestNames().get(0) + "=:" + calculatedValue + ":");
        }
      }
    }
  }

  private String calculateEntireFileDigest(
      String uploadUri,
      String ownerKey,
      ChecksumAlgorithm alg,
      UploadStorageService uploadStorageService)
      throws IOException, TusException {
    MessageDigest md = alg.getMessageDigest();
    if (md == null) {
      return null;
    }
    try (InputStream is = uploadStorageService.getUploadedBytes(uploadUri, ownerKey)) {
      if (is == null) {
        return null;
      }
      try (DigestInputStream dis = new DigestInputStream(is, md)) {
        byte[] buffer = new byte[8192];
        while (dis.read(buffer) != -1) {
          // do nothing, let stream update digest
        }
      }
    }
    return Base64.encodeBase64String(md.digest());
  }
}
