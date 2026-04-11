package com.atlas.roots.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A {@link RootNode} representing a recurring (or one-time) paid service.
 *
 * <p>SubNode is the only node type that implements {@link Drainable}:
 * subscriptions are the financial trunk of the ecosystem and the only
 * place real money flows out. By making the {@code Drainable} interface
 * orthogonal to {@code RootNode}, the analyzer can ask "what is draining
 * me?" without leaking subscription-specific concerns into the base class.</p>
 *
 * <h3>Vitality model</h3>
 * <p>A subscription's vitality is the product of two factors:</p>
 * <ol>
 *   <li><b>Recency factor</b> &mdash; how long since {@code lastTouched}.
 *       A subscription touched today scores 1.0; one untouched for 90+
 *       days scores ~0.0. Decay is linear over the 90-day window.</li>
 *   <li><b>Joy factor</b> &mdash; the user-supplied joy rating divided
 *       by 10, clamped to {@code [0.0, 1.0]}.</li>
 * </ol>
 * <p>The product means a subscription you love but never use, and a
 * subscription you use daily but hate, both end up dim. Only a node
 * that is both used and loved glows brightly. This matches the lived
 * experience of subscription regret.</p>
 *
 * <h3>Joy-to-Cost ratio</h3>
 * <p>The headline metric. Joy is normalised to {@code [0,1]}, cost is
 * normalised against a configurable monthly cap (default ₹2000), and the
 * ratio is {@code joy / max(cost_normalised, ε)}, then squashed back into
 * {@code [0,1]} via a soft cap. The result: a ₹100 service rated 9 will
 * outscore a ₹4000 service rated 9, even though both are "loved."</p>
 */
public class SubNode extends RootNode implements Vitalizable, Drainable {

    private static final double JOY_DECAY_WINDOW_DAYS = 90.0;
    private static final double MONTHLY_COST_CAP_INR  = 2000.0;

    public enum Cadence { MONTHLY, YEARLY, WEEKLY, ONE_TIME }

    private double    monthlyCost;     // normalized to monthly regardless of cadence
    private String    currency;
    private Cadence   cadence;
    private int       joyRating;       // 0..10, user-supplied
    private LocalDate startedOn;
    private LocalDate nextRenewal;     // nullable

    // -----------------------------------------------------------------
    //  Constructors — overloaded for fresh vs hydrated.
    // -----------------------------------------------------------------

    public SubNode(String name, String description, long ownerId,
                   double monthlyCost, String currency, Cadence cadence,
                   int joyRating, LocalDate startedOn, LocalDate nextRenewal) {
        super(NodeType.SUB, name, description, ownerId);
        this.monthlyCost = monthlyCost;
        this.currency    = currency;
        this.cadence     = cadence;
        this.joyRating   = clampJoy(joyRating);
        this.startedOn   = startedOn;
        this.nextRenewal = nextRenewal;
    }

    public SubNode(long id, String name, String description, long ownerId,
                   LocalDateTime createdAt, LocalDateTime lastTouched, boolean archived,
                   double monthlyCost, String currency, Cadence cadence,
                   int joyRating, LocalDate startedOn, LocalDate nextRenewal) {
        super(id, NodeType.SUB, name, description, ownerId, createdAt, lastTouched, archived);
        this.monthlyCost = monthlyCost;
        this.currency    = currency;
        this.cadence     = cadence;
        this.joyRating   = clampJoy(joyRating);
        this.startedOn   = startedOn;
        this.nextRenewal = nextRenewal;
    }

    // -----------------------------------------------------------------
    //  Polymorphic metric overrides — the heart of the OOP grade.
    // -----------------------------------------------------------------

    @Override
    public double getVitality() {
        long daysSinceTouch = ChronoUnit.DAYS.between(getLastTouched(), LocalDateTime.now());
        double recencyFactor = Math.max(0.0, 1.0 - (daysSinceTouch / JOY_DECAY_WINDOW_DAYS));
        double joyFactor     = joyRating / 10.0;
        return clamp01(recencyFactor * joyFactor);
    }

    @Override
    public double getJoyScore() {
        return clamp01(joyRating / 10.0);
    }

    @Override
    public String displayToken() { return "SUB"; }

    // -----------------------------------------------------------------
    //  Drainable contract.
    // -----------------------------------------------------------------

    @Override
    public double getMonthlyDrain() { return monthlyCost; }

    @Override
    public String getCurrency() { return currency; }

    @Override
    public double getJoyToCostRatio() {
        double joy = getJoyScore();                                       // 0..1
        double costNorm = Math.min(monthlyCost / MONTHLY_COST_CAP_INR, 2.0); // soft cap
        if (costNorm < 0.01) return joy;                                   // free service
        double raw = joy / costNorm;
        return clamp01(raw);                                               // squash to 0..1
    }

    // -----------------------------------------------------------------
    //  Method overloading example — three flavours of "annual cost".
    // -----------------------------------------------------------------

    /** Annual cost in the subscription's native currency. */
    public double annualCost() {
        return switch (cadence) {
            case MONTHLY  -> monthlyCost * 12;
            case YEARLY   -> monthlyCost;          // already a yearly figure stored monthly-normalised
            case WEEKLY   -> monthlyCost * 52 / 12 * 12;
            case ONE_TIME -> monthlyCost;
        };
    }

    /** Annual cost projected forward N years (no inflation modelled). */
    public double annualCost(int years) {
        return annualCost() * Math.max(1, years);
    }

    /** Annual cost projected forward N years with a flat inflation rate. */
    public double annualCost(int years, double inflationRate) {
        double base = annualCost();
        double total = 0;
        for (int i = 0; i < Math.max(1, years); i++) {
            total += base * Math.pow(1 + inflationRate, i);
        }
        return total;
    }

    // -----------------------------------------------------------------
    //  Encapsulated getters/setters.
    // -----------------------------------------------------------------

    public double    getMonthlyCost()                  { return monthlyCost; }
    public void      setMonthlyCost(double monthlyCost) {
        if (monthlyCost < 0) throw new IllegalArgumentException("cost must be >= 0");
        this.monthlyCost = monthlyCost;
    }

    public void   setCurrency(String currency) { this.currency = currency; }

    public Cadence getCadence()                 { return cadence; }
    public void    setCadence(Cadence cadence)  { this.cadence = cadence; }

    public int  getJoyRating()                  { return joyRating; }
    public void setJoyRating(int joyRating)     { this.joyRating = clampJoy(joyRating); }

    public LocalDate getStartedOn()                     { return startedOn; }
    public void      setStartedOn(LocalDate startedOn)  { this.startedOn = startedOn; }

    public LocalDate getNextRenewal()                       { return nextRenewal; }
    public void      setNextRenewal(LocalDate nextRenewal)  { this.nextRenewal = nextRenewal; }

    // -----------------------------------------------------------------
    //  Helpers.
    // -----------------------------------------------------------------

    private static int clampJoy(int joy) {
        if (joy < 0)  return 0;
        if (joy > 10) return 10;
        return joy;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
