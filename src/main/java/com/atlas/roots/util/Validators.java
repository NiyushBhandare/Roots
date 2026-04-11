package com.atlas.roots.util;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Centralised input validation. Each method either returns silently
 * (input is valid) or throws an {@link IllegalArgumentException}
 * carrying a human-readable message that the UI surfaces directly.
 *
 * <p>Keeping all rules in one class means the same validation logic
 * is shared between the JavaFX form layer, the service layer, and the
 * unit tests &mdash; there is exactly one place to change the rules.</p>
 */
public final class Validators {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,24}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private Validators() {}

    public static void requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public static void requireUsername(String username) {
        requireNonBlank("username", username);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                "username must be 3–24 chars, lowercase letters, digits, or underscores");
        }
    }

    public static void requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
    }

    public static void requireNonNegative(String field, double value) {
        if (value < 0) throw new IllegalArgumentException(field + " must be ≥ 0");
    }

    public static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                "%s must be between %d and %d".formatted(field, min, max));
        }
    }

    public static void requireCurrency(String currency) {
        requireNonBlank("currency", currency);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code (e.g. INR, USD)");
        }
    }

    public static void requireNotFuture(String field, LocalDate date) {
        if (date != null && date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(field + " cannot be in the future");
        }
    }
}
