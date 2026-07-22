package com.finalexec.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class RuntimeControllerAllowlistConfig {

    static final String SUPPORTED_SURFACE_PROPERTY = "npdev.runtime.supported-surface-enforced";
    static final String SURFACE_PROFILE_PROPERTY = "npdev.runtime.surface-profile";
    static final String ALLOWLIST_RESOURCE = "npdev/runtime-supported-controllers.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Bean
    @ConditionalOnProperty(name = SUPPORTED_SURFACE_PROPERTY, havingValue = "true")
    static BeanDefinitionRegistryPostProcessor supportedRuntimeControllerAllowlistPostProcessor(
            Environment environment
    ) {
        RuntimeSurfaceManifest manifest = loadManifest();
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                String surfaceProfile = environment.getProperty(SURFACE_PROFILE_PROPERTY);
                for (String beanName : registry.getBeanDefinitionNames()) {
                    String beanClassName = registry.getBeanDefinition(beanName).getBeanClassName();
                    if (beanClassName == null) {
                        continue;
                    }
                    if (!beanClassName.startsWith("com.finalexec.") || !beanClassName.endsWith("Controller")) {
                        continue;
                    }
                    String simpleName = beanClassName.substring(beanClassName.lastIndexOf('.') + 1);
                    if (!manifest.allowsController(simpleName, surfaceProfile)) {
                        registry.removeBeanDefinition(beanName);
                    }
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                // No-op: the controller filtering happens before bean instantiation.
            }
        };
    }

    private static RuntimeSurfaceManifest loadManifest() {
        try (InputStream inputStream = new ClassPathResource(ALLOWLIST_RESOURCE).getInputStream()) {
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            Set<String> allowedControllers = readValues(root.path("allowedControllers"));
            if (allowedControllers.isEmpty()) {
                throw new IllegalStateException("Runtime supported-controller allowlist is empty: " + ALLOWLIST_RESOURCE);
            }
            return new RuntimeSurfaceManifest(
                    root.path("defaultSurfaceProfile").asText("supported-core").trim(),
                    allowedControllers,
                    readValues(root.path("deferredControllers")),
                    readValues(root.path("testOnlyControllers"))
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load runtime supported-controller allowlist: " + ALLOWLIST_RESOURCE, exception);
        }
    }

    private static Set<String> readValues(JsonNode arrayNode) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String value = item.asText("").trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private record RuntimeSurfaceManifest(
            String defaultSurfaceProfile,
            Set<String> allowedControllers,
            Set<String> deferredControllers,
            Set<String> testOnlyControllers
    ) {
        boolean allowsController(String controllerName, String requestedSurfaceProfile) {
            String effectiveProfile = requestedSurfaceProfile == null || requestedSurfaceProfile.isBlank()
                    ? defaultSurfaceProfile
                    : requestedSurfaceProfile.trim();

            return switch (effectiveProfile) {
                case "supported-core" -> allowedControllers.contains(controllerName);
                case "non-default", "experimental" -> allowedControllers.contains(controllerName)
                        || deferredControllers.contains(controllerName);
                default -> throw new IllegalStateException(
                        "Unsupported runtime surface profile '" + effectiveProfile + "' for " + ALLOWLIST_RESOURCE
                );
            };
        }
    }
}
