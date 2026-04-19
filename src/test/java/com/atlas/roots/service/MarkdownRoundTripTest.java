package com.atlas.roots.service;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.RepoNodeDao;
import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.dao.UserDao;
import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.RepoNode;
import com.atlas.roots.model.SubNode;
import com.atlas.roots.model.User;
import com.atlas.roots.util.BCryptHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link MarkdownExporter} and {@link MarkdownImporter}
 * are inverse operations: anything we export, we should be able to
 * re-import and get the same logical state back.
 *
 * <p>Uses a fresh isolated database file per test so the production
 * database at {@code ~/.roots/roots.db} is never touched.</p>
 */
class MarkdownRoundTripTest {

    private DatabaseManager db;
    private SubNodeDao subDao;
    private RepoNodeDao repoDao;
    private IdeaNodeDao ideaDao;
    private MarkdownExporter exporter;
    private MarkdownImporter importer;
    private long ownerId;

    @BeforeEach
    void setUp() throws Exception {
        // Point DatabaseManager at an isolated temp file via the
        // built-in forTesting() entry point so the production database
        // at ~/.roots/roots.db is never touched.
        Path tempDb = Files.createTempFile("roots-test-", ".db");
        Files.deleteIfExists(tempDb);

        db = DatabaseManager.forTesting(tempDb);
        db.bootstrapIfNeeded();

        subDao  = new SubNodeDao(db);
        repoDao = new RepoNodeDao(db);
        ideaDao = new IdeaNodeDao(db);
        exporter = new MarkdownExporter(subDao, repoDao, ideaDao);
        importer = new MarkdownImporter(subDao, repoDao, ideaDao);

        UserDao userDao = new UserDao(db);
        var existing = userDao.findByUsername("draco");
        ownerId = existing.isPresent()
                ? existing.get().getId()
                : userDao.insert(new User("draco", BCryptHelper.hash("test1234"), User.Role.ADMIN)).getId();
    }

    @Test
    void roundTripPreservesAllNodeTypes() throws SQLException {
        // Seed fresh test data
        SubNode  sub  = new SubNode("Test Sub", "a test sub", ownerId,
                500.0, "INR", SubNode.Cadence.MONTHLY, 8,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 6, 1));
        subDao.insert(sub);

        RepoNode repo = new RepoNode("Test Repo", "a test repo", ownerId,
                "/tmp/test", "https://github.com/test/test", "Java",
                42, null, 30);
        repoDao.insert(repo);

        IdeaNode idea = new IdeaNode("Test Idea", "a test idea", ownerId,
                "Notes/test.md", 100, 3, null, "test,demo");
        ideaDao.insert(idea);

        // Export
        String md = exporter.exportAll("draco");
        assertNotNull(md);
        assertTrue(md.contains("Test Sub"), "exported markdown should contain Sub");
        assertTrue(md.contains("Test Repo"), "exported markdown should contain Repo");
        assertTrue(md.contains("Test Idea"), "exported markdown should contain Idea");
        assertTrue(md.contains("monthly_cost: 500.00"));
        assertTrue(md.contains("commit_count: 42"));
        assertTrue(md.contains("word_count: 100"));

        // Import into a clean state via REPLACE
        var result = importer.importMarkdown(md, ownerId, MarkdownImporter.Mode.REPLACE);
        assertEquals(0, result.failed, "no rows should fail import");
        assertTrue(result.created >= 3, "at least 3 nodes should be created");

        // Verify the data is back
        var subs  = subDao.findAll();
        var repos = repoDao.findAll();
        var ideas = ideaDao.findAll();
        assertEquals(1, subs.size());
        assertEquals(1, repos.size());
        assertEquals(1, ideas.size());
        assertEquals("Test Sub",  subs.get(0).getName());
        assertEquals(500.0,       subs.get(0).getMonthlyCost(), 0.001);
        assertEquals(8,           subs.get(0).getJoyRating());
        assertEquals("Test Repo", repos.get(0).getName());
        assertEquals(42,          repos.get(0).getCommitCount());
        assertEquals("Test Idea", ideas.get(0).getName());
        assertEquals(100,         ideas.get(0).getWordCount());
        assertEquals("test,demo", ideas.get(0).getTags());
    }

    @Test
    void overwriteModeUpdatesExistingByName() throws SQLException {
        SubNode original = new SubNode("Spotify", "old description", ownerId,
                119.0, "INR", SubNode.Cadence.MONTHLY, 5,
                LocalDate.of(2024, 1, 1), null);
        subDao.insert(original);

        // A markdown blob describing the same name with new fields
        String md = """
                ---
                export_version: 1
                ---
                ## Subscriptions

                ### Spotify
                - type: SUB
                - description: new description
                - monthly_cost: 199.00
                - currency: INR
                - cadence: MONTHLY
                - joy_rating: 9
                - started_on: 2025-01-01
                """;

        var result = importer.importMarkdown(md, ownerId, MarkdownImporter.Mode.OVERWRITE);
        assertEquals(1, result.updated);
        assertEquals(0, result.created);

        var subs = subDao.findAll();
        assertEquals(1, subs.size(), "should still be one Spotify, not two");
        assertEquals(199.0, subs.get(0).getMonthlyCost(), 0.001);
        assertEquals(9, subs.get(0).getJoyRating());
        assertEquals("new description", subs.get(0).getDescription());
    }

    @Test
    void skipModeLeavesExistingAlone() throws SQLException {
        SubNode original = new SubNode("Notion", "old", ownerId,
                500.0, "INR", SubNode.Cadence.MONTHLY, 3,
                LocalDate.of(2024, 6, 1), null);
        subDao.insert(original);

        String md = """
                ## Subscriptions

                ### Notion
                - type: SUB
                - description: should not appear
                - monthly_cost: 999.00
                - currency: INR
                - cadence: MONTHLY
                - joy_rating: 10
                """;

        var result = importer.importMarkdown(md, ownerId, MarkdownImporter.Mode.SKIP);
        assertEquals(1, result.skipped);
        assertEquals(0, result.created);
        assertEquals(0, result.updated);

        var subs = subDao.findAll();
        assertEquals(500.0, subs.get(0).getMonthlyCost(), 0.001, "skip should preserve original");
        assertEquals(3, subs.get(0).getJoyRating());
    }
}
