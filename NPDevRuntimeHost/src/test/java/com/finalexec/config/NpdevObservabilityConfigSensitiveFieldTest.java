package com.finalexec.config;

import com.npdev.adapters.tracing.redaction.SensitiveKeyPolicy;
import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R80 (ledger/items/REG-80.yml, docs/MOVE7_IMPLEMENTATION_SPEC.md): proves {@code
 * NpdevObservabilityConfig.sensitiveFieldNames} + {@code SensitiveKeyPolicy.registerModelSensitiveFieldNames}
 * connect a model author's {@code field.sensitive: true} declaration to real redaction -- the wiring
 * that was completely missing before this. The test subject mirrors {@code
 * NPDevSamples/dsl-conformance-max/Input/model.json}'s EXISTING {@code WidgetOrder.customerEmail}
 * ({@code type: "string", required: true, sensitive: true}) exactly, per this task's own instruction
 * not to author a new corpus witness -- {@link CompiledModel} has no constructor that parses a raw
 * JSON file by path, so it is hand-built here the same way every other RuntimeHost config test in
 * this package already builds one (see {@code RuntimeConceptGatewaySemanticPoliciesTest}).
 */
class NpdevObservabilityConfigSensitiveFieldTest {

    @AfterEach
    void resetModelSensitiveFieldNames() {
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(null);
    }

    @Test
    void extractsSensitiveFieldNamesFromTheCompiledModel() {
        Set<String> names = NpdevObservabilityConfig.sensitiveFieldNames(widgetOrderModel());
        assertTrue(names.contains("customerEmail"));
        assertFalse(names.contains("id"), "non-sensitive fields must not be swept in");
    }

    @Test
    void endToEndRegistrationMakesTheModelFieldRedactedLiveWhereItWasNotBefore() {
        assertFalse(SensitiveKeyPolicy.isSensitiveKey("customerEmail"),
                "not sensitive before registration -- the exact gap REG-80 named");

        Set<String> names = NpdevObservabilityConfig.sensitiveFieldNames(widgetOrderModel());
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(names);

        assertTrue(SensitiveKeyPolicy.isSensitiveKey("customerEmail"),
                "field.sensitive: true must now drive real trace/event redaction");
        assertTrue(SensitiveKeyPolicy.isSensitiveKey("password"),
                "the pre-existing static denylist must still fire alongside it, not be replaced");
    }

    /** Mirrors dsl-conformance-max's WidgetOrder concept exactly: id (not sensitive), customerEmail
     * (sensitive: true, the real corpus witness this test reuses per this task's own instruction). */
    private static CompiledModel widgetOrderModel() {
        List<CompiledField> fields = List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField(
                        "customerEmail", "string", "String", false, true, false,
                        List.of(), null, null, null, null, List.of(), null, null, null, null, true
                )
        );
        CompiledEntity entity = new CompiledEntity(
                "WidgetOrder", "WidgetOrder", "widget_orders", fields, List.of(), List.of(), null
        );
        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(entity.getName(), entity);
        return new CompiledModel("widget.sensitive", "1.0.0", entities);
    }
}
