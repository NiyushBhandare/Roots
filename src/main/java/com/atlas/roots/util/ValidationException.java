package com.atlas.roots.util;

/**
 * Thrown when user input fails a validation rule — name too short,
 * joy rating out of range, cost negative, etc.
 *
 * <p>Extends {@link IllegalArgumentException} rather than
 * {@link RootsException} because existing code throughout the DAO
 * layer catches {@code IllegalArgumentException} for malformed input,
 * and this lets us introduce a domain-specific validation type
 * without breaking those catches. New code should prefer to catch
 * {@code ValidationException} explicitly for clarity.</p>
 *
 * <p>The web layer catches this and converts it to an HTTP 400 Bad
 * Request with the message in the JSON body, so the message should
 * always be safe to show to an end user ("cost must be at least
 * zero") rather than a developer ("SQL constraint violation on
 * column sub_attrs.monthly_cost").</p>
 */
public class ValidationException extends IllegalArgumentException {

    public ValidationException(String message) {
        super(message);
    }
}
