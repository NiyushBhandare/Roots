package com.atlas.roots.service;

import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.RootNode;
import com.atlas.roots.model.SubNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link CognitiveHeatmap} TF-IDF engine.
 *
 * <p>The clustering claim &mdash; "the system discovers semantic
 * relationships nobody told it about" &mdash; is the showpiece of the
 * project. These tests are how we prove the claim is real and not
 * marketing copy.</p>
 */
class CognitiveHeatmapTest {

    private final CognitiveHeatmap heatmap = new CognitiveHeatmap();

    private IdeaNode idea(long id, String name, String description, String tags) {
        IdeaNode n = new IdeaNode(id, name, description, 1L,
                LocalDateTime.now(), LocalDateTime.now(), false,
                "Notes/" + id + ".md", 100, 0, LocalDateTime.now(), tags);
        return n;
    }

    private SubNode sub(long id, String name) {
        SubNode s = new SubNode(id, name, "subscription service", 1L,
                LocalDateTime.now(), LocalDateTime.now(), false,
                500, "INR", SubNode.Cadence.MONTHLY, 7,
                LocalDate.now().minusYears(1), null);
        return s;
    }

    @Test
    void relatedNodesProduceEdge() {
        List<RootNode> nodes = List.of(
                idea(1, "Resonance Score notes",  "proprietary metric definitions for Seen", "seen,metrics"),
                idea(2, "Seen Marketing System",  "the AI-powered platform Seen architecture", "seen,product"),
                idea(3, "Pasta recipe",            "boil water then cook noodles",             "cooking"));

        var result = heatmap.compute(nodes);

        boolean seenPairLinked = result.edges().stream().anyMatch(e ->
                (e.sourceId() == 1 && e.targetId() == 2) ||
                (e.sourceId() == 2 && e.targetId() == 1));
        assertTrue(seenPairLinked,
                "the two Seen-related notes should be linked by an edge");
    }

    @Test
    void unrelatedNodesProduceNoEdge() {
        List<RootNode> nodes = List.of(
                idea(1, "Pasta recipe",  "boil water cook noodles", "cooking"),
                idea(2, "Wave physics",  "amplitude frequency oscillation", "science"));

        var result = heatmap.compute(nodes);
        assertTrue(result.edges().isEmpty(),
                "unrelated nodes should not link, got " + result.edges());
    }

    @Test
    void emptyVaultProducesEmptyHeatmap() {
        var result = heatmap.compute(List.of());
        assertTrue(result.edges().isEmpty());
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void singleNodeProducesNoEdges() {
        var result = heatmap.compute(List.of(idea(1, "Solo", "alone in the world", "lonely")));
        assertEquals(1, result.nodes().size());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void heterogeneousNodesClusterByText() {
        // Mix node types — TF-IDF should still cluster by shared vocabulary.
        List<RootNode> nodes = List.of(
                idea(1, "Ampeiron architecture", "local-first AI system with vector memory", "ai,local"),
                sub(2,  "ChatGPT Plus"),  // unrelated
                idea(3, "Local-first AI", "ampeiron-style vector memory architecture", "ai,local"));

        var result = heatmap.compute(nodes);
        boolean aiPairLinked = result.edges().stream().anyMatch(e ->
                (e.sourceId() == 1 && e.targetId() == 3) ||
                (e.sourceId() == 3 && e.targetId() == 1));
        assertTrue(aiPairLinked, "the two AI architecture notes should cluster across types");
    }

    @Test
    void thresholdControlsEdgeDensity() {
        List<RootNode> nodes = List.of(
                idea(1, "Apple banana cherry", "fruit fruit fruit", ""),
                idea(2, "Apple orange grape",  "fruit fruit fruit", ""));

        var loose = heatmap.compute(nodes, 0.05);
        var tight = heatmap.compute(nodes, 0.99);
        assertTrue(loose.edges().size() >= tight.edges().size(),
                "lower threshold must produce >= edges");
    }
}
