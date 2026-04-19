package com.atlas.roots.service;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.RepoNodeDao;
import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.RepoNode;
import com.atlas.roots.model.SubNode;
import com.atlas.roots.util.ValidationException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Parses a Roots markdown export and applies it to the database.
 *
 * <p>Round-trips with {@link MarkdownExporter}: anything that exporter
 * writes, this importer reads back into the same database state.</p>
 *
 * <h2>Merge modes</h2>
 * <ul>
 *   <li><b>OVERWRITE</b> — for each node in the file, if an existing
 *       node with the same name + type already exists, replace its
 *       fields. Otherwise create. Default for "restoring a backup".</li>
 *   <li><b>SKIP</b> — for each node in the file, if an existing node
 *       with the same name + type already exists, leave it alone.
 *       Otherwise create. Use when "importing someone else's template"
 *       and you don't want to lose your own data.</li>
 *   <li><b>REPLACE</b> — wipe every node owned by the importing user
 *       before reading the file, then create from scratch. Use for
 *       "restore to clean state".</li>
 * </ul>
 */
public final class MarkdownImporter {

    public enum Mode { OVERWRITE, SKIP, REPLACE }

    public static final class Result {
        public int created;
        public int updated;
        public int skipped;
        public int failed;
        public final List<String> errors = new ArrayList<>();
    }

    /** A parsed node from the markdown file, before applying to DB. */
    private static final class ParsedNode {
        String type;             // SUB / REPO / IDEA
        String name;
        Map<String, String> fields = new LinkedHashMap<>();
    }

    private final SubNodeDao  subDao;
    private final RepoNodeDao repoDao;
    private final IdeaNodeDao ideaDao;

    public MarkdownImporter(SubNodeDao subDao, RepoNodeDao repoDao, IdeaNodeDao ideaDao) {
        this.subDao  = subDao;
        this.repoDao = repoDao;
        this.ideaDao = ideaDao;
    }

    /**
     * Parse the markdown text and apply it under the given owner with
     * the chosen merge mode.
     */
    public Result importMarkdown(String markdown, long ownerId, Mode mode) throws SQLException {
        Result result = new Result();
        if (markdown == null || markdown.isBlank()) {
            result.errors.add("empty file");
            return result;
        }

        // REPLACE mode: wipe everything for this owner before parsing.
        if (mode == Mode.REPLACE) {
            subDao.deleteAllByOwner(ownerId);
            repoDao.deleteAllByOwner(ownerId);
            ideaDao.deleteAllByOwner(ownerId);
        }

        List<ParsedNode> parsed = parse(markdown);

        for (ParsedNode p : parsed) {
            try {
                applyOne(p, ownerId, mode, result);
            } catch (Exception e) {
                result.failed++;
                result.errors.add(p.name + ": " + e.getMessage());
            }
        }

        return result;
    }

    // -----------------------------------------------------------------
    //  Parser
    // -----------------------------------------------------------------

    /**
     * Split the markdown into a list of {@link ParsedNode}.
     *
     * <p>The parser is intentionally forgiving: it skips frontmatter,
     * skips H1 / H2 section headers (which are decoration), and treats
     * every H3 as the start of a node block. Bullet lines under each
     * H3 of the form {@code - key: value} become fields. Anything else
     * is ignored, so the file can contain prose between nodes without
     * confusing the parser.</p>
     */
    private List<ParsedNode> parse(String markdown) {
        List<ParsedNode> out = new ArrayList<>();
        ParsedNode current = null;
        boolean inFrontmatter = false;
        int frontmatterMarkers = 0;

        for (String rawLine : markdown.split("\\r?\\n")) {
            String line = rawLine;

            // Skip YAML-style frontmatter at the top
            if (line.trim().equals("---")) {
                frontmatterMarkers++;
                inFrontmatter = (frontmatterMarkers == 1);
                if (frontmatterMarkers == 2) inFrontmatter = false;
                continue;
            }
            if (inFrontmatter) continue;

            // H3 starts a new node
            if (line.startsWith("### ")) {
                if (current != null && current.name != null) out.add(current);
                current = new ParsedNode();
                current.name = line.substring(4).trim();
                continue;
            }

            // Bullet line — `- key: value`
            if (current != null && line.trim().startsWith("- ")) {
                String body = line.trim().substring(2);
                int colon = body.indexOf(':');
                if (colon > 0) {
                    String key   = body.substring(0, colon).trim();
                    String value = body.substring(colon + 1).trim();
                    if ("type".equals(key)) {
                        current.type = value;
                    } else {
                        current.fields.put(key, value);
                    }
                }
            }
        }
        // Flush the last node
        if (current != null && current.name != null) out.add(current);

        return out;
    }

    // -----------------------------------------------------------------
    //  Apply
    // -----------------------------------------------------------------

    private void applyOne(ParsedNode p, long ownerId, Mode mode, Result result) throws SQLException {
        if (p.type == null || p.type.isBlank()) {
            // Try to infer from required fields if user hand-edited
            if (p.fields.containsKey("monthly_cost"))      p.type = "SUB";
            else if (p.fields.containsKey("local_path"))   p.type = "REPO";
            else if (p.fields.containsKey("vault_path"))   p.type = "IDEA";
            else throw new ValidationException("missing type");
        }

        switch (p.type) {
            case "SUB"  -> applySub(p, ownerId, mode, result);
            case "REPO" -> applyRepo(p, ownerId, mode, result);
            case "IDEA" -> applyIdea(p, ownerId, mode, result);
            default     -> throw new ValidationException("unknown type: " + p.type);
        }
    }

