package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.dsl.v1.xref.SemanticGraphJson;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * P2.2 (Path A Phase 2): writes the semantic relationship graph into the generated app as
 * {@code npdev/semantic-graph.json} (contract {@code npdev-semantic-graph.v1}, schema
 * {@code schemas/ai/semantic-graph.schema.json}) -- the graph the Box Inspector (P6.4) and the AI
 * authoring path (P2.4, P7.1) both read to answer "what would I reuse" and "why does this exist".
 *
 * <p>Mirrors {@link XrefEmitter} exactly (same re-parse-plus-resolve shape, for the same reason: the
 * graph is defined over the AST, where the reference-bearing keys live, and {@link ModelResolver}
 * must run first so a specialized concept's inherited references are not misread as orphans). The
 * graph itself is {@link SemanticGraphJson}'s relabeling of {@link ReferenceIndex#edges()} -- the
 * SAME edges {@code XrefEmitter} emits, not a second traversal (Decision D1).
 */
public final class SemanticGraphEmitter {

    private static final String OUTPUT_PATH = "src/main/resources/npdev/semantic-graph.json";

    private final GeneratedSourceWriter writer;
    private final ObjectMapper objectMapper;

    public SemanticGraphEmitter(GeneratedSourceWriter writer) {
        this(writer, new ObjectMapper());
    }

    SemanticGraphEmitter(GeneratedSourceWriter writer, ObjectMapper objectMapper) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void emit(ResolvedModelSource resolvedModelSource, Path modelSourcePath) throws Exception {
        ModelAst parsed;
        if (resolvedModelSource != null) {
            parsed = new JsonModelParser().parse(resolvedModelSource);
        } else if (modelSourcePath != null && Files.exists(modelSourcePath)) {
            parsed = new JsonModelParser().parse(modelSourcePath);
        } else {
            // Same reasoning as XrefEmitter: no model source in scope means no honest graph to emit.
            return;
        }

        ModelAst effectiveModel = new ModelResolver().resolve(parsed).modelAst();
        String identifier = effectiveModel.getNamespace() == null ? "" : effectiveModel.getNamespace();
        writer.writeRelative(OUTPUT_PATH,
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(SemanticGraphJson.toJson(identifier,
                                ReferenceIndex.build(effectiveModel)))
                        + System.lineSeparator());
    }
}
