package com.atlas.roots.db;

import com.atlas.roots.util.BCryptHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Singleton facade over the SQLite connection.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Resolve and create the database file under {@code ~/.roots/}.</li>
 *   <li>Open JDBC connections with foreign keys enabled.</li>
 *   <li>Bootstrap the schema and seed data on first boot.</li>
 *   <li>Substitute BCrypt password placeholders in the seed file with
 *       freshly generated hashes so the committed seed remains
 *       deterministic without committing real credentials.</li>
 * </ul>
 *
 * <p>Singleton was chosen over dependency injection here because the
 * project explicitly mandates the layered architecture without an IoC
 * container, and a single shared file-backed database has no legitimate
 * reason to exist twice in the same JVM. The instance is held in a
 * {@code volatile} field with double-checked locking for thread safety
 * &mdash; the JavaFX UI thread and the Obsidian watcher thread both
 * call into this class.</p>
 */
public final class DatabaseManager {

    private static final String DB_DIR_NAME  = ".roots";
    private static final String DB_FILE_NAME = "roots.db";

    private static volatile DatabaseManager INSTANCE;

    private final Path    dbPath;
    private final String  jdbcUrl;
    private volatile boolean bootstrapped = false;

    private DatabaseManager(Path dbPath) {
        this.dbPath  = Objects.requireNonNull(dbPath);
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    /** Acquire the global instance, creating it on first call. */
    public static DatabaseManager getInstance() {
        DatabaseManager local = INSTANCE;
        if (local == null) {
            synchronized (DatabaseManager.class) {
                local = INSTANCE;
                if (local == null) {
                    Path dir = Path.of(System.getProperty("user.home"), DB_DIR_NAME);
                    try {
                        Files.createDirectories(dir);
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot create roots directory: " + dir, e);
                    }
                    INSTANCE = local = new DatabaseManager(dir.resolve(DB_FILE_NAME));
                }
            }
        }
        return local;
    }

    /**
     * Test-only entry point: lets a unit test point at an in-memory or
     * temp-file database without polluting the user's real ~/.roots dir.
     */
    public static synchronized DatabaseManager forTesting(Path tempDb) {
        INSTANCE = new DatabaseManager(tempDb);
        return INSTANCE;
    }

    /**
     * Open a fresh connection. Foreign keys are enabled per-connection
     * because SQLite ships with FK enforcement off.
     */
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
        }
        return conn;
    }

    public Path getDbPath() { return dbPath; }

    /**
     * Idempotent bootstrap: applies the schema if the {@code users}
     * table is missing, then loads the seed if it is empty. Safe to
     * call on every application start.
     */
    public void bootstrapIfNeeded() throws SQLException, IOException {
        if (bootstrapped) return;
        synchronized (this) {
            if (bootstrapped) return;
            try (Connection conn = getConnection()) {
                if (!tableExists(conn, "users")) {
                    applyScript(conn, loadResource("/sql/schema.sql"));
                }
                if (countRows(conn, "users") == 0) {
                    String seed = loadResource("/sql/seed.sql");
                    seed = substitutePasswordPlaceholders(seed);
                    applyScript(conn, seed);
                }
            }
            bootstrapped = true;
        }
    }

    // -----------------------------------------------------------------
    //  Internals.
    // -----------------------------------------------------------------

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name = ?";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void applyScript(Connection conn, String sql) throws SQLException {
        // Split on semicolon-newline to keep INSERT statements atomic
        // while respecting multi-line CREATE TABLE blocks.
        String[] stmts = sql.split(";\\s*\\r?\\n");
        try (Statement st = conn.createStatement()) {
            for (String raw : stmts) {
                String s = raw.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                st.execute(s);
            }
        }
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = DatabaseManager.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The committed seed file contains placeholder hashes that are
     * substituted with real BCrypt outputs at first boot. This keeps
     * the seed greppable and the credentials reproducible without
     * leaving real password hashes in the repo.
     */
    private static String substitutePasswordPlaceholders(String seed) {
        String dracoHash  = BCryptHelper.hash("roots2026");
        String viewerHash = BCryptHelper.hash("viewer2026");
        return seed
                .replace("$2a$12$PLACEHOLDER_REPLACED_AT_BOOTSTRAP_DRACO", dracoHash)
                .replace("$2a$12$PLACEHOLDER_REPLACED_AT_BOOTSTRAP_VIEW",  viewerHash);
    }
}
