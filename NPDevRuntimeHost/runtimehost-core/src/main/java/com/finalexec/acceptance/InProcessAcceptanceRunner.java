package com.finalexec.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finalexec.npdev.service.ProcedureRunner;
import com.npdev.adapters.events.inproc.InProcEventBus;
import com.npdev.adapters.flowcompiled.ModelBackedKernelRuntimeFactory;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.runtime.support.CelInvariantEngine;
import com.npdev.runtime.support.ReloadableInvariantEngine;
import com.npdev.kernel.procedures.ProcedureExecutionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs the PROCEDURE and FLOW forms of {@code *.scenario.json} in-process: the compiled model, an
 * {@link InMemoryConceptStore} behind the governed concept gateway, and the SAME
 * {@link ProcedureRunner} a generated app uses (so {@code runQuery} honours the query's
 * where/orderBy and {@code callCapability} resolves adapters exactly as in the app). No generate,
 * no app build, no boot.
 *
 * <p>It only OBSERVES: it writes {@code {ok, failureCode, failureMessage, result, concepts}} per
 * scenario and the {@code then} assertions are evaluated by NPDevCli's existing
 * {@code _eval_assertion}, so there is one assertion grammar, not two.
 *
 * <p>Seed rows ({@code given[].rows}) go straight into the store and bypass invariants,
 * permissions and lifecycle -- like a database fixture. The procedure under test then runs through
 * the governed gateway, so its own writes ARE validated. Capabilities answer only from the
 * scenario's {@code stubs}; an unstubbed capability fails with {@code CAPABILITY_NOT_STUBBED}
 * rather than ever reaching a real vendor.
 *
 * <p>A flow scenario ({@code when.flow}) runs the named flow through the SAME {@link KernelRunner}
 * wiring the CLI uses (compiled flow definitions, invariant engine whose unique/scope lookups read the
 * seeded store). Kernel flows act through capabilities, not the concept gateway, so the observed
 * {@code concepts} are the seeded rows unchanged; {@code ok} is {@code status == OK}, and
 * {@code failureCode}/{@code status} carry the kernel's own verdict ({@code WAITING_EVENT} is not ok).
 *
 * <p>Do NOT reuse NPDevGenerator/tools/npdev-cli's procedure converter: it is a divergent twin (no
 * queriesByName, so runQuery ignores {@code where}; no adapter resolution).
 */
