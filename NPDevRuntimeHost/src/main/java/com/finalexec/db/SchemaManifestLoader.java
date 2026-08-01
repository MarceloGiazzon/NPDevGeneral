package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T2.B.4 (pure mechanical extraction): the {@code schema-realization-manifest.json} -&gt;
 * {@link SchemaLifecycleExecutor.SchemaManifest} parsing (the JSON-tree walking helpers
 * {@code loadManifest} used), split out of {@link SchemaLifecycleExecutor} verbatim -- no behavior
 * change. {@link SchemaLifecycleExecutor#loadManifest} (which stays on the executor as a thin
 * delegating wrapper -- it is {@code public static} and called externally by
 * {@code StorageSummaryController} and {@code SchemaImpactFacade} as
 * {@code SchemaLifecycleExecutor.loadManifest()}) calls straight into this sibling class. Flat
 * sibling in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s class
 * javadoc for why.
 */
final class SchemaManifestLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SchemaManifestLoader() {
    }

    static SchemaLifecycleExecutor.SchemaManifest load() {
        try {
            ClassPathResource resource = new ClassPathResource("npdev/db/schema-realization-manifest.json");
            if (!resource.exists()) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(resource.getInputStream());
            JsonNode lifecycle = root.path("schemaLifecycle");
            return new SchemaLifecycleExecutor.SchemaManifest(
                    root.path("engine").asText(""),
                    root.path("storageMode").asText(""),
                    root.path("physicalDatabase").asBoolean(false),
                    root.path("schemaFingerprint").asText(""),
                    strings(root.path("internalTables")),
                    strings(root.path("businessTables")),
                    stringListMap(root.path("businessTableColumns")),
                    stringListMap(root.path("businessTableAdditiveColumns")),
                    stringMapMap(root.path("businessTableColumnTypes")),
                    stringMapMap(root.path("businessTableRenamedColumns")),
                    stringMap(root.path("businessTableRenames")),
                    lifecycle.path("allowDestructiveRecreate").asBoolean(false),
                    lifecycle.path("strategy").asText(""),
                    lifecycle.path("scope").asText(""),
                    lifecycle.path("destructiveRecreateConfirmation").asText(""),
                    // LNCH-1 Phase 4 (task 4.2/4.3): the itemized destructive-acknowledgment token.
                    // Absent from every manifest emitted before this phase (and from every real
                    // manifest until Phase 6 wires -AcknowledgeDestructive into the emitter) --
                    // asText("") correctly defaults to "", which never equals a real computed
                    // token, so pre-Phase-6 apps are unaffected until an author opts in.
                    root.path("destructiveAcknowledgment").asText(""),
                    // LNCH-1 Phase 5. Absent from every manifest emitted before this phase --
                    // each parser defaults to an empty map/list, so pre-Phase-5 apps are unaffected
                    // (no required-column/default/unique-constraint data means the new executor
                    // steps below all find nothing to do and no-op cleanly).
                    stringListMap(root.path("businessTableRequiredColumns")),
                    stringMapMap(root.path("businessTableColumnDefaultLiterals")),
                    stringListMap(root.path("businessTableExpressionDefaultColumns")),
                    uniqueConstraintListMap(root.path("businessTableUniqueConstraints")),
                    // LNCH-1 Phase 6 (task 6.3): the destructive-item stable strings from a migration
                    // plan computed at generation time (see SchemaRealizationEmitter#emit's 5-arg
                    // overload) -- empty when no plan was computed this generation pass (the ordinary
                    // case for every app until a future Build-NpdevApp.ps1 -PlanOnly/-Upgrade wires
                    // --schemaMigrationPlanOut through). Absent from every manifest emitted before
                    // this phase -- strings() defaults to an empty list, so pre-Phase-6 apps are
                    // unaffected (the agreement-check enrichment below simply has nothing to compare).
                    strings(root.path("migrationPlanItemStableStrings")),
                    // REG-7.1: absent from every manifest emitted before this field existed --
                    // asText("NpdevManaged") defaults to today's only behavior, so a pre-existing
                    // manifest is unaffected (same pattern as destructiveAcknowledgment above).
                    lifecycle.path("ownership").asText("NpdevManaged"),
                    // SER-G8: the model's declared FKs/indexes. Absent from every manifest emitted
                    // before G8 -- both helpers default to an empty map, so a pre-G8 app behaves exactly
                    // as it did (nothing to verify, nothing to diff).
                    foreignKeyListMap(root.path("businessTableForeignKeys")),
                    indexListMap(root.path("businessTableIndexes")),
                    // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the expression TEXT for every column
                    // businessTableExpressionDefaultColumns names. Absent from every manifest emitted
                    // before this field existed -- stringMapMap defaults to an empty map, so a
                    // pre-existing app is unaffected (nothing to preview/backfill).
                    stringMapMap(root.path("businessTableColumnDefaultExpressions"))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed loading schema realization manifest", exception);
        }
    }

    /** SER-G8: parses {@code {"table":[{"columns":[..],"referencedTable":"t","referencedColumns":[..]}]}}. */
    private static Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> foreignKeyListMap(JsonNode object) {
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> result = new LinkedHashMap<>();
        if (object == null || !object.isObject()) {
            return Map.copyOf(result);
        }
        object.fields().forEachRemaining(entry -> {
            List<SchemaLifecycleExecutor.ForeignKeyDecl> decls = new ArrayList<>();
            if (entry.getValue().isArray()) {
                for (JsonNode node : entry.getValue()) {
                    List<String> columns = strings(node.path("columns"));
                    String referencedTable = node.path("referencedTable").asText("");
                    if (columns.isEmpty() || referencedTable.isBlank()) {
                        continue;
                    }
                    decls.add(new SchemaLifecycleExecutor.ForeignKeyDecl(columns, referencedTable, strings(node.path("referencedColumns"))));
                }
            }
            if (!decls.isEmpty()) {
                result.put(entry.getKey(), List.copyOf(decls));
            }
        });
        return Map.copyOf(result);
    }

    /** SER-G8: parses {@code {"table":[{"columns":[..],"unique":true}]}}. */
    private static Map<String, List<SchemaLifecycleExecutor.IndexDecl>> indexListMap(JsonNode object) {
        Map<String, List<SchemaLifecycleExecutor.IndexDecl>> result = new LinkedHashMap<>();
        if (object == null || !object.isObject()) {
            return Map.copyOf(result);
        }
        object.fields().forEachRemaining(entry -> {
            List<SchemaLifecycleExecutor.IndexDecl> decls = new ArrayList<>();
            if (entry.getValue().isArray()) {
                for (JsonNode node : entry.getValue()) {
                    List<String> columns = strings(node.path("columns"));
                    if (!columns.isEmpty()) {
                        decls.add(new SchemaLifecycleExecutor.IndexDecl(columns, node.path("unique").asBoolean(false)));
                    }
                }
            }
            if (!decls.isEmpty()) {
                result.put(entry.getKey(), List.copyOf(decls));
            }
        });
        return Map.copyOf(result);
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> stringListMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), strings(field.getValue())));
        return Map.copyOf(out);
    }

    private static Map<String, Map<String, String>> stringMapMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), stringMap(field.getValue())));
        return Map.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), field.getValue().asText("")));
        return Map.copyOf(out);
    }

    /**
     * LNCH-1 P5 (5.1): table -> its declared unique constraints (name/columns/tenant-scoping), as
     * emitted by {@code SchemaRealizationEmitter#collectUniqueConstraints}.
     */
    private static Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> uniqueConstraintListMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            List<SchemaLifecycleExecutor.UniqueConstraintDecl> declarations = new ArrayList<>();
            for (JsonNode item : entry.getValue()) {
                String name = item.path("name").asText("");
                boolean tenantScoped = item.path("tenantScoped").asBoolean(true);
                List<String> columns = strings(item.path("columns"));
                if (!name.isBlank() && !columns.isEmpty()) {
                    declarations.add(new SchemaLifecycleExecutor.UniqueConstraintDecl(name, columns, tenantScoped));
                }
            }
            out.put(entry.getKey(), List.copyOf(declarations));
        });
        return Map.copyOf(out);
    }
}
