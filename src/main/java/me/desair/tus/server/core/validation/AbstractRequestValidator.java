package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;

/**
 * Abstract implementation of {@link RequestValidator} to provide some default implementations
 */
public abstract class AbstractRequestValidator implements RequestValidator {

    @Override
    public boolean supports(final HttpMethod method) {
        return method != null;
    }

}
