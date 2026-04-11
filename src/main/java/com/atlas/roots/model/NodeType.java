package com.atlas.roots.model;

/**
 * The closed set of node types in the Roots ecosystem.
 *
 * <p>Used as the discriminator column in the {@code nodes} table and
 * as the routing key inside the polymorphic DAO. Each value carries
 * its own bioluminescent accent colour and short display label, so
 * the UI can render type-aware chips without a switch in every view.</p>
 */
public enum NodeType {

    /** A subscription — drains money on a cadence. */
    SUB("SUB", "#7FFFB0", "Subscription"),

    /** A code repository — drains attention, generates artifacts. */
    REPO("REPO", "#9FE8FF", "Repository"),

    /** An idea — a note in the Obsidian vault. */
    IDEA("IDEA", "#FFD66B", "Idea");

    private final String token;
    private final String accentHex;
    private final String displayName;

    NodeType(String token, String accentHex, String displayName) {
        this.token       = token;
        this.accentHex   = accentHex;
        this.displayName = displayName;
    }

    public String getToken()       { return token; }
    public String getAccentHex()   { return accentHex; }
    public String getDisplayName() { return displayName; }
}
