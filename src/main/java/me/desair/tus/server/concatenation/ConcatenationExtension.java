package me.desair.tus.server.concatenation;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.concatenation.validation.NoUploadLengthOnFinalValidator;
import me.desair.tus.server.concatenation.validation.PartialUploadsExistValidator;
import me.desair.tus.server.concatenation.validation.PatchFinalUploadValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * This extension can be used to concatenate multiple uploads into a single one enabling Clients to
 * perform parallel uploads and to upload non-contiguous chunks. If the Server supports this
 * extension, it MUST add concatenation to the Tus-Extension header.
 */
public class ConcatenationExtension extends AbstractTusExtension {

  @Override
  public String getName() {
    return "concatenation";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.HEAD);
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    requestValidators.add(new PatchFinalUploadValidator());
    requestValidators.add(new NoUploadLengthOnFinalValidator());
    requestValidators.add(new PartialUploadsExistValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new ConcatenationOptionsRequestHandler());
    requestHandlers.add(new ConcatenationPostRequestHandler());
    requestHandlers.add(new ConcatenationHeadRequestHandler());
  }
}
