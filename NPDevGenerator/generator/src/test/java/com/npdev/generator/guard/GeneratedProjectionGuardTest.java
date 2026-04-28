package com.npdev.generator.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedProjectionGuardTest {

    @Test
    void allowsThinProjectionSources(@TempDir Path tempDir) throws Exception {
        // allowed projection should pass for official samples like simple-contact-intake, simple-user-registry, and medium-expense-approval.
        Path javaFile = tempDir.resolve("src/main/java/com/npdev/generated/services/UserService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.npdev.generated.services;

                public class UserService {
                    public void save() {
                        // runtime resolution happens elsewhere
                    }
                }
                """);

        assertDoesNotThrow(() -> new GeneratedProjectionGuard().assertThinProjection(tempDir));
    }

    @Test
    void rejectsDirectAdapterInstantiation(@TempDir Path tempDir) throws Exception {
        // forbidden projection should fail when an internal field or adapter-specific detail leaks out.
        Path javaFile = tempDir.resolve("src/main/java/com/npdev/generated/services/UserService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.npdev.generated.services;

                public class UserService {
                    public void save() {
                        new NotificationCapabilityAdapter();
                    }
                }
                """);

        assertThrows(IllegalStateException.class, () -> new GeneratedProjectionGuard().assertThinProjection(tempDir));
    }

    @Test
    void rejectsAdapterConditionalSelection(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("src/main/java/com/npdev/generated/services/UserService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.npdev.generated.services;

                public class UserService {
                    public void save(String adapterId) {
                        if (adapterId.equals("notification-inproc")) {
                            System.out.println(adapterId);
                        }
                    }
                }
                """);

        assertThrows(IllegalStateException.class, () -> new GeneratedProjectionGuard().assertThinProjection(tempDir));
    }

    @Test
    void largeModelPerformanceGuardSupports100Concepts(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("src/main/java/com/npdev/generated/services");
        Files.createDirectories(root);
        for (int index = 0; index < 100; index++) {
            Files.writeString(root.resolve("Concept" + index + "Service.java"), """
                    package com.npdev.generated.services;

                    public class ConceptService {
                        public void save() {
                            // large model performance check
                        }
                    }
                    """);
        }

        assertDoesNotThrow(() -> new GeneratedProjectionGuard().assertThinProjection(tempDir));
    }
}
