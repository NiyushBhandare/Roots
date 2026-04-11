package com.atlas.roots.model;

/**
 * Capability marker for any entity that exposes a "vitality" reading —
 * a normalized {@code [0.0, 1.0]} health score derived from how recently
 * and how meaningfully the entity has been touched.
 *
 * <p>Vitality is intentionally separated from {@link Drainable} (which
 * concerns cost) and from {@link Auditable} (which concerns history).
 * A node can be drainable without being vitalizable (e.g. a fixed bill)
 * and vitalizable without being drainable (e.g. a free repo).</p>
 *
 * <p>This is the interface that powers the bioluminescent dashboard:
 * the brighter a node glows, the higher its vitality.</p>
 */
public interface Vitalizable {

    /**
     * @return a vitality score in {@code [0.0, 1.0]}, where 1.0 means
     *         "thriving — touched recently and meaningfully" and 0.0
     *         means "dormant — has not been engaged with in a long time."
     */
    double getVitality();

    /**
     * @return a human-readable label corresponding to the vitality band.
     *         Default implementation provides a four-tier ladder used by
     *         both the dashboard chips and the report generator.
     */
    default String getVitalityBand() {
        double v = getVitality();
        if (v >= 0.75) return "THRIVING";
        if (v >= 0.50) return "STEADY";
        if (v >= 0.25) return "FADING";
        return "DORMANT";
    }
}
