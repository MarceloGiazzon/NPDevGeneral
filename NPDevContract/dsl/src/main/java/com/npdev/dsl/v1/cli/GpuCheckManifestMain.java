package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.gpucheck.GpuCheckManifestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * GPU-1 (G2.5): entry point {@code npdev gpu-check plan} shells out to for a pre-flight sweep
 * (G6) -- builds the GPU check manifest + WGSL shaders for ANY canonical model, with no app
 * regeneration needed. Args: {@code --canonical <compiled-model.json> --out <dir>}; writes
 * {@code <dir>/manifest.json} + {@code <dir>/<pack>.wgsl}. Exit 0 on success, 1 on failure (the
 * message goes to stderr, matching every other {@code *Main} entry point in this package).
 */
public final class GpuCheckManifestMain {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private GpuCheckManifestMain() {
    }

    public static void main(String[] args) {
        String canonicalPath = null;
        String outDir = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--canonical".equals(arg) && i + 1 < args.length) {
                canonicalPath = args[++i];
            } else if ("--out".equals(arg) && i + 1 < args.length) {
                outDir = args[++i];
            }
        }
        if (canonicalPath == null || outDir == null) {
            System.err.println("usage: GpuCheckManifestMain --canonical <compiled-model.json> --out <dir>");
            System.exit(1);
            return;
        }
        try {
            run(Path.of(canonicalPath), Path.of(outDir));
            System.exit(0);
        } catch (Exception e) {
            System.err.println("gpu-check manifest build failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Path canonicalPath, Path outDir) throws IOException {
        CompiledModel model = CompiledModelCanonicalJsonReader.read(canonicalPath);
        Map<String, Object> manifest = GpuCheckManifestBuilder.build(model);
        Map<String, String> shaders = GpuCheckManifestBuilder.shaders(manifest);

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("manifest.json"), MAPPER.writeValueAsString(manifest) + "\n",
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> shader : shaders.entrySet()) {
            Files.writeString(outDir.resolve(shader.getKey()), shader.getValue(), StandardCharsets.UTF_8);
        }
        System.out.println("Wrote " + outDir.resolve("manifest.json") + " (" + shaders.size() + " shader(s))");
    }
}
