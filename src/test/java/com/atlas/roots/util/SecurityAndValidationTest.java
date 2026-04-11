package com.atlas.roots.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAndValidationTest {

    // -----------------------------------------------------------------
    //  BCryptHelper
    // -----------------------------------------------------------------

    @Test
    void bcryptRoundTrips() {
        String hash = BCryptHelper.hash("correct horse battery staple");
        assertTrue(BCryptHelper.verify("correct horse battery staple", hash));
        assertFalse(BCryptHelper.verify("wrong password", hash));
    }

    @Test
    void bcryptHashesAreUniquePerCall() {
        String h1 = BCryptHelper.hash("samepw");
        String h2 = BCryptHelper.hash("samepw");
        assertNotEquals(h1, h2, "BCrypt must use a fresh salt every time");
        assertTrue(BCryptHelper.verify("samepw", h1));
        assertTrue(BCryptHelper.verify("samepw", h2));
    }

    @Test
    void bcryptHandlesGarbageGracefully() {
        assertFalse(BCryptHelper.verify("anything", null));
        assertFalse(BCryptHelper.verify("anything", ""));
        assertFalse(BCryptHelper.verify("anything", "not-a-bcrypt-hash"));
        assertFalse(BCryptHelper.verify(null, "anything"));
    }

    // -----------------------------------------------------------------
    //  Validators
    // -----------------------------------------------------------------

    @Test
    void usernameValidationEnforcesPattern() {
        assertDoesNotThrow(() -> Validators.requireUsername("draco"));
        assertDoesNotThrow(() -> Validators.requireUsername("draco_2026"));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireUsername(""));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireUsername("ab"));   // too short
        assertThrows(IllegalArgumentException.class, () -> Validators.requireUsername("ABC"));  // uppercase
        assertThrows(IllegalArgumentException.class, () -> Validators.requireUsername("has space"));
    }

    @Test
    void passwordValidationRequiresMinimumLength() {
        assertDoesNotThrow(() -> Validators.requirePassword("12345678"));
        assertThrows(IllegalArgumentException.class, () -> Validators.requirePassword("short"));
        assertThrows(IllegalArgumentException.class, () -> Validators.requirePassword(null));
    }

    @Test
    void currencyValidationRequiresThreeLetterCode() {
        assertDoesNotThrow(() -> Validators.requireCurrency("INR"));
        assertDoesNotThrow(() -> Validators.requireCurrency("USD"));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireCurrency("inr"));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireCurrency("RUPEE"));
    }

    @Test
    void rangeValidationCatchesOutOfBounds() {
        assertDoesNotThrow(() -> Validators.requireRange("joy", 5, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireRange("joy", 11, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Validators.requireRange("joy", -1, 0, 10));
    }

    @Test
    void futureDateRejected() {
        assertDoesNotThrow(() -> Validators.requireNotFuture("started", LocalDate.now()));
        assertThrows(IllegalArgumentException.class,
                () -> Validators.requireNotFuture("started", LocalDate.now().plusDays(1)));
    }
}
