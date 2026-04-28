package com.finalexec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "npdev.runtime.supported-surface-enforced=true",
        "npdev.runtime.surface-profile=non-default"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NonDefaultRuntimeSurfaceProfileIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void nonDefaultRuntimeProfileExposesSupportedCoreAndNonDefaultControllersOnly() {
        Set<String> activeControllers = activeRuntimeControllers();
        Set<String> mappedControllers = mappedRuntimeControllers();

        assertTrue(activeControllers.contains("RuntimeMetadataController"));
        assertTrue(activeControllers.contains("RuntimePluginPackagesController"));
        assertTrue(activeControllers.contains("RuntimeRefreshController"));
        assertTrue(activeControllers.contains("ModelSyncStatusController"));
        assertFalse(activeControllers.contains("BetaOnboardingController"));
        assertFalse(mappedControllers.contains("FlowBuilderController"));
    }

    private Set<String> activeRuntimeControllers() {
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Package beanPackage = beanType.getPackage();
            if (beanPackage == null || !"com.finalexec.api".equals(beanPackage.getName())) {
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
            if (beanPackage == null || !"com.finalexec.api".equals(beanPackage.getName())) {
                continue;
            }
            if (beanType.getSimpleName().endsWith("Controller")) {
                controllers.add(beanType.getSimpleName());
            }
        }
        return Set.copyOf(controllers);
    }
}
