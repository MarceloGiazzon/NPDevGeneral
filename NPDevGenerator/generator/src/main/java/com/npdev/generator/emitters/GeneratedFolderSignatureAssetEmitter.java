package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.nio.file.Path;
import java.util.Objects;

public final class GeneratedFolderSignatureAssetEmitter {

    private static final String OUTPUT_PATH = "src/main/resources/npdev/generated-folder-signature.json";
    private static final String SIGNATURE_VERSION = "1.0.0";

    private final GeneratedSourceWriter writer;
    private final ObjectMapper objectMapper;

    public GeneratedFolderSignatureAssetEmitter(GeneratedSourceWriter writer) {
        this(writer, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    GeneratedFolderSignatureAssetEmitter(GeneratedSourceWriter writer, ObjectMapper objectMapper) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void emit(CompiledModel model, Path modelSourcePath) throws Exception {
        Objects.requireNonNull(model, "model");

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("signatureVersion", SIGNATURE_VERSION);
        root.put("generatorComponent", "GeneratedFolderSignatureAssetEmitter");
        root.put("generatedFrom", modelSourcePath == null ? "" : modelSourcePath.toString().replace('\\', '/'));
        root.put("signatureScope", "generated-folder");
        writer.writeRelative(OUTPUT_PATH, objectMapper.writeValueAsString(root) + System.lineSeparator());
    }
}
