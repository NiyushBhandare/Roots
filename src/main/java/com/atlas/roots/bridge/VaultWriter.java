package com.atlas.roots.bridge;

import com.atlas.roots.model.IdeaNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes an {@link IdeaNode} back to its source markdown file in the vault.
 *
 * <p>This is the "Roots → vault" half of the bidirectional sync. The other
 * half is {@link ObsidianBridge}, which reads files and upserts IdeaNodes.
 * Together they mean: whichever side you edit on, the other catches up.</p>
 *
 * <h2>File format</h2>
 * <p>Files are rendered as markdown with YAML frontmatter for metadata.
 * Existing body content (the meaningful part — paragraphs, headings, lists)
 * is preserved verbatim when a file is updated. Only the frontmatter is
 * rewritten. This means: a user editing the body in Obsidian, Roots
 * updating the description separately, both edits survive — as long as
 * the edits don't collide on the same lines.</p>
 *
 * <pre>
 * ---
 * roots_id: 42
 * roots_word_count: 1250
 * roots_backlinks: 3
 * tags: [seen, metrics]
 * last_touched: 2026-04-17T14:20:00Z
 * ---
 *
 * # The Resonance Score
 *
 * This is the body content that the user wrote in Obsidian…
 * </pre>
 *
 * <h2>Self-write debouncing</h2>
 * <p>If Roots writes a file and {@link ObsidianBridge}'s FileWatcher is
 * running, the write will trigger an ENTRY_MODIFY event that would cause
 * Roots to re-read the same file it just wrote. {@link #wasRecentlyWritten}
 * lets the watcher skip those events. The debounce window is 2 seconds —
 * long enough to catch the echo, short enough that a real external edit
 * 3 seconds later still gets picked up.</p>
 */
public final class VaultWriter {

    /** Debounce window for self-write detection, in milliseconds. */
    private static final long SELF_WRITE_WINDOW_MS = 2_000L;

    /**
     * Map of vault-relative-path → epoch-millis-of-last-self-write.
     * Used by FileWatcher to skip events triggered by our own writes.
     * ConcurrentHashMap because the FileWatcher runs on a separate thread.
     */
    private final Map<String, Long> recentWrites = new ConcurrentHashMap<>();

    /**
     * Render an {@link IdeaNode} to markdown and write it to
     * {@code vaultRoot / node.getVaultPath()}. Creates parent directories
     * as needed. If the file already exists, the body content is preserved
     * and only the frontmatter is updated.
     */
    public void writeNode(Path vaultRoot, IdeaNode node) throws IOException {
        if (vaultRoot == null) throw new IOException("vault root is not configured");
        if (node.getVaultPath() == null || node.getVaultPath().isBlank()) {
            throw new IOException("idea node has no vault_path; cannot write to vault");
        }

        Path target = vaultRoot.resolve(node.getVaultPath());
        Files.createDirectories(target.getParent() == null ? vaultRoot : target.getParent());

        // Preserve existing body if the file already exists so user edits
        // in Obsidian survive a Roots-side update.
        String existingBody = "";
        if (Files.exists(target)) {
            String existing = Files.readString(target);
            existingBody = stripFrontmatter(existing);
        }

        String content = renderMarkdown(node, existingBody);
        Files.writeString(target, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        // Mark this file as recently self-written so the FileWatcher
        // doesn't round-trip back into Roots.
        recentWrites.put(node.getVaultPath(), System.currentTimeMillis());
    }

    /**
     * True if Roots wrote the given vault-relative path within the
     * debounce window. The FileWatcher consults this before acting
     * on ENTRY_MODIFY events to avoid infinite feedback loops.
     */
    public boolean wasRecentlyWritten(String vaultRelativePath) {
        Long ts = recentWrites.get(vaultRelativePath);
        if (ts == null) return false;
        boolean recent = System.currentTimeMillis() - ts < SELF_WRITE_WINDOW_MS;
        if (!recent) recentWrites.remove(vaultRelativePath);
        return recent;
    }

    // -----------------------------------------------------------------
    //  Rendering + parsing helpers
    // -----------------------------------------------------------------

    /**
     * Render an IdeaNode as a markdown document. The body argument is
     * the existing body content (everything after the frontmatter) that
     * should be preserved; pass an empty string for a fresh file.
     */
    String renderMarkdown(IdeaNode node, String existingBody) {
        StringBuilder out = new StringBuilder();

        // Frontmatter — machine-readable metadata
        out.append("---\n");
        out.append("roots_id: ").append(node.getId()).append('\n');
        out.append("roots_word_count: ").append(node.getWordCount()).append('\n');
        out.append("roots_backlinks: ").append(node.getBacklinkCount()).append('\n');
        if (node.getTags() != null && !node.getTags().isBlank()) {
            out.append("tags: [").append(formatTags(node.getTags())).append("]\n");
        }
        if (node.getLastEditedAt() != null) {
            out.append("last_touched: ")
               .append(node.getLastEditedAt().atZone(java.time.ZoneId.systemDefault())
                       .toInstant().toString())
               .append('\n');
        }
        out.append("---\n\n");

        // If there's an existing body, keep it verbatim. Otherwise,
        // synthesize a minimal body from name + description.
        if (!existingBody.isBlank()) {
            out.append(existingBody.trim()).append('\n');
        } else {
            out.append("# ").append(node.getName() == null ? "Untitled" : node.getName()).append("\n\n");
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                out.append(node.getDescription().trim()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Strip YAML frontmatter from markdown content, returning just the
     * body. If there is no frontmatter, the whole input is returned.
     */
    static String stripFrontmatter(String content) {
        if (content == null) return "";
        // Frontmatter starts with --- at file start, ends with --- on its own line
        if (!content.startsWith("---")) return content;
        int endMarker = content.indexOf("\n---", 3);
        if (endMarker < 0) return content;
        int bodyStart = content.indexOf('\n', endMarker + 1);
        if (bodyStart < 0) return "";
        return content.substring(bodyStart + 1);
    }

    /** Turn "a,b,c" into "a, b, c" — trivial but YAML-friendly. */
    private static String formatTags(String tagsCsv) {
        String[] parts = tagsCsv.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts[i].trim());
        }
        return sb.toString();
    }
}
