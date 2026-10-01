package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.gpucheck.GpuCheckManifestBuilder;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * GPU-1 (G2.4): emits {@code src/main/resources/npdev/gpu-checks/manifest.json} +
 * {@code <pack>.wgsl} into the app, when {@code checks.gpuArtifacts} is on. Pure write: all the
 * actual translation lives in {@link GpuCheckManifestBuilder} (dsl) -- this class is only the
 * I/O seam between that pure function and {@link GeneratedSourceWriter}, same role
 * {@link ModelAuthoringEmitter} plays for its own page.
 */
public final class GpuCheckEmitter extends AbstractEmitter {

    public static final String MANIFEST_RELATIVE_PATH = "src/main/resources/npdev/gpu-checks/manifest.json";
    public static final String SHADER_RELATIVE_DIR = "src/main/resources/npdev/gpu-checks/";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public GpuCheckEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        Map<String, Object> manifest = GpuCheckManifestBuilder.build(model);
        Map<String, String> shaders = GpuCheckManifestBuilder.shaders(manifest);
        writer.writeRelative(MANIFEST_RELATIVE_PATH, toPrettyJson(manifest));
        for (Map.Entry<String, String> shader : shaders.entrySet()) {
            writer.writeRelative(SHADER_RELATIVE_DIR + shader.getKey(), shader.getValue());
        }
    }

    private static String toPrettyJson(Map<String, Object> manifest) {
        try {
            return MAPPER.writeValueAsString(manifest) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Failed serializing GPU check manifest", e);
        }
    }
}
