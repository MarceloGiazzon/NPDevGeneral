package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "npdev.runtime.supported-surface-enforced=true",
        "npdev.runtime.surface-profile=supported-core"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportedRuntimeSurfaceAllowlistIntegrationTest {

    private static final String ALLOWLIST_RESOURCE = "npdev/runtime-supported-controllers.json";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void defaultRuntimeProfileExposesOnlyAllowlistedControllers() throws Exception {
        Set<String> supportedControllers = loadArray("allowedControllers");
        Set<String> activeControllers = activeRuntimeControllers();
        Set<String> mappedControllers = mappedRuntimeControllers();

        assertFalse(activeControllers.isEmpty(), "Expected the supported runtime profile to register API controllers.");
        assertTrue(supportedControllers.containsAll(activeControllers), "Unsupported active controllers: " + difference(activeControllers, supportedControllers));
        assertTrue(supportedControllers.containsAll(mappedControllers), "Unsupported mapped controllers: " + difference(mappedControllers, supportedControllers));
        assertTrue(activeControllers.contains("RuntimeMetadataController"));
        assertTrue(activeControllers.contains("RuntimePluginStatusController"));
        assertTrue(activeControllers.contains("SupportDiagnosticsController"));
        assertFalse(activeControllers.contains("RuntimePluginPackagesController"));
        assertFalse(activeControllers.contains("RuntimeRefreshController"));
        assertFalse(activeControllers.contains("ModelSyncStatusController"));
    }

    private Set<String> activeRuntimeControllers() {
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Package beanPackage = beanType.getPackage();
            if (beanPackage == null || !beanPackage.getName().startsWith("com.finalexec")) {
                continue;
            }
            if (beanType.getSimpleName().endsWith("Controller")) {
                controllers.add(beanType.getSimpleName());
            }
        }
        return Set.copyOf(controllers);
    }

    private Set<String> mappedRuntimeControllers() {
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
            Class<?> beanType = handlerMethod.getBeanType();
            Package beanPackage = beanType.getPackage();
            if (beanPackage == null || !beanPackage.getName().startsWith("com.finalexec")) {
                continue;
            }
            if (beanType.getSimpleName().endsWith("Controller")) {
                controllers.add(beanType.getSimpleName());
            }
        }
        return Set.copyOf(controllers);
    }

    private Set<String> loadArray(String propertyName) throws Exception {
        try (InputStream inputStream = new ClassPathResource(ALLOWLIST_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            LinkedHashSet<String> controllers = new LinkedHashSet<>();
            for (JsonNode item : root.path(propertyName)) {
                String value = item.asText("").trim();
                if (!value.isEmpty()) {
                    controllers.add(value);
                }
            }
            return Set.copyOf(controllers);
        }
    }

    private static Set<String> difference(Set<String> actual, Set<String> allowed) {
        LinkedHashSet<String> difference = new LinkedHashSet<>(actual);
        difference.removeIf(allowed::contains);
        return Set.copyOf(difference);
    }
}
