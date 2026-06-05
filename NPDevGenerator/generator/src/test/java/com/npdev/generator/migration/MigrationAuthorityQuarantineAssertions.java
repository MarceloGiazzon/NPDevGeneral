package com.npdev.generator.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class MigrationAuthorityQuarantineAssertions {
    private MigrationAuthorityQuarantineAssertions() {
    }

    static void assertOldMigrationAuthorityAbsent() {
        Path workspaceRoot = resolveWorkspaceRoot();
        assertFalse(
                Files.exists(workspaceRoot.resolve("NPDevGenerator/generator/src/main/java/com/npdev/generator/migration")),
                "Old active generator migration/model-diff package must remain quarantined outside src/main/java."
        );
        assertFalse(
                hasVersionedMigration(workspaceRoot.resolve("NPDevGenerator/db-history/src/main/resources/db/migration")),
                "Old V5001..V5014 db-history migration SQL must not return."
        );
        assertFalse(
                hasVersionedMigration(workspaceRoot.resolve("NPDevRuntimeHost/src/main/resources/db/migration")),
                "Old V5001..V5014 RuntimeHost migration SQL must not return."
        );
    }

    private static boolean hasVersionedMigration(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.matches("^V50(0[1-9]|1[0-4])__.*\\.sql$"));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to inspect migration directory " + dir, exception);
        }
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("NPDevGenerator"))
                    && Files.isDirectory(current.resolve("NPDevContract"))
                    && Files.isDirectory(current.resolve("NPDevRuntimeHost"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to resolve workspace root from " + System.getProperty("user.dir"));
    }
}
