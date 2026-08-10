package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-14: "this database is not mine to manage", declared at the point of choice.
 *
 * <p>The behaviour that matters lives in the five emitted {@code _ops} scripts and is proved by
 * {@code helpers/red-prove-reset-refusal.ps1} against a real generated app, because the thing being
 * prevented is a {@code Remove-Item -Recurse -Force} and nothing short of running it proves it did
 * not happen. What is testable HERE is the layer underneath: that the flag survives the load into
 * the plan the scripts read, and that it is refused where it cannot mean anything.
 *
 * <p><b>Why the refusal is not merely tidiness.</b> An embedded engine has no server. Accepting
 * {@code externallyProvisioned: true} for H2Local would produce a plan whose Reset refuses to delete
 * a data root that belongs to nobody but this app -- an app that can then never be reset, for a
 * reason its owner declared by accident and no message ever explained.
 */
class ExternallyProvisionedDatabaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the flag reaches the plan the _ops scripts branch on")
    void theFlagReachesThePlan(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, "postgres-external", node -> {
            node.put("engine", "Postgres");
            node.put("externallyProvisioned", true);
        });

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definition, null);

        assertTrue(plan.externallyProvisioned(),
                "the five scripts branch on this field and on nothing else -- a hand-written "
                        + "engine-name comparison fails check-engine-parity.py");
    }

    @Test
    @DisplayName("absent means false, so every existing definition keeps today's behaviour")
    void absentMeansFalse(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, "postgres-default", node -> node.put("engine", "Postgres"));

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definition, null);

        assertFalse(plan.externallyProvisioned());
    }

    @Test
    @DisplayName("refused on an embedded engine, naming the engine and the reason")
    void refusedOnAnEmbeddedEngine(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, "h2-external", node -> {
            node.put("engine", "H2Local");
            node.put("externallyProvisioned", true);
        });

        String message = assertThrows(IllegalArgumentException.class,
                () -> new UserDatabaseDefinitionLoader().load(definition, null)).getMessage();

        assertTrue(message.contains("H2Local"), "name the engine: " + message);
        assertTrue(message.contains("embedded"), "name why it cannot apply: " + message);
        assertTrue(message.contains("STOR-14"), "cite the item: " + message);
    }

    /**
     * H2Server is a server whose environment happens to be a Java process rather than a container.
     * Someone can already be running one, so external mode has to cover it -- and it does, for free,
     * because every {@code _ops} operation checks the plan flag BEFORE it looks at
     * {@code profile.kind}.
     */
    @Test
    @DisplayName("allowed on H2Server, which is a server even though it is not containerized")
    void allowedOnH2Server(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, "h2server-external", node -> {
            node.put("engine", "H2Server");
            node.put("host", "localhost");
            node.put("port", 9092);
            node.put("externallyProvisioned", true);
        });

        GeneratedDatabasePlan plan = assertDoesNotThrow(
                () -> new UserDatabaseDefinitionLoader().load(definition, null));
        assertTrue(plan.externallyProvisioned());
    }

    /**
     * The 0.2 decision, pinned in code as well as in the ledger item: these two fields are
     * ORTHOGONAL. An NPDev-provisioned container can hold a schema NPDev must not issue DDL against;
     * a server NPDev did not provision can hold a schema it owns entirely. All four combinations are
     * meaningful, so neither field validates the other -- and this test exists so that a later
     * reader who notices the overlap does not "tidy" one into the other.
     */
    @Test
    @DisplayName("orthogonal to schemaLifecycle.ownership -- neither implies nor excludes the other")
    void orthogonalToSchemaOwnership(@TempDir Path temp) throws Exception {
        Path externalServerNpdevSchema = writeDefinition(temp, "external-server-owned-schema", node -> {
            node.put("engine", "Postgres");
            node.put("externallyProvisioned", true);
        });
        GeneratedDatabasePlan a = new UserDatabaseDefinitionLoader().load(externalServerNpdevSchema, null);
        assertTrue(a.externallyProvisioned());
        assertFalse(a.schemaLifecycle().externallyManaged(),
                "a DBA handing you an empty database on their server is still a schema NPDev owns");

        Path npdevServerExternalSchema = writeDefinition(temp, "npdev-server-external-schema", node ->
                node.put("engine", "Postgres"));
        Path withOwnership = rewriteOwnership(npdevServerExternalSchema);
        GeneratedDatabasePlan b = new UserDatabaseDefinitionLoader().load(withOwnership, null);
        assertFalse(b.externallyProvisioned(),
                "a container NPDev started can perfectly well hold a schema it must not touch");
        assertTrue(b.schemaLifecycle().externallyManaged());
    }

    private static Path rewriteOwnership(Path definition) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(definition.toFile());
        ((ObjectNode) root.get("schemaLifecycle")).put("ownership", "ExternallyManaged");
        Files.writeString(definition, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return definition;
    }

    private static Path writeDefinition(Path temp, String appName,
            java.util.function.Consumer<ObjectNode> tweak) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode database = root.putObject("database");
        database.put("databaseName", "npdev_stor14_probe");
        database.put("host", "localhost");
        database.put("port", 5432);
        database.put("username", "npdev");
        database.put("password", "npdev");
        database.put("createInternalTables", true);
        database.put("createBusinessTables", true);
        tweak.accept(database);
        ObjectNode lifecycle = root.putObject("schemaLifecycle");
        lifecycle.put("strategy", "KeepExistingIfCompatible");
        lifecycle.put("allowDestructiveRecreate", false);
        lifecycle.put("destructiveRecreateConfirmation", "");
        lifecycle.put("scope", "NpdevOwnedTablesOnly");

        // One directory per app: the loader derives identity from the definition's own location.
        Path dir = Files.createDirectories(temp.resolve(appName));
        Path definition = dir.resolve("db.definition.json");
        Files.writeString(definition, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return definition;
    }
}
