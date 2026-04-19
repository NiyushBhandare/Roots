package com.atlas.roots.service;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.SkillDao;
import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.Skill;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Infer a user's skills by running TF-IDF over their IdeaNode vault and
 * clustering the strongest tokens into 5-8 skill groups.
 *
 * <h2>Why this feature exists</h2>
 * <p>A user's Obsidian vault is a written record of what they think about.
 * A vault full of notes on React, SwiftUI, and Tailwind tells you the user
 * is a frontend developer — without them ever typing "I am a frontend
 * developer." Roots uses this as the foundation for the context export:
 * when Claude or ChatGPT answers a prompt, it should know what the user
 * is, not just what they asked.</p>
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>Tokenize</b> every Idea: lowercase, split on non-alphanumerics,
 *       drop stopwords and very short tokens. Tags are weighted 2x because
 *       the user explicitly marked them.</li>
 *   <li><b>Compute TF-IDF</b> per token across the corpus of Ideas.
 *       High-TF-IDF tokens are the ones that are specific to this user's
 *       vault — unique to them, not generic words that appear in every note.</li>
 *   <li><b>Rank</b> tokens by total TF-IDF mass. Keep the top N (default 60).</li>
 *   <li><b>Cluster</b> via greedy pairing: iterate tokens in descending
 *       weight; for each, either attach it to an existing cluster if it
 *       co-occurs in the same Ideas as that cluster's seed, or start a
 *       new cluster. This produces 5-8 themed groups.</li>
 *   <li><b>Name</b> each cluster after its heaviest token.</li>
 * </ol>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Co-occurrence, not semantics.</b> Two tokens that show up in
 *       the same notes are in the same cluster. This catches "react" +
 *       "tailwind" + "ui" without needing an embedding model.</li>
 *   <li><b>Hard cap on cluster count.</b> The UI shows skills as cards on
 *       a single screen. Eight is the right number for a demo — more is
 *       visually noisy, fewer makes the feature feel weak.</li>
 *   <li><b>Atomic rebuild.</b> No incremental updates. Every recompute
 *       throws away old skills and regenerates from the current vault.
 *       Keeps the code honest about what a "skill" means (a snapshot of
 *       the vault at the time of extraction).</li>
 * </ul>
 */
public final class SkillExtractor {

