package com.npdev.dsl.v1.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-8 Step 7: tests for {@code npdev pack search} -- filtering by query, by category,
 * and empty results.
 */
class PackSearchMainTest {

    @TempDir
    Path temp;

    private static final String CATALOG = """
            {
              "schemaVersion": "pack-catalog.v1",
              "generatedAt": "2026-08-20T00:00:00Z",
              "packs": [
                {
                  "packId": "identity",
                  "version": "1.0.0",
                  "description": "Built-in identity model: users, roles, and assignments.",
                  "author": "NPDev",
                  "category": "security",
                  "concepts": ["User", "Role", "UserRole"]
                },
                {
                  "packId": "workspace",
                  "version": "1.0.0",
                  "description": "Workspace tables: navigation Menus and PropertyValue.",
                  "author": "NPDev",
                  "category": "web-ui",
                  "concepts": ["Menu", "PropertyValue"]
                },
                {
                  "packId": "project-tracker-demo",
                  "version": "1.0.0",
                  "description": "Demo pack for project tracking.",
                  "author": "NPDev Sample Author",
                  "category": "other",
                  "concepts": ["Project"]
                }
              ]
            }
            """;

    @Test
    void filterByQueryMatchesPackId() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString(), "--query", "identity"});
            assertEquals(0, exit);
        });
        assertTrue(out.contains("identity"), "expected identity in output: " + out);
        assertTrue(out.contains("1.0.0"), "expected version in output: " + out);
    }

    @Test
    void filterByQueryMatchesDescription() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString(), "--query", "navigation"});
            assertEquals(0, exit);
        });
        assertTrue(out.contains("workspace"), "expected workspace in output: " + out);
    }

    @Test
    void filterByQueryMatchesConcepts() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString(), "--query", "Project"});
            assertEquals(0, exit);
        });
        assertTrue(out.contains("project-tracker-demo"), "expected project-tracker-demo in output: " + out);
    }

    @Test
    void filterByCategory() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString(), "--category", "security"});
            assertEquals(0, exit);
        });
        assertTrue(out.contains("identity"), "expected identity in output: " + out);
        assertTrue(!out.contains("workspace"), "workspace should not appear for security category: " + out);
    }

    @Test
    void filterByQueryAndCategory() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{
                    "--catalog", catalog.toString(), "--query", "User", "--category", "security"
            });
            assertEquals(0, exit);
        });
        assertTrue(out.contains("identity"), "expected identity in output: " + out);
    }

    @Test
    void noResultsReturnsExitOne() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString(), "--query", "nonexistent"});
            assertEquals(1, exit);
        });
        assertTrue(out.contains("No packs found"), "expected no-results message: " + out);
    }

    @Test
    void missingCatalogArgReturnsExitTwo() {
        int exit = PackSearchMain.run(new String[]{"--query", "test"});
        assertEquals(2, exit);
    }

    @Test
    void allPacksReturnedWithNoFilters() throws Exception {
        Path catalog = writeCatalog();
        String out = captureStdout(() -> {
            int exit = PackSearchMain.run(new String[]{"--catalog", catalog.toString()});
            assertEquals(0, exit);
        });
        assertTrue(out.contains("identity"), "expected identity: " + out);
        assertTrue(out.contains("workspace"), "expected workspace: " + out);
        assertTrue(out.contains("project-tracker-demo"), "expected project-tracker-demo: " + out);
    }

    private Path writeCatalog() throws Exception {
        Path catalog = temp.resolve("pack-catalog.json");
        Files.writeString(catalog, CATALOG);
        return catalog;
    }

    private String captureStdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
