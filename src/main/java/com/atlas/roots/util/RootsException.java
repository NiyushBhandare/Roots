package com.atlas.roots.util;

/**
 * Root of Roots' own exception hierarchy.
 *
 * <p>We throw {@code RootsException} (or one of its subclasses) instead
 * of generic {@link RuntimeException} wherever we want the failure to
 * carry domain meaning. Grepping the codebase for {@code RootsException}
 * gives you a complete map of every place Roots can fail for its own
 * reasons, as opposed to failures that come out of the JDK or JDBC.</p>
 *
 * <p>All Roots exceptions are unchecked. The rationale: the callers
 * almost always want to let the exception propagate up to the web layer,
 * where a single handler converts it to a JSON error response. Forcing
 * every DAO method to declare {@code throws RootsException} would add
 * noise without adding safety.</p>
 */
public class RootsException extends RuntimeException {

    public RootsException(String message) {
        super(message);
    }

    public RootsException(String message, Throwable cause) {
        super(message, cause);
    }
}
