package com.atlas.roots.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vitality tests for {@link RepoNode} and {@link IdeaNode}.
 *
 * <p>Together with {@link SubNodeTest}, these prove all three subclasses
 * implement the abstract metric methods correctly &mdash; i.e. that the
 * polymorphism is real and the dashboard's ranking will produce sane
 * orderings on the seeded data.</p>
 */
class RepoAndIdeaNodeTest {

    // -----------------------------------------------------------------
    //  RepoNode
    // -----------------------------------------------------------------

    private RepoNode repo(int commits, int daysSinceLastCommit, int staleDays) {
        RepoNode r = new RepoNode("Test Repo", "desc", 1L,
                "/tmp/test", null, "Java",
                commits, LocalDateTime.now().minusDays(daysSinceLastCommit), staleDays);
        r.rehydrateTimestamps(
                LocalDateTime.now().minusDays(commits * 3L), // 1 commit / 3 days lifetime
                LocalDateTime.now());
        return r;
    }

    @Test
    void freshActiveRepoScoresHigh() {
        RepoNode r = repo(100, 0, 30);
        assertTrue(r.getVitality() > 0.9,
                "active repo touched today should be near 1.0, got " + r.getVitality());
    }

    @Test
    void staleRepoBeyondThresholdScoresZero() {
        RepoNode r = repo(50, 60, 30); // 60 days since commit, threshold 30
        assertEquals(0.0, r.getVitality(), 0.001,
                "repo past stale threshold should decay to 0");
    }

    @Test
    void noCommitYieldsZeroVitality() {
        RepoNode r = new RepoNode("Empty", null, 1L,
                "/tmp/empty", null, "Java",
                0, null, 30);
        assertEquals(0.0, r.getVitality());
        assertEquals(0.0, r.getJoyScore());
    }

    @Test
    void isStaleMatchesThreshold() {
        assertTrue(repo(10, 100, 30).isStale());
        assertFalse(repo(10, 5,   30).isStale());
    }

    // -----------------------------------------------------------------
    //  IdeaNode
    // -----------------------------------------------------------------

    private IdeaNode idea(int wordCount, int backlinks, int daysSinceEdit) {
        IdeaNode i = new IdeaNode("Test Note", "desc", 1L,
                "Notes/test.md", wordCount, backlinks,
                LocalDateTime.now().minusDays(daysSinceEdit), "test");
        i.rehydrateTimestamps(LocalDateTime.now().minusDays(daysSinceEdit), LocalDateTime.now());
        return i;
    }

    @Test
    void freshNoteWithBacklinksScoresHigh() {
        IdeaNode n = idea(2000, 10, 0);
        assertTrue(n.getVitality() > 0.85,
                "fresh well-linked note should be near 1.0, got " + n.getVitality());
    }

    @Test
    void orphanedFreshNoteStillVital() {
        IdeaNode n = idea(500, 0, 0);
        assertTrue(n.getVitality() > 0.5,
                "fresh note with no backlinks still has recency vitality");
    }

    @Test
    void wordCountDrivesJoy() {
        IdeaNode tiny  = idea(20,   0, 0);
        IdeaNode large = idea(3000, 0, 0);
        assertTrue(large.getJoyScore() > tiny.getJoyScore(),
                "longer notes should score higher joy");
    }

    @Test
    void veryStaleNoteScoresLow() {
        IdeaNode n = idea(1000, 0, 200); // beyond 60-day recency window
        assertTrue(n.getVitality() < 0.05,
                "note untouched for 200 days with no backlinks should be dormant, got " + n.getVitality());
    }

    @Test
    void vitalityBandsCoverFullRange() {
        // Use the default Vitalizable interface band ladder.
        assertEquals("THRIVING", idea(2000, 10, 0).getVitalityBand());
        assertEquals("DORMANT",  idea(50,   0, 200).getVitalityBand());
    }
}
