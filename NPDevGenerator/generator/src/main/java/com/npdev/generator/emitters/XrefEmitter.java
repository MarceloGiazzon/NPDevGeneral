package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.dsl.v1.xref.ReferenceIndexJson;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * XREF-1: writes the model-wide reference index into the generated app as
 * {@code npdev/model-xref.json} (contract {@code npdev-model-xref.v1}, schema
 * {@code schemas/ai/model-xref.schema.json}).
 *
 * <p>Its own artifact rather than a section of {@code compiled-metadata.json}, for three measured
 * reasons: that file's {@code catalogs} has no contract schema at all,
 * {@code CompiledMetadataCanonicalJson} is already one of the repo's large files, and an independent
 * document is directly consumable by the CLI, the Monitor and the Editor without a running app.
 *
 * <p>Re-parses the model source rather than deriving the index from {@link
 * com.npdev.dsl.v1.compiled.CompiledModel}: the index is defined over the AST (that is where the
 * reference-bearing keys live -- a compiled model has already collapsed several of them), and
 * {@code PluginRequirementAssetEmitter} established the same re-parse pattern for the same reason.
 * The parse is deterministic, which matters because
 * {@code scripts/hygiene/check-deterministic-generation.ps1} SHA-256s this file across two
 * generation runs.
 *
 * <p>{@link ModelResolver} runs before indexing, exactly as {@code SemanticValidator} does. Skipping
 * it would index the raw parse, reporting every specialized concept's inherited references as
 * orphans.
 */
public final class XrefEmitter {

    private static final String OUTPUT_PATH = "src/main/resources/npdev/model-xref.json";

    private final GeneratedSourceWriter writer;
    private final ObjectMapper objectMapper;

    public XrefEmitter(GeneratedSourceWriter writer) {
        this(writer, new ObjectMapper());
    }

    XrefEmitter(GeneratedSourceWriter writer, ObjectMapper objectMapper) {
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
            // No model source in scope (a caller that handed the facade a pre-compiled model
            // only). Emitting an empty index would be worse than emitting nothing: an empty
            // `edges` array is indistinguishable from a model that genuinely references nothing.
            return;
        }

        ModelAst effectiveModel = new ModelResolver().resolve(parsed).modelAst();
        String identifier = effectiveModel.getNamespace() == null ? "" : effectiveModel.getNamespace();
        writer.writeRelative(OUTPUT_PATH,
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ReferenceIndexJson.toJson(identifier,
                                ReferenceIndex.build(effectiveModel)))
                        + System.lineSeparator());
    }
}
