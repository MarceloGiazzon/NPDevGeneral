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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

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
        Set<String> classpathControllers = discoverClasspathControllers();
        Set<String> allowedControllers = loadArray("allowedControllers");
        Set<String> deferredControllers = loadArray("deferredControllers");
        Set<String> testOnlyControllers = loadArray("testOnlyControllers");

        Set<String> allClassified = new LinkedHashSet<>();
        allClassified.addAll(allowedControllers);
        allClassified.addAll(deferredControllers);
        allClassified.addAll(testOnlyControllers);

        for (String classified : allClassified) {
            assertTrue(
                    classpathControllers.contains(classified) || compileExcludedSourceExists(classified),
                    "Manifest entry '" + classified + "' is phantom: not on classpath and no compile-excluded source found.");
        }

        assertNoOverlap("allowed/deferred", allowedControllers, deferredControllers);
        assertNoOverlap("allowed/test-only", allowedControllers, testOnlyControllers);
        assertNoOverlap("deferred/test-only", deferredControllers, testOnlyControllers);

        assertEquals(classpathControllers, allClassified,
                "Every api controller on the classpath (RuntimeHost + runtimehost-core) must be classified "
                        + "as allowed, deferred, or test-only in the manifest.");
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

    private Set<String> discoverClasspathControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (String pkg : new String[]{"com.finalexec.api"}) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                String className = bd.getBeanClassName();
                if (className != null) {
                    String simpleName = className.substring(className.lastIndexOf('.') + 1);
                    controllers.add(simpleName);
                }
            }
        }
        return Set.copyOf(controllers);
    }

    private boolean compileExcludedSourceExists(String controllerName) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource(
                    "file:src/main/java/com/finalexec/" + controllerName + ".java");
            return resource.exists();
        } catch (Exception e) {
            return false;
        }
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
