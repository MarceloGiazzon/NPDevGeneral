package com.npdev.test.postgres;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresTestSupportLinuxCompatibilityTest {

    @Test
    void linuxUsesTestcontainersAutoDiscoveryWhenDockerHostIsUnset() {
        PostgresTestSupport.DockerHostConfiguration configuration =
                PostgresTestSupport.resolveDockerHostConfiguration("Linux", null, null);

        assertNull(configuration.dockerHost());
        assertNull(configuration.clientStrategy());
    }

    @Test
    void macUsesTestcontainersAutoDiscoveryWhenDockerHostIsUnset() {
        PostgresTestSupport.DockerHostConfiguration configuration =
                PostgresTestSupport.resolveDockerHostConfiguration("Mac OS X", null, null);

        assertNull(configuration.dockerHost());
        assertNull(configuration.clientStrategy());
    }

    @Test
    void existingDockerHostIsRespectedOnEveryPlatform() {
        PostgresTestSupport.DockerHostConfiguration configuration =
                PostgresTestSupport.resolveDockerHostConfiguration("Windows 11", "tcp://127.0.0.1:2375", null);

        assertNull(configuration.dockerHost());
        assertNull(configuration.clientStrategy());
    }

    @Test
    void windowsFallsBackToDockerDesktopNamedPipeWhenNoDockerHostIsConfigured() {
        PostgresTestSupport.DockerHostConfiguration configuration =
                PostgresTestSupport.resolveDockerHostConfiguration("Windows 11", null, null);

        assertEquals("npipe:////./pipe/dockerDesktopLinuxEngine", configuration.dockerHost());
        assertEquals(
                "org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy",
                configuration.clientStrategy()
        );
    }
}
