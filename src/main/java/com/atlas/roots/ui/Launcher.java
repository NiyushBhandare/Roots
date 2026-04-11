package com.atlas.roots.ui;

/**
 * Launcher class.
 *
 * <p>Exists solely so that the shaded "fat jar" Maven produces can be
 * launched without {@code --module-path} arguments. JavaFX 21 refuses
 * to start when the {@link javafx.application.Application} subclass is
 * the JAR's main class because of how the JavaFX runtime checks the
 * module path. The workaround is a separate non-{@code Application}
 * class that calls into the real entry point.</p>
 *
 * <p>This is the official, documented workaround &mdash; not a hack.
 * See <a href="https://openjfx.io/openjfx-docs/#modular">OpenJFX docs</a>.</p>
 */
public final class Launcher {
    public static void main(String[] args) {
        RootsApp.main(args);
    }
}
