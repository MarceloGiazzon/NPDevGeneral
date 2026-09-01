package com.finalexec;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyDecision;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Model B callback allowlist gate (SEC-3): a plugin's manifest entry pins which capabilities it may
 * call back into over the future IPC channel, so an admitted plugin cannot ask the host to perform a
 * capability it never declared. See docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1 and
 * {@link PluginExecutionPolicyEvaluator#evaluateCallback}.
 */
class PluginExecutionPolicyEvaluatorCallbackTest {

    @Test
    void allowsACallbackIntoACapabilityThePluginsOwnManifestEntryDeclares() {
        RuntimePluginAdapterRegistry registry = registryWithAuditLogPluginDeclaring("auditLog", "append");
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PluginExecutionPolicyDecision decision = evaluator.evaluateCallback(
                contribution("auditlog-plugin", "auditLog", "append", "auditlog-inproc"),
                registry,
                "auditLog",
                "append"
        );

        assertTrue(decision.allowed());
        assertEquals("PLUGIN_CALLBACK_ALLOWED", decision.decisionCode());
    }

    @Test
    void deniesACallbackIntoACapabilityThePluginNeverDeclared() {
        RuntimePluginAdapterRegistry registry = registryWithAuditLogPluginDeclaring("auditLog", "append");
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PluginExecutionPolicyDecision decision = evaluator.evaluateCallback(
                contribution("auditlog-plugin", "auditLog", "append", "auditlog-inproc"),
                registry,
                "persistence",
                "dropAll"
        );

        assertFalse(decision.allowed());
        assertEquals("PLUGIN_CALLBACK_NOT_DECLARED", decision.decisionCode());
    }

    @Test
    void deniesACallbackWhenThePluginIsGloballyDeniedByRuntimePolicy() {
        RuntimePluginAdapterRegistry registry = registryWithAuditLogPluginDeclaring("auditLog", "append");
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(
                null, "dev", "auditlog-plugin", "", "", ""
        );

        PluginExecutionPolicyDecision decision = evaluator.evaluateCallback(
                contribution("auditlog-plugin", "auditLog", "append", "auditlog-inproc"),
                registry,
                "auditLog",
                "append"
        );

        assertFalse(decision.allowed());
        assertEquals("PLUGIN_POLICY_DENY_PLUGIN_ID", decision.decisionCode());
    }

    @Test
    void deniesACallbackThatDoesNotNameACapability() {
        RuntimePluginAdapterRegistry registry = registryWithAuditLogPluginDeclaring("auditLog", "append");
        PluginExecutionPolicyEvaluator evaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PluginExecutionPolicyDecision decision = evaluator.evaluateCallback(
                contribution("auditlog-plugin", "auditLog", "append", "auditlog-inproc"),
                registry,
                "  ",
                "append"
        );

        assertFalse(decision.allowed());
        assertEquals("PLUGIN_CALLBACK_MISSING_CAPABILITY", decision.decisionCode());
    }

    private static RuntimePluginAdapterRegistry registryWithAuditLogPluginDeclaring(String capability, String operation) {
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json",
                "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        "auditlog-plugin",
                        "Audit Log Plugin",
                        "1.0.0",
                        true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                capability,
                                operation,
                                "auditlog-inproc",
                                capability + "." + operation,
                                new RuntimePluginManifest.ImplementationRef("class", "com.example.AuditLogHandler")
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }

    private static RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution(
            String pluginId, String capability, String operation, String adapterId
    ) {
        return new RuntimePluginAdapterRegistry.RegisteredAdapterContribution(
                pluginId, "1.0.0", capability, operation, adapterId, capability + "." + operation,
                "class", "com.example.AuditLogHandler"
        );
    }
}
