package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.generator.emitters.trustedsource.model.TrustedProcedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.npdev.generator.emitters.TrustedSourceTemplateSupport.metadataText;
import static com.npdev.generator.emitters.TrustedSourceTemplateSupport.quote;

/**
 * Packaged-procedure wrapping and the {@code GeneratedActionRegistry} template: builds the
 * {@code List<GeneratedActionDescriptor>} literal (one entry per trusted procedure) from each
 * procedure's compiled {@link CompiledGeneratedActionDescriptorSpec} or, absent one, from its
 * trusted-source manifest metadata / naming conventions.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedActionRegistryTemplate {
    private static final String PACKAGE_NAME = "com.npdev.generated.trusted";

    private TrustedActionRegistryTemplate() {
    }

    static String packagedProcedureSource(TrustedProcedure procedure) {
        return "package " + PACKAGE_NAME + ";\n\n" + procedure.source();
    }

    static String generatedActionRegistrySource(List<TrustedProcedure> procedures) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedActionRegistry {
                    private static final List<GeneratedActionDescriptor> DESCRIPTORS = List.of(
                """);
        for (int index = 0; index < procedures.size(); index++) {
            TrustedProcedure procedure = procedures.get(index);
            source.append("            new GeneratedActionDescriptor(")
                    .append(quote(descriptorActionName(procedure)))
                    .append(", ")
                    .append(quote(procedure.id()))
                    .append(", ")
                    .append(quote(procedure.requiredRole()))
                    .append(", ")
                    .append(procedure.tenantScoped())
                    .append(", ")
                    .append(generatedStringList(descriptorAffectedConcepts(procedure)))
                    .append(", ")
                    .append(quote(descriptorSideEffectConcept(procedure)))
                    .append(", ")
                    .append(quote(descriptorEventNameOnSuccess(procedure)))
                    .append(", ")
                    .append(quote(descriptorAuditResourceType(procedure)))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "idempotencyPolicy", "record")))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "tracePolicy", "record")))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "correlationPolicy", "claim")))
                    .append(", ")
                    .append(quote(descriptorCapabilityId(procedure)))
                    .append(", context -> new ")
                    .append(procedure.className())
                    .append("().")
                    .append(procedure.method())
                    .append("(context))");
            if (index < procedures.size() - 1) {
                source.append(",");
            }
            source.append("\n");
        }
        source.append("""
                    );
                    private static final Map<String, GeneratedActionDescriptor> BY_ACTION_NAME = byActionName();

                    private GeneratedActionRegistry() {
                    }

                    public static List<GeneratedActionDescriptor> all() {
                        return DESCRIPTORS;
                    }

                    public static GeneratedActionDescriptor find(String actionName) {
                        if (actionName == null) {
                            return null;
                        }
                        return BY_ACTION_NAME.get(actionName.trim());
                    }

                    private static Map<String, GeneratedActionDescriptor> byActionName() {
                        Map<String, GeneratedActionDescriptor> out = new LinkedHashMap<>();
                        for (GeneratedActionDescriptor descriptor : DESCRIPTORS) {
                            out.put(descriptor.actionName(), descriptor);
                            out.put(descriptor.procedureName(), descriptor);
                        }
                        return Map.copyOf(out);
                    }
                }
                """);
        return source.toString();
    }

    private static String descriptorActionName(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.actionName() != null && !descriptor.actionName().isBlank()) {
            return descriptor.actionName();
        }
        return procedure.id();
    }

    private static String descriptorCapabilityId(TrustedProcedure procedure) {
        return "generated.action." + descriptorActionName(procedure);
    }

    private static List<String> descriptorAffectedConcepts(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            return descriptor.affectedConcepts();
        }
        String explicit = metadataText(procedure.metadata(), "affectedConcepts");
        if (!explicit.isBlank()) {
            return splitMetadataList(explicit);
        }
        String sideEffectConcept = descriptorSideEffectConcept(procedure);
        return sideEffectConcept.isBlank() ? List.of() : List.of(sideEffectConcept);
    }

    private static String descriptorSideEffectConcept(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            return descriptor.sideEffectConcept() == null ? "" : descriptor.sideEffectConcept();
        }
        String explicit = metadataText(procedure.metadata(), "sideEffectConcept");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return inferConceptName(procedure.id());
    }

    private static String descriptorEventNameOnSuccess(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.eventNameOnSuccess() != null && !descriptor.eventNameOnSuccess().isBlank()) {
            return descriptor.eventNameOnSuccess();
        }
        String explicit = metadataText(procedure.metadata(), "eventNameOnSuccess");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return "generated.action." + safeEventToken(descriptorActionName(procedure)) + ".completed";
    }

    private static String descriptorAuditResourceType(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.auditResourceType() != null && !descriptor.auditResourceType().isBlank()) {
            return descriptor.auditResourceType();
        }
        String explicit = metadataText(procedure.metadata(), "auditResourceType");
        return explicit.isBlank() ? "GENERATED_ACTION" : explicit;
    }

    private static String descriptorPolicy(TrustedProcedure procedure, String metadataKey, String defaultValue) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            String value = switch (metadataKey) {
                case "idempotencyPolicy" -> descriptor.idempotencyPolicy();
                case "tracePolicy" -> descriptor.tracePolicy();
                case "correlationPolicy" -> descriptor.correlationPolicy();
                default -> "";
            };
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String explicit = metadataText(procedure.metadata(), metadataKey);
        return explicit.isBlank() ? defaultValue : explicit;
    }

    private static List<String> splitMetadataList(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String item = token == null ? "" : token.trim();
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out.isEmpty() ? List.of("GeneratedAction") : List.copyOf(out);
    }

    private static String generatedStringList(List<String> values) {
        List<String> safeValues = values == null ? List.of() : values;
        StringBuilder out = new StringBuilder("List.of(");
        for (int index = 0; index < safeValues.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(quote(safeValues.get(index)));
        }
        out.append(")");
        return out.toString();
    }

    private static String inferConceptName(String actionName) {
        String cleaned = actionName == null ? "" : actionName.trim();
        if (cleaned.isBlank()) {
            return "GeneratedAction";
        }
        for (String prefix : List.of("Create", "Add", "Register", "Upsert", "Update", "Save")) {
            if (cleaned.startsWith(prefix) && cleaned.length() > prefix.length()) {
                return cleaned.substring(prefix.length());
            }
        }
        return cleaned;
    }

    private static String safeEventToken(String actionName) {
        String cleaned = actionName == null ? "" : actionName.trim();
        if (cleaned.isBlank()) {
            return "action";
        }
        return cleaned.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }
}
