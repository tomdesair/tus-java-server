package me.desair.tus.server.digest.validation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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

/**
 * Validates HTTP Digest headers ({@code Content-Digest}, {@code Repr-Digest}, and {@code
 * Want-Repr-Digest}) according to RFC 9530 and RFC 9651 structured field specifications.
 */
public class HttpDigestsValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    try {
      // Step 1: Validate Content-Digest header if provided
      String contentDigest = request.getHeader(HttpHeader.CONTENT_DIGEST);
      if (StringUtils.isNotBlank(contentDigest)) {
        // Step 1.1: Parse header value as an RFC 9651 structured dictionary
        Map<String, Object> digestDict = StructuredHeaderUtil.parseDictionary(contentDigest);

        // Step 1.2: Validate dictionary is not empty
        if (digestDict.isEmpty()) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Content-Digest cannot be empty");
        }

        // Step 1.3: Validate that every digest algorithm specified in Content-Digest is supported
        // by the server
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

      // Step 2: Validate Repr-Digest header if provided
      String reprDigest = request.getHeader(HttpHeader.REPR_DIGEST);
      if (StringUtils.isNotBlank(reprDigest)) {
        // Step 2.1: Parse header value as an RFC 9651 structured dictionary
        Map<String, Object> digestDict = StructuredHeaderUtil.parseDictionary(reprDigest);

        // Step 2.2: Validate dictionary is not empty
        if (digestDict.isEmpty()) {
          throw new TusException(HttpServletResponse.SC_BAD_REQUEST, "Repr-Digest cannot be empty");
        }

        // Step 2.3: Validate that every digest algorithm specified in Repr-Digest is supported by
        // the server
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

      // Step 3: Validate Want-Repr-Digest header if provided
      String wantReprDigest = request.getHeader(HttpHeader.WANT_REPR_DIGEST);
      if (StringUtils.isNotBlank(wantReprDigest)) {
        // Step 3.1: Parse header value as an RFC 9651 structured list
        List<String> items = StructuredHeaderUtil.parseList(wantReprDigest);

        // Step 3.2: Validate list is not empty
        if (items.isEmpty()) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Want-Repr-Digest cannot be empty");
        }

        // Step 3.3: Extract algorithm token names (stripping parameters like ';q=0.5')
        // and validate that each token strictly conforms to allowed character syntax
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
      // Re-throw validation exceptions directly
      throw te;
    } catch (Exception e) {
      // Step 4: Catch structured field parsing/syntax errors and translate to HTTP 400 Bad Request
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
