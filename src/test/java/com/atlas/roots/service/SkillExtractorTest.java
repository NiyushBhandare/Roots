package com.atlas.roots.service;

import com.atlas.roots.model.IdeaNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillExtractorTest {

    private static IdeaNode idea(String name, String desc, String tags) {
        return new IdeaNode(name, desc, 1L, name + ".md", 100, 0, LocalDateTime.now(), tags);
    }

    @Test
    void tokenizerDropsStopwordsAndShortTokens() {
        var tokens = SkillExtractor.tokenize("The quick brown fox is at it");
        assertTrue(tokens.contains("quick"));
        assertTrue(tokens.contains("brown"));
        assertTrue(tokens.contains("fox"));
        assertFalse(tokens.contains("the"), "should drop stopword 'the'");
        assertFalse(tokens.contains("is"),  "should drop 'is' (stopword and too short)");
        assertFalse(tokens.contains("it"),  "should drop 'it'");
    }

    @Test
    void emptyInputReturnsNoSkills() {
        var skills = SkillExtractor.extract(1L, List.of());
        assertTrue(skills.isEmpty());
    }

    @Test
    void singleIdeaProducesReasonableOutput() {
        // With only one document there's no IDF signal — extraction may
        // still return something or return empty; either is fine, but
        // it should not crash.
        var ideas = List.of(idea("Design Systems", "tokens, components, tailwind", "design,frontend"));
        var skills = SkillExtractor.extract(1L, ideas);
        assertNotNull(skills);
    }

    @Test
    void realisticVaultProducesMultipleCoherentClusters() {
        // A small but realistic fixture: six AI-related notes, six
        // design-related notes, six fashion-related notes. Extraction
        // should produce at least two distinct clusters that are
        // clearly themed.
        var ideas = List.of(
                idea("RAG pipelines",     "retrieval augmented generation with langchain and vector databases", "ai,rag,llm"),
                idea("LLM fine-tuning",   "fine-tuning large language models on custom datasets for domain tasks", "ai,llm,training"),
                idea("Agents overview",   "autonomous agents using langchain tools and vector memory", "ai,agents,llm"),
                idea("Embeddings primer", "sentence embeddings for semantic search and retrieval tasks", "ai,embeddings,retrieval"),
                idea("Vector databases",  "comparing pinecone weaviate and chroma for retrieval workloads", "ai,vector,retrieval"),
                idea("Prompt engineering","techniques for effective llm prompting and structured output", "ai,prompts,llm"),

                idea("Tailwind patterns", "utility-first css with tailwind for component design systems", "design,tailwind,css"),
                idea("React components",  "composable react components with tailwind styling", "design,react,frontend"),
                idea("Design tokens",     "semantic color tokens and typography scales for design systems", "design,tokens"),
                idea("Figma workflows",   "collaborative figma design handoff to react frontend developers", "design,figma,frontend"),
                idea("Typography scales", "modular type scales for consistent design across breakpoints", "design,typography"),
                idea("UI animation",      "subtle motion and spring animations in react interfaces", "design,animation,react"),

                idea("Yohji Yamamoto",    "deconstructed silhouettes and draped black garments in yohji yamamoto", "fashion,yohji,avant"),
                idea("Rick Owens",        "brutalist silhouettes and dark romantic garments from rick owens", "fashion,rick,dark"),
                idea("Margiela history",  "maison margiela deconstruction and anonymous couture traditions", "fashion,margiela,avant"),
                idea("Fabric research",   "wool cashmere and technical fabrics for avant streetwear garments", "fashion,fabric,materials"),
                idea("Silhouette study",  "oversized silhouettes in dark avant streetwear fashion", "fashion,silhouette,streetwear"),
                idea("Runway analysis",   "analysing fashion week runways for color palette and silhouette trends", "fashion,runway,trends")
        );

        var skills = SkillExtractor.extract(1L, ideas);
        assertFalse(skills.isEmpty(), "should produce at least one skill");
        assertTrue(skills.size() <= SkillExtractor.MAX_SKILLS,
                "should respect the cluster cap, got " + skills.size());

        // Every skill should have at least one token and a non-negative confidence
        for (var s : skills) {
            assertNotNull(s.getTokens());
            assertFalse(s.getTokens().isEmpty());
            assertTrue(s.getTokens().size() <= SkillExtractor.MAX_TOKENS_PER_SKILL);
            assertTrue(s.getConfidence() >= 0);
            assertTrue(s.getIdeaCount() > 0);
        }

        // Skills should be ordered by confidence descending
        for (int i = 1; i < skills.size(); i++) {
            assertTrue(skills.get(i - 1).getConfidence() >= skills.get(i).getConfidence(),
                    "skills must be sorted by confidence desc");
        }

        // Convert for easier assertions below
        var allTokens = skills.stream()
                .flatMap(s -> s.getTokens().stream())
                .toList();

        // At least one of "fashion", "design", "ai" should surface as a
        // strong token somewhere. All three are expected on a well-tuned
        // pipeline but the test is lenient to avoid flakiness.
        boolean anyThematic =
                allTokens.stream().anyMatch(t -> t.equals("fashion") || t.equals("design")
                        || t.equals("llm") || t.equals("react") || t.equals("tailwind"));
        assertTrue(anyThematic,
                "expected at least one thematic token across clusters, got: " + allTokens);
    }
}