    /**
     * Expanded stopword list for skill extraction. Larger than the set
     * used by {@link CognitiveHeatmap} because skill clusters surface
     * single-token names — any function word that slips through ends up
     * as a skill name, which looks wrong to users. Better to over-filter
     * here than let "Every" or "Actually" appear as a Skill card title.
     *
     * Derived from the standard NLTK English stopword list plus common
     * contraction fragments ("isn", "don", "won") that result from
     * stripping apostrophes during tokenization.
     *
     * Implementation note: we build via {@code Set.copyOf(List.of(...))}
     * instead of {@code Set.of(...)} because the former silently dedupes
     * while the latter throws at class-init time on any duplicate. Given
     * the list has ~400 entries across several thematic groups, an
     * accidental duplicate would crash the whole app at boot — the
     * copyOf form keeps the code robust to that edit class.
     */
    private static final Set<String> STOPWORDS = Set.copyOf(java.util.List.of(
            // Articles, pronouns, determiners
            "the","a","an","and","or","but","nor","for","yet","so","of","to","in",
            "on","at","by","is","it","be","as","if","do","my","me","we","us","you",
            "he","she","his","her","him","hers","its","our","ours","your","yours",
            "their","theirs","them","they","these","those","this","that","which",
            "what","who","whom","whose","when","where","why","how","any","each",
            "every","all","some","such","no","not","only","own","same","both",
            "few","many","much","more","most","other","another","others",
            // Common verbs / modals
            "am","are","was","were","been","being","has","have","had","having",
            "does","did","doing","done","can","could","would","should","shall",
            "will","may","might","must","ought","need","needs","needed","use",
            "used","using","uses","make","makes","made","making","get","gets",
            "got","getting","go","goes","going","gone","went","come","comes",
            "came","take","takes","taken","took","give","gives","gave","given",
            "see","sees","saw","seen","know","knows","knew","known","think",
            "thinks","thought","feel","feels","felt","look","looks","looked",
            "want","wants","wanted","seem","seems","seemed","say","says","said",
            "mean","means","meant","let","lets","put","puts","set","sets",
            "find","finds","found","turn","turns","turned","show","shows",
            "showed","shown","tell","tells","told","ask","asks","asked",
            "keep","keeps","kept","become","becomes","became",
            // Conjunctions, prepositions, adverbs
            "about","above","across","after","against","along","among","around",
            "before","behind","below","beside","between","beyond",
            "during","except","from","inside","into","near","off","onto","out",
            "outside","over","past","since","through","throughout","toward",
            "towards","under","until","upon","with","within","without","also",
            "just","even","still","ever","always","often","sometimes","never",
            "here","there","then","than","now","well","rather","quite",
            "very","really","actually","already","almost","enough","perhaps",
            "maybe","probably","likely","simply","certainly","exactly","truly",
            "essentially","basically","generally","usually","mostly","mainly",
            "literally","clearly","obviously","however","therefore","because",
            "although","though","unless","whether","while","whereas",
            // Contraction fragments (apostrophe stripping artefacts)
            "isn","aren","wasn","weren","hasn","haven","hadn","doesn","didn",
            "won","wouldn","shan","shouldn","couldn","mustn","mightn",
            "needn","ain",
            // Common noise
            "thing","things","way","ways","time","times","day","days","year",
            "years","week","weeks","month","months","minute","minutes","hour",
            "hours","seconds","point","points","part","parts","kind",
            "type","types","sort","sorts","lot","lots","bit","bits",
            "one","two","three","four","five","six","seven","eight","nine",
            "ten","first","second","third","next","last","new","old",
            "good","bad","best","worst","better","worse","big","small","large",
            "little","long","short","high","low","right","left","wrong",
            "different","similar","whole","full","empty","real","true",
            "false","sure","able","hard","easy","fast","slow",
            // UI and common-product fillers
            "tool","tools","app","apps","system","systems","feature","features",
            "user","users","people","person","stuff","item","items",
            "case","cases","place","places","side","sides","end","ends",
            "start","starts","beginning","middle","top","bottom","back",
            "front","screen","screens","page","pages","line","lines","word",
            "words","name","names","number","numbers","list","lists",
            "example","examples","version","versions","group","groups",
            // Vague verbs that rarely carry skill signal
            "work","works","worked","working","build","builds","built","building",
            "run","runs","ran","running","try","tries","tried","trying",
            "help","helps","helped","helping","call","calls","called","calling",
            "pick","picks","picked","picking","move","moves","moved","moving"
    ));

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");

    /** Minimum token length to keep — filters "a", "is", etc. */
    static final int MIN_TOKEN_LENGTH = 3;

    /** How many top tokens enter the clustering step. More = richer clusters, slower. */
    static final int TOP_TOKENS = 60;

    /** Max number of skill clusters to produce. Hard cap for UI polish. */
    static final int MAX_SKILLS = 8;

    /** Max tokens per skill cluster — anything beyond this is trimmed. */
    static final int MAX_TOKENS_PER_SKILL = 8;

    /**
     * Minimum co-occurrence overlap for a token to join an existing
     * cluster. Range 0..1 — the fraction of the token's Ideas that must
     * also contain the cluster seed.
     */
    static final double COOCCURRENCE_THRESHOLD = 0.3;

    /**
     * Minimum confidence for the weakest cluster. Prevents weak clusters
     * from displaying as 0% (which looks broken). The strongest cluster
     * always maps to 1.0; everything else interpolates linearly from
     * this floor up to 1.0 by percentile rank of raw strength.
     */
    static final double CONFIDENCE_FLOOR = 0.35;

    private final IdeaNodeDao ideaDao;
    private final SkillDao    skillDao;

    public SkillExtractor(IdeaNodeDao ideaDao, SkillDao skillDao) {
        this.ideaDao  = ideaDao;
        this.skillDao = skillDao;
    }

    /**
     * Run extraction for one user, persist the result, and return the
     * new skill list. If the user has no Ideas, the skill set is
     * cleared (atomic empty replace) and an empty list is returned.
     *
     * User renames (stored in skill_overrides) are re-applied to any
     * freshly-extracted cluster whose signature matches — so curation
     * survives re-extraction.
     */
    public List<Skill> recomputeForUser(long ownerId) throws SQLException {
        List<IdeaNode> ideas = ideaDao.findByOwner(ownerId);
        List<Skill> skills = ideas.isEmpty() ? List.of() : extract(ownerId, ideas);

        // Apply any user-set rename overrides before persisting. The
        // signature is derived from the cluster's top-3 tokens (stable
        // across extractions of the same theme).
        if (!skills.isEmpty()) {
            java.util.Map<String, String> overrides = skillDao.loadOverrides(ownerId);
            if (!overrides.isEmpty()) {
                for (Skill s : skills) {
                    String sig = SkillDao.signatureOf(s.getTokens());
                    String renamed = overrides.get(sig);
                    if (renamed != null && !renamed.isBlank()) {
                        s.setName(renamed);
                    }
                }
            }
        }

        skillDao.replaceAllForOwner(ownerId, skills);
        return skills;
    }

