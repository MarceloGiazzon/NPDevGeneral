package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirement;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirementGraph;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirementGraphBuilder;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PluginRequirementAssetEmitter {

    private static final String OUTPUT_PATH = "src/main/resources/npdev/plugins/generated.plugin-requirements.json";

    private final GeneratedSourceWriter writer;
    private final ObjectMapper objectMapper;

    public PluginRequirementAssetEmitter(GeneratedSourceWriter writer) {
        this(writer, new ObjectMapper());
    }

    PluginRequirementAssetEmitter(GeneratedSourceWriter writer, ObjectMapper objectMapper) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void emit(Path modelSourcePath) throws Exception {
        emit(null, modelSourcePath);
    }

    public void emit(ResolvedModelSource resolvedModelSource, Path modelSourcePath) throws Exception {
        if (modelSourcePath == null || !Files.exists(modelSourcePath)) {
            if (resolvedModelSource == null) {
                return;
            }
        }

        ModelAst modelAst;
        if (resolvedModelSource != null) {
            modelAst = new JsonModelParser().parse(resolvedModelSource);
        } else {
            modelAst = new JsonModelParser().parse(modelSourcePath);
        }
        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(modelAst);
        writer.writeRelative(OUTPUT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(graph)) + System.lineSeparator());
    }

    public void emit(ModelAst modelAst) throws Exception {
        if (modelAst == null) {
            return;
        }
        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(modelAst);
        writer.writeRelative(OUTPUT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(graph)) + System.lineSeparator());
    }

    private static Map<String, Object> toJson(CompiledPluginRequirementGraph graph) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pluginRequirementsVersion", "1.0");

        List<Map<String, Object>> requirements = new ArrayList<>();
        for (CompiledPluginRequirement requirement : graph.getRequirements()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capability", requirement.capabilityName());
            item.put("capabilityType", requirement.capabilityType());
            item.put("operation", requirement.operationName());
            item.put("flow", requirement.flowName());
            item.put("step", requirement.stepName());
            item.put("bindingAdapter", requirement.boundAdapter());
            item.put("externalCandidate", requirement.externalCandidate());
            requirements.add(item);
        }
        root.put("requirements", requirements);
        return root;
    }
}
