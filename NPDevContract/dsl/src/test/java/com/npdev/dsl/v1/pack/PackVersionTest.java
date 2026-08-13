package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackVersionTest {

    @Test
    void parsesMajorMinorPatch() {
        PackVersion version = PackVersion.parse("2.5.13");
        assertEquals(2, version.major());
        assertEquals(5, version.minor());
        assertEquals(13, version.patch());
    }

    @Test
    void comparesByMajorThenMinorThenPatch() {
        assertTrue(PackVersion.parse("2.0.0").compareTo(PackVersion.parse("1.9.9")) > 0);
        assertTrue(PackVersion.parse("2.1.0").compareTo(PackVersion.parse("2.0.9")) > 0);
        assertTrue(PackVersion.parse("2.0.5").compareTo(PackVersion.parse("2.0.4")) > 0);
        assertEquals(0, PackVersion.parse("2.0.0").compareTo(PackVersion.parse("2.0.0")));
    }

    @Test
    void rejectsTwoSegmentVersion() {
        assertThrows(IllegalArgumentException.class, () -> PackVersion.parse("2.0"));
    }

    @Test
    void rejectsNonNumeric() {
        assertThrows(IllegalArgumentException.class, () -> PackVersion.parse("2.x.0"));
    }
}
