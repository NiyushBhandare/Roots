package com.atlas.roots.util;

/**
 * Thrown when a requested entity does not exist — {@code GET /api/subs/99}
 * where there is no sub with id 99, or a DAO update call against a row
 * that was deleted between the read and the write.
 *
 * <p>The web layer catches this and returns HTTP 404.</p>
 */
public class NotFoundException extends RootsException {

    public NotFoundException(String entityType, Object id) {
        super(entityType + " not found: " + id);
    }

    public NotFoundException(String message) {
        super(message);
    }
}
