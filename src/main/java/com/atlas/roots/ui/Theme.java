package com.atlas.roots.ui;

import javafx.scene.paint.Color;

/**
 * The Roots design language, expressed as Java constants.
 *
 * <p>Every colour, font, and spacing value the UI uses lives here.
 * Views reference {@code Theme.SOIL} instead of hard-coding
 * {@code #0A0B0D}, so a future redesign touches one file.</p>
 *
 * <p>The aesthetic is "Swiss Bioluminescence" &mdash; precise
 * grid-based International Typographic Style merged with bio-UI
 * elements. Black-grounded, hairline-bordered, with per-type
 * accent colours that glow rather than shout.</p>
 */
public final class Theme {

    private Theme() {}

    // -----------------------------------------------------------------
    //  Surfaces — the soil and the air above it.
    // -----------------------------------------------------------------
    public static final String SOIL_HEX        = "#0A0B0D";   // near-black, faint green undertone
    public static final String SOIL_RAISED_HEX = "#13151A";   // panels lifted above the soil
    public static final String LOAM_HEX        = "#1A1C20";   // hairline borders, dividers
    public static final String STONE_HEX       = "#2A2D33";   // disabled, secondary

    public static final Color  SOIL        = Color.web(SOIL_HEX);
    public static final Color  SOIL_RAISED = Color.web(SOIL_RAISED_HEX);
    public static final Color  LOAM        = Color.web(LOAM_HEX);
    public static final Color  STONE       = Color.web(STONE_HEX);

    // -----------------------------------------------------------------
    //  Foreground.
    // -----------------------------------------------------------------
    public static final String BONE_HEX  = "#E8E6E1";  // primary text
    public static final String ASH_HEX   = "#9A9994";  // secondary text
    public static final String DUST_HEX  = "#5E5C58";  // tertiary

    public static final Color  BONE = Color.web(BONE_HEX);
    public static final Color  ASH  = Color.web(ASH_HEX);
    public static final Color  DUST = Color.web(DUST_HEX);

    // -----------------------------------------------------------------
    //  Bioluminescent accents — one per node type.
    // -----------------------------------------------------------------
    public static final String MINT_HEX  = "#7FFFB0";  // SUB high joy
    public static final String CORAL_HEX = "#FF6B5B";  // SUB drain warning
    public static final String CYAN_HEX  = "#9FE8FF";  // REPO active
    public static final String AMBER_HEX = "#FFD66B";  // IDEA fresh
    public static final String LILAC_HEX = "#C9A8FF";  // accent / heatmap edges

    public static final Color  MINT  = Color.web(MINT_HEX);
    public static final Color  CORAL = Color.web(CORAL_HEX);
    public static final Color  CYAN  = Color.web(CYAN_HEX);
    public static final Color  AMBER = Color.web(AMBER_HEX);
    public static final Color  LILAC = Color.web(LILAC_HEX);

    // -----------------------------------------------------------------
    //  Typography.
    //  We deliberately use locally-installed system fallbacks so the
    //  app starts without a webfont download. JetBrains Mono and
    //  Instrument Serif if available, otherwise Menlo and Georgia.
    // -----------------------------------------------------------------
    public static final String FONT_MONO    = "JetBrains Mono, Menlo, Consolas, monospace";
    public static final String FONT_SERIF   = "Instrument Serif, Georgia, serif";
    public static final String FONT_DISPLAY = FONT_SERIF;

    // -----------------------------------------------------------------
    //  Sizing.
    // -----------------------------------------------------------------
    public static final int    GRID_UNIT    = 8;       // base spacing unit
    public static final int    HAIRLINE     = 1;       // border weight
    public static final double WINDOW_W     = 1280;
    public static final double WINDOW_H     = 800;

    /** Lookup the accent colour for a node type token. */
    public static Color accentFor(String token) {
        return switch (token) {
            case "SUB"  -> MINT;
            case "REPO" -> CYAN;
            case "IDEA" -> AMBER;
            default     -> BONE;
        };
    }

    public static String accentHexFor(String token) {
        return switch (token) {
            case "SUB"  -> MINT_HEX;
            case "REPO" -> CYAN_HEX;
            case "IDEA" -> AMBER_HEX;
            default     -> BONE_HEX;
        };
    }
}
