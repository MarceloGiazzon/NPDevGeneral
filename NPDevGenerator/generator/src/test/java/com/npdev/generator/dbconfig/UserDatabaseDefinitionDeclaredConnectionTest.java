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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-8: a declared connection that CONTRADICTS the real one must be refused, and one that agrees
 * must still load.
 *
 * <p><b>Both halves matter, and the second is the one that shaped the fix.</b> The obvious change was
 * to refuse {@code jdbcUrl} and {@code h2FilePath} outright, since they read as authoritative and one
 * is consulted by nothing. Measured first: TWELVE app definitions set one of them, four of them
 * official samples, and every one declares exactly what NPDev composes anyway. A blanket refusal
 * would have broken all twelve to fix a hazard none of them has.
 */
class UserDatabaseDefinitionDeclaredConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("an h2FilePath that agrees with the derived path still loads")
    void agreeingDeclarationLoads(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, node -> { });
        GeneratedDatabasePlan plan = assertDoesNotThrow(
                () -> new UserDatabaseDefinitionLoader().load(definition, null));
        // Re-declaring the derived path is redundant, not wrong -- and it is what the corpus does.
        Path echoed = writeDefinition(temp, node ->
                node.put("h2FilePath", plan.resolvedDataRoot() + "/" + plan.resolvedDatabaseName()));
        assertDoesNotThrow(() -> new UserDatabaseDefinitionLoader().load(echoed, null),
                "a declaration that MATCHES what NPDev composes must not be refused -- twelve real "
                + "app definitions are in exactly this state");
    }

    @Test
    @DisplayName("an h2FilePath pointing somewhere else is refused, naming BOTH paths")
    void contradictingFilePathIsRefused(@TempDir Path temp) throws Exception {
        Path definition = writeDefinition(temp, node ->
                node.put("h2FilePath", "D:/SomeoneElses/production/customers"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new UserDatabaseDefinitionLoader().load(definition, null));
        String message = failure.getMessage();
        // Naming both is the point: "this field is ignored" leaves the reader guessing what IS used,
        // which is how they conclude the tool is broken rather than their file.
        assertTrue(message.contains("D:/SomeoneElses/production/customers"),
                "the refusal must quote what was DECLARED: " + message);
        assertTrue(message.toLowerCase().contains("actual"),
                "the refusal must also name what NPDev will actually use: " + message);
        assertTrue(message.contains("STOR-8"), "the refusal must cite its item: " + message);
    }

    @Test
    @DisplayName("a jdbcUrl for a different database is refused -- the silent-wrong-answer case")
    void contradictingJdbcUrlIsRefused(@TempDir Path temp) throws Exception {
        // The hazard in one line: a user points this at a database that already exists, gets no
        // error, connects somewhere else entirely, and writes to it.
        Path definition = writeDefinition(temp, node -> {
            node.put("engine", "H2Server");
            node.put("host", "localhost");
            node.put("port", 9092);
            node.put("jdbcUrl", "jdbc:h2:tcp://prod-db.internal:9092/D:/company/customers");
        });
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new UserDatabaseDefinitionLoader().load(definition, null));
        assertTrue(failure.getMessage().contains("prod-db.internal"), failure.getMessage());
    }

    private static Path writeDefinition(Path temp, java.util.function.Consumer<ObjectNode> tweak)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode database = root.putObject("database");
        database.put("engine", "H2Local");
        database.put("databaseName", "npdev_stor8_probe");
        database.put("username", "sa");
        database.put("password", "");
        tweak.accept(database);
        ObjectNode lifecycle = root.putObject("schemaLifecycle");
        lifecycle.put("strategy", "KeepExistingIfCompatible");
        lifecycle.put("allowDestructiveRecreate", false);

        // A fresh directory per call: the loader derives the app id from the definition's own
        // location, so two definitions in one directory would resolve to the same app.
        Path dir = Files.createTempDirectory(temp, "stor8");
        Path definition = dir.resolve("db.definition.json");
        Files.writeString(definition,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return definition;
    }
}
