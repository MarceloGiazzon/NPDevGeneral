package com.npdev.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * deps-and-java/PLAN.md W1.3, widened by ROUND2_PLAN.md R1c: the third-party user who asked for "a
 * newer Java version" wanted this future-proofed against every Java version to come, not just 21 --
 * so there is no upper enum here anymore, only a floor at the platform's minimum of 17.
 */
class GeneratorMainJavaVersionResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsTo17WhenConfigIsNull() {
        assertEquals(17, GeneratorMain.resolveJavaVersion(null));
    }

    @Test
    void defaultsTo17WhenBuildBlockIsAbsent() throws Exception {
        assertEquals(17, GeneratorMain.resolveJavaVersion(MAPPER.readTree("{}")));
    }

    @Test
    void defaultsTo17WhenJavaVersionKeyIsAbsent() throws Exception {
        assertEquals(17, GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                { "build": { "repositories": [] } }
                """)));
    }

    @Test
    void readsAnExplicitValueAtTheFloor() throws Exception {
        assertEquals(21, GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                { "build": { "javaVersion": 21 } }
                """)));
    }

    @Test
    void acceptsTheCurrentLtsWithNoUpperBound() throws Exception {
        assertEquals(25, GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                { "build": { "javaVersion": 25 } }
                """)));
    }

    @Test
    void acceptsAJavaVersionThatDoesNotExistYet() throws Exception {
        // The floor is the only guard -- no allowlist to keep updating as new JDKs ship.
        assertEquals(99, GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                { "build": { "javaVersion": 99 } }
                """)));
    }

    @Test
    void rejectsAValueBelowThePlatformFloor() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                        { "build": { "javaVersion": 11 } }
                        """))
        );

        assertTrue(ex.getMessage().contains("11"), ex.getMessage());
        assertTrue(ex.getMessage().contains("17"), ex.getMessage());
    }

    @Test
    void rejectsANonIntegerValue() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                        { "build": { "javaVersion": "17" } }
                        """))
        );

        assertTrue(ex.getMessage().contains("integer"), ex.getMessage());
    }
}
