package com.atlas.roots.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A skill inferred from a user's vault content by TF-IDF clustering.
 *
 * <p>Skills are not user-editable like nodes are — they are a derived
 * artefact, rebuilt atomically from the user's Ideas whenever
 * {@code SkillExtractor.recomputeForUser} is called. This means every
 * Skill row is a snapshot of what the vault said about the user at the
 * moment of extraction.</p>
 *
 * <p>The {@code name} is usually the highest-weighted token in the
 * cluster. The {@code tokens} list is the full membership of the cluster,
 * ordered by descending TF-IDF weight. {@code ideaCount} is the number
 * of IdeaNodes that contained any of the cluster's tokens.
 * {@code confidence} is the mean TF-IDF weight of the cluster's tokens,
 * in {@code [0, 1]} — used to sort skills by strength in the UI.</p>
 */
public final class Skill {

    private long id;
    private long ownerId;
    private String name;
    private List<String> tokens;
    private int ideaCount;
    private double confidence;
    private LocalDateTime createdAt;

    public Skill() {}

    public Skill(long ownerId, String name, List<String> tokens,
                 int ideaCount, double confidence) {
        this.ownerId    = ownerId;
        this.name       = name;
        this.tokens     = tokens;
        this.ideaCount  = ideaCount;
        this.confidence = confidence;
    }

    public long          getId()         { return id; }
    public void          setId(long id)  { this.id = id; }
    public long          getOwnerId()    { return ownerId; }
    public void          setOwnerId(long ownerId) { this.ownerId = ownerId; }
    public String        getName()       { return name; }
    public void          setName(String name) { this.name = name; }
    public List<String>  getTokens()     { return tokens; }
    public void          setTokens(List<String> tokens) { this.tokens = tokens; }
    public int           getIdeaCount()  { return ideaCount; }
    public void          setIdeaCount(int ideaCount) { this.ideaCount = ideaCount; }
    public double        getConfidence() { return confidence; }
    public void          setConfidence(double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public void          setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
