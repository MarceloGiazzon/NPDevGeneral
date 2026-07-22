package com.finalexec;

import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.security.PermissionDecision;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
@Testcontainers
@Tag("integration")
public abstract class AbstractScenarioIntegrationTest {
    @DynamicPropertySource
    static void registerScenarioDefaults(DynamicPropertyRegistry registry) {
        registry.add("npdev.auth.enabled", () -> "true");
        registry.add("npdev.auth.mode", () -> "apikey");
        registry.add(
                "npdev.auth.api-keys",
                () -> "dev-key=dev:developer:ADMIN|DEBUG|USER;"
                        + "tenant-a-key=tenant-a:actor-a:ADMIN|USER;"
                        + "tenant-b-key=tenant-b:actor-b:ADMIN|USER"
        );
    }

    // The Testcontainers JDBC URL in application-postgres.yml manages lifecycle.

    @TestConfiguration
    static class RuntimeHostIntegrationPermissionConfig {
        @Bean
        @Primary
        PermissionEvaluator runtimeHostIntegrationPermissionEvaluator() {
            return (subject, requirement) -> subject.roles().contains("admin")
                    ? PermissionDecision.allow("integration_admin_role")
                    : PermissionDecision.deny("integration_permission_denied", "Permission denied by integration test policy");
        }
    }
}
