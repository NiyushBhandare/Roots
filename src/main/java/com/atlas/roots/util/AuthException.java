package com.atlas.roots.util;

/**
 * Thrown when authentication or authorization fails — a missing or
 * invalid session token, a viewer trying to perform an admin-only
 * action, or a login attempt against unknown credentials.
 *
 * <p>The web layer catches this and returns HTTP 401 Unauthorized
 * or HTTP 403 Forbidden depending on the subtype.</p>
 */
public class AuthException extends RootsException {

    public AuthException(String message) {
        super(message);
    }
}
