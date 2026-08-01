package com.npdev.kernel.concepts;

import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * O5 (Move 11 W4): the default gateway for a kernel test that WRITES.
 *
 * <p>Three shipped bugs, one cause -- each test started downstream of the layer holding the bug:
 * <ul>
 *   <li>REG-71 used a <b>noop</b> semantic policy;</li>
 *   <li>REG-83 used a test gateway that lacked the <b>governed</b> policy;</li>
 *   <li>REG-89's kernel tests build a {@code ProcedureStep} directly, never through the validator.</li>
 * </ul>
 *
 * <p>REG-83 is the one this class is for. {@code new DefaultConceptGateway(store)} wires
 * {@link ConceptGatewaySemanticPolicy#noop()} -- no required-field, enum or lifecycle enforcement at
 * all. A generated app runs {@link ConfiguredConceptGatewaySemanticPolicy}, which requires every
 * declared required field (including {@code id}, required on essentially every real concept) to be
 * present in the write's own data map. So a write path that resolved an id but never folded it into
 * that map passed every kernel test and was denied {@code CONCEPT_FIELD_REQUIRED} by every real app.
 *
 * <p>{@link DefaultConceptGateway#governedBy(ConceptStore, com.npdev.dsl.v1.compiled.CompiledModel)}
 * is the same idea for a test that already has a compiled model in hand. Kernel tests generally do
 * not -- they declare a concept in two lines and write to it -- so this is that door: same policy
 * class, same enforcement, no model file.
 *
 * <p>The default field set is deliberately {@code id} + the named ones, all required: an "id is
 * required" rule is what REG-83 tripped over, and a helper that quietly omitted it would recreate
 * the hole it exists to close.
 */
public final class GovernedTestGateways {

    private GovernedTestGateways() {
    }

    /** A governed gateway over a fresh in-memory store, for concepts whose fields are id + the given names. */
    public static DefaultConceptGateway forConcepts(ConceptSpec... concepts) {
        return over(new InMemoryConceptStore(), concepts);
    }

    /** A governed gateway over an existing store (for a test that seeds rows first). */
    public static DefaultConceptGateway over(ConceptStore store, ConceptSpec... concepts) {
        List<ConceptDefinition> definitions = new ArrayList<>();
        for (ConceptSpec spec : concepts) {
            List<FieldDefinition> fields = new ArrayList<>();
            fields.add(new FieldDefinition("id", true, List.of(), null, null, null));
            for (String field : spec.requiredFields()) {
                if (!"id".equals(field)) {
                    fields.add(new FieldDefinition(field, true, List.of(), null, null, null));
                }
            }
            for (String field : spec.optionalFields()) {
                fields.add(new FieldDefinition(field, false, List.of(), null, null, null));
            }
            definitions.add(ConceptDefinition.of(spec.name(), fields, List.of(), null));
        }
        return new DefaultConceptGateway(
                store,
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(definitions),
                record -> { }
        );
    }

    /** One concept the test writes to. {@code id} is always added as required. */
    public record ConceptSpec(String name, List<String> requiredFields, List<String> optionalFields) {

        /** A concept whose only required field is its id; everything else is free-form. */
        public static ConceptSpec of(String name, String... optionalFields) {
            return new ConceptSpec(name, List.of(), List.of(optionalFields));
        }

        public static ConceptSpec required(String name, String... requiredFields) {
            return new ConceptSpec(name, List.of(requiredFields), List.of());
        }
    }
}
