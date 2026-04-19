package com.atlas.roots.bridge;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.model.IdeaNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Obsidian bridge.
 *
 * <p>Treats a folder of {@code .md} files as the source of truth for
 * a user's {@link IdeaNode}s. Two paths into the system:</p>
 *
 * <ul>
 *   <li><b>Manual scan</b> &mdash; {@link #scanVault(Path, long)} walks
 *       the folder once, parses every markdown file, and upserts an
 *       IdeaNode per file. This is the demo-safe path: deterministic,
 *       blocking, returns a count when done.</li>
 *
 *   <li><b>Live watch</b> &mdash; {@link #startWatching(Path, long)} spins
 *       up a {@link WatchService} on a daemon thread that re-ingests
 *       any {@code .md} file the user creates or saves while Roots is
 *       running. This is the showpiece: edit a note in Obsidian, switch
 *       to Roots, the dashboard reflects the change.</li>
 * </ul>
 *
 * <p>The markdown parser is intentionally minimal. It extracts:</p>
 * <ul>
 *   <li>The first {@code # heading} as the node name (falls back to filename)</li>
 *   <li>The first non-empty paragraph as the description (truncated)</li>
 *   <li>Word count by splitting on whitespace</li>
 *   <li>Backlinks via the {@code [[wiki link]]} pattern</li>
 *   <li>Tags via {@code #tag} hashtags or YAML front matter "tags:" lines</li>
 * </ul>
 *
 * <p>Files are identified by their <em>relative</em> path within the vault,
 * which is what {@link IdeaNodeDao#upsertByVaultPath} uses as the key.
 * Moving a file inside the vault therefore registers as a delete + insert
 * &mdash; that is the right behaviour, since the path is the file's
 * stable identity within Obsidian itself.</p>
 */
public final class ObsidianBridge implements AutoCloseable {

    private static final Pattern BACKLINK   = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern HEADING_H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern HASHTAG    = Pattern.compile("(?<![\\w/])#([a-zA-Z][a-zA-Z0-9_-]{1,30})");
    private static final Pattern FRONTMATTER_TAGS =
            Pattern.compile("(?ms)^---.*?^tags:\\s*\\[?([^\\]\\n]+)\\]?.*?^---");

    private final IdeaNodeDao   ideaDao;
    private final VaultWriter   vaultWriter;  // for self-write debouncing
    private       WatchService  watchService;
    private       Thread        watchThread;
    private final AtomicBoolean watching = new AtomicBoolean(false);

    public ObsidianBridge(IdeaNodeDao ideaDao) {
        this(ideaDao, null);
    }

    public ObsidianBridge(IdeaNodeDao ideaDao, VaultWriter vaultWriter) {
        this.ideaDao     = ideaDao;
        this.vaultWriter = vaultWriter;
    }

    // -----------------------------------------------------------------
    //  Manual scan path.
    // -----------------------------------------------------------------

    /**
     * Walk the vault once and upsert every {@code .md} file as an
     * {@link IdeaNode}. Returns the number of nodes touched.
     *
     * @throws IOException if the vault folder cannot be walked.
     */
    public ScanResult scanVault(Path vaultRoot, long ownerId) throws IOException {
        if (!Files.isDirectory(vaultRoot)) {
            throw new IOException("vault root is not a directory: " + vaultRoot);
        }

        List<Path> markdownFiles;
        try (Stream<Path> stream = Files.walk(vaultRoot)) {
            markdownFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .collect(Collectors.toList());
        }

        int created = 0;
        int updated = 0;
        int failed  = 0;
        for (Path file : markdownFiles) {
            try {
                IdeaNode candidate = parseMarkdownFile(vaultRoot, file, ownerId);
                long beforeId = candidate.getId();
                IdeaNode result = ideaDao.upsertByVaultPath(candidate);
                if (beforeId == 0 && result.getId() != 0 && result.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(2))) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                failed++;
            }
        }
        return new ScanResult(markdownFiles.size(), created, updated, failed);
    }

    // -----------------------------------------------------------------
    //  Live watch path.
    // -----------------------------------------------------------------

    /**
     * Start watching the vault on a daemon thread. Re-entrant: calling
     * this twice without {@link #close()} in between is a no-op.
     */
    public synchronized void startWatching(Path vaultRoot, long ownerId) throws IOException {
        if (watching.get()) return;
        if (!Files.isDirectory(vaultRoot)) {
            throw new IOException("vault root is not a directory: " + vaultRoot);
        }

        this.watchService = FileSystems.getDefault().newWatchService();
        registerAll(vaultRoot);

        watching.set(true);
        watchThread = new Thread(() -> watchLoop(vaultRoot, ownerId), "roots-obsidian-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override
    public synchronized void close() {
        watching.set(false);
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }

    public boolean isWatching() { return watching.get(); }

    // -----------------------------------------------------------------
    //  Internals.
    // -----------------------------------------------------------------

    private void registerAll(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE; // skip .obsidian, .git, etc.
                }
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop(Path vaultRoot, long ownerId) {
        while (watching.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                Path watched = (Path) key.watchable();
                Path child   = watched.resolve((Path) event.context());
                if (!child.toString().toLowerCase().endsWith(".md")) continue;
                if (!Files.isRegularFile(child)) continue;

                // Skip events for files Roots just wrote itself — otherwise
                // a single user edit inside Roots would round-trip back
                // through the FileWatcher and trigger a second DB write.
                String relative = vaultRoot.relativize(child).toString().replace('\\', '/');
                if (vaultWriter != null && vaultWriter.wasRecentlyWritten(relative)) continue;

                try {
                    IdeaNode candidate = parseMarkdownFile(vaultRoot, child, ownerId);
                    ideaDao.upsertByVaultPath(candidate);
                } catch (Exception ignored) { /* swallow per-file errors */ }
            }
            if (!key.reset()) break;
        }
    }

    /**
     * Parse a single markdown file into an in-memory {@link IdeaNode}.
     * The id is left at 0 &mdash; the DAO upsert decides insert vs update.
     */
    IdeaNode parseMarkdownFile(Path vaultRoot, Path file, long ownerId) throws IOException {
        String content   = Files.readString(file);
        String relative  = vaultRoot.relativize(file).toString().replace('\\', '/');
        String fallback  = file.getFileName().toString().replaceFirst("\\.md$", "");

        String name = extractH1(content).orElse(fallback);
        String description = extractFirstParagraph(content);
        int    wordCount   = countWords(content);
        int    backlinks   = countBacklinks(content);
        String tags        = extractTags(content);

        LocalDateTime lastEdited = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(),
                ZoneId.systemDefault());

        return new IdeaNode(name, description, ownerId,
                relative, wordCount, backlinks, lastEdited, tags);
    }

    static java.util.Optional<String> extractH1(String content) {
        Matcher m = HEADING_H1.matcher(content);
        if (m.find()) return java.util.Optional.of(m.group(1).trim());
        return java.util.Optional.empty();
    }

    static String extractFirstParagraph(String content) {
        // Skip front matter and headings, find the first non-empty
        // paragraph, truncate to 160 characters.
        String stripped = content.replaceAll("(?s)^---.*?---\\s*", "");
        for (String line : stripped.split("\\r?\\n\\s*\\r?\\n")) {
            String trimmed = line.replaceAll("^#+\\s+.*", "").trim();
            if (!trimmed.isEmpty()) {
                String oneline = trimmed.replaceAll("\\s+", " ");
                return oneline.length() > 160 ? oneline.substring(0, 157) + "..." : oneline;
            }
        }
        return "";
    }

    static int countWords(String content) {
        String stripped = content
                .replaceAll("(?s)```.*?```", " ")            // code blocks
                .replaceAll("(?s)^---.*?---", " ")            // front matter
                .replaceAll("\\[\\[([^\\]]+)\\]\\]", "$1")    // unwrap backlinks
                .replaceAll("[#*_>`-]", " ");                 // markdown syntax
        String[] tokens = stripped.trim().split("\\s+");
        if (tokens.length == 1 && tokens[0].isEmpty()) return 0;
        return tokens.length;
    }

    static int countBacklinks(String content) {
        Matcher m = BACKLINK.matcher(content);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    static String extractTags(String content) {
        // Pull from YAML front matter first, then hashtag scan.
        List<String> tags = new ArrayList<>();
        Matcher fm = FRONTMATTER_TAGS.matcher(content);
        if (fm.find()) {
            for (String t : fm.group(1).split("[,\\s]+")) {
                String cleaned = t.trim().replaceAll("['\"]", "");
                if (!cleaned.isEmpty()) tags.add(cleaned);
            }
        }
        Matcher h = HASHTAG.matcher(content);
        while (h.find()) {
            String tag = h.group(1);
            if (!tags.contains(tag)) tags.add(tag);
        }
        return String.join(",", tags);
    }

    /** Result record returned by a manual scan. */
    public record ScanResult(int totalFiles, int created, int updated, int failed) {
        @Override public String toString() {
            return "scanned %d files (%d new, %d updated, %d failed)"
                    .formatted(totalFiles, created, updated, failed);
        }
    }
}
