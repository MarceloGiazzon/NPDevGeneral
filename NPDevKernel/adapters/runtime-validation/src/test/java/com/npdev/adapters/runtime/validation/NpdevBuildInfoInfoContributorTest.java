package com.npdev.adapters.runtime.validation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoEndpoint;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpdevBuildInfoInfoContributorTest {

    @Test
    void actuatorInfoShouldContainVersionAndCommit() {
        RuntimeSettings settings = new RuntimeSettings(
                "postgres",
                true,
                100,
                2000,
                true,
                262144,
                128,
                "jdbc:postgresql://localhost:5432/npdev",
                "npdev",
                "npdev",
                5,
                30,
                8,
                16384,
                null,
                true
        );

        InfoEndpoint endpoint = new InfoEndpoint(List.of(new NpdevBuildInfoInfoContributor(settings)));
        Map<String, Object> info = endpoint.info();

        assertEquals("0.1.0-test", info.get("npdev.version"));
        assertEquals("abc123def456", info.get("npdev.commit"));
        assertEquals("2026-02-25T00:00:00Z", info.get("npdev.builtAt"));
        assertEquals("0.1.0-generator-test", info.get("npdev.generator.version"));
        assertEquals("M32-Test", info.get("npdev.generator.tag"));
        assertEquals("postgres", info.get("npdev.mode"));
        assertTrue((Boolean) info.get("npdev.authEnabled"));
    }
}
