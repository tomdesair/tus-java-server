package me.desair.tus.server;

import lombok.RequiredArgsConstructor;
import me.desair.tus.server.exception.InvalidTusResumableException;
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
@RequiredArgsConstructor
public class TusResumableValidator {

    public static final String TUS_API_VERSION = "1.0.0";

    private final HttpMethod method;
    private final HttpServletRequest request;

    public void validate() throws InvalidTusResumableException {
        String requestVersion = request.getHeader(HttpHeader.TUS_RESUMABLE);
        if (!method.equals(HttpMethod.OPTIONS) && !StringUtils.equals(requestVersion, TUS_API_VERSION)) {
            throw new InvalidTusResumableException("This server does not support tus protocol version " + requestVersion);
        }
    }
}
