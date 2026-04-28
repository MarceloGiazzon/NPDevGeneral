package com.finalexec;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
@Testcontainers
public abstract class AbstractScenarioIntegrationTest {
    @DynamicPropertySource
    static void registerScenarioDefaults(DynamicPropertyRegistry registry) {
        registry.add("npdev.auth.enabled", () -> "true");
        registry.add("npdev.auth.api-keys", () -> "dev-key=dev:developer:ADMIN|DEBUG|USER");
    }

    // The Testcontainers JDBC URL in application-test.yml manages lifecycle.
}
