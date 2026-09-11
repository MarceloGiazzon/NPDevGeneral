package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.resolution.ConceptLineage;
import com.npdev.dsl.v1.resolution.ModelResolver;
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

    private static String toJson(CompiledModel model, Map<String, ConceptLineage> lineageByConceptName) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-box-manifest.v1");
        root.put("boxes", boxes(model, lineageByConceptName));
        try {
            return OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize box manifest", exception);
        }
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
            box.put("truthLevel", "T2_GENERATED");
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
