package com.npdev.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** deps-and-java/PLAN.md W1.3. */
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
    void readsAnExplicitSupportedValue() throws Exception {
        assertEquals(21, GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                { "build": { "javaVersion": 21 } }
                """)));
    }

    @Test
    void rejectsAnUnsupportedValueWithANamedLimiter() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GeneratorMain.resolveJavaVersion(MAPPER.readTree("""
                        { "build": { "javaVersion": 25 } }
                        """))
        );

        assertTrue(ex.getMessage().contains("25"), ex.getMessage());
        assertTrue(ex.getMessage().contains("17"), ex.getMessage());
        assertTrue(ex.getMessage().contains("21"), ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("gradle"), ex.getMessage());
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
