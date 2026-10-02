package com.npdev.dsl.v1.gpucheck;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.dsl.v1.expr.ComputedExpression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GPU-1 (G2.1/G2.2): pure functions turning a {@link CompiledModel} into the GPU check manifest
 * (shape: {@code gpu-check-manifest.schema.json}) and, from that manifest, the WGSL shader text for
 * every pack. No I/O here -- {@code GpuCheckEmitter} (generator) and {@code GpuCheckManifestMain}
 * (CLI, G2.5) own writing the files.
 *
 * <p>Every field-level rule (required/min/max/minLength/maxLength/enum) is SYNTHESIZED directly
 * into a portable tree here (internal {@code __}-prefixed calls, never user-visible) -- field rules
 * and invariants then share exactly one translator ({@link GpuWgslEmitter}, and in G3 its numpy
 * counterpart). An invariant's own expression goes through
 * {@link ComputedExpression#toPortableTree} (G1) unchanged.
 */
public final class GpuCheckManifestBuilder {

    private static final int MAX_CHECKS_PER_PACK = 32;
    private static final int MAX_COLUMNS_PER_PACK = 31;

    private GpuCheckManifestBuilder() {
    }

    public static Map<String, Object> build(CompiledModel model) {
        List<CompiledConcept> concepts = new ArrayList<>(model.getConcepts());
        concepts.sort(Comparator.comparing(CompiledConcept::getName, String.CASE_INSENSITIVE_ORDER));

        List<Object> conceptEntries = new ArrayList<>();
        for (CompiledConcept concept : concepts) {
            if (concept.getTableName() == null || concept.getTableName().isBlank()) {
                continue;
            }
            Map<String, Object> entry = buildConcept(model, concept);
            if (entry != null) {
                conceptEntries.add(entry);
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "npdev-gpu-check-manifest.v1");
        manifest.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        manifest.put("concepts", conceptEntries);
        return manifest;
    }

    /** Regenerates WGSL shader text (file name -> text) purely from an already-built manifest's own
     *  pack columns + check trees -- no {@link CompiledModel} needed, which is what lets
     *  {@code GpuCheckManifestMain} (G2.5) work from a canonical-JSON-only model for pre-flight. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> shaders(Map<String, Object> manifest) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Object> concepts = (List<Object>) manifest.get("concepts");
        if (concepts == null) {
            return out;
        }
        for (Object conceptObj : concepts) {
            Map<String, Object> concept = (Map<String, Object>) conceptObj;
            List<Object> packs = (List<Object>) concept.get("packs");
            for (Object packObj : packs) {
                Map<String, Object> pack = (Map<String, Object>) packObj;
                String shaderFile = String.valueOf(pack.get("shader"));
                List<Object> columnsJson = (List<Object>) pack.get("columns");
                List<GpuManifestColumn> columns = new ArrayList<>();
                int packScale = 0;
                for (Object colObj : columnsJson) {
                    Map<String, Object> col = (Map<String, Object>) colObj;
                    Integer scale = asInt(col.get("scale"));
                    if (scale != null) {
                        packScale = Math.max(packScale, scale);
                    }
                    columns.add(new GpuManifestColumn(
                            asInt(col.get("word")), asInt(col.get("nullBit")),
                            String.valueOf(col.get("field")), String.valueOf(col.get("column")),
                            String.valueOf(col.get("encoding")), scale,
                            (List<String>) col.get("enumValues")));
                }
                GpuWgslEmitter emitter = new GpuWgslEmitter(columns, packScale);
                List<Object> checks = (List<Object>) pack.get("checks");
                List<String> lines = new ArrayList<>();
                for (Object checkObj : checks) {
                    Map<String, Object> check = (Map<String, Object>) checkObj;
                    int bit = asInt(check.get("bit"));
                    String id = String.valueOf(check.get("id"));
                    String message = String.valueOf(check.get("message"));
                    Map<String, Object> tree = (Map<String, Object>) check.get("tree");
                    String expr = emitter.booleanExpr(tree);
                    lines.add("    // [" + bit + "] " + id + " -- " + message + "\n"
                            + "    if (!(" + expr + ")) { f = f | " + (1 << bit) + "u; }\n");
                }
                out.put(shaderFile, buildShaderText(String.valueOf(concept.get("concept")), checks.size(), lines));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- one concept

    private record CandidateCheck(String id, String kind, List<String> fields, String message, String source,
            Map<String, Object> tree) {
    }

    private static final class PackBuilder {
        final List<String> columnOrder = new ArrayList<>();
        final List<CandidateCheck> checks = new ArrayList<>();
    }

    private static Map<String, Object> buildConcept(CompiledModel model, CompiledConcept concept) {
        CompiledField idField = concept.getFields().stream().filter(CompiledField::isId).findFirst().orElse(null);
        if (idField == null) {
            return null;
        }
        String idColumn = SqlIdentifierSupport.columnName(idField);

        Map<String, GpuColumnInfo> allColumns = new LinkedHashMap<>();
        Map<String, CompiledField> fieldByName = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            if (field.isId()) {
                continue;
            }
            fieldByName.put(field.getName(), field);
            GpuColumnInfo info = encodingFor(field);
            if (info != null) {
                allColumns.put(field.getName(), info);
            }
        }

        List<CandidateCheck> candidates = new ArrayList<>();
        List<Map<String, Object>> hostChecks = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (CompiledField field : concept.getFields()) {
            if (field.isId()) {
                continue;
            }
            collectFieldRules(model, concept, field, allColumns, candidates, hostChecks, skipped);
        }
        for (CompiledInvariant invariant : concept.getInvariants()) {
            collectInvariant(concept, invariant, fieldByName, allColumns, candidates, hostChecks, skipped);
        }

        if (candidates.isEmpty() && hostChecks.isEmpty() && skipped.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparing(CandidateCheck::id, String.CASE_INSENSITIVE_ORDER));
        List<Object> packs = assignPacks(concept, candidates, allColumns, fieldByName, skipped);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("concept", concept.getName());
        entry.put("table", concept.getTableName());
        entry.put("idColumn", idColumn);
        entry.put("packs", packs);
        entry.put("hostChecks", hostChecks);
        entry.put("skipped", skipped);
        return entry;
    }

    private static void collectFieldRules(CompiledModel model, CompiledConcept concept, CompiledField field,
            Map<String, GpuColumnInfo> allColumns, List<CandidateCheck> candidates,
            List<Map<String, Object>> hostChecks, List<Map<String, Object>> skipped) {
        String fname = field.getName();
        String conceptName = concept.getName();
        CompiledSchema schema = field.getSchema();
        boolean packable = allColumns.containsKey(fname);

        if (field.isRequired()) {
            String id = conceptName + "." + fname + ".required";
            if (packable) {
                candidates.add(new CandidateCheck(id, "required", List.of(fname),
                        conceptName + "." + fname + " is required", null, notNullTree(fname)));
            } else {
                hostChecks.add(hostCheckEntry(id, "requiredOpaque", List.of(fname),
                        List.of(SqlIdentifierSupport.columnName(field)),
                        conceptName + "." + fname + " is required (" + (field.getDslType() == null ? "" : field.getDslType())
                                + ": checked on the host from the null mask only)", null, null, null));
            }
        }
        if (schema != null && schema.getMin() != null) {
            String id = conceptName + "." + fname + ".min";
            if (packable) {
                String lit = bigDecimalPlain(schema.getMin());
                candidates.add(new CandidateCheck(id, "min", List.of(fname),
                        conceptName + "." + fname + " must be >= " + lit, null,
                        nullOrTree(fname, binTree(">=", varTree(fname), litTree(lit)))));
            } else {
                skipped.add(skippedEntry(id, "min", null, "field '" + fname + "' has no GPU encoding"));
            }
        }
        if (schema != null && schema.getMax() != null) {
            String id = conceptName + "." + fname + ".max";
            if (packable) {
                String lit = bigDecimalPlain(schema.getMax());
                candidates.add(new CandidateCheck(id, "max", List.of(fname),
                        conceptName + "." + fname + " must be <= " + lit, null,
                        nullOrTree(fname, binTree("<=", varTree(fname), litTree(lit)))));
            } else {
                skipped.add(skippedEntry(id, "max", null, "field '" + fname + "' has no GPU encoding"));
            }
        }
        if (schema != null && schema.getMinLength() != null) {
            String id = conceptName + "." + fname + ".minLength";
            if (packable) {
                String lit = String.valueOf(schema.getMinLength());
                candidates.add(new CandidateCheck(id, "minLength", List.of(fname),
                        conceptName + "." + fname + " must be at least " + lit + " characters", null,
                        nullOrTree(fname, binTree(">=", lenTree(fname), litTree(lit)))));
            } else {
                skipped.add(skippedEntry(id, "minLength", null, "field '" + fname + "' has no GPU encoding"));
            }
        }
        if (schema != null && schema.getMaxLength() != null) {
            String id = conceptName + "." + fname + ".maxLength";
            if (packable) {
                String lit = String.valueOf(schema.getMaxLength());
                candidates.add(new CandidateCheck(id, "maxLength", List.of(fname),
                        conceptName + "." + fname + " must be at most " + lit + " characters", null,
                        nullOrTree(fname, binTree("<=", lenTree(fname), litTree(lit)))));
            } else {
                skipped.add(skippedEntry(id, "maxLength", null, "field '" + fname + "' has no GPU encoding"));
            }
        }
        if ("enum".equalsIgnoreCase(field.getDslType()) && field.getEnumValues() != null
                && !field.getEnumValues().isEmpty() && packable) {
            String id = conceptName + "." + fname + ".enum";
            candidates.add(new CandidateCheck(id, "enum", List.of(fname),
                    conceptName + "." + fname + " must be one of " + String.join(", ", field.getEnumValues()),
                    null, nullOrTree(fname, inEnumTree(fname))));
        }
        if (field.isUnique()) {
            hostChecks.add(hostCheckEntry(conceptName + "." + fname + ".unique", "unique", List.of(fname),
                    List.of(SqlIdentifierSupport.columnName(field)), conceptName + "." + fname + " must be unique",
                    null, null, null));
        }
        if ("reference".equalsIgnoreCase(field.getDslType()) && field.getReferenceTarget() != null
                && !field.getReferenceTarget().isBlank()) {
            model.findConcept(field.getReferenceTarget()).ifPresent(target -> {
                CompiledField targetId = target.getFields().stream().filter(CompiledField::isId).findFirst().orElse(null);
                if (targetId != null) {
                    hostChecks.add(hostCheckEntry(conceptName + "." + fname + ".reference", "reference", List.of(fname),
                            List.of(SqlIdentifierSupport.columnName(field)),
                            conceptName + "." + fname + " must reference an existing " + field.getReferenceTarget(),
                            field.getReferenceTarget(), target.getTableName(), SqlIdentifierSupport.columnName(targetId)));
                }
            });
        }
    }

    private static void collectInvariant(CompiledConcept concept, CompiledInvariant invariant,
            Map<String, CompiledField> fieldByName, Map<String, GpuColumnInfo> allColumns,
            List<CandidateCheck> candidates, List<Map<String, Object>> hostChecks, List<Map<String, Object>> skipped) {
        if ("required".equalsIgnoreCase(invariant.getType())) {
            // ModelCompiler synthesizes one of these per required field (ref "required(<field>)")
            // purely for its own cross-reference bookkeeping -- collectFieldRules already emits the
            // real packed/host check for field.isRequired() directly, so this is a pure duplicate,
            // not a genuinely skipped check. Reporting it would be noise, not signal.
            return;
        }
        String conceptName = concept.getName();
        String id = conceptName + ".inv." + invariant.getRef();
        if ("unique".equalsIgnoreCase(invariant.getType())) {
            List<String> fields = invariant.getFields();
            List<String> columns = new ArrayList<>();
            for (String f : fields) {
                CompiledField field = fieldByName.get(f);
                if (field != null) {
                    columns.add(SqlIdentifierSupport.columnName(field));
                }
            }
            hostChecks.add(hostCheckEntry(id, "unique", fields, columns,
                    "Invariant " + invariant.getRef() + ": fields (" + String.join(", ", fields)
                            + ") must be unique together", null, null, null));
            return;
        }
        String expr = invariant.getExpression();
        if (expr == null || expr.isBlank()) {
            skipped.add(skippedEntry(id, "invariant", null,
                    "invariant has no expression and is not a unique rule"));
            return;
        }
        try {
            Map<String, Object> tree = ComputedExpression.toPortableTree(expr);
            // CompiledInvariant.getFields() is only ever populated for the "unique(...)" shorthand --
            // ModelCompiler registers every "expression" invariant with fields=null (always []), so
            // the var names actually read by the tree are the only honest source for this.
            List<String> fields = new ArrayList<>(collectVarNames(tree));
            if (fields.size() > MAX_COLUMNS_PER_PACK) {
                throw new GpuUnsupportedException("touches more than " + MAX_COLUMNS_PER_PACK + " fields");
            }
            int scale = GpuCheckSupport.analyze(tree, allColumns);
            // analyze() does not catch every shape the emitter refuses (a string compared with
            // anything but ''), and shaders() emits all packs in one pass -- so a refused check that
            // got packed would fail EVERY shader of the model, not just its own. Emit it once here.
            List<GpuManifestColumn> trialColumns = new ArrayList<>();
            int word = 1;
            for (String f : fields) {
                GpuColumnInfo info = allColumns.get(f);
                trialColumns.add(new GpuManifestColumn(word, word - 1, f, f, info.encoding(), info.scale(),
                        info.enumValues()));
                word++;
            }
            new GpuWgslEmitter(trialColumns, scale).booleanExpr(tree);
            candidates.add(new CandidateCheck(id, "invariant", fields,
                    "Invariant " + invariant.getRef() + ": " + expr, expr, tree));
        } catch (RuntimeException e) {
            skipped.add(skippedEntry(id, "invariant", expr, e.getMessage()));
        }
    }

    private static List<Object> assignPacks(CompiledConcept concept, List<CandidateCheck> candidates,
            Map<String, GpuColumnInfo> allColumns, Map<String, CompiledField> fieldByName,
            List<Map<String, Object>> skipped) {
        List<PackBuilder> builders = new ArrayList<>();
        for (CandidateCheck candidate : candidates) {
            Set<String> needed = collectVarNames(candidate.tree());
            PackBuilder target = null;
            for (PackBuilder pb : builders) {
                if (pb.checks.size() >= MAX_CHECKS_PER_PACK) {
                    continue;
                }
                Set<String> union = new LinkedHashSet<>(pb.columnOrder);
                union.addAll(needed);
                if (union.size() <= MAX_COLUMNS_PER_PACK) {
                    target = pb;
                    break;
                }
            }
            if (target == null) {
                target = new PackBuilder();
                builders.add(target);
            }
            for (String f : needed) {
                if (!target.columnOrder.contains(f)) {
                    target.columnOrder.add(f);
                }
            }
            target.checks.add(candidate);
        }

        List<Object> packs = new ArrayList<>();
        String conceptSlug = fileSafe(concept.getName());
        int packIndex = 0;
        for (PackBuilder pb : builders) {
            String packId = conceptSlug + "-p" + packIndex;
            packIndex++;

            int packScale = 0;
            for (String f : pb.columnOrder) {
                GpuColumnInfo info = allColumns.get(f);
                if (info != null && "fixed32".equals(info.encoding())) {
                    packScale = Math.max(packScale, info.scale());
                }
            }

            List<Object> columns = new ArrayList<>();
            int word = 1;
            for (String f : pb.columnOrder) {
                GpuColumnInfo info = allColumns.get(f);
                CompiledField field = fieldByName.get(f);
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("word", word);
                col.put("nullBit", word - 1);
                col.put("field", f);
                col.put("column", field == null ? f : SqlIdentifierSupport.columnName(field));
                col.put("encoding", info.encoding());
                if ("fixed32".equals(info.encoding())) {
                    col.put("scale", packScale);
                }
                if ("enum_index".equals(info.encoding())) {
                    col.put("enumValues", info.enumValues());
                }
                columns.add(col);
                word++;
            }

            List<Object> checks = new ArrayList<>();
            int bit = 0;
            for (CandidateCheck c : pb.checks) {
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("bit", bit);
                check.put("id", c.id());
                check.put("kind", c.kind());
                check.put("fields", c.fields());
                check.put("message", c.message());
                if (c.source() != null) {
                    check.put("source", c.source());
                }
                check.put("tree", c.tree());
                checks.add(check);
                bit++;
            }

            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("packId", packId);
            pack.put("shader", packId + ".wgsl");
            pack.put("strideWords", pb.columnOrder.size() + 1);
            pack.put("columns", columns);
            pack.put("checks", checks);
            packs.add(pack);
        }
        return packs;
    }

    // ---------------------------------------------------------------- helpers

    private static GpuColumnInfo encodingFor(CompiledField field) {
        String type = field.getDslType() == null ? "" : field.getDslType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "boolean" -> new GpuColumnInfo("bool", 0, List.of());
            case "int", "integer" -> new GpuColumnInfo("i32", 0, List.of());
            // The column's real scale (SqlTypeSupport's D1 default 4 when undeclared), not 0: an
            // undeclared-scale decimal stores "120.5000", which never fits scale 0, so every such
            // pack fell back unchecked (found live on Pigmentampas' Order.totalAmount, G4).
            case "decimal" -> new GpuColumnInfo("fixed32", SqlTypeSupport.decimalScale(field), List.of());
            case "enum" -> new GpuColumnInfo("enum_index", 0,
                    field.getEnumValues() == null ? List.of() : field.getEnumValues());
            case "string" -> new GpuColumnInfo("str_len", 0, List.of());
            default -> null;
        };
    }

    private static String bigDecimalPlain(double d) {
        BigDecimal bd = BigDecimal.valueOf(d).stripTrailingZeros();
        if (bd.scale() < 0) {
            bd = bd.setScale(0);
        }
        return bd.toPlainString();
    }

    private static String fileSafe(String conceptName) {
        return conceptName.replace("::", "__");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectVarNames(Map<String, Object> tree) {
        Set<String> out = new LinkedHashSet<>();
        collectVarNamesInto(tree, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectVarNamesInto(Map<String, Object> node, Set<String> out) {
        String k = String.valueOf(node.get("k"));
        switch (k) {
            case "var" -> out.add(String.valueOf(node.get("name")));
            case "un" -> collectVarNamesInto((Map<String, Object>) node.get("a"), out);
            case "bin" -> {
                collectVarNamesInto((Map<String, Object>) node.get("l"), out);
                collectVarNamesInto((Map<String, Object>) node.get("r"), out);
            }
            case "call" -> {
                for (Object arg : (List<Object>) node.get("args")) {
                    collectVarNamesInto((Map<String, Object>) arg, out);
                }
            }
            default -> {
                // lit, lambda -- no field references
            }
        }
    }

    private static Map<String, Object> varTree(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "var");
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> litTree(String decimalValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "lit");
        m.put("t", "num");
        m.put("v", decimalValue);
        return m;
    }

    private static Map<String, Object> callTree(String name, Map<String, Object>... args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "call");
        m.put("name", name);
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> arg : args) {
            list.add(arg);
        }
        m.put("args", list);
        return m;
    }

    private static Map<String, Object> binTree(String op, Map<String, Object> l, Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "bin");
        m.put("op", op);
        m.put("l", l);
        m.put("r", r);
        return m;
    }

    private static Map<String, Object> notNullTree(String field) {
        return callTree("__notNull", varTree(field));
    }

    private static Map<String, Object> nullOrTree(String field, Map<String, Object> boolExpr) {
        return callTree("__nullOr", varTree(field), boolExpr);
    }

    private static Map<String, Object> lenTree(String field) {
        return callTree("__len", varTree(field));
    }

    private static Map<String, Object> inEnumTree(String field) {
        return callTree("__inEnum", varTree(field));
    }

    private static Map<String, Object> hostCheckEntry(String id, String kind, List<String> fields,
            List<String> columns, String message, String targetConcept, String targetTable, String targetIdColumn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("fields", fields);
        m.put("columns", columns);
        m.put("message", message);
        if (targetConcept != null) {
            m.put("targetConcept", targetConcept);
        }
        if (targetTable != null) {
            m.put("targetTable", targetTable);
        }
        if (targetIdColumn != null) {
            m.put("targetIdColumn", targetIdColumn);
        }
        return m;
    }

    private static Map<String, Object> skippedEntry(String id, String kind, String source, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        if (source != null) {
            m.put("source", source);
        }
        m.put("reason", reason);
        return m;
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static String buildShaderText(String concept, int checkCount, List<String> checkLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("// npdev gpu-check: generated by NPDev, do not edit. Concept ").append(concept)
                .append(", ").append(checkCount).append(" checks.\n");
        sb.append("@group(0) @binding(0) var<storage, read> data : array<u32>;\n");
        sb.append("@group(0) @binding(1) var<storage, read_write> fails : array<u32>;\n");
        sb.append("@group(0) @binding(2) var<uniform> params : vec4<u32>;\n");
        sb.append("fn isNull(base : u32, bit : u32) -> bool { return (data[base] & (1u << bit)) != 0u; }\n");
        sb.append("fn i(base : u32, word : u32) -> i32 { return bitcast<i32>(data[base + word]); }\n");
        sb.append("fn u(base : u32, word : u32) -> u32 { return data[base + word]; }\n");
        sb.append("@compute @workgroup_size(64)\n");
        sb.append("fn main(@builtin(global_invocation_id) gid : vec3<u32>) {\n");
        sb.append("    let row = gid.x + gid.y * (65535u * 64u);\n");
        sb.append("    if (row >= params.x) { return; }\n");
        sb.append("    let base = row * params.y;\n");
        sb.append("    var f : u32 = 0u;\n");
        for (String line : checkLines) {
            sb.append(line);
        }
        sb.append("    fails[row] = f;\n");
        sb.append("}\n");
        return sb.toString();
    }
}
