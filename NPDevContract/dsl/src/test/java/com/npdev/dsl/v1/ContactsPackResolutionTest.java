package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAutoPanel;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the canonical built-in contacts pack ({@code NPDevContract/packs/contacts/pack.json}):
 * Organization + Contact + Address, four queries and three auto-panels. Loads the real artifact (not
 * an inline copy) and consumes it the way an app author would: a local {@code $ref} under an alias,
 * plus an own concept whose reference points at {@code contacts::Organization}.
 */
class ContactsPackResolutionTest {

    @TempDir
    Path temp;

    @Test
    void builtInContactsPackCompilesAndIsReferenceableFromAnOwnConcept() throws Exception {
        Path realPack = Path.of("..", "packs", "contacts", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(realPack), "Built-in contacts pack must exist at " + realPack);

        write("packs/contacts/pack.json", Files.readString(realPack));
        Path model = write("model.json", """
                {
                  "namespace": "contacts.pack.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/contacts/pack.json", "as": "contacts" }
                  ],
                  "concepts": [
                    { "name": "Invoice", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "customerId", "type": "reference", "required": true,
                        "reference": { "target": "contacts::Organization", "displayField": "name" } }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected contacts pack model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept organization = compiled.findConcept("contacts::Organization").orElseThrow();
        CompiledConcept contact = compiled.findConcept("contacts::Contact").orElseThrow();
        CompiledConcept address = compiled.findConcept("contacts::Address").orElseThrow();
        CompiledConcept invoice = compiled.findConcept("Invoice").orElseThrow();

        CompiledField kind = field(organization, "kind");
        assertTrue(kind.getEnumValues().contains("CUSTOMER"), "Organization.kind should offer CUSTOMER");
        assertTrue(kind.getEnumValues().contains("SUPPLIER"), "Organization.kind should offer SUPPLIER");

        assertReferencesOrganization(field(contact, "organizationId"));
        assertReferencesOrganization(field(address, "organizationId"));
        assertReferencesOrganization(field(invoice, "customerId"));

        List<String> queryNames = compiled.getQueries().stream().map(q -> q.name()).toList();
        for (String expected : List.of("ActiveOrganizations", "Customers", "Suppliers", "ActiveContacts")) {
            assertTrue(queryNames.stream().anyMatch(n -> n.endsWith(expected)),
                    "Expected query " + expected + " among " + queryNames);
        }

        List<String> routes = compiled.getAutoPanels().stream().map(CompiledAutoPanel::route).toList();
        assertEquals(3, routes.size(), "the pack declares three auto-panels, got " + routes);
        for (String expected : List.of("/organizations", "/contacts", "/addresses")) {
            assertTrue(routes.stream().anyMatch(r -> r.startsWith(expected)),
                    "Expected an auto-panel route starting with " + expected + " among " + routes);
        }
    }

    private static void assertReferencesOrganization(CompiledField field) {
        String target = field.getReferenceTarget();
        assertTrue(target != null && target.endsWith("Organization"),
                field.getName() + " should reference the pack's Organization, got " + target);
    }

    private static CompiledField field(CompiledConcept concept, String name) {
        return concept.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow();
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
