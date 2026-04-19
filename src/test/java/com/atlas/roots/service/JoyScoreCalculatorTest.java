package com.atlas.roots.service;

import com.atlas.roots.model.SubNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Joy Score v2.1 — the multiplicative-penalty formula.
 * Each factor is tested in isolation, then the whole formula is
 * tested for the key behavioural guarantees the class javadoc claims.
 */
class JoyScoreCalculatorTest {

    private static SubNode freshSub(int joyRating, double monthlyCost) {
        SubNode s = new SubNode(
                "Test", "desc", 1L,
                monthlyCost, "INR", SubNode.Cadence.MONTHLY,
                joyRating, LocalDate.now(), null);
        s.setLastTouched(LocalDateTime.now());
        return s;
    }

    // ----- Recency -----

    @Test
    void recencyFactorIsOneForTouchedToday() {
        SubNode s = freshSub(5, 500);
        assertEquals(1.0, JoyScoreCalculator.computeRecency(s), 0.001);
    }

    @Test
    void recencyFactorIsZeroForTouchedOverWindowAgo() {
        SubNode s = freshSub(5, 500);
        s.setLastTouched(LocalDateTime.now().minusDays(91));
        assertEquals(0.0, JoyScoreCalculator.computeRecency(s), 0.001);
    }

    @Test
    void recencyFactorIsHalfAtMidpoint() {
        SubNode s = freshSub(5, 500);
        s.setLastTouched(LocalDateTime.now().minusDays(45));
        assertEquals(0.5, JoyScoreCalculator.computeRecency(s), 0.05);
    }

    // ----- Usage intensity -----

    @Test
    void usageIntensityIsZeroForZeroTouches() {
        assertEquals(0.0, JoyScoreCalculator.computeUsageIntensity(0), 0.001);
    }

    @Test
    void usageIntensityScalesLogarithmically() {
        double one    = JoyScoreCalculator.computeUsageIntensity(1);
        double ten    = JoyScoreCalculator.computeUsageIntensity(10);
        double thirty = JoyScoreCalculator.computeUsageIntensity(30);
        assertTrue(one  > 0.0 && one  < 0.3, "1 touch should be small but non-zero, got " + one);
        assertTrue(ten  > 0.5 && ten  < 0.8, "10 touches should be around 0.65, got " + ten);
        assertTrue(thirty >= 0.99, "30 touches should saturate to ~1.0, got " + thirty);
    }

    // ----- Cost penalty -----

    @Test
    void costPenaltyIsOneForFreeService() {
        assertEquals(1.0, JoyScoreCalculator.computeCostPenalty(0), 0.001);
    }

    @Test
    void costPenaltyIsFloorForExpensiveService() {
        assertEquals(JoyScoreCalculator.COST_PENALTY_FLOOR,
                JoyScoreCalculator.computeCostPenalty(JoyScoreCalculator.COST_CAP_INR), 0.001);
        assertEquals(JoyScoreCalculator.COST_PENALTY_FLOOR,
                JoyScoreCalculator.computeCostPenalty(5000), 0.001);
    }

    @Test
    void costPenaltyInterpolatesLinearlyInBetween() {
        double half = JoyScoreCalculator.computeCostPenalty(JoyScoreCalculator.COST_CAP_INR / 2);
        double expected = 1.0 - ((1.0 - JoyScoreCalculator.COST_PENALTY_FLOOR) * 0.5);
        assertEquals(expected, half, 0.001);
    }

    // ----- Whole-formula guarantees -----

    @Test
    void formulaIsBoundedInZeroToOne() {
        SubNode loved = freshSub(10, 0);
        SubNode hated = freshSub(0, 5000);
        hated.setLastTouched(LocalDateTime.now().minusDays(365));
        double a = JoyScoreCalculator.computeWithBreakdown(loved, 50).finalScore();
        double b = JoyScoreCalculator.computeWithBreakdown(hated,  0).finalScore();
        assertTrue(a <= 1.0 && a >= 0.0, "loved score out of range: " + a);
        assertTrue(b <= 1.0 && b >= 0.0, "hated score out of range: " + b);
        assertTrue(a > b, "loved sub should score higher than hated one");
    }

    @Test
    void abandonedSubCollapsesRegardlessOfRating() {
        SubNode loved_but_abandoned = freshSub(10, 500);
        loved_but_abandoned.setLastTouched(LocalDateTime.now().minusDays(120));
        double score = JoyScoreCalculator.computeWithBreakdown(loved_but_abandoned, 0).finalScore();
        assertTrue(score < 0.25,
                "a 10/10 sub that's been abandoned for 120 days should score below 0.25, got " + score);
    }

    @Test
    void expensiveBadSubScoresLowerThanCheapBadSub() {
        SubNode cheap_bad     = freshSub(3, 200);
        SubNode expensive_bad = freshSub(3, 4000);
        cheap_bad.setLastTouched(LocalDateTime.now().minusDays(60));
        expensive_bad.setLastTouched(LocalDateTime.now().minusDays(60));

        double cheapScore     = JoyScoreCalculator.computeWithBreakdown(cheap_bad, 0).finalScore();
        double expensiveScore = JoyScoreCalculator.computeWithBreakdown(expensive_bad, 0).finalScore();

        assertTrue(expensiveScore < cheapScore,
                "expensive bad sub should score lower than cheap bad one: " +
                "cheap=" + cheapScore + ", expensive=" + expensiveScore);
    }

    @Test
    void activeMidRatedSubBeatsLovedAbandonedSub() {
        SubNode loved_but_unused = freshSub(9, 500);
        loved_but_unused.setLastTouched(LocalDateTime.now().minusDays(80));

        SubNode mid_but_active = freshSub(5, 500);
        mid_but_active.setLastTouched(LocalDateTime.now());

        double unused = JoyScoreCalculator.computeWithBreakdown(loved_but_unused, 0).finalScore();
        double active = JoyScoreCalculator.computeWithBreakdown(mid_but_active, 15).finalScore();

        assertTrue(active > unused,
                "an actively-used mid-rated sub should outscore a loved-but-abandoned one: " +
                "active=" + active + ", unused=" + unused);
    }

    @Test
    void breakdownExposesEveryIntermediateValue() {
        SubNode s = freshSub(8, 500);
        var b = JoyScoreCalculator.computeWithBreakdown(s, 10);
        assertEquals(0.8, b.userRating(),     0.001);
        assertEquals(1.0, b.recencyFactor(),  0.001);
        assertTrue(b.usageIntensity() > 0.5 && b.usageIntensity() < 0.8);
        assertEquals(1.0, b.activity(), 0.001);
        assertTrue(b.core() > 0.8 && b.core() < 1.0);
        assertEquals(1.0, b.engagementPenalty(), 0.001);
        assertTrue(b.costPenalty() > 0.5 && b.costPenalty() < 1.0);
        assertTrue(b.finalScore() > 0.0 && b.finalScore() < 1.0);
    }

    @Test
    void coreWeightsSumToOne() {
        double sum = JoyScoreCalculator.W_CORE_USER_RATING + JoyScoreCalculator.W_CORE_ACTIVITY;
        assertEquals(1.0, sum, 0.001, "core weights must sum to 1.0");
    }
}
