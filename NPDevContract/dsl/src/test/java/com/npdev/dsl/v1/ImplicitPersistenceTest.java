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
                {"namespace": "demo.rooms", "dslVersion": "3.0.0", "version": "1.0",
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
        assertEquals(List.of("eventBus:inproc", "persistence:repository"), bindings(compiled));
    }

    @Test
    void theRemovedRootBindingsKeyIsRejectedWithAMigrationHint() throws Exception {
        Path file = Files.createTempFile("npdev-implicit-persistence-", ".json");
        Files.writeString(file, model(
                "\"bindings\": [{\"capability\": \"persistence\", \"adapter\": \"repository\"}],\n" + FLOW),
                StandardCharsets.UTF_8);
        com.npdev.dsl.v1.parser.DeprecationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.npdev.dsl.v1.parser.DeprecationException.class, () -> new JsonModelParser().parse(file));
        assertEquals("LEGACY_BINDINGS_ROOT_KEY", ex.getDiagnostic().getCode());
    }

    @Test
    void aCapabilityAdapterWinsOverTheDefault() throws Exception {
        CompiledModel compiled = compile(model(
                "\"capabilities\": [{\"name\": \"persistence\", \"type\": \"PersistenceCapability\", \"adapter\": \"inproc\", \"operations\": [\"save\"]}],\n"
                        + FLOW));
        assertEquals(List.of("eventBus:inproc", "persistence:inproc"), bindings(compiled));
        assertEquals(List.of("save"), only(compiled.getCapabilities()).getOperations().stream().map(o -> o.getName()).toList());
    }

    @Test
    void aModelWithoutPersistingFlowsGetsNothing() throws Exception {
        CompiledModel compiled = compile(model("\"flows\": []"));
        assertTrue(compiled.getCapabilities().isEmpty());
        assertTrue(compiled.getBindings().isEmpty());
    }

    @Test
    void aDeclaredPersistenceCapabilityWithNoPersistingFlowStillGetsTheRepositoryBinding() throws Exception {
        CompiledModel compiled = compile(model(
                "\"capabilities\": [{\"name\": \"persistence\", \"type\": \"PersistenceCapability\", \"operations\": [\"save\"]}],\n"
                        + "\"flows\": []"));
        assertEquals(List.of("persistence:repository"), bindings(compiled));
    }

    @Test
    void aDifferentlyNamedPersistenceCapabilityIsBoundByTypeToTheRepository() throws Exception {
        // a pack-qualified capability (pos::persistence) is not the bare "persistence" name, so the default binds by type
        CompiledModel compiled = compile(model(
                "\"capabilities\": [{\"name\": \"ledger\", \"type\": \"PersistenceCapability\", \"operations\": [\"save\"]}],\n"
                        + "\"flows\": []"));
        assertEquals(List.of("ledger:repository"), bindings(compiled));
    }

    @Test
    void aCapabilityAdapterIsItsBindingAndNotificationDefaultsAreDerived() throws Exception {
        CompiledModel compiled = compile(model(
                "\"capabilities\": [{\"name\": \"notify\", \"type\": \"NotificationCapability\", \"operations\": [\"send\"]},"
                        + " {\"name\": \"audit\", \"type\": \"AuditLogCapability\", \"adapter\": \"plugin:java-source\", \"operations\": [\"record\"]}],\n"
                        + FLOW));
        assertEquals(List.of("audit:plugin:java-source", "eventBus:inproc", "notify:notification-inproc",
                "persistence:repository"), bindings(compiled));
    }

    @Test
    void configAdapterOverridesReplaceADerivedBinding() throws Exception {
        Path file = Files.createTempFile("npdev-implicit-persistence-", ".json");
        Files.writeString(file, model(FLOW), StandardCharsets.UTF_8);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(file),
                java.util.Map.of("persistence", "memory"));
        assertEquals(List.of("eventBus:inproc", "persistence:memory"), bindings(compiled));
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
