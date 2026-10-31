package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model whose flows persist but that declares no persistence capability/binding gets both derived
 *  (persistence -> repository); it used to compile and then refuse to boot. */
class ImplicitPersistenceTest {

    private static final String FLOW = """
            "flows": [{"name": "CreateRoom", "input": {"concept": "Room", "mode": "create"},
              "steps": [{"name": "save-room", "type": "createConcept", "scope": "Room", "input": "$input", "output": "$saved"},
                        {"name": "return-room", "type": "return", "value": "$saved"}]}]
            """;

    private static String model(String extra) {
        return """
                {"namespace": "demo.rooms", "dslVersion": "1.0.0", "version": "1.0",
                 "concepts": [{"name": "Room", "fields": [{"name": "id", "type": "uuid", "id": true},
                                                          {"name": "name", "type": "string", "required": true}]}],
                """ + extra + "}";
    }

    @Test
    void aPersistingFlowWithNoDeclarationGetsTheRepositoryBindingAndTheCapability() throws Exception {
        CompiledModel compiled = compile(model(FLOW));
        CompiledCapability persistence = only(compiled.getCapabilities());
        assertEquals("persistence", persistence.getName());
        assertEquals("PersistenceCapability", persistence.getType());
        assertTrue(persistence.getOperations().stream().anyMatch(op -> !op.getName().isBlank()));
        assertEquals(List.of("persistence:repository"), bindings(compiled));
    }

    @Test
    void aBindingWithoutTheCapabilityGetsTheCapability() throws Exception {
        CompiledModel compiled = compile(model(
                "\"bindings\": [{\"capability\": \"persistence\", \"adapter\": \"repository\"}],\n" + FLOW));
        assertEquals("persistence", only(compiled.getCapabilities()).getName());
        assertEquals(List.of("persistence:repository"), bindings(compiled));
    }

    @Test
    void anExplicitBindingWins() throws Exception {
        CompiledModel compiled = compile(model(
                "\"capabilities\": [{\"name\": \"persistence\", \"type\": \"PersistenceCapability\", \"operations\": [\"save\"]}],\n"
                        + "\"bindings\": [{\"capability\": \"persistence\", \"adapter\": \"inproc\"}],\n" + FLOW));
        assertEquals(List.of("persistence:inproc"), bindings(compiled));
        assertEquals(List.of("save"), only(compiled.getCapabilities()).getOperations().stream().map(o -> o.getName()).toList());
    }

    @Test
    void aModelWithoutPersistingFlowsGetsNothing() throws Exception {
        CompiledModel compiled = compile(model("\"flows\": []"));
        assertTrue(compiled.getCapabilities().isEmpty());
        assertTrue(compiled.getBindings().isEmpty());
    }

    private static CompiledCapability only(List<CompiledCapability> capabilities) {
        assertEquals(1, capabilities.size(), capabilities.toString());
        return capabilities.get(0);
    }

    private static List<String> bindings(CompiledModel compiled) {
        return compiled.getBindings().stream()
                .map((CompiledCapabilityBinding b) -> b.getCapability() + ":" + b.getAdapter()).toList();
    }

    private static CompiledModel compile(String json) throws Exception {
        Path file = Files.createTempFile("npdev-implicit-persistence-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new ModelCompiler().compile(new JsonModelParser().parse(file));
    }
}
