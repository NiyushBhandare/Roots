package com.atlas.roots.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal {@code .env} file loader.
 *
 * <h2>Why hand-rolled</h2>
 * <p>The dotenv-java library has known bugs with Java 17+ and brings
 * in ~3 dependencies. This 40-line loader handles the format we actually
 * need ({@code KEY=value}, {@code # comments}, blank lines) and nothing
 * else. Zero dependencies added to the Maven tree.</p>
 *
 * <h2>Precedence</h2>
 * <ol>
 *   <li>Actual environment variables set by the shell</li>
 *   <li>Values in {@code .env} if the env var is not already set</li>
 * </ol>
 *
 * <p>So CI/production pipelines can still override via {@code export
 * GOOGLE_CLIENT_ID=...} without touching the file, while local devs
 * get a zero-setup experience by just having a {@code .env} in the
 * project root.</p>
 *
 * <h2>What this doesn't do</h2>
 * <ul>
 *   <li>No variable interpolation ({@code $VAR} is literal)</li>
 *   <li>No quote handling — values are read as-is</li>
 *   <li>No multi-line values</li>
 *   <li>No modification of the actual process environment (use
 *       {@link #get(String, String)} instead of {@link System#getenv})</li>
 * </ul>
 */
public final class DotenvLoader {

    private static final Map<String, String> VALUES = new HashMap<>();
    private static boolean loaded = false;

    private DotenvLoader() {}

    /**
     * Load values from a {@code .env} file at the project root.
     * Safe to call multiple times; subsequent calls are no-ops.
     * Silent on missing file — callers just fall through to actual
     * env vars or their own defaults.
     */
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) return;
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 1) continue;
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                // Strip surrounding double or single quotes if present
                if (val.length() >= 2 &&
                        ((val.startsWith("\"") && val.endsWith("\""))
                         || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                VALUES.put(key, val);
            }
        } catch (IOException silent) {
            // Deliberate: a malformed .env shouldn't crash the app.
            // Fall through — the caller's defaults will apply.
        }
    }

    /**
     * Return the value of {@code key}, checking real env vars first,
     * then the loaded {@code .env}, falling back to {@code defaultValue}.
     */
    public static String get(String key, String defaultValue) {
        load();
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;
        String fromFile = VALUES.get(key);
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        return defaultValue;
    }
}
