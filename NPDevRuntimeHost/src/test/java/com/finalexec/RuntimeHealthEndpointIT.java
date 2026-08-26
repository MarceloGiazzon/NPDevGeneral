package com.finalexec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// W3.3 (2026-08-25 remediation plan / QUAL-33): revived from @Disabled. The "when a dependency is
// DOWN" half lives in NPDevKernel/adapters/runtime-validation's own
// RuntimeHealthIndicatorsTest#eventHealthShouldBeDownWhenStoreDependencyIsDown -- a plain unit test
// of NpdevEventStoreHealthIndicator.health(), not a second @SpringBootTest here. A @SpringBootTest
// version was tried first (substituting a broken EventStore @Primary bean) and abandoned: its
// override leaked into THIS class's separate @SpringBootTest run in the same Gradle test JVM (both
// showed the identical test-double exception despite genuinely distinct Spring Boot startup
// banners), and neither @Import(explicit) nor @DirtiesContext fixed it. The unit test proves the
// same real logic with no Spring context at all, so there is nothing to leak.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
class RuntimeHealthEndpointIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        String body = response.getBody() == null ? "" : response.getBody();

        assertEquals(200, response.getStatusCode().value(), "body was: " + body);
        assertTrue(body.contains("\"status\":\"UP\""), "expected {\"status\":\"UP\",...}, got: " + body);
    }
}
