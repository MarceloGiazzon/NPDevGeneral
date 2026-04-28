package com.finalexec;

import com.finalexec.npdev.service.PluginExecutionPolicyDecision;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginExecutionPolicyEvaluatorTest {

    @Test
    void allowsManifestBackedPluginExecutionByDefault() {
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PluginExecutionPolicyDecision decision = evaluator.evaluate(contribution(), notificationCall());

        assertTrue(decision.allowed());
        assertEquals("PLUGIN_EXECUTION_ALLOWED", decision.decisionCode());
    }

    @Test
    void deniesExecutionWhenAdapterIdIsBlocked() {
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(
                null,
                "dev",
                "",
                "notification-inproc",
                "",
                ""
        );

        PluginExecutionPolicyDecision decision = evaluator.evaluate(contribution(), notificationCall());

        assertFalse(decision.allowed());
        assertEquals("PLUGIN_POLICY_DENY_ADAPTER_ID", decision.decisionCode());
        assertEquals("notification-inproc", decision.adapterId());
    }

    @Test
    void deniesExecutionWhenAllowlistExcludesPlugin() {
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(
                null,
                "dev",
                "",
                "",
                "another-plugin",
                ""
        );

        PluginExecutionPolicyDecision decision = evaluator.evaluate(contribution(), notificationCall());

        assertFalse(decision.allowed());
        assertEquals("PLUGIN_POLICY_PLUGIN_NOT_ALLOWED", decision.decisionCode());
    }

    private static RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution() {
        return new RuntimePluginAdapterRegistry.RegisteredAdapterContribution(
                "notification-inproc-plugin",
                "1.0.0",
                "notification",
                "send",
                "notification-inproc",
                "notification.send",
                "runtimeref",
                "notificationInProcCapabilityAdapter"
        );
    }

    private static CapabilityCall notificationCall() {
        return new CapabilityCall(
                "notification",
                "NotificationCapability",
                "notification-inproc",
                "send",
                Map.of("message", "hello")
        );
    }
}
