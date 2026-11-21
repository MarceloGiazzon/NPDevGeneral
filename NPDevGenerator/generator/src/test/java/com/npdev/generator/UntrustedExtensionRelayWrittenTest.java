package com.npdev.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.emitters.UntrustedExtensionEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REG-355: the app folder a browser AI's Java class leaves behind (model.json naming it from a flow, extensions/Greeter.java,
 * untrusted-extension-manifest.json whose entry the RELAY wrote, sha256 included) is accepted by the generator's own hash-pinned seam.
 * The fixture is the output of NPDevCli/npdev_relay_extensions.manifest_text; NPDevCli/tests/test_relay_extensions.py pins the same
 * fixture to that function, so a change on either side fails one of the two tests.
 */
class UntrustedExtensionRelayWrittenTest {

    private static final Path FIXTURE = Path.of("src/test/resources/relay-java-extension");

    @TempDir
    Path temp;

    private Path stage() throws IOException {
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                Path target = temp.resolve(FIXTURE.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return temp.resolve("model.json");
    }

    private void emit(Path modelPath, Path out) throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        new UntrustedExtensionEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model, modelPath);
    }

    @Test
    void theFolderTheRelayWritesGeneratesTheTrustedClassBoundToTheFlowAndTheRole() throws Exception {
        Path out = Files.createTempDirectory("npdev-relay-java-out-");
        emit(stage(), out);
        Path trusted = out.resolve("src/main/java/com/npdev/generated/trusted");
        assertTrue(Files.isRegularFile(trusted.resolve("Greeter.java")), "the pinned class is generated");
        String registry = Files.readString(trusted.resolve("GeneratedActionRegistry.java"));
        assertTrue(registry.contains("\"GreetFlow\"") && registry.contains("\"ADMIN\""),   // the platform upper-cases the role
                registry);
    }

    @Test
    void aClassChangedAfterTheRelayPinnedItIsRefusedByTheHash() throws Exception {
        Path modelPath = stage();
        Files.writeString(temp.resolve("extensions/Greeter.java"),
                Files.readString(temp.resolve("extensions/Greeter.java")) + "// edited after pinning\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> emit(modelPath, Files.createTempDirectory("npdev-relay-java-out-")));
        assertEquals(true, failure.getMessage().toLowerCase().contains("hash") || failure.getMessage().toLowerCase().contains("sha"),
                failure.getMessage());
    }
}
