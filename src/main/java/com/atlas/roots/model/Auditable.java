package com.atlas.roots.model;

import java.time.LocalDateTime;

/**
 * Capability marker for any entity whose lifecycle should be recorded
 * in the audit trail. Cleanly separated from the inheritance hierarchy
 * because auditability is a cross-cutting concern: a {@link User}
 * is auditable but is not a {@link RootNode}.
 */
public interface Auditable {

    /** @return when the entity was first created. Never null. */
    LocalDateTime getCreatedAt();

    /** @return when the entity was most recently mutated. Never null. */
    LocalDateTime getLastTouched();

    /**
     * Mark the entity as touched right now. Called by services after
     * any successful mutation, before the DAO persists the change.
     */
    void touch();
}