    private void applySub(ParsedNode p, long ownerId, Mode mode, Result result) throws SQLException {
        Optional<SubNode> existing = (mode == Mode.REPLACE)
                ? Optional.empty()
                : subDao.findByExactName(ownerId, p.name);

        if (existing.isPresent() && mode == Mode.SKIP) {
            result.skipped++;
            return;
        }

        double      monthlyCost  = parseDouble(p.fields.get("monthly_cost"), 0.0);
        String      currency     = orDefault(p.fields.get("currency"), "INR");
        SubNode.Cadence cadence  = parseCadence(p.fields.get("cadence"));
        int         joyRating    = parseInt(p.fields.get("joy_rating"), 5);
        LocalDate   startedOn    = parseDate(p.fields.get("started_on"), LocalDate.now());
        LocalDate   nextRenewal  = parseDate(p.fields.get("next_renewal"), null);
        String      description  = p.fields.get("description");

        if (existing.isPresent() && mode == Mode.OVERWRITE) {
            SubNode e = existing.get();
            SubNode updated = new SubNode(
                    e.getId(), p.name, description, ownerId,
                    e.getCreatedAt(), LocalDateTime.now(), false,
                    monthlyCost, currency, cadence, joyRating, startedOn, nextRenewal);
            subDao.update(updated);
            result.updated++;
        } else {
            SubNode created = new SubNode(
                    p.name, description, ownerId,
                    monthlyCost, currency, cadence, joyRating, startedOn, nextRenewal);
            subDao.insert(created);
            result.created++;
        }
    }

    private void applyRepo(ParsedNode p, long ownerId, Mode mode, Result result) throws SQLException {
        Optional<RepoNode> existing = (mode == Mode.REPLACE)
                ? Optional.empty()
                : repoDao.findByExactName(ownerId, p.name);

        if (existing.isPresent() && mode == Mode.SKIP) {
            result.skipped++;
            return;
        }

        String        localPath          = orDefault(p.fields.get("local_path"), "");
        String        remoteUrl          = p.fields.get("remote_url");
        String        primaryLanguage    = p.fields.get("primary_language");
        int           commitCount        = parseInt(p.fields.get("commit_count"), 0);
        LocalDateTime lastCommitAt       = parseDateTime(p.fields.get("last_commit_at"), null);
        int           staleThresholdDays = parseInt(p.fields.get("stale_threshold_days"), 30);
        String        description        = p.fields.get("description");

        if (existing.isPresent() && mode == Mode.OVERWRITE) {
            RepoNode e = existing.get();
            RepoNode updated = new RepoNode(
                    e.getId(), p.name, description, ownerId,
                    e.getCreatedAt(), LocalDateTime.now(), false,
                    localPath, remoteUrl, primaryLanguage,
                    commitCount, lastCommitAt, staleThresholdDays);
            repoDao.update(updated);
            result.updated++;
        } else {
            RepoNode created = new RepoNode(
                    p.name, description, ownerId,
                    localPath, remoteUrl, primaryLanguage,
                    commitCount, lastCommitAt, staleThresholdDays);
            repoDao.insert(created);
            result.created++;
        }
    }

    private void applyIdea(ParsedNode p, long ownerId, Mode mode, Result result) throws SQLException {
        Optional<IdeaNode> existing = (mode == Mode.REPLACE)
                ? Optional.empty()
                : ideaDao.findByExactName(ownerId, p.name);

        if (existing.isPresent() && mode == Mode.SKIP) {
            result.skipped++;
            return;
        }

        String        vaultPath     = orDefault(p.fields.get("vault_path"), p.name + ".md");
        int           wordCount     = parseInt(p.fields.get("word_count"), 0);
        int           backlinkCount = parseInt(p.fields.get("backlink_count"), 0);
        LocalDateTime lastEditedAt  = parseDateTime(p.fields.get("last_edited_at"), null);
        String        tags          = p.fields.get("tags");
        String        description   = p.fields.get("description");

        if (existing.isPresent() && mode == Mode.OVERWRITE) {
            IdeaNode e = existing.get();
            IdeaNode updated = new IdeaNode(
                    e.getId(), p.name, description, ownerId,
                    e.getCreatedAt(), LocalDateTime.now(), false,
                    vaultPath, wordCount, backlinkCount, lastEditedAt, tags);
            ideaDao.update(updated);
            result.updated++;
        } else {
            IdeaNode created = new IdeaNode(
                    p.name, description, ownerId,
                    vaultPath, wordCount, backlinkCount, lastEditedAt, tags);
            ideaDao.insert(created);
            result.created++;
        }
    }

    // -----------------------------------------------------------------
    //  Field parsing helpers — every one is forgiving, never throws on
    //  malformed input, falls back to a sensible default instead.
    // -----------------------------------------------------------------

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (DateTimeParseException e) { return fallback; }
    }

    private static LocalDateTime parseDateTime(String s, LocalDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (DateTimeParseException e) { return fallback; }
    }

    private static SubNode.Cadence parseCadence(String s) {
        if (s == null || s.isBlank()) return SubNode.Cadence.MONTHLY;
        try { return SubNode.Cadence.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return SubNode.Cadence.MONTHLY; }
    }

    private static String orDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
