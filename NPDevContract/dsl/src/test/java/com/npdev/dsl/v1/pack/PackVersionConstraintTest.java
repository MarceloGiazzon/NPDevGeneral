package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackVersionConstraintTest {

    @Test
    void caretParsesAndSatisfiesSameMajorAtOrAboveTheMinimum() {
        PackVersionConstraint constraint = PackVersionConstraint.parse("^2.3");
        assertTrue(constraint instanceof PackVersionConstraint.Caret);
        assertEquals(2, constraint.requiredMajor());
        assertTrue(constraint.satisfies(new PackVersion(2, 3, 0)));
        assertTrue(constraint.satisfies(new PackVersion(2, 5, 0)));
        assertTrue(constraint.satisfies(new PackVersion(2, 3, 7)));
        assertFalse(constraint.satisfies(new PackVersion(2, 2, 9)));
        assertFalse(constraint.satisfies(new PackVersion(3, 0, 0)));
        assertFalse(constraint.satisfies(new PackVersion(1, 9, 9)));
    }

    @Test
    void exactParsesAndSatisfiesOnlyThatVersion() {
        PackVersionConstraint constraint = PackVersionConstraint.parse("2.3.1");
        assertTrue(constraint instanceof PackVersionConstraint.Exact);
        assertEquals(2, constraint.requiredMajor());
        assertTrue(constraint.satisfies(new PackVersion(2, 3, 1)));
        assertFalse(constraint.satisfies(new PackVersion(2, 3, 2)));
        assertFalse(constraint.satisfies(new PackVersion(2, 4, 1)));
    }

    @Test
    void exactWithTwoSegmentsDefaultsPatchToZero() {
        PackVersionConstraint constraint = PackVersionConstraint.parse("2.3");
        assertEquals(new PackVersion(2, 3, 0), constraint.minimum());
        assertTrue(constraint.satisfies(new PackVersion(2, 3, 0)));
        assertFalse(constraint.satisfies(new PackVersion(2, 3, 1)));
    }

    @Test
    void rejectsTilde() {
        assertThrows(IllegalArgumentException.class, () -> PackVersionConstraint.parse("~2.3"));
    }

    @Test
    void rejectsRange() {
        assertThrows(IllegalArgumentException.class, () -> PackVersionConstraint.parse(">=2.0"));
    }

    @Test
    void rejectsOr() {
        assertThrows(IllegalArgumentException.class, () -> PackVersionConstraint.parse("^2.0 || ^3.0"));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> PackVersionConstraint.parse(""));
    }
}
