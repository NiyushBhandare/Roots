package com.atlas.roots.service;

import com.atlas.roots.model.SubNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Computes the Joy Score for a subscription using the documented v2.1 formula.
 *
 * <h2>The v1 problem</h2>
 * <p>The original Joy Score was simply the user-supplied joy rating divided
 * by 10: {@code joy = joyRating / 10.0}. This was vibes-based — it had no
 * opinion about usage, recency, or cost pressure. A subscription the user
 * rated 9/10 but hadn't opened in 6 months still scored 0.9, which is
 * obviously wrong.</p>
 *
 * <h2>The v2 problem (superseded)</h2>
 * <p>v2 was a simple weighted sum of four factors. It improved on v1 but
 * was too soft on bad subscriptions: a sub rated 3/10 that you never touch
 * still scored ~0.12 from user rating alone, plus whatever the other
 * factors contributed. Worse, cost pressure being additive meant a cheap
 * bad sub could score <i>higher</i> than an expensive bad sub — backwards.</p>
 *
 * <h2>The v2.1 formula</h2>
 * <p>v2.1 uses <b>multiplicative penalties</b> so abandoned and expensive
 * bad subscriptions actually collapse. The headline score is:</p>
 *
 * <pre>
 *   activity = max(recencyFactor, usageIntensity)
 *   core     = 0.5 × userRating + 0.5 × activity
 *   joy      = core × engagementPenalty × costPenalty
 *
 *   engagementPenalty = 0.3 + 0.7 × activity      // [0.3, 1.0]
 *   costPenalty       = 1.0 for free services,
 *                       decays linearly to 0.5 at COST_CAP_INR and below
 * </pre>
 *
 * <h3>Why multiplicative</h3>
 * <p>If a subscription has zero activity (never touched, no usage), the
 * {@code engagementPenalty} drops to 0.3, pulling the final score down
 * <i>regardless</i> of how high the user rated it. A loved-but-abandoned
 * sub can't score above ~0.3. Meanwhile an expensive sub gets an
 * additional cost penalty, so a ₹4000 dormant sub scores around 0.1 — which
 * is where a "cancel this now" recommendation lives on the colour scale.</p>
 *
 * <h3>Why {@code max(recency, usage)} for activity</h3>
 * <p>The two factors measure related but distinct things: recency is
 * "when was the last time?" and usage intensity is "how often lately?".
 * A weekly user with one recent touch should not be penalised for not
 * having daily usage. Taking the max means either signal of activity is
 * enough to count the sub as alive.</p>
 *
 * <h3>Cost penalty floor at 0.5</h3>
 * <p>The cost penalty never goes below 0.5 — even a ₹5000 sub still gets
 * half-weight. This is because a user who rates a ₹5000 sub 10/10 and
 * uses it daily (Claude Pro, Adobe for a working designer) <i>should</i>
 * still score highly. The cost factor nudges, it doesn't veto.</p>
 *
 * <h2>Breakdown for tooltip display</h2>
 * <p>The {@link Breakdown} record returned by {@link #computeWithBreakdown}
 * exposes every intermediate value so the UI can show the user exactly
 * how the final score was assembled.</p>
 */
public final class JoyScoreCalculator {

    // v2.1 formula tuning — documented in class javadoc above.
    // Changing these is a deliberate product decision; don't tweak casually.

    /** Weight on self-rating inside the core term. Self-rating and activity each carry half. */
    public static final double W_CORE_USER_RATING = 0.5;
    /** Weight on activity inside the core term. Activity = max(recency, usage). */
    public static final double W_CORE_ACTIVITY    = 0.5;

    /** Minimum engagement penalty — even a totally-abandoned sub keeps 30% of its score. */
    public static final double ENGAGEMENT_FLOOR = 0.3;

    /** Minimum cost penalty — even the most expensive sub keeps 50% of its score. */
    public static final double COST_PENALTY_FLOOR = 0.5;

    // Recency window: a subscription untouched for this many days scores
    // zero on the recency axis. 90 days is roughly a full quarter —
    // anything older is functionally abandoned.
    public static final double RECENCY_WINDOW_DAYS = 90.0;

    // Usage intensity normalisation: 1 touch in 30 days gives ~0.25,
    // 10 touches gives ~0.75, 30+ touches gives ~1.0. The constant below
    // controls where "daily use" lands on the curve.
    public static final double USAGE_LOG_BASE = 30.0;

    // Cost pressure: a subscription costing this much or more scores at
    // the COST_PENALTY_FLOOR. Below it, the penalty interpolates linearly
    // from 1.0 (free) down to the floor.
    public static final double COST_CAP_INR = 2000.0;

    // Kept for backward compatibility with any external callers that
    // imported the v2 weight names. Unused by the v2.1 formula itself.
    public static final double W_USER_RATING     = 0.40;
    public static final double W_RECENCY         = 0.30;
    public static final double W_USAGE_INTENSITY = 0.20;
    public static final double W_COST_PRESSURE   = 0.10;

    private JoyScoreCalculator() {}

    /**
     * The breakdown of a Joy Score v2.1 computation. Exposes every
     * intermediate value so the UI can explain the final number to
     * the user in full.
     */
    public record Breakdown(
            double userRating,
            double recencyFactor,
            double usageIntensity,
            double activity,
            double core,
            double engagementPenalty,
            double costPenalty,
            double finalScore
    ) {}

    /**
     * Compute the Joy Score without touching usage data. Used when the
     * caller doesn't have audit log access.
     */
    public static double compute(SubNode sub) {
        return computeWithBreakdown(sub, 0).finalScore();
    }

    /**
     * Compute the Joy Score v2.1 with the full breakdown. Callers with
     * access to the audit log should pass {@code touchCountLast30Days}
     * to get an accurate activity reading.
     *
     * @param sub the subscription to score
     * @param touchCountLast30Days how many times the node was touched
     *        (referenced in any audit log action) in the last 30 days
     * @return the breakdown, including every intermediate value
     */
    public static Breakdown computeWithBreakdown(SubNode sub, int touchCountLast30Days) {
        double userRating     = clamp01(sub.getJoyRating() / 10.0);
        double recencyFactor  = computeRecency(sub);
        double usageIntensity = computeUsageIntensity(touchCountLast30Days);

        // Activity is the louder of the two signals — a weekly user with
        // recent touches shouldn't be punished for not being a daily user.
        double activity = Math.max(recencyFactor, usageIntensity);

        // Core score: self-rating and activity each carry half.
        double core = W_CORE_USER_RATING * userRating + W_CORE_ACTIVITY * activity;

        // Multiplicative penalties — this is what makes v2.1 different from v2.
        // An abandoned sub (activity=0) gets engagementPenalty=ENGAGEMENT_FLOOR=0.3,
        // capping its final score at 30% of core regardless of self-rating.
        double engagementPenalty = ENGAGEMENT_FLOOR + (1.0 - ENGAGEMENT_FLOOR) * activity;
        double costPenalty       = computeCostPenalty(sub.getMonthlyCost());

        double finalScore = clamp01(core * engagementPenalty * costPenalty);

        return new Breakdown(
                userRating, recencyFactor, usageIntensity,
                activity, core, engagementPenalty, costPenalty,
                finalScore);
    }

    // -----------------------------------------------------------------
    //  Factor computations — each pure, each testable in isolation
    // -----------------------------------------------------------------

    static double computeRecency(SubNode sub) {
        LocalDateTime touched = sub.getLastTouched();
        if (touched == null) return 0.0;
        long days = ChronoUnit.DAYS.between(touched, LocalDateTime.now());
        if (days <= 0) return 1.0;
        if (days >= RECENCY_WINDOW_DAYS) return 0.0;
        return 1.0 - (days / RECENCY_WINDOW_DAYS);
    }

    static double computeUsageIntensity(int touchCountLast30Days) {
        if (touchCountLast30Days <= 0) return 0.0;
        // Log-normalised: touches=1 → ~0.20, touches=10 → ~0.66, touches=30 → 1.0.
        // Using log1p so touches=0 doesn't blow up.
        double normalised = Math.log1p(touchCountLast30Days) / Math.log1p(USAGE_LOG_BASE);
        return clamp01(normalised);
    }

    /**
     * Cost penalty: multiplicative, interpolates from 1.0 (free) down to
     * COST_PENALTY_FLOOR at {@link #COST_CAP_INR}. A ₹1000 sub gets a
     * penalty of 0.75 (halfway between 1.0 and 0.5), meaning a moderately
     * expensive sub pays a moderate penalty. Free services get no penalty.
     */
    static double computeCostPenalty(double monthlyCostInr) {
        if (monthlyCostInr <= 0) return 1.0;
        double range = 1.0 - COST_PENALTY_FLOOR;
        if (monthlyCostInr >= COST_CAP_INR) return COST_PENALTY_FLOOR;
        return 1.0 - range * (monthlyCostInr / COST_CAP_INR);
    }

    /** Kept for backward compat — same as {@link #computeCostPenalty} for external callers. */
    static double computeCostPressure(double monthlyCostInr) {
        if (monthlyCostInr <= 0) return 1.0;
        if (monthlyCostInr >= COST_CAP_INR) return 0.0;
        return 1.0 - (monthlyCostInr / COST_CAP_INR);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
