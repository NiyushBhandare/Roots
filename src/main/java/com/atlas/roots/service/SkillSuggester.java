package com.atlas.roots.service;

import com.atlas.roots.model.Skill;
import com.atlas.roots.model.SubNode;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Suggests which skills a subscription likely supports, based on token
 * overlap between the sub's name/description and the skill's token set.
 *
 * <p>This is the auto-tag engine used by the Subscriptions page: when
 * the user clicks a sub's "suggest skills" button, each skill gets a
 * score from this class and the top 2-3 suggestions are offered as
 * one-click tag options.</p>
 *
 * <h3>Scoring</h3>
 * <p>For each skill, the score is the count of skill-tokens that appear
 * (case-insensitively, whole-word) anywhere in the sub's name or
 * description, divided by the skill's token count. Range {@code [0, 1]}.
 * A sub whose name exactly matches 3 of a skill's 6 tokens scores 0.5.</p>
 *
 * <p>Deliberately simple. No TF-IDF, no embeddings, no fuzzy matching.
 * The signal is strong enough without fanciness: if "Claude Pro" is the
 * sub name and the AI skill has tokens {claude, llm, prompt, retrieval},
 * the overlap is 1/4 = 0.25, which clears the default threshold.</p>
 */
public final class SkillSuggester {

    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");

    /** Minimum normalized score for a skill to appear as a suggestion. */
    public static final double MIN_SUGGESTION_SCORE = 0.15;

    /** Maximum suggestions to surface per sub. */
    public static final int MAX_SUGGESTIONS = 3;

    private SkillSuggester() {}

    /**
     * Return the best-scoring skills for one subscription, ordered by
     * score descending, capped at {@link #MAX_SUGGESTIONS}. Skills
     * below {@link #MIN_SUGGESTION_SCORE} are filtered out.
     */
    public static List<Suggestion> suggestForSub(SubNode sub, List<Skill> skills) {
        Set<String> subTokens = tokenize(sub.getName() + " " + sub.getDescription());
        if (subTokens.isEmpty()) return List.of();

        List<Suggestion> scored = new ArrayList<>();
        for (Skill skill : skills) {
            List<String> skillTokens = skill.getTokens();
            if (skillTokens == null || skillTokens.isEmpty()) continue;
            int matches = 0;
            List<String> matched = new ArrayList<>();
            for (String token : skillTokens) {
                if (token == null) continue;
                if (subTokens.contains(token.toLowerCase())) {
                    matches++;
                    matched.add(token);
                }
            }
            if (matches == 0) continue;
            double score = (double) matches / skillTokens.size();
            if (score < MIN_SUGGESTION_SCORE) continue;
            scored.add(new Suggestion(skill.getId(), skill.getName(), score, matched));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.size() > MAX_SUGGESTIONS ? scored.subList(0, MAX_SUGGESTIONS) : scored;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String token : WORD_SPLIT.split(text.toLowerCase())) {
            if (token.length() >= 3) out.add(token);
        }
        return out;
    }

    /** A single suggestion: which skill, how confident, which tokens matched. */
    public record Suggestion(long skillId, String skillName, double score, List<String> matchedTokens) {}
}
