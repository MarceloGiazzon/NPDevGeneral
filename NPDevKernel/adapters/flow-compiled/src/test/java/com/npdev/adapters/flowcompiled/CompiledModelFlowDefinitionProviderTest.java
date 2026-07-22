package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledModelFlowDefinitionProviderTest {

    @Test
    void providerMapsCompiledFlowToKernelFlowDefinition() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true },
                        { "name":"email", "type":"string", "required":true }
                      ],
                      "invariants": [
                        { "name":"EmailRequired", "expr":"email != null" },
                        { "name":"EmailUnique", "rule":"unique(email)" }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name":"persistence", "type":"PersistenceCapability", "operations":["save"] }
                  ],
                  "bindings": [
                    { "capability":"persistence", "adapter":"inmemory" }
                  ],
                  "events": [
                    { "name":"UserCreated", "payload":[{"name":"id","type":"uuid"}] }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "input": { "concept":"User", "mode":"create" },
                      "inputSchema": {
                        "type": "object",
                        "required": ["email"],
                        "properties": {
                          "email": { "type": "string" }
                        }
                      },
                      "steps": [
                        { "name":"validate", "type":"enforceInvariants", "scope":"User", "invariants":["EmailRequired","EmailUnique"] },
                        { "name":"save", "type":"capabilityCall", "cap":"persistence", "op":"save", "args":["$input"], "out":"$saved" },
                        { "name":"emit", "type":"emitEvent", "event":"UserCreated", "from":"$saved" },
                        { "name":"ret", "type":"return", "value":"$saved" }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        FlowDefinition flow = provider.getFlow("CreateUser");

        assertEquals("CreateUser", flow.getName());
        assertEquals("User", flow.getEntityName());
        assertEquals(4, flow.getSteps().size());
        assertEquals("object", flow.getInputSchema().getType());
        assertEquals(List.of("email"), flow.getInputSchema().getRequired());
        assertEquals("string", flow.getInputSchema().getProperties().get("email").getType());

        FlowStepDefinition invariant = flow.getSteps().get(0);
        assertEquals(FlowStepDefinition.Type.INVARIANT_CHECK, invariant.getType());
        assertEquals("User", invariant.getInvariantScope());
        assertEquals(List.of("EmailRequired", "EmailUnique"), invariant.getInvariants());
        assertEquals(FlowStepDefinition.InvariantCheckpoint.PRE, invariant.getCheckpoint());

        FlowStepDefinition capabilityStep = flow.getSteps().get(1);
        assertEquals(FlowStepDefinition.Type.CAPABILITY_CALL, capabilityStep.getType());
        assertEquals("inmemory", capabilityStep.getCapabilityAdapterId());
    }

    @Test
    void providerMapsCompiledForEachStepAndExecutesEachLoopIterationOnce() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "wms",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name":"id", "type":"uuid", "id":true, "required":true } ] }
                  ],
                  "events": [
                    { "name":"OrderProcessed", "payload":[{"name":"id","type":"uuid"}] }
                  ],
                  "flows": [
                    {
                      "name": "ProcessOrders",
                      "input": { "concept":"Order", "mode":"update" },
                      "steps": [
                        { "name":"process-orders", "type":"forEach", "collection":"input.orders", "itemKey":"order",
                          "maxLoopIterations": 5,
                          "steps": [
                            { "type":"emitEvent", "event":"OrderProcessed", "from":"$order" }
                          ]
                        },
                        { "type":"return", "value":"$input" }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        FlowDefinition flow = provider.getFlow("ProcessOrders");

        FlowStepDefinition forEachStep = flow.getSteps().get(0);
        assertEquals(FlowStepDefinition.Type.FOR_EACH, forEachStep.getType());
        assertEquals("input.orders", forEachStep.getCollectionRef());
        assertEquals("order", forEachStep.getItemKey());
        assertEquals(5, forEachStep.getMaxLoopIterations());
        assertEquals(1, forEachStep.getLoopSteps().size());
        assertEquals(FlowStepDefinition.Type.EMIT_EVENT, forEachStep.getLoopSteps().get(0).getType());

        List<EventEnvelope> published = new ArrayList<>();
        ForEachEventInfrastructure eventInfrastructure = new ForEachEventInfrastructure(published);
        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                provider,
                (call, state) -> com.npdev.kernel.CapabilityResult.success(null),
                eventInfrastructure
        );

        Map<String, Object> input = Map.of(
                "id", "11111111-1111-1111-1111-111111111111",
                "orders", List.of(
                        Map.of("id", "22222222-2222-2222-2222-222222222222"),
                        Map.of("id", "33333333-3333-3333-3333-333333333333")
                )
        );

        ExecutionResult result = runner.execute("ProcessOrders", input);

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(
                List.of("22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333"),
                published.stream().map(event -> String.valueOf(event.payload().get("id"))).toList(),
                "each item in the compiled forEach's collection must be processed exactly once, in order"
        );
    }

    @Test
    void providerThrowsUnknownFlowException() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "EmitInvoice",
                      "input": { "concept":"Invoice", "mode":"update" },
                      "steps": [
                        { "type":"return", "value":"$input" }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        assertThrows(UnknownFlowException.class, () -> provider.getFlow("MissingFlow"));
    }

    @Test
    void providerAcceptsCanonicalConceptPersistenceFlowActions() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "ExpenseRequest",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true },
                        { "name":"amount", "type":"integer", "required":true }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name":"persistence", "type":"PersistenceCapability", "operations":["save"] }
                  ],
                  "bindings": [
                    { "capability":"persistence", "adapter":"inmemory" }
                  ],
                  "flows": [
                    {
                      "name": "SubmitExpense",
                      "input": { "concept":"ExpenseRequest", "mode":"create" },
                      "steps": [
                        { "name":"create-expense", "type":"createConcept", "scope":"ExpenseRequest", "input":"$input", "out":"$created" },
                        { "name":"update-expense", "type":"updateConcept", "scope":"ExpenseRequest", "input":"$created", "out":"$updated" },
                        { "name":"ret", "type":"return", "value":"$updated" }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        FlowDefinition flow = provider.getFlow("SubmitExpense");

        assertEquals(3, flow.getSteps().size());
        FlowStepDefinition createStep = flow.getSteps().get(0);
        FlowStepDefinition updateStep = flow.getSteps().get(1);
        assertEquals(FlowStepDefinition.Type.CAPABILITY_CALL, createStep.getType());
        assertEquals(FlowStepDefinition.Type.CAPABILITY_CALL, updateStep.getType());
        assertEquals("persistence", createStep.getCapability());
        assertEquals("persistence", updateStep.getCapability());
        assertEquals("save", createStep.getOperation());
        assertEquals("save", updateStep.getOperation());
        assertEquals("inmemory", createStep.getCapabilityAdapterId());
        assertEquals("inmemory", updateStep.getCapabilityAdapterId());
    }

    @Test
    void providerAssignsStableDefaultStepNamesWhenDslStepNameIsMissing() throws Exception {
        CompiledModel compiled = new CompiledModel(
                "demo",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new com.npdev.dsl.v1.compiled.CompiledFlow(
                        "CreateUser",
                        "User",
                        List.of(
                                new com.npdev.dsl.v1.compiled.CompiledFlowStep(
                                        null,
                                        "event",
                                        null,
                                        null,
                                        List.of(),
                                        "UserCreated",
                                        "$input",
                                        "$input",
                                        null
                                ),
                                new com.npdev.dsl.v1.compiled.CompiledFlowStep(
                                        "",
                                        "return",
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "$input",
                                        null
                                )
                        )
                )),
                List.of()
        );

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        FlowDefinition flow = provider.getFlow("CreateUser");

        assertEquals(2, flow.getSteps().size());
        assertEquals("event-0", flow.getSteps().get(0).getName());
        assertEquals("return-1", flow.getSteps().get(1).getName());
        assertFalse(flow.getSteps().get(0).getName().isBlank());
        assertFalse(flow.getSteps().get(1).getName().isBlank());
    }

    @Test
    void providerAllowsMissingCapabilityBindingAndKernelReturnsStructuredFailure() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name":"persistence", "type":"PersistenceCapability", "operations":["save"] }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "input": { "concept":"User", "mode":"create" },
                      "steps": [
                        { "type":"capabilityCall", "cap":"persistence", "op":"save", "args":["$input"], "out":"$saved" }
                      ]
                    }
                  ]
                }
                """);

        KernelRunner runner = new KernelRunner(
                envelope -> {
                },
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(compiled),
                new RegistryCapabilityDispatcher(new CapabilityRegistry())
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));
        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals("CAPABILITY_BINDING_MISSING", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.NOT_FOUND, result.getCapabilityError().kind());
    }

    @Test
    void runtimeBehaviorChangesWhenModelFlowOrderChangesWithoutCodeChange() throws Exception {
        CompiledModel firstModel = compile(flowOrderModel("InvoiceDrafted", "InvoiceIssued"));
        CompiledModel secondModel = compile(flowOrderModel("InvoiceIssued", "InvoiceDrafted"));

        List<String> firstOrder = runAndCaptureEventOrder(firstModel);
        List<String> secondOrder = runAndCaptureEventOrder(secondModel);

        assertEquals(List.of("InvoiceDrafted", "InvoiceIssued"), firstOrder);
        assertEquals(List.of("InvoiceIssued", "InvoiceDrafted"), secondOrder);
    }

    @Test
    void runtimeFactoryBuildsKernelRunnerFromModelFile() throws Exception {
        Path modelPath = Files.createTempFile("npdev-runtime-factory-", ".json");
        Files.writeString(modelPath, flowOrderModel("InvoiceDrafted", "InvoiceIssued"), StandardCharsets.UTF_8);

        List<String> order = new ArrayList<>();
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure(order);
        KernelRunner runner = ModelBackedKernelRuntimeFactory.createKernelRunner(
                modelPath,
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                (call, state) -> com.npdev.kernel.CapabilityResult.success(null)
        );

        ExecutionResult result = runner.execute("EmitInvoiceFlow", Map.of("id", "inv-1"));
        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(List.of("InvoiceDrafted", "InvoiceIssued"), order);
    }

    @Test
    void runtimeBehaviorChangesWhenModelBindingChangesWithoutCodeChange() throws Exception {
        CompiledModel inmemoryModel = compile(capabilityBindingModel("inmemory"));
        CompiledModel postgresModel = compile(capabilityBindingModel("postgres"));

        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new BindingAwarePersistenceAdapter("inmemory"));
        registry.register("persistence", "PersistenceCapability", "postgres", new BindingAwarePersistenceAdapter("postgres"));
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        Object first = runAndCaptureCapabilityResult(inmemoryModel, dispatcher);
        Object second = runAndCaptureCapabilityResult(postgresModel, dispatcher);

        assertEquals("inmemory:saved:a@b.com", first);
        assertEquals("postgres:saved:a@b.com", second);
    }

    private static List<String> runAndCaptureEventOrder(CompiledModel model) {
        List<String> order = new ArrayList<>();
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure(order);

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(model),
                (call, state) -> com.npdev.kernel.CapabilityResult.success(null)
        );

        ExecutionResult result = runner.execute("EmitInvoiceFlow", Map.of("id", "inv-1"));
        assertEquals(ExecutionStatus.OK, result.getStatus());
        return order;
    }

    private static Object runAndCaptureCapabilityResult(
            CompiledModel model,
            RegistryCapabilityDispatcher dispatcher
    ) {
        KernelRunner runner = new KernelRunner(
                envelope -> {
                },
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(model),
                dispatcher
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));
        assertEquals(ExecutionStatus.OK, result.getStatus());
        return result.getOutput();
    }

    private static String flowOrderModel(String firstEvent, String secondEvent) {
        return """
                {
                  "namespace": "billing",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true }
                      ]
                    }
                  ],
                  "events": [
                    { "name":"InvoiceDrafted", "payload":[{"name":"id","type":"uuid"}] },
                    { "name":"InvoiceIssued", "payload":[{"name":"id","type":"uuid"}] }
                  ],
                  "flows": [
                    {
                      "name":"EmitInvoiceFlow",
                      "input": { "concept":"Invoice", "mode":"update" },
                      "steps": [
                        { "type":"emitEvent", "event":"%s", "from":"$input" },
                        { "type":"emitEvent", "event":"%s", "from":"$input" },
                        { "type":"return", "value":"$input" }
                      ]
                    }
                  ]
                }
                """.formatted(firstEvent, secondEvent);
    }

    private static String capabilityBindingModel(String adapter) {
        return """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true },
                        { "name":"email", "type":"string", "required":true }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name":"persistence", "type":"PersistenceCapability", "operations":["save"] }
                  ],
                  "bindings": [
                    { "capability":"persistence", "adapter":"%s" }
                  ],
                  "flows": [
                    {
                      "name":"CreateUser",
                      "input": { "concept":"User", "mode":"create" },
                      "steps": [
                        { "type":"capabilityCall", "cap":"persistence", "op":"save", "args":["$input"], "out":"$saved" },
                        { "type":"return", "value":"$saved" }
                      ]
                    }
                  ]
                }
                """.formatted(adapter);
    }

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-compiled-provider-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }

    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        private final List<String> publishedOrder;
        private final List<EventEnvelope> stored = new ArrayList<>();

        private RecordingEventInfrastructure(List<String> publishedOrder) {
            this.publishedOrder = publishedOrder;
        }

        @Override
        public void publish(EventEnvelope event) {
            publishedOrder.add(event.eventName());
        }

        @Override
        public void append(EventEnvelope event) {
            stored.add(event);
        }

        @Override
        public java.util.Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
            return stored.stream()
                    .filter(event -> eventName.equals(event.eventName()) && correlationId.equals(event.correlationId()))
                    .findFirst();
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return stored.stream()
                    .filter(event -> correlationId.equals(event.correlationId()))
                    .toList();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return stored.stream()
                    .filter(event -> eventName.equals(event.eventName()))
                    .toList();
        }
    }

    private static final class ForEachEventInfrastructure implements EventBus, EventStore {
        private final List<EventEnvelope> published;

        private ForEachEventInfrastructure(List<EventEnvelope> published) {
            this.published = published;
        }

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
        }

        @Override
        public void append(EventEnvelope event) {
        }

        @Override
        public java.util.Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return List.of();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return List.of();
        }
    }

    public static final class BindingAwarePersistenceAdapter {
        private final String id;

        public BindingAwarePersistenceAdapter(String id) {
            this.id = id;
        }

        public String save(Object payload) {
            return id + ":saved:" + ((Map<?, ?>) payload).get("email");
        }
    }
}