public final class InProcessAcceptanceRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private InProcessAcceptanceRunner() {
    }

    /** args: --model &lt;model.json&gt; --scenarios &lt;dir&gt; --out &lt;observed.json&gt; */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        CompiledModel model = compile(Path.of(a.get("--model")));
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(a.get("--scenarios")))) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".scenario.json")).sorted().toList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> scenario = MAPPER.readValue(file.toFile(), Map.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", file.getFileName().toString());
                if (!isInProcess(scenario)) {
                    row.put("skipped", "HTTP scenario (when.path) -- run without --in-process");
                } else {
                    try {
                        row.put("observed", observe(model, scenario));
                    } catch (RuntimeException e) {
                        row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
                out.add(row);
            }
        }
        Path outFile = Path.of(a.get("--out"));
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }
        MAPPER.writeValue(outFile.toFile(), Map.of(
                "schemaVersion", "npdev-acceptance-inprocess-observed.v1", "scenarios", out));
    }

    public static CompiledModel compile(Path modelPath) throws IOException {
        var ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Model validation failed: " + String.join(" | ", errors));
        }
        return new ModelCompiler().compile(ast);
    }

    static boolean isInProcess(Map<String, Object> scenario) {
        return scenario.get("when") instanceof Map<?, ?> when
                && (when.containsKey("procedure") || when.containsKey("flow"));
    }

    static boolean scenarioHasFlow(Map<String, Object> scenario) {
        return scenario.get("when") instanceof Map<?, ?> when && when.containsKey("flow");
    }

    /** One fresh in-memory store per scenario: scenarios never see each other's rows. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> observe(CompiledModel model, Map<String, Object> scenario) {
        Map<String, Object> when = (Map<String, Object>) scenario.get("when");
        String tenant = String.valueOf(when.getOrDefault("tenant", "default"));
        List<String> roles = (List<String>) when.getOrDefault("roles", List.of("ADMIN"));
        ExecutionContext ctx = ExecutionContext.of(tenant, "acceptance").withRoles(new LinkedHashSet<>(roles));

        InMemoryConceptStore store = new InMemoryConceptStore(model);
        for (Map<String, Object> given : (List<Map<String, Object>>) scenario.getOrDefault("given", List.of())) {
            String concept = String.valueOf(given.get("concept"));
            for (Map<String, Object> rowData : (List<Map<String, Object>>) given.getOrDefault("rows", List.of())) {
                Map<String, Object> data = new LinkedHashMap<>(rowData);
                String id = String.valueOf(data.computeIfAbsent("id", k -> UUID.randomUUID().toString()));
                store.save(new ConceptRecord(concept, id, tenant, data));
            }
        }
        ConceptGateway gateway = DefaultConceptGateway.governedBy(store, model);

        Map<String, Object> stubs = (Map<String, Object>) scenario.getOrDefault("stubs", Map.of());
        CapabilityDispatcher dispatcher = (CapabilityCall call, Map<String, Object> state) -> {
            String key = call.capability() + "." + call.operation();
            return stubs.containsKey(key)
                    ? CapabilityResult.success(stubs.get(key))
                    : CapabilityResult.failure("CAPABILITY_NOT_STUBBED",
                            "in-process acceptance: no stubs[\"" + key + "\"] in the scenario",
                            CapabilityErrorKind.PERMANENT, Map.of());
        };

        Map<String, Object> input = (Map<String, Object>) when.getOrDefault("input", Map.of());
        Map<String, Object> observed = new LinkedHashMap<>();
        if (when.containsKey("flow")) {
            ReloadableInvariantEngine invariants = new ReloadableInvariantEngine(model,
                    (tenantId, conceptName) -> CelInvariantEngine.Lookups.fromConceptStore(store, tenantId));
            KernelRunner kernel = ModelBackedKernelRuntimeFactory.createKernelRunner(
                    model, new InProcEventBus(), invariants, dispatcher, ExecutionTracer.NOOP);
            ExecutionResult result = kernel.execute(String.valueOf(when.get("flow")), new HashMap<>(input), ctx);
            observed.put("ok", result.getStatus() == ExecutionStatus.OK);
            observed.put("status", result.getStatus().name());
            observed.put("failureCode", result.getErrorCode());
            observed.put("failureMessage", result.getError());
            observed.put("result", result.getOutput());
        } else {
            ProcedureRunner runner = new ProcedureRunner(model, gateway, dispatcher, null);
            String procedure = String.valueOf(when.get("procedure"));
            ProcedureExecutionResult result = runner.execute(procedure, new HashMap<>(input), ctx);
            observed.put("ok", result.ok());
            observed.put("failureCode", result.failureCode());
            observed.put("failureMessage", result.failureMessage());
            // Same rule AggregateRuntime.invoke applies: a `return` step's value IS the result.
            observed.put("result", result.state().containsKey("return") ? result.state().get("return") : result.state());
        }

        // Snapshot every declared concept that has rows now (seeded or written by the procedure).
        Map<String, Object> concepts = new TreeMap<>();
        model.getConcepts().forEach(c -> {
            List<ConceptRecord> rows = gateway.list(new ConceptListRequest(c.getName(), tenant), ctx);
            if (!rows.isEmpty()) {
                concepts.put(c.getName(), rows.stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r.data());
                    m.put("id", r.id());
                    return m;
                }).toList());
            }
        });
        observed.put("concepts", concepts);
        return observed;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            out.put(args[i], args[i + 1]);
        }
        for (String required : List.of("--model", "--scenarios", "--out")) {
            if (!out.containsKey(required)) {
                throw new IllegalArgumentException("missing " + required);
            }
        }
        return out;
    }
}
