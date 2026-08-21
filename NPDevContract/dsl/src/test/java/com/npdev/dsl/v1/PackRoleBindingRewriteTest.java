package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * PACK-9: {@code role('logicalName')} is a compile-time token, rewritten by {@code
 * PackRoleBindingRewriter} at pack-composition time into the composing app's own concrete role
 * name (declared in root {@code provides.roleBindings}) -- the "internal rewrite" half of PACK-9
 * that the presence-only {@code requires.roles}/{@code provides.roles} check ({@code
 * PackRequiresBindingTest}) never attempted. The same pack composed into two apps with different
 * role vocabularies must enforce two different, correct concrete role names; an unbound logical
 * name must refuse AT COMPOSITION, naming the pack and the key.
 */
class PackRoleBindingRewriteTest {

    @TempDir
    Path temp;

    private static final String CURATOR_PACK = """
            {
              "dslVersion": "1.0.0",
              "pack": "labeling",
              "version": "1.0.0",
              "concepts": [
                {
                  "name": "Label",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "text", "type": "string", "required": true }
                  ],
                  "access": {
                    "write": "$user.roles.contains(role('curator'))"
                  }
                }
              ],
              "panels": [
                {
                  "name": "LabelCuratorPanel",
                  "route": "/labeling/curator",
                  "visibility": "role:role('curator')",
                  "actions": [
                    {
                      "name": "approve",
                      "label": "Approve",
                      "binding": "conceptQuery",
                      "concept": "Label",
                      "permissionRequirements": [ "role('curator')" ],
                      "metadata": { "permissionHint": "role('curator')" }
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    void unboundRoleTokenRefusesNamingThePackAndTheKey() throws Exception {
        write("packs/labeling/pack.json", CURATOR_PACK);
        Path model = write("model.json", """
                {
                  "namespace": "role.binding.unbound.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/labeling/pack.json" } ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        String message = thrown.getMessage();
        assertTrue(message.contains("labeling"), "must name the pack, got: " + message);
        assertTrue(message.contains("curator"), "must name the unbound logical role, got: " + message);
        assertTrue(message.contains("provides.roleBindings"), "must name the fix, got: " + message);
    }

    @Test
    void twoAppsBindTheSamePackToDifferentConcreteRolesAndBothResolveCorrectly() throws Exception {
        write("appA/packs/labeling/pack.json", CURATOR_PACK);
        Path modelA = write("appA/model.json", """
                {
                  "namespace": "role.binding.appA.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "provides": { "roleBindings": { "curator": "WidgetLabelCurator" } },
                  "packs": [ { "$ref": "packs/labeling/pack.json" } ]
                }
                """);

        write("appB/packs/labeling/pack.json", CURATOR_PACK);
        Path modelB = write("appB/model.json", """
                {
                  "namespace": "role.binding.appB.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "provides": { "roleBindings": { "curator": "SUPERVISOR" } },
                  "packs": [ { "$ref": "packs/labeling/pack.json" } ]
                }
                """);

        ResolvedModelSource resolvedA = new ModelSourceResolver().resolve(modelA);
        ResolvedModelSource resolvedB = new ModelSourceResolver().resolve(modelB);

        JsonNode conceptA = findConcept(resolvedA.resolvedRoot(), "labeling::Label");
        JsonNode conceptB = findConcept(resolvedB.resolvedRoot(), "labeling::Label");
        assertEquals("$user.roles.contains('WidgetLabelCurator')", conceptA.path("access").path("write").asText());
        assertEquals("$user.roles.contains('SUPERVISOR')", conceptB.path("access").path("write").asText());

        JsonNode panelA = findPanel(resolvedA.resolvedRoot(), "labeling::LabelCuratorPanel");
        JsonNode panelB = findPanel(resolvedB.resolvedRoot(), "labeling::LabelCuratorPanel");
        assertEquals("role:WidgetLabelCurator", panelA.path("visibility").asText());
        assertEquals("role:SUPERVISOR", panelB.path("visibility").asText());

        JsonNode actionA = panelA.path("actions").get(0);
        JsonNode actionB = panelB.path("actions").get(0);
        assertEquals("WidgetLabelCurator", actionA.path("permissionRequirements").get(0).asText());
        assertEquals("SUPERVISOR", actionB.path("permissionRequirements").get(0).asText());
        assertEquals("WidgetLabelCurator", actionA.path("metadata").path("permissionHint").asText());
        assertEquals("SUPERVISOR", actionB.path("metadata").path("permissionHint").asText());

        // Neither app's resolved JSON carries the token anywhere -- the token never reaches
        // JsonModelParser/ModelCompiler/the generator/the kernel, only the app's own literal does.
        assertFalse(resolvedA.resolvedRoot().toString().contains("role('curator')"));
        assertFalse(resolvedB.resolvedRoot().toString().contains("role('curator')"));
    }

    @Test
    void packWithNoRoleTokenIsUnaffected() throws Exception {
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "concepts": [ { "name": "Invoice", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "role.binding.notoken.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/billing/pack.json" } ]
                }
                """);

        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        assertTrue(resolved.resolvedRoot().has("concepts"));
    }

    private static JsonNode findConcept(JsonNode resolvedRoot, String qualifiedName) {
        for (JsonNode concept : resolvedRoot.path("concepts")) {
            if (qualifiedName.equals(concept.path("name").asText())) {
                return concept;
            }
        }
        throw new AssertionError("concept not found: " + qualifiedName);
    }

    private static JsonNode findPanel(JsonNode resolvedRoot, String qualifiedName) {
        for (JsonNode panel : resolvedRoot.path("panels")) {
            if (qualifiedName.equals(panel.path("name").asText())) {
                return panel;
            }
        }
        throw new AssertionError("panel not found: " + qualifiedName);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
