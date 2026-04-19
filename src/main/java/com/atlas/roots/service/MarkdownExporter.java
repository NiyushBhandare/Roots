package com.atlas.roots.service;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.RepoNodeDao;
import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.RepoNode;
import com.atlas.roots.model.SubNode;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports the entire Roots database as a single structured markdown file.
 *
 * <p>The output is both human-readable and machine-parseable: you can
 * open the file in Obsidian and read it as a snapshot of your ecosystem,
 * or re-upload it through {@link MarkdownImporter} to rebuild the
 * database on another machine (or as a backup/restore operation).</p>
 *
 * <h2>File format</h2>
 * <pre>
 * ---
 * export_version: 1
 * exported_at: 2026-04-14T14:30:00Z
 * exported_by: draco
 * node_count: 16
 * ---
 *
 * # Roots Export
 *
 * &gt; A local-first life management grounder. This file is both a
 * &gt; human-readable snapshot and a machine-parseable backup.
 *
 * ## Subscriptions
 *
 * ### Claude Pro
 * - type: SUB
 * - description: Primary AI assistant
 * - monthly_cost: 1800.00
 * - currency: INR
 * - cadence: MONTHLY
 * - joy_rating: 10
 * - started_on: 2025-03-15
 * - next_renewal: 2026-05-15
 *
 * ## Repositories
 *
 * ### Ampeiron
 * - type: REPO
 * - description: Local-first AI system
 * - local_path: ~/PycharmProjects/Ampeiron
 * - ...
 *
 * ## Ideas
 *
 * ### SEXAPPEAL — Style Bible
 * - type: IDEA
 * - description: Personal brand
 * - vault_path: SEXAPPEAL/bible.md
 * - ...
 * </pre>
 *
 * <p>Every field on every node is written. Empty / null fields are
 * omitted (not written as {@code null}) so the file reads naturally
 * when viewed in Obsidian.</p>
 */
public final class MarkdownExporter {

    private final SubNodeDao  subDao;
    private final RepoNodeDao repoDao;
    private final IdeaNodeDao ideaDao;

    public MarkdownExporter(SubNodeDao subDao, RepoNodeDao repoDao, IdeaNodeDao ideaDao) {
        this.subDao  = subDao;
        this.repoDao = repoDao;
        this.ideaDao = ideaDao;
    }

    /**
     * Build the complete markdown export as a single string.
     *
     * @param exportedBy the username of the person requesting the export
     * @return the full markdown document, ready to be written to a file
     */
    public String exportAll(String exportedBy) throws SQLException {
        List<SubNode>  subs  = subDao.findAll();
        List<RepoNode> repos = repoDao.findAll();
        List<IdeaNode> ideas = ideaDao.findAll();
        int totalNodes = subs.size() + repos.size() + ideas.size();

        StringBuilder out = new StringBuilder(8192);

        // Frontmatter
        out.append("---\n");
        out.append("export_version: 1\n");
        out.append("exported_at: ").append(Instant.now().toString()).append('\n');
        out.append("exported_by: ").append(safe(exportedBy)).append('\n');
        out.append("node_count: ").append(totalNodes).append('\n');
        out.append("---\n\n");

        // Preamble
        out.append("# Roots Export\n\n");
        out.append("> A local-first life management grounder. This file is both a\n");
        out.append("> human-readable snapshot of your ecosystem and a machine-parseable\n");
        out.append("> backup. Re-importing it rebuilds the database.\n\n");

        // Subscriptions
        out.append("## Subscriptions\n\n");
        if (subs.isEmpty()) {
            out.append("_no subscriptions_\n\n");
        } else {
            for (SubNode s : subs) appendSub(out, s);
        }

        // Repositories
        out.append("## Repositories\n\n");
        if (repos.isEmpty()) {
            out.append("_no repositories_\n\n");
        } else {
            for (RepoNode r : repos) appendRepo(out, r);
        }

        // Ideas
        out.append("## Ideas\n\n");
        if (ideas.isEmpty()) {
            out.append("_no ideas_\n\n");
        } else {
            for (IdeaNode i : ideas) appendIdea(out, i);
        }

        return out.toString();
    }

    // -----------------------------------------------------------------
    //  Per-type emitters
    // -----------------------------------------------------------------

    private void appendSub(StringBuilder out, SubNode s) {
        out.append("### ").append(safe(s.getName())).append('\n');
        kv(out, "type", "SUB");
        kvIf(out, "description", s.getDescription());
        kv(out, "monthly_cost", String.format("%.2f", s.getMonthlyCost()));
        kvIf(out, "currency", s.getCurrency());
        kv(out, "cadence", s.getCadence().name());
        kv(out, "joy_rating", String.valueOf(s.getJoyRating()));
        kvIf(out, "started_on", fmt(s.getStartedOn()));
        kvIf(out, "next_renewal", fmt(s.getNextRenewal()));
        kvIf(out, "created_at", fmt(s.getCreatedAt()));
        kvIf(out, "last_touched", fmt(s.getLastTouched()));
        out.append('\n');
    }

    private void appendRepo(StringBuilder out, RepoNode r) {
        out.append("### ").append(safe(r.getName())).append('\n');
        kv(out, "type", "REPO");
        kvIf(out, "description", r.getDescription());
        kvIf(out, "local_path", r.getLocalPath());
        kvIf(out, "remote_url", r.getRemoteUrl());
        kvIf(out, "primary_language", r.getPrimaryLanguage());
        kv(out, "commit_count", String.valueOf(r.getCommitCount()));
        kvIf(out, "last_commit_at", fmt(r.getLastCommitAt()));
        kv(out, "stale_threshold_days", String.valueOf(r.getStaleThresholdDays()));
        kvIf(out, "created_at", fmt(r.getCreatedAt()));
        kvIf(out, "last_touched", fmt(r.getLastTouched()));
        out.append('\n');
    }

    private void appendIdea(StringBuilder out, IdeaNode i) {
        out.append("### ").append(safe(i.getName())).append('\n');
        kv(out, "type", "IDEA");
        kvIf(out, "description", i.getDescription());
        kvIf(out, "vault_path", i.getVaultPath());
        kv(out, "word_count", String.valueOf(i.getWordCount()));
        kv(out, "backlink_count", String.valueOf(i.getBacklinkCount()));
        kvIf(out, "last_edited_at", fmt(i.getLastEditedAt()));
        kvIf(out, "tags", i.getTags());
        kvIf(out, "created_at", fmt(i.getCreatedAt()));
        kvIf(out, "last_touched", fmt(i.getLastTouched()));
        out.append('\n');
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void kv(StringBuilder out, String key, String value) {
        out.append("- ").append(key).append(": ").append(safe(value)).append('\n');
    }

    private void kvIf(StringBuilder out, String key, String value) {
        if (value == null || value.isBlank()) return;
        kv(out, key, value);
    }

    private String fmt(LocalDate d) {
        return d == null ? null : d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private String fmt(LocalDateTime dt) {
        return dt == null ? null : dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Escape a value for inline storage in the markdown file. Strips
     * newlines (which would break the single-line bullet format) and
     * trims surrounding whitespace. The format is forgiving enough
     * that we don't need full markdown escaping.
     */
    private String safe(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
