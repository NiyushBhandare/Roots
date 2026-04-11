package com.atlas.roots.model;

/**
 * Capability marker for any entity that drains a resource from the user —
 * money, attention, or time. Implemented by {@link SubNode} (drains
 * money monthly) and conceptually applicable to any future paid service.
 *
 * <p>Separating drain from cost lets the analyzer ask "what is draining
 * me right now?" across heterogeneous node types in a single pass — the
 * core capability behind the Joy-to-Cost report.</p>
 */
public interface Drainable {

    /**
     * @return the monetary drain per calendar month, in the entity's
     *         own currency, normalized to a monthly cadence regardless
     *         of the underlying billing period.
     */
    double getMonthlyDrain();

    /**
     * @return the ISO currency code (e.g. "INR", "USD") of the drain.
     */
    String getCurrency();

    /**
     * @return a "joy per unit cost" score in {@code [0.0, 1.0]}.
     *         A high value means each rupee/dollar spent buys a lot of
     *         joy. A low value flags the entity as a drain candidate.
     */
    double getJoyToCostRatio();
}
