package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every generated app runs {@link LiveConceptGatewaySemanticPolicy}, a hot-reload wrapper -- so an
 * interface method it does not override silently answers with the interface DEFAULT in production
 * while every unit test (which uses the configured policy directly) passes. Found 2026-10-07:
 * {@code rowReadRule} was added to the interface and the configured policy only, and every
 * row-scoped aggregate in a real app still returned 403.
 */
class LiveConceptGatewaySemanticPolicyDelegationTest {

    @Test
    void liveWrapperOverridesEveryInterfaceMethod() {
        List<String> missing = new ArrayList<>();
        for (Method method : ConceptGatewaySemanticPolicy.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() != ConceptGatewaySemanticPolicy.class) {
                continue;
            }
            try {
                Method declared = LiveConceptGatewaySemanticPolicy.class.getMethod(method.getName(), method.getParameterTypes());
                if (declared.getDeclaringClass() != LiveConceptGatewaySemanticPolicy.class) {
                    missing.add(method.getName());
                }
            } catch (NoSuchMethodException impossible) {
                missing.add(method.getName());
            }
        }
        assertTrue(missing.isEmpty(), "LiveConceptGatewaySemanticPolicy must delegate: " + missing);
    }
}
