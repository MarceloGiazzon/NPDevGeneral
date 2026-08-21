package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSeed;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8.8 (Roadmap Wave 2, 2026-08-19): pack-declared seed data.
 *
 * <p>Covers the two compile-time invariants the roadmap item's "done when" names: (1) a pack seed
 * targeting a concept the SAME pack declares composes, is rewritten to the pack-qualified concept
 * name, and reaches {@link CompiledModel#getSeeds()}; (2) a pack seed naming a concept the pack
 * does NOT own is a compile error, thrown eagerly at pack-composition time
 * ({@code ModelSourceResolver.rewriteSeedConceptOwnership}) -- proven by asserting it fires even
 * before {@link com.npdev.dsl.v1.validation.SeedValidation} would ever see it. Also covers
 * {@code SeedValidation}'s own two checks (concept existence, cross-seed alias uniqueness), using
 * ROOT-declared seeds -- the one seed shape ownership enforcement never touches -- so those checks
 * are proven reachable rather than dead code.
 */
class SeedCompositionAndValidationTest {

    @TempDir
    Path temp;

    private static final String PACK_WITH_OWNED_SEED = """
            {
              "dslVersion": "1.0.0",
              "pack": "widgets",
              "version": "1.0.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "label", "type": "string" }
                ] }
              ],
              "seeds": [
                { "concept": "Widget", "alias": "defaultWidget", "data": { "label": "Default Widget" } }
              ]
            }
            """;

    private static final String PACK_WITH_UNOWNED_SEED = """
            {
              "dslVersion": "1.0.0",
              "pack": "widgets",
              "version": "1.0.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                ] }
              ],
              "seeds": [
                { "concept": "Gadget", "data": {} }
              ]
            }
            """;

    @Test
    void packSeedTargetingAConceptItOwnsComposesAndReachesTheCompiledModel() throws Exception {
        write("packs/widgets/pack.json", PACK_WITH_OWNED_SEED);
        Path model = write("model.json", """
                {
                  "namespace": "r88.owned.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/widgets/pack.json" }
                  ],
                  "concepts": [
                    { "name": "Anchor", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        JsonNode seeds = resolvedSource.resolvedRoot().get("seeds");
        assertTrue(seeds != null && seeds.isArray() && seeds.size() == 1, "resolved model must have one seed");
        assertEquals("widgets::Widget", seeds.get(0).get("concept").asText(),
                "a pack-owned seed's concept must be rewritten to pack-qualified form during composition");

        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected the composed model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        List<CompiledSeed> compiledSeeds = compiled.getSeeds();
        assertEquals(1, compiledSeeds.size());
        CompiledSeed seed = compiledSeeds.get(0);
        assertEquals("widgets::Widget", seed.concept());
        assertEquals("defaultWidget", seed.alias());
        assertEquals("Default Widget", seed.data().get("label"));
    }

    @Test
    void packSeedTargetingAConceptItDoesNotOwnFailsAtCompositionTime() throws Exception {
        write("packs/widgets/pack.json", PACK_WITH_UNOWNED_SEED);
        Path model = write("model.json", """
                {
                  "namespace": "r88.unowned.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/widgets/pack.json" }
                  ],
                  "concepts": [
                    { "name": "Anchor", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);

        ModelSourceResolver resolver = new ModelSourceResolver();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try {
                resolver.resolve(model);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String message = rootMessage(thrown);
        assertTrue(message.contains("does not own"), "Expected an ownership error, got: " + message);
        assertTrue(message.contains("widgets"), "Expected the error to name the declaring pack, got: " + message);
        assertTrue(message.contains("Gadget"), "Expected the error to name the unowned concept, got: " + message);
    }

    @Test
    void rootDeclaredSeedNamingAnUnknownConceptIsAValidationError() throws Exception {
        Path model = write("model.json", """
                {
                  "namespace": "r88.rootunknown.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" }
                    ] }
                  ],
                  "seeds": [
                    { "concept": "Ghost", "data": { "label": "nope" } }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown concept") && e.contains("Ghost")),
                "Expected an unknown-concept error naming Ghost, got: " + errors);
    }

    @Test
    void duplicateSeedAliasAcrossDifferentSeedsIsAValidationError() throws Exception {
        Path model = write("model.json", """
                {
                  "namespace": "r88.dupalias.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" }
                    ] }
                  ],
                  "seeds": [
                    { "concept": "Widget", "alias": "dup", "data": { "label": "A" } },
                    { "concept": "Widget", "alias": "dup", "data": { "label": "B" } }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate seed alias") && e.contains("dup")),
                "Expected a duplicate-alias error naming 'dup', got: " + errors);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