    // -----------------------------------------------------------------
    //  Extraction pipeline — pure functions, testable without a DB
    // -----------------------------------------------------------------

    /** Extract skills from an in-memory list of Ideas. */
    static List<Skill> extract(long ownerId, List<IdeaNode> ideas) {
        // 1. Tokenize every idea into a Set<String> of unique tokens.
        List<Set<String>> docs = new ArrayList<>(ideas.size());
        for (IdeaNode idea : ideas) {
            docs.add(tokenizeIdea(idea));
        }

        // 2. Compute document frequency per token (how many docs contain it).
        Map<String, Integer> df = new HashMap<>();
        for (Set<String> doc : docs) {
            for (String token : doc) {
                df.merge(token, 1, Integer::sum);
            }
        }

        // 3. Compute TF-IDF score per token, summed across docs.
        //    Since our per-doc tokens are already unique (we use a Set),
        //    TF is effectively 1 for every appearance. IDF is log(N/df).
        int N = Math.max(1, docs.size());
        Map<String, Double> tfidf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            String token = e.getKey();
            int    docFreq = e.getValue();
            // Skip tokens that appear in every doc — they're not
            // discriminating, they're just frequent.
            if (docFreq == N && N > 3) continue;
            // Skip tokens that appear in only one doc — too rare to
            // be a skill, likely proper nouns or typos.
            if (docFreq < 2 && N > 5) continue;
            double idf = Math.log((double) N / docFreq);
            tfidf.put(token, docFreq * idf);
        }

        // 4. Keep the top N by score.
        List<String> topTokens = tfidf.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(TOP_TOKENS)
                .map(Map.Entry::getKey)
                .toList();

        if (topTokens.isEmpty()) return List.of();

        // 5. Build a map of token → set-of-doc-indices-it-appears-in.
        //    This is what drives the co-occurrence clustering below.
        Map<String, Set<Integer>> tokenDocs = new HashMap<>();
        for (String token : topTokens) tokenDocs.put(token, new HashSet<>());
        for (int i = 0; i < docs.size(); i++) {
            for (String token : docs.get(i)) {
                Set<Integer> bucket = tokenDocs.get(token);
                if (bucket != null) bucket.add(i);
            }
        }

