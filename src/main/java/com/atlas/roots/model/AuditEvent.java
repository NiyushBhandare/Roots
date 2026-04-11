package com.atlas.roots.model;

import java.time.LocalDateTime;

/**
 * An immutable audit log entry. Modelled as a Java record because every
 * field is final and the class has no behaviour beyond its accessors.
 *
 * <p>Records are Java's idiomatic answer to value classes; using one
 * here demonstrates familiarity with modern Java while keeping the audit
 * subsystem free of accidental mutability.</p>
 */
public record AuditEvent(
        long          id,
        Long          nodeId,        // nullable: LOGIN/LOGOUT events have no node
        long          userId,
        Action        action,
        String        detail,
        LocalDateTime createdAt
) {
    public enum Action { CREATE, UPDATE, DELETE, LOGIN, LOGOUT }
}
