package com.atlas.roots.service;

import com.atlas.roots.model.RootNode;
import com.atlas.roots.model.Vitalizable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Vitality orchestration across heterogeneous node types.
 *
 * <p>Every method in this class operates on {@code List<? extends RootNode>}
 * &mdash; subscriptions, repos, and ideas all flow through the same code
 * path. The class never asks "what kind of node is this?" because it
 * doesn't need to: each subclass overrides {@link RootNode#getVitality()}
 * with its own algorithm, and the calculator just calls the method.</p>
 *
 * <p>This is the polymorphism payoff. Adding a fourth node type (say,
 * {@code BookmarkNode}) requires zero changes here. The calculator is
 * closed to modification but open to extension &mdash; the
 * Liskov/open-closed principle in 60 lines.</p>
 */
public final class VitalityCalculator {

    /**
     * Sort the given nodes from most-vital to least-vital.
     * Returns a new list; does not mutate the input.
     */
    public List<RootNode> rankByVitality(List<? extends RootNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparingDouble(RootNode::getVitality).reversed())
                .collect(Collectors.toList());
    }

    /**
     * The "thriving" set: nodes scoring above the given threshold.
     * Default threshold of 0.75 matches the THRIVING band on
     * {@link Vitalizable#getVitalityBand()}.
     */
    public List<RootNode> thriving(List<? extends RootNode> nodes) {
        return thriving(nodes, 0.75);
    }

    /** Method overloading: same name, different default threshold. */
    public List<RootNode> thriving(List<? extends RootNode> nodes, double threshold) {
        return nodes.stream()
                .filter(n -> n.getVitality() >= threshold)
                .collect(Collectors.toList());
    }

    /** The "dormant" set: nodes scoring below the given threshold. */
    public List<RootNode> dormant(List<? extends RootNode> nodes) {
        return dormant(nodes, 0.25);
    }

    public List<RootNode> dormant(List<? extends RootNode> nodes, double threshold) {
        return nodes.stream()
                .filter(n -> n.getVitality() < threshold)
                .collect(Collectors.toList());
    }

    /**
     * Average vitality across the input. Useful as a single
     * "ecosystem health" gauge for the dashboard header.
     */
    public double averageVitality(List<? extends RootNode> nodes) {
        if (nodes.isEmpty()) return 0.0;
        return nodes.stream()
                .mapToDouble(RootNode::getVitality)
                .average()
                .orElse(0.0);
    }

    /**
     * Group nodes by their vitality band. The dashboard renders one
     * column per band, so this is the data the layout consumes directly.
     */
    public Map<String, List<RootNode>> groupByBand(List<? extends RootNode> nodes) {
        return nodes.stream()
                .collect(Collectors.groupingBy(n ->
                        // Each subclass implements Vitalizable, so this cast is safe
                        // for every node type that ever reaches this method.
                        ((Vitalizable) n).getVitalityBand()));
    }
}
