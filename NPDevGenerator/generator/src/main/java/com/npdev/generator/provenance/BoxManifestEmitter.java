package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.resolution.ConceptLineage;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.packs.BuiltinPackComposer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emits {@code npdev/box/box-manifest.json}: one "Entity Box" entry per persisted concept, per the
 * Box/Object/Truth model — name, table, field/bond counts, whether it's an app-authored or built-in
 * pack ("admin") concept, and a truth level.
 *
 * <p>The truth level here is honestly bounded by what generation time can know: every concept that
 * compiled and got a real CREATE TABLE is at least T2 (Generated). It is NOT bumped to T3
 * (RunsLocally) here — only the running app itself can honestly claim that, the first time it
 * successfully answers a request (see {@code GeneratedBoxViewController}). T4+ (Tested,
 * EvidenceBacked, ReleaseApproved) require external evidence this generator has no way to observe,
 * so they are deliberately never claimed here or at runtime — surfacing "not yet evidenced" is the
 * truthful answer, not a guess.</p>
 *
 * <p>P3.3: when a model source is in scope, each box for a specialized concept also carries a
 * {@code specializes} entry -- {@link ConceptLineage}'s parent/version/inherits/adds/changes/removes
 * -- computed by re-parsing the model source the same "re-parse-plus-resolve" way {@code XrefEmitter}
 * and {@code SemanticGraphEmitter} do, since {@link CompiledModel} has already flattened away which
 * fields a specialization added versus inherited. The existing box shape is otherwise unchanged, so
 * the no-model-source overload (used by callers that only ever had a {@link CompiledModel}, e.g.
 * this class's own pre-P3.3 tests) still emits exactly what it always did.
 *
 * <p>P6.4 (Path A Phase 6, Box Inspector): the manifest is now a small HIERARCHY rather than a flat
 * concept list -- an {@code application} box, one {@code modules} entry per distinct
 * {@link CompiledConcept#getModule()} value, each concept box's own {@code fields} (Field Boxes) and
 * {@code rules} (Rule Boxes, from {@link CompiledConcept#getInvariants()} /
 * {@code getExpressionInvariants()}), and a top-level {@code capabilities} list (Capability Boxes,
 * each carrying its bound {@code implementation} from {@link CompiledModel#getBindings()} when one
 * exists). Every field/rule/capability box carries a {@code graphKind}/{@code graphName} pair --
 * {@link ReferenceIndex}'s node vocabulary -- so the Box Inspector UI can answer "why does this
 * exist" by querying {@code npdev/semantic-graph.json} for edges touching that node LIVE, at read
 * time, rather than baking a "why" string in here (the graph is the single source of that answer,
 * per Decision D1 -- this emitter never re-derives it).
 */
public final class BoxManifestEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/npdev/box/box-manifest.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void emit(CompiledModel model, GeneratedSourceWriter writer) {
        writer.writeRelative(RELATIVE_PATH, toJson(model, Map.of()));
    }

    public void emit(
            CompiledModel model,
            GeneratedSourceWriter writer,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath
    ) throws Exception {
        writer.writeRelative(RELATIVE_PATH, toJson(model, lineageByConceptName(resolvedModelSource, modelSourcePath)));
    }

    private static Map<String, ConceptLineage> lineageByConceptName(
            ResolvedModelSource resolvedModelSource, Path modelSourcePath
    ) throws Exception {
        ModelAst raw;
        if (resolvedModelSource != null) {
            raw = new JsonModelParser().parse(resolvedModelSource);
        } else if (modelSourcePath != null && Files.exists(modelSourcePath)) {
            raw = new JsonModelParser().parse(modelSourcePath);
        } else {
            // Same reasoning as XrefEmitter/SemanticGraphEmitter: no model source in scope means
            // no honest lineage to compute -- boxes are still emitted, just without a
            // "specializes" entry, rather than fabricating one.
            return Map.of();
        }
        return ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());
    }

    /** Concept boxes with no declared {@code module} are grouped under this Module Box name. */
    private static final String UNGROUPED_MODULE = "Ungrouped";

    /** Generation-time truth floor -- see the class doc and {@link BoxManifestEmitter} truth-level note. */
    private static final String TRUTH_LEVEL_GENERATED = "T2_GENERATED";

    private static String toJson(CompiledModel model, Map<String, ConceptLineage> lineageByConceptName) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-box-manifest.v2");
        List<Map<String, Object>> boxes = boxes(model, lineageByConceptName);
        root.put("application", applicationBox(model));
        root.put("modules", moduleBoxes(boxes));
        root.put("boxes", boxes);
        root.put("capabilities", capabilityBoxes(model));
        try {
            return OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize box manifest", exception);
        }
    }

    /** The Application Box: the outer product boundary every Module Box lives inside. */
    private static Map<String, Object> applicationBox(CompiledModel model) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("name", model == null || model.getNamespace() == null ? "" : model.getNamespace());
        box.put("truthLevel", TRUTH_LEVEL_GENERATED);
        return box;
    }

    /** One Module Box per distinct concept-declared module, derived from the boxes already built. */
    private static List<Map<String, Object>> moduleBoxes(List<Map<String, Object>> boxes) {
        Map<String, Integer> countByModule = new TreeMap<>();
        for (Map<String, Object> box : boxes) {
            String module = (String) box.get("module");
            countByModule.merge(module, 1, Integer::sum);
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countByModule.entrySet()) {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("name", entry.getKey());
            module.put("conceptCount", entry.getValue());
            module.put("truthLevel", TRUTH_LEVEL_GENERATED);
            modules.add(module);
        }
        return modules;
    }

    /**
     * Capability Boxes: every declared {@link CompiledCapability}, paired with its bound
     * {@link CompiledCapabilityBinding} (the Implementation) when the model binds one. An unbound
     * capability still gets a box -- "declared but not yet wired to an adapter" is itself an honest
     * truth-classification fact, not an error.
     */
    private static List<Map<String, Object>> capabilityBoxes(CompiledModel model) {
        if (model == null || model.getCapabilities() == null) {
            return List.of();
        }
        Map<String, String> adapterByCapability = new LinkedHashMap<>();
        for (CompiledCapabilityBinding binding : model.getBindings() == null ? List.<CompiledCapabilityBinding>of() : model.getBindings()) {
            if (binding != null && binding.getCapability() != null) {
                adapterByCapability.put(binding.getCapability(), binding.getAdapter());
            }
        }
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (CompiledCapability capability : model.getCapabilities()) {
            if (capability == null || capability.getName() == null) {
                continue;
            }
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("name", capability.getName());
            box.put("type", capability.getType());
            box.put("truthLevel", TRUTH_LEVEL_GENERATED);
            box.put("graphKind", ReferenceIndex.KIND_CAPABILITY);
            box.put("graphName", capability.getName());
            String adapter = adapterByCapability.get(capability.getName());
            if (adapter != null) {
                Map<String, Object> implementation = new LinkedHashMap<>();
                implementation.put("adapter", adapter);
                box.put("implementation", implementation);
            } else {
                box.put("implementation", null);
            }
            capabilities.add(box);
        }
        capabilities.sort(Comparator.comparing(box -> (String) box.get("name"), String.CASE_INSENSITIVE_ORDER));
        return capabilities;
    }

    /** Field Boxes for one concept: one per {@link CompiledField}, named for graph lookup as {@code Concept.field}. */
    private static List<Map<String, Object>> fieldBoxes(CompiledConcept concept) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            if (field == null || field.getName() == null) {
                continue;
            }
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("name", field.getName());
            box.put("dslType", field.getDslType());
            box.put("required", field.isRequired());
            box.put("id", field.isId());
            box.put("unique", field.isUnique());
            box.put("truthLevel", TRUTH_LEVEL_GENERATED);
            box.put("graphKind", ReferenceIndex.KIND_FIELD);
            box.put("graphName", concept.getName() + "." + field.getName());
            fields.add(box);
        }
        return fields;
    }

    /**
     * Rule Boxes for one concept: {@link CompiledInvariant}s (which carry a {@code ref} the semantic
     * graph's invariant nodes are named for) plus any free-form {@code expressionInvariants} (which
     * have no {@code ref} and so no graph node -- {@code graphName} is null, and the Inspector shows
     * "no graph edges recorded" rather than guessing one).
     */
    private static List<Map<String, Object>> ruleBoxes(CompiledConcept concept) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (CompiledInvariant invariant : concept.getInvariants() == null ? List.<CompiledInvariant>of() : concept.getInvariants()) {
            if (invariant == null) {
                continue;
            }
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("description", ruleDescription(invariant));
            box.put("truthLevel", TRUTH_LEVEL_GENERATED);
            box.put("graphKind", invariant.getRef() == null ? null : ReferenceIndex.KIND_INVARIANT);
            box.put("graphName", invariant.getRef());
            rules.add(box);
        }
        for (String expression : concept.getExpressionInvariants() == null ? List.<String>of() : concept.getExpressionInvariants()) {
            if (expression == null || expression.isBlank()) {
                continue;
            }
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("description", expression);
            box.put("truthLevel", TRUTH_LEVEL_GENERATED);
            box.put("graphKind", null);
            box.put("graphName", null);
            rules.add(box);
        }
        return rules;
    }

    private static String ruleDescription(CompiledInvariant invariant) {
        String type = invariant.getType() == null ? "invariant" : invariant.getType();
        String field = invariant.getField();
        String expression = invariant.getExpression();
        StringBuilder description = new StringBuilder(type);
        if (field != null && !field.isBlank()) {
            description.append(" on ").append(field);
        }
        if (expression != null && !expression.isBlank()) {
            description.append(": ").append(expression);
        }
        return description.toString();
    }

    private static List<Map<String, Object>> boxes(CompiledModel model, Map<String, ConceptLineage> lineageByConceptName) {
        if (model == null) {
            return List.of();
        }
        List<CompiledConcept> concepts = persistedConcepts(model);
        Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (CompiledConcept concept : concepts) {
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("conceptName", concept.getName());
            box.put("displayName", displayName(concept.getName()));
            box.put("table", concept.getTableName());
            box.put("fieldCount", concept.getFields().size());
            box.put("bondCount", bondCount(concept, conceptsByName));
            box.put("admin", isAdminConcept(concept));
            box.put("truthLevel", TRUTH_LEVEL_GENERATED);
            box.put("declaredTruthLevel", concept.getTruthLevel());
            box.put("module", concept.getModule() == null || concept.getModule().isBlank()
                    ? UNGROUPED_MODULE : concept.getModule());
            box.put("graphKind", ReferenceIndex.KIND_CONCEPT);
            box.put("graphName", concept.getName());
            box.put("fields", fieldBoxes(concept));
            box.put("rules", ruleBoxes(concept));
            ConceptLineage lineage = lineageByConceptName.get(concept.getName());
            if (lineage != null) {
                box.put("specializes", lineageJson(lineage));
            }
            boxes.add(box);
        }
        return boxes;
    }

    private static Map<String, Object> lineageJson(ConceptLineage lineage) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("parent", lineage.parent());
        json.put("parentVersion", lineage.parentVersion());
        json.put("parentDigest", lineage.parentDigest());
        json.put("inherits", lineage.inherits());
        json.put("adds", lineage.adds());
        json.put("changes", lineage.changes());
        json.put("removes", lineage.removes());
        return json;
    }

    private static int bondCount(CompiledConcept concept, Map<String, CompiledConcept> conceptsByName) {
        int count = 0;
        for (CompiledField field : concept.getFields()) {
            if (BondModelSupport.resolveBond(concept, field, conceptsByName).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private static List<CompiledConcept> persistedConcepts(CompiledModel model) {
        List<CompiledConcept> out = new ArrayList<>();
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null || concept.getName() == null || concept.getName().isBlank()) {
                continue;
            }
            if (concept.getTableName() == null || concept.getTableName().isBlank()) {
                continue;
            }
            out.add(concept);
        }
        out.sort(Comparator.comparing(CompiledConcept::getName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static String displayName(String conceptName) {
        int sep = conceptName.indexOf("::");
        if (sep < 0) {
            return conceptName;
        }
        return conceptName.substring(0, sep) + "::" + conceptName.substring(sep + 2);
    }

    /** Mirrors {@code BusinessUiEmitter.isAdminConcept}: true for concepts contributed by a built-in platform pack. */
    private static boolean isAdminConcept(CompiledConcept concept) {
        String name = concept == null ? null : concept.getName();
        if (name == null) {
            return false;
        }
        int sep = name.indexOf("::");
        if (sep < 0) {
            return false;
        }
        return BuiltinPackComposer.BUILTIN_PACK_ALIASES.contains(name.substring(0, sep));
    }
}
