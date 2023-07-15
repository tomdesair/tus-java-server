package me.desair.tus.server.creation.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.PostOnInvalidRequestURIException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * The Client MUST send a POST request against a known upload creation URL to request a new upload
 * resource.
 */
public class PostUriValidator implements RequestValidator {

  private Pattern uploadUriPattern = null;

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    Matcher uploadUriMatcher =
        getUploadUriPattern(uploadStorageService).matcher(request.getRequestURI());

    if (!uploadUriMatcher.matches()) {
      throw new PostOnInvalidRequestURIException(
          "POST requests have to be sent to '" + uploadStorageService.getUploadUri() + "'. ");
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }

  private Pattern getUploadUriPattern(UploadStorageService uploadStorageService) {
    if (uploadUriPattern == null) {
      // A POST request should match the full URI
      uploadUriPattern = Pattern.compile("^" + uploadStorageService.getUploadUri() + "$");
    }
    return uploadUriPattern;
  }
}
