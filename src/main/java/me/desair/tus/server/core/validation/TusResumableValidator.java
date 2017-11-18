package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.TusFileUploadHandler;
import me.desair.tus.server.util.Utils;
import me.desair.tus.server.exception.InvalidTusResumableException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

/** Class that will validate if the tus version in the request corresponds to our implementation version
 *
 * The Tus-Resumable header MUST be included in every request and response except for OPTIONS requests.
 * The value MUST be the version of the protocol used by the Client or the Server.
 * If the the version specified by the Client is not supported by the Server, it MUST respond with the
 * 412 Precondition Failed status and MUST include the Tus-Version header into the response.
 * In addition, the Server MUST NOT process the request.
 *
 * (https://tus.io/protocols/resumable-upload.html#tus-resumable)
 */
public class TusResumableValidator extends AbstractRequestValidator {

    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException {
        String requestVersion = Utils.getHeader(request, HttpHeader.TUS_RESUMABLE);
        if (!HttpMethod.OPTIONS.equals(method) && !StringUtils.equals(requestVersion, TusFileUploadHandler.TUS_API_VERSION)) {
            throw new InvalidTusResumableException("This server does not support tus protocol version " + requestVersion);
        }
    }
}
