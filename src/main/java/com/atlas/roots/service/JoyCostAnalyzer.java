package com.atlas.roots.service;

import com.atlas.roots.model.Drainable;
import com.atlas.roots.model.RootNode;
import com.atlas.roots.model.SubNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The financial brain.
 *
 * <p>Operates exclusively on the {@link Drainable} interface, not on
 * any concrete subclass. This is intentional: today only {@link SubNode}
 * implements {@code Drainable}, but the analyzer is built so any future
 * drain (recurring donations, paid API tiers, etc.) joins the system
 * without modification here.</p>
 *
 * <p>This is the "interface vs abstract class" lesson made concrete:
 * abstract classes share state, interfaces share capabilities. Drain
 * is a capability, so it lives in an interface, so the analyzer is
 * free of any single hierarchy.</p>
 */
public final class JoyCostAnalyzer {

    /**
     * Total monthly burn across every drainable input, in the input's
     * native currencies. Mixed currencies are summed naively in v1 &mdash;
     * a future version will normalise via FX rates.
     */
    public double totalMonthlyBurn(List<? extends Drainable> drains) {
        return drains.stream().mapToDouble(Drainable::getMonthlyDrain).sum();
    }

    /** Per-currency burn breakdown. Useful when the user has subs in INR + USD. */
    public Map<String, Double> burnByCurrency(List<? extends Drainable> drains) {
        return drains.stream().collect(Collectors.groupingBy(
                Drainable::getCurrency,
                Collectors.summingDouble(Drainable::getMonthlyDrain)));
    }

    /**
     * The worst offenders &mdash; lowest joy-to-cost ratios first.
     * These are the drains that should probably be cancelled.
     */
    public List<Drainable> worstOffenders(List<? extends Drainable> drains, int limit) {
        return drains.stream()
                .sorted(Comparator.comparingDouble(Drainable::getJoyToCostRatio))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * The best value &mdash; highest joy-to-cost ratios first.
     * These are the drains worth defending in any belt-tightening.
     */
    public List<Drainable> bestValue(List<? extends Drainable> drains, int limit) {
        return drains.stream()
                .sorted(Comparator.comparingDouble(Drainable::getJoyToCostRatio).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * The single ecosystem-level joy-to-cost score: a weighted average
     * where each drain's joy ratio is weighted by its monthly cost.
     * Means a single expensive bad drain hurts the score more than a
     * cheap one, which is what we want.
     */
    public double weightedJoyScore(List<? extends Drainable> drains) {
        double totalCost = totalMonthlyBurn(drains);
        if (totalCost <= 0) return 0.0;
        double weightedSum = drains.stream()
                .mapToDouble(d -> d.getJoyToCostRatio() * d.getMonthlyDrain())
                .sum();
        return weightedSum / totalCost;
    }

    /**
     * Filter a generic node list down to just the drainable ones,
     * then run the burn calculation. Convenience method for the
     * dashboard, which deals in {@code RootNode} not {@code Drainable}.
     */
    public double totalMonthlyBurnFromNodes(List<? extends RootNode> nodes) {
        List<Drainable> drains = nodes.stream()
                .filter(n -> n instanceof Drainable)
                .map(n -> (Drainable) n)
                .collect(Collectors.toList());
        return totalMonthlyBurn(drains);
    }
}
