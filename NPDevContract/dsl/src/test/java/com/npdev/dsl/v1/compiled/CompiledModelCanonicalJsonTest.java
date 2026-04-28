package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledModelCanonicalJsonTest {

    @Test
    void canonicalDemoProducesByteStableCanonicalJsonAcrossRepeatedCompilations() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("resources", "Models", "canonical-demo", "model.json"),
                Path.of("..", "resources", "Models", "canonical-demo", "model.json")
        ));

        ModelAst firstAst = new JsonModelParser().parse(modelPath);
        ModelAst secondAst = new JsonModelParser().parse(modelPath);

        String first = CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(firstAst));
        String second = CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(secondAst));

        assertEquals(first, second, "Compiled model canonical JSON must be byte-for-byte deterministic for identical inputs.");
        assertTrue(first.contains("\"Appointment\""), "Expected canonical JSON to contain the Appointment concept.");
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve canonical-demo model path.");
    }
}