        // 6. Greedy clustering by co-occurrence.
        List<Cluster> clusters = new ArrayList<>();
        for (String token : topTokens) {
            Set<Integer> tokenIn = tokenDocs.get(token);
            Cluster best = null;
            double  bestOverlap = 0.0;
            for (Cluster c : clusters) {
                double overlap = jaccard(tokenIn, c.docs);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = c;
                }
            }
            if (best != null && bestOverlap >= COOCCURRENCE_THRESHOLD
                    && best.tokens.size() < MAX_TOKENS_PER_SKILL) {
                best.tokens.add(token);
                best.weights.add(tfidf.get(token));
                best.docs.addAll(tokenIn);
            } else if (clusters.size() < MAX_SKILLS) {
                Cluster c = new Cluster();
                c.tokens.add(token);
                c.weights.add(tfidf.get(token));
                c.docs.addAll(tokenIn);
                clusters.add(c);
            }
            // If we've hit MAX_SKILLS and the token doesn't fit any, it's dropped.
        }

        // 7. Convert clusters to Skill records.
        // First compute a raw strength score per cluster — this drives
        // both the confidence percentile and the internal ordering.
        // Strength rewards clusters that are broad (many tokens), deep
        // (many supporting ideas), and thematically sharp (high mean TF-IDF).
        double[] rawStrength = new double[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            double meanWeight = average(c.weights);
            // Log-scale the sizes so a single giant cluster doesn't
            // dominate the distribution; diversity of tokens + ideas
            // matters more than raw counts beyond a threshold.
            double tokenSpread = Math.log1p(c.tokens.size());
            double ideaSpread  = Math.log1p(c.docs.size());
            rawStrength[i] = meanWeight * tokenSpread * ideaSpread;
        }

        // Build a sorted copy of strengths to compute percentile ranks.
        // Percentile-based confidence guarantees that the strongest skill
        // always lands at 1.0 and the weakest at CONFIDENCE_FLOOR, with
        // everything else interpolated linearly — producing a useful
        // spread regardless of vault size.
        double[] sortedStrength = rawStrength.clone();
        java.util.Arrays.sort(sortedStrength);
        double minStrength = sortedStrength[0];
        double maxStrength = sortedStrength[sortedStrength.length - 1];
        double range = Math.max(0.0001, maxStrength - minStrength);

        List<Skill> out = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            // Pick the most *central* token as the skill name — the one
            // with the highest sum of co-occurrence with the rest of the
            // cluster. This avoids the bug where a high-TF-IDF-but-
            // peripheral token wins the name (e.g. "Context" beating "Ai").
            String name = pickCentralTokenName(c, tokenDocs);

            // Percentile-based confidence: map each cluster's raw strength
            // to [CONFIDENCE_FLOOR, 1.0] so even the weakest cluster gets
            // a visible band and the strongest always saturates to 1.0.
            double normalised = CONFIDENCE_FLOOR
                    + (1.0 - CONFIDENCE_FLOOR) * ((rawStrength[i] - minStrength) / range);

            Skill skill = new Skill(ownerId, name, new ArrayList<>(c.tokens),
                    c.docs.size(), normalised);
            out.add(skill);
        }

        // 8. Sort skills by confidence so the UI shows the strongest first.
        out.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
        return out;
    }

    /**
     * Pick a display name for a cluster by choosing the token whose
     * Jaccard overlap with the rest of the cluster's documents is
     * highest. This produces a *central* theme name rather than the
     * peripheral-but-high-TF-IDF token that would otherwise win.
     *
     * If the cluster has one token, that token is the name. If two
     * tokens, the one with the wider document footprint wins.
     */
    private static String pickCentralTokenName(Cluster c, Map<String, Set<Integer>> tokenDocs) {
        if (c.tokens.size() <= 1) return titleCase(c.tokens.get(0));
        String best = c.tokens.get(0);
        double bestScore = -1.0;
        for (String token : c.tokens) {
            Set<Integer> docs = tokenDocs.get(token);
            if (docs == null) continue;
            // A token's centrality score: size of its doc footprint
            // (how many ideas support it) times the mean overlap with
            // every *other* token's footprint (how tied-in it is).
            double sumOverlap = 0;
            int    others     = 0;
            for (String other : c.tokens) {
                if (other.equals(token)) continue;
                Set<Integer> otherDocs = tokenDocs.get(other);
                if (otherDocs == null) continue;
                sumOverlap += jaccard(docs, otherDocs);
                others++;
            }
            double centrality = docs.size() * (others == 0 ? 1.0 : sumOverlap / others);
            if (centrality > bestScore) {
                bestScore = centrality;
                best = token;
            }
        }
        return titleCase(best);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Tokenize a single idea. Combines name + description + tags, with
     * tags weighted 2x (added to the set twice effectively — but since
     * we use a Set this double-add doesn't literally multiply; tags
     * simply appear whether or not the description mentions them).
     */
    static Set<String> tokenizeIdea(IdeaNode idea) {
        StringBuilder sb = new StringBuilder();
        if (idea.getName() != null)        sb.append(idea.getName()).append(' ');
        if (idea.getDescription() != null) sb.append(idea.getDescription()).append(' ');
        if (idea.getTags() != null) {
            // Tags appear twice so they count in both the document and in
            // the co-occurrence graph with extra weight.
            sb.append(idea.getTags().replace(',', ' ')).append(' ');
            sb.append(idea.getTags().replace(',', ' ')).append(' ');
        }
        return tokenize(sb.toString());
    }

    /** Tokenize arbitrary text. Returns a Set for uniqueness per document. */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String raw : TOKEN_SPLIT.split(text.toLowerCase())) {
            if (raw.length() < MIN_TOKEN_LENGTH) continue;
            if (STOPWORDS.contains(raw)) continue;
            if (raw.chars().allMatch(Character::isDigit)) continue;
            out.add(raw);
        }
        return out;
    }

    /** Jaccard similarity between two integer sets. */
    static double jaccard(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (int x : a) if (b.contains(x)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    static double average(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    /** Make a token look presentable as a skill name. */
    static String titleCase(String token) {
        if (token == null || token.isEmpty()) return token;
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    /** Internal mutable cluster used during greedy aggregation. */
    private static final class Cluster {
        final List<String>   tokens  = new ArrayList<>();
        final List<Double>   weights = new ArrayList<>();
        final Set<Integer>   docs    = new HashSet<>();
    }
}
