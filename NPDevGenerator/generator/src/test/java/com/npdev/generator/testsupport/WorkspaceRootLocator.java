package com.npdev.generator.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the NPDev_General workspace root from a test's working directory, for the small number of
 * tests that legitimately assert against a path elsewhere in the repo rather than against emitter
 * output.
 *
 * <p>Extracted from {@code MigrationAuthorityQuarantineAssertions}, which established the pattern,
 * when {@code PlatformColumnContractTest} became its second caller -- deliberately, so a third
 * caller does not become a third copy of "walk up until you find NPDevGenerator".
 */
public final class WorkspaceRootLocator {
    private WorkspaceRootLocator() {
    }

    /**
     * Walks up from {@code user.dir} until it finds the directory holding all three top-level
     * modules. Gradle sets {@code user.dir} to the subproject directory, so the walk is short.
     */
    public static Path resolveWorkspaceRoot() {
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
