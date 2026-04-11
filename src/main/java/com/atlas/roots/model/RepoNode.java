package com.atlas.roots.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A {@link RootNode} representing a code repository under active or
 * stale development.
 *
 * <h3>Vitality model</h3>
 * <p>A repo's vitality is determined by <em>commit recency relative to
 * its own staleness threshold</em>. The threshold is per-repo because
 * a daily-active product repo and a long-running research repo have
 * fundamentally different rhythms; comparing them with one global
 * window would be wrong.</p>
 *
 * <p>Formula: {@code vitality = max(0, 1 - daysSinceLastCommit /
 * staleThresholdDays)}, then multiplied by a small commit-volume
 * bonus that rewards repos with sustained history (logarithmic, so
 * a repo with 1000 commits doesn't dominate one with 100). The result
 * is a score that decays smoothly toward zero as the repo goes silent.</p>
 *
 * <h3>Joy model</h3>
 * <p>A repo has no user-supplied joy rating &mdash; the user does not
 * sit and rate their own code. Instead, joy is derived from commit
 * cadence: a repo that gets touched regularly is, by revealed
 * preference, joyful. Specifically, the joy score is the inverse of
 * the average days-between-commits over the repo's lifetime, normalised
 * against a "ideal cadence" of 3 days.</p>
 *
 * <p>This is the kind of derived metric that distinguishes Roots from
 * a CRUD tracker: the system observes behaviour and infers value,
 * rather than asking the user to type a number.</p>
 */
public class RepoNode extends RootNode implements Vitalizable {

    private static final double IDEAL_CADENCE_DAYS = 3.0;

    private String        localPath;
    private String        remoteUrl;
    private String        primaryLanguage;
    private int           commitCount;
    private LocalDateTime lastCommitAt;
    private int           staleThresholdDays;

    // -----------------------------------------------------------------
    //  Constructors.
    // -----------------------------------------------------------------

    public RepoNode(String name, String description, long ownerId,
                    String localPath, String remoteUrl, String primaryLanguage,
                    int commitCount, LocalDateTime lastCommitAt, int staleThresholdDays) {
        super(NodeType.REPO, name, description, ownerId);
        this.localPath          = localPath;
        this.remoteUrl          = remoteUrl;
        this.primaryLanguage    = primaryLanguage;
        this.commitCount        = Math.max(0, commitCount);
        this.lastCommitAt       = lastCommitAt;
        this.staleThresholdDays = Math.max(1, staleThresholdDays);
    }

    public RepoNode(long id, String name, String description, long ownerId,
                    LocalDateTime createdAt, LocalDateTime lastTouched, boolean archived,
                    String localPath, String remoteUrl, String primaryLanguage,
                    int commitCount, LocalDateTime lastCommitAt, int staleThresholdDays) {
        super(id, NodeType.REPO, name, description, ownerId, createdAt, lastTouched, archived);
        this.localPath          = localPath;
        this.remoteUrl          = remoteUrl;
        this.primaryLanguage    = primaryLanguage;
        this.commitCount        = Math.max(0, commitCount);
        this.lastCommitAt       = lastCommitAt;
        this.staleThresholdDays = Math.max(1, staleThresholdDays);
    }

    // -----------------------------------------------------------------
    //  Polymorphic metric overrides.
    // -----------------------------------------------------------------

    @Override
    public double getVitality() {
        if (lastCommitAt == null) return 0.0;
        long daysSince = ChronoUnit.DAYS.between(lastCommitAt, LocalDateTime.now());
        double recencyFactor = Math.max(0.0, 1.0 - ((double) daysSince / staleThresholdDays));
        // Logarithmic volume bonus, capped at 1.5x.
        double volumeBonus = Math.min(1.5, 1.0 + Math.log10(Math.max(1, commitCount)) / 6.0);
        return clamp01(recencyFactor * volumeBonus);
    }

    @Override
    public double getJoyScore() {
        if (lastCommitAt == null || commitCount == 0) return 0.0;
        long lifetimeDays = Math.max(1, ChronoUnit.DAYS.between(getCreatedAt(), LocalDateTime.now()));
        double avgDaysBetweenCommits = (double) lifetimeDays / commitCount;
        // Closer to ideal cadence => higher joy. Beyond 30 days/commit => zero.
        if (avgDaysBetweenCommits >= 30) return 0.0;
        return clamp01(IDEAL_CADENCE_DAYS / avgDaysBetweenCommits);
    }

    @Override
    public String displayToken() { return "REPO"; }

    /** True if the repo has crossed its own staleness threshold. */
    public boolean isStale() {
        if (lastCommitAt == null) return true;
        long daysSince = ChronoUnit.DAYS.between(lastCommitAt, LocalDateTime.now());
        return daysSince > staleThresholdDays;
    }

    // -----------------------------------------------------------------
    //  Getters/setters.
    // -----------------------------------------------------------------

    public String getLocalPath()                      { return localPath; }
    public void   setLocalPath(String localPath)      { this.localPath = localPath; }

    public String getRemoteUrl()                      { return remoteUrl; }
    public void   setRemoteUrl(String remoteUrl)      { this.remoteUrl = remoteUrl; }

    public String getPrimaryLanguage()                { return primaryLanguage; }
    public void   setPrimaryLanguage(String lang)     { this.primaryLanguage = lang; }

    public int  getCommitCount()                      { return commitCount; }
    public void setCommitCount(int commitCount)       { this.commitCount = Math.max(0, commitCount); }

    public LocalDateTime getLastCommitAt()                       { return lastCommitAt; }
    public void          setLastCommitAt(LocalDateTime lastCommit){ this.lastCommitAt = lastCommit; }

    public int  getStaleThresholdDays()                { return staleThresholdDays; }
    public void setStaleThresholdDays(int staleThresholdDays) {
        this.staleThresholdDays = Math.max(1, staleThresholdDays);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
