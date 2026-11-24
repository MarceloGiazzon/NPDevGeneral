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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                  "dslVersion": "3.0.0",
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
    void anArrayRefEntryIsSplicedInPlaceAndTheIncludedFilesMetadataStaysOutOfTheModel() throws Exception {
        write("plugin/sendMail.json", """
                {
                  "capabilities": [
                    {
                      "name": "sendMail",
                      "type": "NotificationCapability",
                      "operations": [
                        "send"
                      ],
                      "adapter": "plugin:generic"
                    }
                  ],
                  "metadata": {
                    "pluginSource": "sendMail"
                  }
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "capabilities": [
                    { "name": "localCapability", "type": "ExternalCapability", "operations": ["run"] },
                    { "$ref": "plugin/sendMail.json" }
                  ],
                  "metadata": {
                    "owner": "root"
                  }
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        assertEquals("localCapability", resolved.get("capabilities").get(0).get("name").asText());
        assertEquals("sendMail", resolved.get("capabilities").get(1).get("name").asText());
        assertEquals(2, resolved.get("capabilities").size());
        assertEquals("plugin:generic", resolved.get("capabilities").get(1).get("adapter").asText());
        assertEquals("root", resolved.get("metadata").get("owner").asText());
        assertFalse(resolved.get("metadata").has("pluginSource"), "an included file's metadata is not merged into the model");
    }

    @Test
    void nestedArrayRefsResolveRelativeToTheContainingFile() throws Exception {
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
                { "capabilities": [{ "$ref": "nested/capability.json" }] }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "capabilities": [{ "$ref": "plugin/sendMail.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        assertEquals(1, resolved.get("capabilities").size());
        assertEquals("sendMail", resolved.get("capabilities").get(0).get("name").asText());
    }

    @Test
    void rootFragmentsIsRefusedWithAMigrationMessage() throws Exception {
        write("plugin/sendMail.json", "{ \"capabilities\": [] }");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "fragments": [{ "$ref": "plugin/sendMail.json" }],
                  "concepts": [
                    { "name": "User", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("Root 'fragments' was removed"), error.getMessage());
    }

    @Test
    void rejectsMalformedAndUnsafeRefs() throws Exception {
        assertIncludeFailure("{ \"$ref\": \"concept/user.json\", \"extra\": true }", "$ref object");
        assertIncludeFailure("{ \"$ref\": \"https://example.com/user.json\" }", "not a URL");
        assertIncludeFailure("{ \"$ref\": \"concept/user.txt\" }", ".json");
        assertIncludeFailure("{ \"$ref\": \"../outside.json\" }", "escapes");
    }

    @Test
    void rejectsCircularArrayRefsThroughCanonicalPaths() throws Exception {
        write("plugin/a.json", "{ \"concepts\": [{ \"$ref\": \"./sub/../b.json\" }] }");
        write("plugin/b.json", "{ \"concepts\": [{ \"$ref\": \"a.json\" }] }");
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [{ "$ref": "plugin/a.json" }]
                }
                """);

        IOException error = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(error.getMessage().contains("Circular model include"), error.getMessage());
    }

    @Test
    void packNamespacesConceptsAndRewritesIntraPackReferences() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
        // Intra-pack reference target is namespaced; PK-1 (PACK-ROADMAP.md) now also namespaces the
        // domainType reference itself -- before PK-1, non-concept members (and references to them)
        // were never rewritten, only concepts were.
        assertEquals("catalog::Product",
                concepts.get(2).get("fields").get(1).get("reference").get("target").asText());
        assertEquals("catalog::Sku", concepts.get(1).get("fields").get(1).get("domainType").asText());
        // Non-concept pack arrays are merged AND namespaced (PK-1), not silently dropped.
        assertEquals("catalog::Sku", resolved.get("domainTypes").get(0).get("name").asText());
        assertEquals("catalog::lookup", resolved.get("capabilities").get(0).get("name").asText());
    }


    /** FORM P3-T5b: a pack's own query / flow named by a flow step is qualified with its member. */
    @Test
    void packFlowStepsRewriteTheirRunQueryAndCallFlowTargets() throws Exception {
        write("pack/reports.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "reports",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Item", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "queries": [ { "name": "AllItems", "concept": "Item" } ],
                  "flows": [
                    { "name": "Helper", "concept": "Item", "steps": [ { "name": "r", "type": "return", "value": "input" } ] },
                    { "name": "Main", "concept": "Item", "steps": [
                      { "name": "q", "type": "runQuery", "query": "AllItems", "output": "rows" },
                      { "name": "c", "type": "callFlow", "flow": "Helper", "output": "x" } ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/reports.json" }]
                }
                """);

        JsonNode flows = new ModelSourceResolver().resolve(model).resolvedRoot().get("flows");

        JsonNode main = null;
        for (JsonNode flow : flows) {
            if (flow.get("name").asText().endsWith("Main")) {
                main = flow;
            }
        }
        assertEquals("reports::AllItems", main.get("steps").get(0).get("query").asText());
        assertEquals("reports::Helper", main.get("steps").get(1).get("flow").asText());
    }

    @Test
    void packContributedRolesPropertyScopesAndPropertiesAreMergedNotDropped() throws Exception {
        // Move 13 / REG-108: roles, propertyScopes and properties are top-level arrays exactly like
        // domainTypes/capabilities/etc, but were missing from MODEL_ARRAY_KEYS -- a pack declaring
        // any of the three had it silently dropped by mergePackNonConceptArrays, which only loops
        // that set. Root-level declarations of these three were never affected (a separate,
        // unrecognized-key passthrough in resolveRoot covers the root only) -- this test is about
        // pack-contributed declarations specifically.
        write("pack/tenancy.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "tenancy",
                  "version": "1.0.0",
                  "roles": [{ "name": "PackRole", "grants": ["EXECUTE_FLOW"] }],
                  "propertyScopes": [{ "name": "tenant" }],
                  "properties": [
                    { "name": "pack.pageRows", "type": "int", "default": 25, "settableAt": ["tenant"] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/tenancy.json" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "roles": [{ "name": "RootRole", "grants": ["READ_EXECUTIONS"] }],
                  "propertyScopes": [{ "name": "user", "from": "$user.id" }],
                  "properties": [
                    { "name": "root.pageRows", "type": "int", "default": 10, "settableAt": ["user"] }
                  ]
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();

        // PK-1 (PACK-ROADMAP.md): pack-contributed roles/propertyScopes/properties are now
        // namespaced qualifierId::Name exactly like every other non-concept kind -- root-level
        // declarations of the same kinds are untouched (they carry no pack qualifier at all).
        assertEquals(2, resolved.get("roles").size());
        assertEquals("RootRole", resolved.get("roles").get(0).get("name").asText());
        assertEquals("tenancy::PackRole", resolved.get("roles").get(1).get("name").asText());
        assertEquals(2, resolved.get("propertyScopes").size());
        assertEquals("tenancy::tenant", resolved.get("propertyScopes").get(1).get("name").asText());
        assertEquals(2, resolved.get("properties").size());
        assertEquals("tenancy::pack.pageRows", resolved.get("properties").get(1).get("name").asText());
        // The property's own settableAt[] names its pack's OWN propertyScope -- an intra-pack
        // reference, so PK-1 rewrites it too.
        assertEquals("tenancy::tenant", resolved.get("properties").get(1).get("settableAt").get(0).asText());
    }

    @Test
    void aPackScopeWithAFromGoesInFrontOfTheModelsRootScope() throws Exception {
        // REG-359: propertyScopes' order is the cascade precedence and the root scope (no `from`) must be last.
        // A pack's scope used to be appended AFTER the app's own root scope, which PropertyValidation then refused,
        // so no pack could contribute a scope to an app that declares its root scope.
        write("pack/desks.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "desks",
                  "version": "1.0.0",
                  "propertyScopes": [{ "name": "desk", "from": "$user.deskId" }],
                  "properties": [
                    { "name": "pack.deskLabel", "type": "string", "default": "none", "settableAt": ["desk"] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/desks.json" }],
                  "concepts": [
                    { "name": "RootOnly", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "propertyScopes": [{ "name": "user", "from": "$user.id" }, { "name": "tenant" }]
                }
                """);

        JsonNode scopes = new ModelSourceResolver().resolve(model).resolvedRoot().get("propertyScopes");

        assertEquals(3, scopes.size());
        assertEquals("user", scopes.get(0).get("name").asText());
        assertEquals("desks::desk", scopes.get(1).get("name").asText(), "the pack's scope is more specific than the root scope");
        assertEquals("tenant", scopes.get(2).get("name").asText(), "the root scope stays last");
        assertTrue(scopes.get(2).path("from").isMissingNode());
    }

    @Test
    void packAliasOverridesPackIdentifier() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "screens": [
                    {
                      "name": "ProductPanel", "kind": "custom", "panel": {
                      "route": "/products",
                      "dataSources": [{ "name": "products", "concept": "Product" }],
                      "actions": [{ "name": "archive", "binding": "procedure", "concept": "Product" }]}}
                  ]
                }
                """);
        write("pack/catalog.json", """
                {
                  "dslVersion": "3.0.0",
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
        assertEquals("cat::Product",
                resolved.get("screens").get(0).get("panel").get("dataSources").get(0).get("concept").asText());
        assertEquals("cat::Product",
                resolved.get("screens").get(0).get("panel").get("actions").get(0).get("concept").asText());
    }

    @Test
    void packWithoutIdentifierOrAliasIsRejected() throws Exception {
        write("pack/catalog.json", """
                {
                  "dslVersion": "3.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "version": "1.0.0",
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        write("pack/catalog/pack.json", """
                {
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                    { "dslVersion": "3.0.0", "pack": "outside", "version": "1.0.0" }
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
                  "dslVersion": "3.0.0",
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
