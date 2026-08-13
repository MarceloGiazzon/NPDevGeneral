package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledDomainType;
import com.npdev.dsl.v1.compiled.CompiledModel;
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
 * PK-1 (PACK-ROADMAP.md card PK-1, MASTER-ROADMAP.md Step 4): before this card,
 * {@code mergeQualifiedNonConceptArrays} skipped concepts and threw a case-insensitive duplicate
 * error the moment two packs each contributed a non-concept member with the same bare name -- so
 * two packs could never both declare a {@code domainType} named {@code Email}. That was the RED:
 * composing the two throwaway packs below with the pre-PK-1 resolver throws
 * "Pack 'packb' contributes duplicate domainTypes member 'Email'". After PK-1, every non-concept
 * kind is namespaced exactly like concepts already were, and an unqualified reference resolves
 * automatically when exactly one composed pack provides it -- or fails, naming every candidate,
 * when more than one does.
 */
class PackNonConceptNamespacingTest {

    @TempDir
    Path temp;

    private static final String PACK_A = """
            {
              "dslVersion": "1.0.0",
              "pack": "packa",
              "version": "1.0.0",
              "domainTypes": [
                { "name": "Email", "baseType": "string", "format": "packa-email" }
              ]
            }
            """;

    private static final String PACK_B = """
            {
              "dslVersion": "1.0.0",
              "pack": "packb",
              "version": "1.0.0",
              "domainTypes": [
                { "name": "Email", "baseType": "string", "format": "packb-email" }
              ]
            }
            """;

    @Test
    void twoPacksDeclaringTheSameDomainTypeNameComposeWithoutCollision() throws Exception {
        write("packs/packa/pack.json", PACK_A);
        write("packs/packb/pack.json", PACK_B);
        Path model = write("model.json", """
                {
                  "namespace": "pk1.namespacing.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/packa/pack.json" },
                    { "$ref": "packs/packb/pack.json" }
                  ],
                  "concepts": [
                    { "name": "Anchor", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        JsonNode domainTypes = resolvedSource.resolvedRoot().get("domainTypes");
        assertTrue(domainTypes != null && domainTypes.isArray(), "resolved model must have a domainTypes array");
        List<String> names = List.of(domainTypes.get(0).get("name").asText(), domainTypes.get(1).get("name").asText());
        assertEquals(List.of("packa::Email", "packb::Email"), names);

        // End to end through the real DSL, not just JSON-level resolution.
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected the composed model to validate, got: " + errors);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        List<CompiledDomainType> compiledDomainTypes = compiled.getDomainTypes();
        assertTrue(compiledDomainTypes.stream().anyMatch(d -> "packa::Email".equals(d.getName())));
        assertTrue(compiledDomainTypes.stream().anyMatch(d -> "packb::Email".equals(d.getName())));
    }

    @Test
    void unqualifiedReferenceToAnAmbiguousBareNameFailsNamingBothCandidates() throws Exception {
        write("packs/packa/pack.json", PACK_A);
        write("packs/packb/pack.json", PACK_B);
        Path model = write("model.json", """
                {
                  "namespace": "pk1.ambiguity.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/packa/pack.json" },
                    { "$ref": "packs/packb/pack.json" }
                  ],
                  "concepts": [
                    { "name": "Contact", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "address", "type": "string", "domainType": "Email" }
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
        assertTrue(message.contains("ambiguous"), "Expected an ambiguity error, got: " + message);
        assertTrue(message.contains("packa::Email"), "Expected the error to name packa::Email, got: " + message);
        assertTrue(message.contains("packb::Email"), "Expected the error to name packb::Email, got: " + message);
    }

    @Test
    void unqualifiedReferenceToAnUnambiguousBareNameResolvesAutomatically() throws Exception {
        write("packs/packa/pack.json", PACK_A);
        Path model = write("model.json", """
                {
                  "namespace": "pk1.unambiguous.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/packa/pack.json" }
                  ],
                  "concepts": [
                    { "name": "Contact", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "address", "type": "string", "domainType": "Email" }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        JsonNode concepts = resolvedSource.resolvedRoot().get("concepts");
        JsonNode addressField = null;
        for (JsonNode field : concepts.get(0).get("fields")) {
            if ("address".equals(field.get("name").asText())) {
                addressField = field;
            }
        }
        assertTrue(addressField != null, "expected an 'address' field");
        assertEquals("packa::Email", addressField.get("domainType").asText(),
                "an unqualified reference to a bare name exactly one pack provides must auto-resolve");
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
