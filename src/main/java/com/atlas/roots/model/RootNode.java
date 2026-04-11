package com.atlas.roots.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The abstract base of every node in the Roots ecosystem.
 *
 * <p>RootNode encapsulates the columns and behaviour shared by every
 * subtype: identity, ownership, name, description, timestamps, and
 * archival state. Subclasses contribute their own attributes and, more
 * importantly, their own implementations of the two abstract metrics
 * that drive the entire dashboard:</p>
 *
 * <ul>
 *   <li>{@link #getVitality()} &mdash; "how alive is this node?"</li>
 *   <li>{@link #getJoyScore()} &mdash; "how much value does this node
 *       give me, independent of cost?"</li>
 * </ul>
 *
 * <p>The two metrics are deliberately abstract because each subtype
 * computes them differently: a subscription's vitality decays when you
 * stop using the service, a repo's vitality decays when commits stop,
 * and an idea's vitality decays when the note stops being edited or
 * referenced. Same method, three completely different algorithms.</p>
 *
 * <p>RootNode implements {@link Auditable} because every node has a
 * lifecycle worth tracking, but it does not implement {@link Vitalizable}
 * directly &mdash; that's left to the subclasses, all of which do
 * implement it. The split is intentional: {@code Vitalizable} is a
 * <em>capability</em>, {@code Auditable} is a <em>universal contract</em>
 * for everything in the ecosystem.</p>
 */
public abstract class RootNode implements Auditable {

    // -----------------------------------------------------------------
    //  Encapsulated state — all fields private, accessed via getters.
    //  Setters exist only where mutation is legal post-construction.
    // -----------------------------------------------------------------
    private long id;                       // 0 until persisted
    private final NodeType type;           // immutable discriminator
    private String name;
    private String description;
    private long ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime lastTouched;
    private boolean archived;

    // -----------------------------------------------------------------
    //  Constructors — overloaded for the two common construction paths:
    //  (1) building a fresh node in memory, (2) hydrating from the DB.
    // -----------------------------------------------------------------

    /** Constructor for fresh, in-memory nodes that have not yet been persisted. */
    protected RootNode(NodeType type, String name, String description, long ownerId) {
        this.type        = Objects.requireNonNull(type, "type");
        this.name        = Objects.requireNonNull(name, "name");
        this.description = description;
        this.ownerId     = ownerId;
        this.createdAt   = LocalDateTime.now();
        this.lastTouched = this.createdAt;
        this.archived    = false;
    }

    /** Constructor for hydrating a node read back from the database. */
    protected RootNode(long id, NodeType type, String name, String description,
                       long ownerId, LocalDateTime createdAt,
                       LocalDateTime lastTouched, boolean archived) {
        this.id          = id;
        this.type        = Objects.requireNonNull(type, "type");
        this.name        = Objects.requireNonNull(name, "name");
        this.description = description;
        this.ownerId     = ownerId;
        this.createdAt   = Objects.requireNonNull(createdAt, "createdAt");
        this.lastTouched = Objects.requireNonNull(lastTouched, "lastTouched");
        this.archived    = archived;
    }

    // -----------------------------------------------------------------
    //  Abstract contract — every subclass must answer these two.
    // -----------------------------------------------------------------

    /**
     * @return a vitality score in {@code [0.0, 1.0]}. Each subclass
     *         defines its own decay function. See subclass Javadoc.
     */
    public abstract double getVitality();

    /**
     * @return a joy score in {@code [0.0, 1.0]} representing the
     *         intrinsic value of the node to the user, before any
     *         consideration of cost. Each subclass interprets joy
     *         differently: subscriptions store an explicit rating,
     *         repos derive it from commit cadence, ideas from
     *         backlink density.
     */
    public abstract double getJoyScore();

    /**
     * @return the symbolic display token used by the UI to render this
     *         node type. Three characters, monospace, all caps. The
     *         dashboard uses these as the small-cap labels under each
     *         glowing node.
     */
    public abstract String displayToken();

    // -----------------------------------------------------------------
    //  Auditable contract.
    // -----------------------------------------------------------------

    @Override public LocalDateTime getCreatedAt()   { return createdAt; }
    @Override public LocalDateTime getLastTouched() { return lastTouched; }
    @Override public void touch() { this.lastTouched = LocalDateTime.now(); }

    // -----------------------------------------------------------------
    //  Encapsulated getters/setters.
    // -----------------------------------------------------------------

    public long getId()          { return id; }
    public void setId(long id)   { this.id = id; }   // set by DAO post-insert

    public NodeType getType()    { return type; }

    public String getName()                  { return name; }
    public void   setName(String name)       { this.name = Objects.requireNonNull(name); }

    public String getDescription()                       { return description; }
    public void   setDescription(String description)     { this.description = description; }

    public long getOwnerId()           { return ownerId; }
    public void setOwnerId(long ownerId){ this.ownerId = ownerId; }

    public boolean isArchived()              { return archived; }
    public void    setArchived(boolean a)    { this.archived = a; }

    /** Used only by the DAO when hydrating; do not call from business code. */
    public void rehydrateTimestamps(LocalDateTime createdAt, LocalDateTime lastTouched) {
        this.createdAt   = createdAt;
        this.lastTouched = lastTouched;
    }

    // -----------------------------------------------------------------
    //  Object overrides — equality is identity-based once persisted.
    // -----------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RootNode that)) return false;
        return id != 0 && id == that.id;
    }

    @Override
    public int hashCode() { return Long.hashCode(id); }

    /** Method overloading: a default toString and a verbose one. */
    @Override
    public String toString() { return toString(false); }

    public String toString(boolean verbose) {
        if (!verbose) {
            return "%s[%d, %s]".formatted(type, id, name);
        }
        return """
               %s[id=%d, name=%s, owner=%d,
                  vitality=%.2f, joy=%.2f, archived=%s,
                  createdAt=%s, lastTouched=%s]"""
                .formatted(type, id, name, ownerId,
                           getVitality(), getJoyScore(), archived,
                           createdAt, lastTouched);
    }
}
