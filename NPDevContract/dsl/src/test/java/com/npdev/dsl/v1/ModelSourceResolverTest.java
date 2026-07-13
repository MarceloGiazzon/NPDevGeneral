package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSourceResolverTest {

    @TempDir
    Path temp;

    @Test
    void flatModelStillParsesUnchanged() throws Exception {
        Path model = write("model.json", minimalModel("FlatUser"));

        ModelAst ast = new JsonModelParser().parse(model);

        assertEquals(1, ast.getConcepts().size());
        assertEquals("FlatUser", ast.getConcepts().get(0).getName());
    }

    @Test
    void conceptRefsExpandAtDeclarationPosition() throws Exception {
        write("concept/user.json", concept("User"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "InlineA", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] },
                    { "$ref": "concept/user.json" },
                    { "name": "InlineB", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);
        JsonNode concepts = source.resolvedRoot().get("concepts");

        assertEquals("InlineA", concepts.get(0).get("name").asText());
        assertEquals("User", concepts.get(1).get("name").asText());
        assertEquals("InlineB", concepts.get(2).get("name").asText());
        assertEquals(1, source.includedFiles().size());
    }

    @Test
    void topLevelFragmentsAppendAfterRootInlineEntriesAndMergeMetadataSafely() throws Exception {
        write("plugin/sendMail.json", """
                {
                  "capabilities": [
                    { "name": "sendMail", "type": "NotificationCapability", "operations": ["send"] }
                  ],
                  "bindings": [
                    { "capability": "sendMail", "adapter": "plugin:generic" }
                  ],
                  "metadata": {
                    "pluginSource": "sendMail"
                  }
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "fragments": [{ "$ref": "plugin/sendMail.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "capabilities": [
                    { "name": "localCapability", "type": "ExternalCapability", "operations": ["run"] }
                  ],
                  "metadata": {
                    "owner": "root"
                  }
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        assertEquals("localCapability", resolved.get("capabilities").get(0).get("name").asText());
        assertEquals("sendMail", resolved.get("capabilities").get(1).get("name").asText());
        assertEquals("root", resolved.get("metadata").get("owner").asText());
        assertEquals("sendMail", resolved.get("metadata").get("pluginSource").asText());
    }

    @Test
    void nestedFragmentsResolveRelativeToContainingFile() throws Exception {
        write("plugin/nested/capability.json", """
                {
                  "capabilities": [
                    { "$ref": "../sendMailCapability.json" }
                  ]
                }
                """);
        write("plugin/sendMailCapability.json", """
                { "name": "sendMail", "type": "NotificationCapability", "operations": ["send"] }
                """);
        write("plugin/sendMail.json", """
                { "fragments": [{ "$ref": "nested/capability.json" }] }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "fragments": [{ "$ref": "plugin/sendMail.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        assertEquals("sendMail", resolved.get("capabilities").get(0).get("name").asText());
    }

    @Test
    void rejectsMalformedAndUnsafeRefs() throws Exception {
        assertIncludeFailure("{ \"$ref\": \"concept/user.json\", \"extra\": true }", "$ref object");
        assertIncludeFailure("{ \"$ref\": \"https://example.com/user.json\" }", "not a URL");
        assertIncludeFailure("{ \"$ref\": \"concept/user.txt\" }", ".json");
        assertIncludeFailure("{ \"$ref\": \"../outside.json\" }", "escapes");
    }

    @Test
    void rejectsCircularFragmentsThroughCanonicalPaths() throws Exception {
        write("plugin/a.json", "{ \"fragments\": [{ \"$ref\": \"./sub/../b.json\" }] }");
        write("plugin/b.json", "{ \"fragments\": [{ \"$ref\": \"a.json\" }] }");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "fragments": [{ "$ref": "plugin/a.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("Circular model include"));
    }

    @Test
    void rejectsMetadataCollisionBetweenFragments() throws Exception {
        write("plugin/a.json", "{ \"metadata\": { \"pluginSource\": \"a\" } }");
        write("plugin/b.json", "{ \"metadata\": { \"pluginSource\": \"b\" } }");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "fragments": [{ "$ref": "plugin/a.json" }, { "$ref": "plugin/b.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("Duplicate fragment metadata key"));
    }

    @Test
    void packNamespacesConceptsAndRewritesIntraPackReferences() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "domainTypes": [{ "name": "Sku", "baseType": "string" }],
                  "capabilities": [{ "name": "lookup", "type": "ExternalCapability", "operations": ["run"] }],
                  "concepts": [
                    { "name": "Product", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "code", "type": "string", "domainType": "Sku" }
                    ] },
                    { "name": "Variant", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/catalog.json" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        JsonNode concepts = resolved.get("concepts");

        assertEquals("RootOnly", concepts.get(0).get("name").asText());
        assertEquals("catalog::Product", concepts.get(1).get("name").asText());
        assertEquals("catalog::Variant", concepts.get(2).get("name").asText());
        // Intra-pack reference target is namespaced; the domainType ref (by name) is left intact.
        assertEquals("catalog::Product",
                concepts.get(2).get("fields").get(1).get("reference").get("target").asText());
        assertEquals("Sku", concepts.get(1).get("fields").get(1).get("domainType").asText());
        // Non-concept pack arrays are merged, not silently dropped.
        assertEquals("Sku", resolved.get("domainTypes").get(0).get("name").asText());
        assertEquals("lookup", resolved.get("capabilities").get(0).get("name").asText());
    }

    @Test
    void packAliasOverridesPackIdentifier() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/catalog.json", "as": "cat" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertEquals("cat::Product", resolved.get("concepts").get(1).get("name").asText());
    }

    @Test
    void packAliasMayContainHyphenAndNamespacesConcepts() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        Path model = packImportModelWithAlias("pack/catalog.json", "sales-core");

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertEquals("sales-core::Product", resolved.get("concepts").get(1).get("name").asText());
    }

    @Test
    void packImportRejectsNonStringAlias() throws Exception {
        writeSimplePack("pack/catalog.json", "catalog");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/catalog.json", "as": 123 }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("/packs/0/as"), error.getMessage());
        assertTrue(error.getMessage().contains("must be a string"), error.getMessage());
    }

    @Test
    void packImportRejectsBlankAlias() throws Exception {
        writeSimplePack("pack/catalog.json", "catalog");
        Path model = packImportModelWithAlias("pack/catalog.json", "   ");

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("/packs/0/as"), error.getMessage());
        assertTrue(error.getMessage().contains("non-blank"), error.getMessage());
    }

    @Test
    void packImportRejectsInvalidAlias() throws Exception {
        writeSimplePack("pack/catalog.json", "catalog");
        Path model = packImportModelWithAlias("pack/catalog.json", "bad alias");

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("/packs/0/as"), error.getMessage());
        assertTrue(error.getMessage().contains("must match"), error.getMessage());
    }

    @Test
    void packImportRejectsDuplicateNamespaceAlias() throws Exception {
        writeSimplePack("pack/catalog.json", "catalog");
        writeSimplePack("pack/other.json", "other");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "pack/catalog.json", "as": "cat" },
                    { "$ref": "pack/other.json", "as": "cat" }
                  ],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("/packs/1/as"), error.getMessage());
        assertTrue(error.getMessage().contains("Duplicate pack namespace"), error.getMessage());
    }

    @Test
    void packNonConceptArtifactsRewriteLocalConceptReferencesOnly() throws Exception {
        write("pack/non-concepts.json", """
                {
                  "queries": [
                    { "name": "FindProducts", "concept": "Product" }
                  ],
                  "flows": [
                    {
                      "name": "ApproveProduct",
                      "concept": "Product",
                      "steps": [{ "name": "done", "type": "return", "value": "ok" }]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "ArchiveProduct",
                      "steps": [{ "name": "load", "type": "load", "concept": "Product" }],
                      "actionDescriptor": {
                        "sideEffectConcept": "Product",
                        "affectedConcepts": ["Product", "billing::Invoice"]
                      }
                    }
                  ],
                  "panels": [
                    {
                      "name": "ProductPanel",
                      "route": "/products",
                      "dataSources": [{ "name": "products", "concept": "Product" }],
                      "actions": [{ "name": "archive", "binding": "procedure", "concept": "Product" }]
                    }
                  ],
                  "ruleProfiles": [
                    { "name": "ProductRules", "appliesTo": ["Product", "billing::Invoice"] }
                  ],
                  "orchestrations": [
                    {
                      "name": "ProductCreated",
                      "trigger": { "type": "event", "event": "ProductCreated" },
                      "action": { "type": "createConcept", "targetConcept": "Product" }
                    }
                  ]
                }
                """);
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "fragments": [
                    { "$ref": "non-concepts.json" }
                  ]
                }
                """);
        Path model = packImportModel("pack/catalog.json");

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        assertEquals("cat::Product", resolved.get("queries").get(0).get("concept").asText());
        assertEquals("cat::Product", resolved.get("flows").get(0).get("concept").asText());
        assertEquals("cat::Product", resolved.get("procedures").get(0).get("steps").get(0).get("concept").asText());
        assertEquals("cat::Product",
                resolved.get("procedures").get(0).get("actionDescriptor").get("sideEffectConcept").asText());
        assertEquals("billing::Invoice",
                resolved.get("procedures").get(0).get("actionDescriptor").get("affectedConcepts").get(1).asText());
        assertEquals("cat::Product",
                resolved.get("panels").get(0).get("dataSources").get(0).get("concept").asText());
        assertEquals("cat::Product",
                resolved.get("panels").get(0).get("actions").get(0).get("concept").asText());
        assertEquals("cat::Product", resolved.get("ruleProfiles").get(0).get("appliesTo").get(0).asText());
        assertEquals("billing::Invoice", resolved.get("ruleProfiles").get(0).get("appliesTo").get(1).asText());
        assertEquals("cat::Product",
                resolved.get("orchestrations").get(0).get("action").get("targetConcept").asText());
    }

    @Test
    void packWithoutIdentifierOrAliasIsRejected() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/catalog.json" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("pack"), error.getMessage());
    }

    @Test
    void packImportRejectsUnsafeRefs() throws Exception {
        assertPackRefFailure("https://example.test/catalog.json", "Pack $ref");
        assertPackRefFailure("pack/catalog.txt", ".json");
        assertPackRefFailure("../outside.json", "escapes the model root");
        assertPackRefFailure(temp.resolve("pack/catalog.json").toString(), "must be relative");
    }

    @Test
    void packSchemaRejectsTypoConceptsProperty() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concets": []
                }
                """);
        Path model = packImportModel("pack/catalog.json");

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("concets"), error.getMessage());
    }

    @Test
    void packImportRejectsDslVersionMismatch() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "2.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        Path model = packImportModel("pack/catalog.json");

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("dslVersion"), error.getMessage());
    }

    @Test
    void packFragmentsAndLocalRefsResolveBeforeConceptNamespace() throws Exception {
        write("pack/catalog/concepts/product.json", """
                { "name": "Product", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
                ] }
                """);
        write("pack/catalog/fragments/variant.json", """
                {
                  "concepts": [
                    { "name": "Variant", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "reference": { "target": "Product", "via": "skuId", "onDelete": "cascade" }
                      }
                    ] }
                  ]
                }
                """);
        write("pack/catalog/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "$ref": "concepts/product.json" }
                  ],
                  "fragments": [
                    { "$ref": "fragments/variant.json" }
                  ]
                }
                """);
        Path model = packImportModelWithRoot("pack/catalog/pack.json", """
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "reference": { "target": "cat::Product", "via": "skuId", "onDelete": "restrict" }
                      }
                    ] }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        JsonNode concepts = resolved.get("concepts");

        assertEquals("Order", concepts.get(0).get("name").asText());
        assertEquals("cat::Product", concepts.get(1).get("name").asText());
        assertEquals("cat::Variant", concepts.get(2).get("name").asText());
        assertEquals("cat::Product",
                concepts.get(2).get("fields").get(1).get("reference").get("target").asText());
        assertEquals("cat::Product",
                concepts.get(0).get("fields").get(1).get("reference").get("target").asText());
    }

    @Test
    void packConceptWithConnectableAnchorIsPreservedInResolvedModel() throws Exception {
        // BOND-B6: a bond from a root concept to a pack-namespaced concept must survive the full
        // JSON -> ModelSourceResolver pipeline intact, including the connectable:anchor field the
        // bond resolves against -- not just at the pre-built CompiledConcept level.
        write("packs/catalog.json", """
                {
                  "pack": "catalog",
                  "version": "1.0",
                  "dslVersion": "1.0.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
                      ]
                    }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "order.app",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "packs/catalog.json" }],
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "catalog::Product",
                            "via": "skuId",
                            "onDelete": "restrict"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        JsonNode concepts = new ModelSourceResolver().resolve(model).resolvedRoot().get("concepts");
        assertEquals(2, concepts.size(), "Should have catalog::Product and Order after pack merge");

        boolean foundPackConcept = false;
        boolean foundOrderConcept = false;
        for (JsonNode concept : concepts) {
            String name = concept.get("name").asText();
            if ("catalog::Product".equals(name)) {
                foundPackConcept = true;
                boolean skuAnchorFound = false;
                for (JsonNode field : concept.get("fields")) {
                    if ("skuId".equals(field.get("name").asText())) {
                        assertEquals("anchor", field.get("connectable").asText(),
                                "connectable:anchor must survive pack merge");
                        skuAnchorFound = true;
                    }
                }
                assertTrue(skuAnchorFound, "skuId anchor field should be present after merge");
            }
            if ("Order".equals(name)) {
                foundOrderConcept = true;
                assertEquals("catalog::Product",
                        concept.get("fields").get(1).get("reference").get("target").asText(),
                        "Bond's reference target must stay pack-namespaced after merge");
            }
        }
        assertTrue(foundPackConcept, "catalog::Product must be present after pack merge");
        assertTrue(foundOrderConcept, "Order must be present after pack merge");
    }

    @Test
    void packFragmentRejectsIdentityFields() throws Exception {
        write("pack/catalog/fragments/product.json", """
                {
                  "dslVersion": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        write("pack/catalog/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "fragments": [{ "$ref": "fragments/product.json" }]
                }
                """);
        Path model = packImportModel("pack/catalog/pack.json");

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("identity key"), error.getMessage());
    }

    private void assertIncludeFailure(String refJson, String expectedMessage) throws Exception {
        Path outside = temp.getParent().resolve("outside.json");
        if (!Files.exists(outside)) {
            Files.writeString(outside, concept("Outside"));
        }
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    %s
                  ]
                }
                """.formatted(refJson));

        Exception error = assertThrows(Exception.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    private void assertPackRefFailure(String ref, String expectedMessage) throws Exception {
        Path outside = temp.getParent().resolve("outside.json");
        if (!Files.exists(outside)) {
            Files.writeString(outside, """
                    { "dslVersion": "1.0.0", "pack": "outside", "version": "1.0.0" }
                    """);
        }
        Path model = packImportModel(ref);

        Exception error = assertThrows(Exception.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    private Path packImportModel(String ref) throws IOException {
        return packImportModelWithRoot(ref, concept("RootOnly"));
    }

    private Path packImportModelWithRoot(String ref, String rootConceptJson) throws IOException {
        return write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "%s", "as": "cat" }],
                  "concepts": [
                    %s
                  ]
                }
                """.formatted(ref.replace("\\", "\\\\"), rootConceptJson));
    }

    private Path packImportModelWithAlias(String ref, String alias) throws IOException {
        return write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "%s", "as": "%s" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """.formatted(ref.replace("\\", "\\\\"), alias));
    }

    private void writeSimplePack(String relative, String packId) throws IOException {
        write(relative, """
                {
                  "dslVersion": "1.0.0",
                  "pack": "%s",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """.formatted(packId));
    }

    private String minimalModel(String conceptName) {
        return """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    %s
                  ]
                }
                """.formatted(concept(conceptName));
    }

    private static String concept(String name) {
        return """
                {
                  "name": "%s",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ]
                }
                """.formatted(name);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
