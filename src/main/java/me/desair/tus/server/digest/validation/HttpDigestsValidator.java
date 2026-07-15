package me.desair.tus.server.digest.validation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.StructuredHeaderUtil;
import org.apache.commons.lang3.StringUtils;

/** Validates HTTP Digest headers (Content-Digest, Repr-Digest, Want-Repr-Digest) structure. */
public class HttpDigestsValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    try {
      String contentDigest = request.getHeader(HttpHeader.CONTENT_DIGEST);
      if (StringUtils.isNotBlank(contentDigest)) {
        Map<String, Object> digestDict = StructuredHeaderUtil.parseDictionary(contentDigest);
        if (digestDict.isEmpty()) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Content-Digest cannot be empty");
        }
        for (String key : digestDict.keySet()) {
          if (ChecksumAlgorithm.forHttpDigestName(key) == null) {
            throw new ChecksumAlgorithmNotSupportedException(
                "The "
                    + HttpHeader.CONTENT_DIGEST
                    + " header value contains unsupported algorithm: "
                    + key);
          }
        }
      }

      String reprDigest = request.getHeader(HttpHeader.REPR_DIGEST);
      if (StringUtils.isNotBlank(reprDigest)) {
        Map<String, Object> digestDict = StructuredHeaderUtil.parseDictionary(reprDigest);
        if (digestDict.isEmpty()) {
          throw new TusException(HttpServletResponse.SC_BAD_REQUEST, "Repr-Digest cannot be empty");
        }
        for (String key : digestDict.keySet()) {
          if (ChecksumAlgorithm.forHttpDigestName(key) == null) {
            throw new ChecksumAlgorithmNotSupportedException(
                "The "
                    + HttpHeader.REPR_DIGEST
                    + " header value contains unsupported algorithm: "
                    + key);
          }
        }
      }

      String wantReprDigest = request.getHeader(HttpHeader.WANT_REPR_DIGEST);
      if (StringUtils.isNotBlank(wantReprDigest)) {
        java.util.List<String> items = StructuredHeaderUtil.parseList(wantReprDigest);
        if (items.isEmpty()) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Want-Repr-Digest cannot be empty");
        }
        for (String item : items) {
          String token = StringUtils.substringBefore(item, ";").trim();
          if (!token.matches("^[a-zA-Z0-9_*./-]+$")) {
            throw new TusException(
                HttpServletResponse.SC_BAD_REQUEST,
                "Invalid token format in Want-Repr-Digest: " + token);
          }
        }
      }
    } catch (TusException te) {
      throw te;
    } catch (Exception e) {
      throw new TusException(
          HttpServletResponse.SC_BAD_REQUEST,
          "Invalid structured header format: " + e.getMessage());
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method)
        || HttpMethod.PUT.equals(method)
        || HttpMethod.PATCH.equals(method);
  }
}
