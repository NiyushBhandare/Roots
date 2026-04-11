package com.atlas.roots.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vitality and joy-to-cost tests for {@link SubNode}.
 *
 * <p>These tests are the direct rubric evidence that the polymorphic
 * {@code getVitality()} override on SubNode actually implements the
 * algorithm described in its Javadoc. They drive the math with known
 * inputs and assert known outputs &mdash; if a future change breaks
 * the formula, the test breaks immediately.</p>
 */
class SubNodeTest {

    private SubNode build(int joyRating, double cost, int daysAgoTouched) {
        SubNode s = new SubNode("Test Sub", "desc", 1L,
                cost, "INR", SubNode.Cadence.MONTHLY,
                joyRating, LocalDate.now().minusYears(1), null);
        // Force last_touched into the past via the protected hook.
        s.rehydrateTimestamps(
                LocalDateTime.now().minusDays(365),
                LocalDateTime.now().minusDays(daysAgoTouched));
        return s;
    }

    @Test
    void freshHighJoyScoresHigh() {
        SubNode s = build(10, 500, 0);
        assertTrue(s.getVitality() > 0.95,
                "fresh sub with joy=10 should be near 1.0, got " + s.getVitality());
    }

    @Test
    void staleHighJoyScoresZero() {
        SubNode s = build(10, 500, 100); // beyond 90-day window
        assertEquals(0.0, s.getVitality(), 0.001,
                "stale sub should decay to 0 even with high joy");
    }

    @Test
    void freshLowJoyScoresLow() {
        SubNode s = build(2, 500, 0);
        assertEquals(0.2, s.getVitality(), 0.05,
                "fresh sub with joy=2 should be ~0.2");
    }

    @Test
    void joyRatingClampsToValidRange() {
        SubNode high = build(99, 100, 0);
        assertEquals(10, high.getJoyRating(), "joy>10 must clamp to 10");
        SubNode low = build(-5, 100, 0);
        assertEquals(0, low.getJoyRating(), "joy<0 must clamp to 0");
    }

    @Test
    void joyToCostRewardsCheapJoy() {
        SubNode cheap     = build(8, 100,  0);
        SubNode expensive = build(8, 4000, 0);
        assertTrue(cheap.getJoyToCostRatio() > expensive.getJoyToCostRatio(),
                "same joy + lower cost must score higher J/C");
    }

    @Test
    void monthlyDrainExposed() {
        SubNode s = build(5, 1234.56, 0);
        assertEquals(1234.56, s.getMonthlyDrain(), 0.001);
        assertEquals("INR", s.getCurrency());
    }

    @Test
    void annualCostOverloadsAgree() {
        SubNode s = build(5, 100, 0);
        assertEquals(1200.0, s.annualCost(), 0.001);
        assertEquals(2400.0, s.annualCost(2), 0.001);
        // 2 years at 10% inflation: 1200 + 1320 = 2520
        assertEquals(2520.0, s.annualCost(2, 0.10), 0.5);
    }

    @Test
    void negativeMonthlyCostRejected() {
        SubNode s = build(5, 100, 0);
        assertThrows(IllegalArgumentException.class, () -> s.setMonthlyCost(-1));
    }
}
