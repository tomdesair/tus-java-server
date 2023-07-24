package me.desair.tus.server.util;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.TusExtension;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

/** Abstract class to implement a tus extension using validators and request handlers. */
public abstract class AbstractTusExtension implements TusExtension {

  private List<RequestValidator> requestValidators = new LinkedList<>();
  private List<RequestHandler> requestHandlers = new LinkedList<>();

  protected AbstractTusExtension() {
    initValidators(requestValidators);
    initRequestHandlers(requestHandlers);
  }

  protected abstract void initValidators(List<RequestValidator> requestValidators);

  protected abstract void initRequestHandlers(List<RequestHandler> requestHandlers);

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest servletRequest,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    for (RequestValidator requestValidator : requestValidators) {
      if (requestValidator.supports(method)) {
        requestValidator.validate(method, servletRequest, uploadStorageService, ownerKey);
      }
    }
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    for (RequestHandler requestHandler : requestHandlers) {
      if (requestHandler.supports(method)) {
        requestHandler.process(
            method, servletRequest, servletResponse, uploadStorageService, ownerKey);
      }
    }
  }

  @Override
  public void handleError(
      HttpMethod method,
      TusServletRequest request,
      TusServletResponse response,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    for (RequestHandler requestHandler : requestHandlers) {
      if (requestHandler.supports(method) && requestHandler.isErrorHandler()) {
        requestHandler.process(method, request, response, uploadStorageService, ownerKey);
      }
    }
  }
}
