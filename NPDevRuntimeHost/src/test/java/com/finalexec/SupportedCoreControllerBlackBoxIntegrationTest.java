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
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "npdev.runtime.supported-surface-enforced=true",
        "npdev.runtime.surface-profile=supported-core",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportedCoreControllerBlackBoxIntegrationTest {

    private static final String ALLOWLIST_RESOURCE = "npdev/runtime-supported-controllers.json";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyRuntimeHostControllerIsClassifiedExactlyOnce() throws Exception {
        Set<String> sourceControllers = discoverSourceControllers();
        Set<String> allowedControllers = loadArray("allowedControllers");
        Set<String> deferredControllers = loadArray("deferredControllers");
        Set<String> testOnlyControllers = loadArray("testOnlyControllers");

        assertNoOverlap("allowed/deferred", allowedControllers, deferredControllers);
        assertNoOverlap("allowed/test-only", allowedControllers, testOnlyControllers);
        assertNoOverlap("deferred/test-only", deferredControllers, testOnlyControllers);

        LinkedHashSet<String> classifiedControllers = new LinkedHashSet<>();
        classifiedControllers.addAll(allowedControllers);
        classifiedControllers.addAll(deferredControllers);
        classifiedControllers.addAll(testOnlyControllers);

        assertEquals(sourceControllers, classifiedControllers,
                "Every RuntimeHost controller source must be classified as allowed, deferred, or test-only.");
    }

    @Test
    void supportedCoreProfileExposesAllowedControllersOnly() throws Exception {
        Set<String> allowedControllers = loadArray("allowedControllers");
        Set<String> deferredControllers = loadArray("deferredControllers");
        Set<String> testOnlyControllers = loadArray("testOnlyControllers");
        Set<String> activeControllers = activeRuntimeControllers();
        Set<String> mappedControllers = mappedRuntimeControllers();

        assertFalse(activeControllers.isEmpty(), "Expected supported-core profile to register runtime controllers.");
        assertTrue(activeControllers.containsAll(allowedControllers), "Missing allowed controllers: " + difference(allowedControllers, activeControllers));
        assertTrue(mappedControllers.containsAll(allowedControllers), "Missing mapped allowed controllers: " + difference(allowedControllers, mappedControllers));
        assertTrue(allowedControllers.containsAll(activeControllers), "Unlisted active controllers: " + difference(activeControllers, allowedControllers));
        assertTrue(allowedControllers.containsAll(mappedControllers), "Unlisted mapped controllers: " + difference(mappedControllers, allowedControllers));

        for (String controller : deferredControllers) {
            assertFalse(activeControllers.contains(controller), controller + " must be absent from supported-core.");
            assertFalse(mappedControllers.contains(controller), controller + " must not be mapped in supported-core.");
        }
        for (String controller : testOnlyControllers) {
            assertFalse(activeControllers.contains(controller), controller + " must be absent from supported-core.");
            assertFalse(mappedControllers.contains(controller), controller + " must not be mapped in supported-core.");
        }
    }

    private Set<String> discoverSourceControllers() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java", "com", "finalexec");
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .sorted()
                    .forEach(controllers::add);
        }
        return Set.copyOf(controllers);
    }

    private Set<String> activeRuntimeControllers() {
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null || beanType.getPackage() == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(beanType);
            if (userClass.getPackageName().startsWith("com.finalexec") && userClass.getSimpleName().endsWith("Controller")) {
                controllers.add(userClass.getSimpleName());
            }
        }
        return Set.copyOf(controllers);
    }

    private Set<String> mappedRuntimeControllers() {
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
            Class<?> beanType = handlerMethod.getBeanType();
            if (beanType.getPackage() == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(beanType);
            if (userClass.getPackageName().startsWith("com.finalexec") && userClass.getSimpleName().endsWith("Controller")) {
                controllers.add(userClass.getSimpleName());
            }
        }
        return Set.copyOf(controllers);
    }

    private Set<String> loadArray(String propertyName) throws Exception {
        try (InputStream inputStream = new ClassPathResource(ALLOWLIST_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode item : root.path(propertyName)) {
                String value = item.asText("").trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return Set.copyOf(values);
        }
    }

    private static void assertNoOverlap(String label, Set<String> left, Set<String> right) {
        assertTrue(difference(left, right).size() == left.size(), "Controller classifications overlap: " + label);
    }

    private static Set<String> difference(Set<String> actual, Set<String> allowed) {
        LinkedHashSet<String> difference = new LinkedHashSet<>(actual);
        difference.removeIf(allowed::contains);
        return Set.copyOf(difference);
    }
}
