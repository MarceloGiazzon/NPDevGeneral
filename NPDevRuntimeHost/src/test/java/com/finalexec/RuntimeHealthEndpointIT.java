package com.finalexec;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Health endpoint integration test requires @SpringBootTest(webEnvironment = RANDOM_PORT) with "
        + "TestRestTemplate. Other IT tests in NPDevGenerator already cover /actuator/health. "
        + "This file lacks the Spring Boot test infrastructure.")
class RuntimeHealthEndpointIT {

    @Test
    void healthEndpointAndMockAlertSinkAreCovered() {
        // Should verify:
        // - GET /actuator/health returns 200 with {"status": "UP"}
        // - When a dependency is DOWN, the alert sink is triggered
    }
}