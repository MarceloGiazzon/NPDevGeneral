package com.npdev.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.cli.runtime.CliRuntime;
import com.npdev.cli.runtime.CliRuntimeFactory;
import com.npdev.cli.runtime.CliRuntimeOptions;
import com.npdev.cli.runtime.ExecutionDebugReport;
import com.npdev.cli.runtime.ExecutionDebugReportBuilder;
import com.npdev.cli.trace.DebugReportPrinter;
import com.npdev.cli.trace.TracePrinter;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.paths.CanonicalModelPaths;
import com.npdev.dsl.v1.repo.FileSystemModelRepository;
import com.npdev.dsl.v1.repo.ModelArtifact;
import com.npdev.dsl.v1.repo.ModelArtifactManifest;
import com.npdev.dsl.v1.repo.ModelRepository;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import com.npdev.kernel.procedures.ProcedureStep;
import com.npdev.kernel.procedures.ProcedureStepType;
import com.npdev.kernel.trace.FlowTrace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NPDevCliMain {
    private final PrintStream out;
    private final PrintStream err;
    private final ObjectMapper objectMapper;
    private final CliRuntimeFactory runtimeFactory;
    private final TracePrinter tracePrinter;
    private final DebugReportPrinter debugReportPrinter;
    private final ExecutionDebugReportBuilder debugReportBuilder;

    public NPDevCliMain() {
        this(System.out, System.err, new ObjectMapper().findAndRegisterModules());
    }

    NPDevCliMain(PrintStream out, PrintStream err, ObjectMapper objectMapper) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.runtimeFactory = new CliRuntimeFactory(this.objectMapper);
        this.tracePrinter = new TracePrinter();
        this.debugReportPrinter = new DebugReportPrinter();
        this.debugReportBuilder = new ExecutionDebugReportBuilder();
    }

    public static void main(String[] args) {
        int exitCode = new NPDevCliMain().run(args);
        System.exit(exitCode);
    }

    int run(String[] args) {
        try {
            ParsedArgs parsed = ParsedArgs.parse(args);
            if (parsed.command() == null) {
                printUsage();
                return 0;
            }
            if ("help".equals(parsed.command()) || "--help".equals(parsed.command()) || "-h".equals(parsed.command())) {
                printUsage();
                return 0;
            }
            return executeCommand(parsed);
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            printUsage();
            return 2;
        } catch (Exception exception) {
            err.println("CLI failed: " + exception.getMessage());
            return 1;
        }
    }

    private int executeCommand(ParsedArgs args) throws IOException {
        if ("repo-publish".equals(args.command())) {
            return repoPublish(args);
        }
        if ("repo-list".equals(args.command())) {
            return repoList(args);
        }
        if ("repo-show".equals(args.command())) {
            return repoShow(args);
        }

        if ("compile-model".equals(args.command())) {
            return compileModel(args);
        }
        if ("validate-bundle".equals(args.command())) {
            return validateBundle(args);
        }

        Path modelPath = args.pathOrDefault("model", CanonicalModelPaths.defaultModelPath());
        Path simulationPath = args.path("sim");
        Path storeDir = args.path("store-dir");
        Path permissionManifestPath = args.pathOrDefault("permissions", Path.of("resources", "security", "dev.permissions.json"));
        CliRuntime runtime = runtimeFactory.create(new CliRuntimeOptions(modelPath, simulationPath, storeDir, permissionManifestPath));
        try {
            return switch (args.command()) {
                case "execute" -> executeFlow(args, runtime);
                case "publish-event" -> publishEvent(args, runtime);
                case "resume" -> resumeExecution(args, runtime);
                case "trace" -> printTrace(args, runtime);
                case "diagnose" -> diagnoseExecution(args, runtime);
                case "list-executions" -> listExecutions(args, runtime);
                case "list-failures" -> listFailures(args, runtime);
                case "correlation" -> correlationTimeline(args, runtime);
                case "list-flows" -> listFlows(args, runtime);
                case "list-procedures" -> listProcedures(args, runtime);
                case "run-procedure" -> runProcedure(args, runtime, false);
                case "debug-procedure" -> runProcedure(args, runtime, true);
                default -> throw new IllegalArgumentException("Unknown command: " + args.command());
            };
        } finally {
            runtimeFactory.persist(runtime, storeDir);
        }
    }

    private int compileModel(ParsedArgs args) throws IOException {
        Path modelPath = args.pathOrDefault("model", CanonicalModelPaths.defaultModelPath());
        Path outPath = args.pathOrDefault("out", CanonicalModelPaths.defaultCompiledModelPath());

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        if (!validation.getWarnings().isEmpty()) {
            validation.getWarnings().forEach(warning -> err.println("WARNING: " + warning));
        }
        if (validation.hasErrors()) {
            err.println("Model validation failed:");
            validation.getErrors().forEach(error -> err.println(" - " + error));
            return 1;
        }

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModelCanonicalJson.write(outPath, compiled);

        if ("json".equals(args.format())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelPath.toAbsolutePath().normalize().toString());
            payload.put("compiledOut", outPath.toAbsolutePath().normalize().toString());
            payload.put("warnings", validation.getWarnings());
            printJson(payload);
        } else {
            out.println("Compiled model written to: " + outPath.toAbsolutePath().normalize());
            if (!validation.getWarnings().isEmpty()) {
                out.println("Warnings: " + validation.getWarnings().size());
            }
        }
        return 0;
    }

    private int validateBundle(ParsedArgs args) throws IOException {
        Path modelPath = args.pathOrDefault("model", CanonicalModelPaths.defaultModelPath());
        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        CompiledModel compiled = validation.hasErrors() ? null : new ModelCompiler().compile(ast);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelPath.toAbsolutePath().normalize().toString());
        payload.put("valid", !validation.hasErrors());
        payload.put("warnings", validation.getWarnings());
        payload.put("errors", validation.getErrors());
        payload.put("procedureCount", compiled == null ? 0 : compiled.getProcedures().size());
        payload.put("panelCount", compiled == null ? 0 : compiled.getPanels().size());
        payload.put("flowCount", compiled == null ? 0 : compiled.getFlows().size());

        if ("json".equals(args.format())) {
            printJson(payload);
        } else if (validation.hasErrors()) {
            err.println("Bundle validation failed:");
            validation.getErrors().forEach(error -> err.println(" - " + error));
        } else {
            out.println("Bundle validation passed: " + modelPath.toAbsolutePath().normalize());
        }
        return validation.hasErrors() ? 1 : 0;
    }

    private int executeFlow(ParsedArgs args, CliRuntime runtime) throws IOException {
        String flowName = args.required("flow");
        ExecutionContext context = executionContext(args);
        Map<String, Object> input = readJsonObject(args.path("json"));
        ExecutionResult result = runtime.kernelRunner().executeFlow(flowName, input, context);
        printByFormat(args.format(), result);
        return 0;
    }

    private int publishEvent(ParsedArgs args, CliRuntime runtime) throws IOException {
        String eventName = args.required("event");
        String correlationId = args.required("correlation");
        ExecutionContext context = executionContext(args);
        Map<String, Object> payload = readJsonObject(args.path("json"));
        EventEnvelope envelope = runtime.kernelRunner().publishExternalEvent(
                eventName,
                payload,
                correlationId,
                null,
                context
        );
        printByFormat(args.format(), envelope);
        return 0;
    }

    private int resumeExecution(ParsedArgs args, CliRuntime runtime) {
        String executionId = args.required("execution");
        ExecutionResult result = runtime.kernelRunner().resumeExecution(executionId);
        printByFormat(args.format(), result);
        return 0;
    }

    private int printTrace(ParsedArgs args, CliRuntime runtime) throws IOException {
        String executionId = args.required("execution");
        FlowTrace trace = runtime.traceStore().findByExecutionId(executionId).orElse(null);
        if (trace == null) {
            err.println("Trace not found for executionId=" + executionId);
            return 1;
        }
        String format = args.format();
        if ("json".equals(format)) {
            out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
        } else {
            out.println(tracePrinter.printExecutionTrace(trace));
        }

        Path exportPath = args.path("export");
        if (exportPath != null) {
            FlowInstance execution = runtime.flowInstanceStore().findByExecutionId(executionId).orElse(null);
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("execution", execution);
            export.put("trace", trace);
            Files.createDirectories(exportPath.getParent() == null ? Path.of(".") : exportPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(exportPath.toFile(), export);
        }
        return 0;
    }

    private int listExecutions(ParsedArgs args, CliRuntime runtime) {
        String mode = args.getOrDefault("mode", "recent").toLowerCase(Locale.ROOT);
        int limit = args.intOrDefault("limit", 50);
        int offset = args.intOrDefault("offset", 0);
        String tenantId = args.getOrDefault("tenant", "default");

        List<FlowInstance> executions = switch (mode) {
            case "waiting" -> runtime.flowInstanceStore().findWaiting(tenantId, limit, offset);
            case "recent" -> runtime.flowInstanceStore().findRecent(tenantId, limit, offset);
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };

        printByFormat(args.format(), executions);
        return 0;
    }

    private int correlationTimeline(ParsedArgs args, CliRuntime runtime) {
        String correlationId = args.required("id");
        String tenantId = args.getOrDefault("tenant", "default");
        int limit = args.intOrDefault("limit", 50);
        int offset = args.intOrDefault("offset", 0);

        List<EventEnvelope> events = runtime.eventStore().findByCorrelationId(tenantId, correlationId, limit, offset);
        List<FlowInstance> executions = runtime.flowInstanceStore().findByCorrelationId(tenantId, correlationId);
        List<FlowTrace> traces = runtime.traceStore().search(new TraceQuery(
                correlationId,
                null,
                null,
                null,
                null,
                limit,
                offset,
                tenantId,
                null
        ));
        if ("json".equals(args.format())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("correlationId", correlationId);
            payload.put("events", events);
            payload.put("executions", executions);
            payload.put("traces", traces.stream().map(trace -> trace.meta().executionId()).toList());
            printJson(payload);
            return 0;
        }
        out.println(tracePrinter.printCorrelationTimeline(correlationId, events, executions, traces));
        return 0;
    }

    private int diagnoseExecution(ParsedArgs args, CliRuntime runtime) throws IOException {
        String executionId = args.required("execution");
        int limit = args.intOrDefault("limit", 50);
        ExecutionDebugReport report = debugReportBuilder.build(runtime, executionId, limit);
        if (report.execution() == null) {
            err.println("Execution not found for executionId=" + executionId);
            return 1;
        }

        String format = args.format();
        if ("json".equals(format)) {
            printJson(report);
        } else {
            out.println(debugReportPrinter.print(report));
        }

        Path exportPath = args.path("export");
        if (exportPath != null) {
            Files.createDirectories(exportPath.getParent() == null ? Path.of(".") : exportPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(exportPath.toFile(), report);
        }
        return 0;
    }

    private int listFailures(ParsedArgs args, CliRuntime runtime) {
        String tenantId = args.getOrDefault("tenant", "default");
        int limit = args.intOrDefault("limit", 50);
        int offset = args.intOrDefault("offset", 0);

        List<ExecutionSummary> failures = runtime.flowInstanceStore().listFailureSummaries(tenantId, limit, offset);
        printByFormat(args.format(), failures);
        return 0;
    }

    private int listFlows(ParsedArgs args, CliRuntime runtime) {
        List<String> flows = runtime.compiledModel().getFlows().stream().map(CompiledFlow::getName).toList();
        printByFormat(args.format(), flows);
        return 0;
    }

    private int listProcedures(ParsedArgs args, CliRuntime runtime) {
        List<Map<String, Object>> procedures = runtime.compiledModel().getProcedures().stream()
                .map(NPDevCliMain::procedureSummary)
                .toList();
        printByFormat(args.format(), procedures);
        return 0;
    }

    private int runProcedure(ParsedArgs args, CliRuntime runtime, boolean debug) throws IOException {
        String procedureName = args.required("procedure");
        Map<String, ProcedureDefinition> procedures = buildProcedureDefinitions(runtime.compiledModel());
        ProcedureDefinition definition = procedures.get(procedureName);
        if (definition == null) {
            err.println("Procedure not found: " + procedureName);
            return 1;
        }
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                runtime.conceptGateway(),
                runtime.capabilityDispatcher(),
                event -> runtime.eventStore().append(event),
                procedures
        );
        ExecutionContext context = executionContext(args).withTag("executionMode", "headless");
        ProcedureExecutionResult result = executor.execute(definition, readJsonObject(args.path("json")), context);
        if (debug) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("procedure", procedureName);
            payload.put("result", result);
            payload.put("gatewayTrace", runtime.conceptGateway().explain());
            payload.put("events", runtime.eventStore().snapshotEvents());
            printByFormat(args.format(), payload);
        } else {
            printByFormat(args.format(), result);
        }
        return result.ok() ? 0 : 1;
    }

    private ExecutionContext executionContext(ParsedArgs args) {
        String tenant = args.getOrDefault("tenant", "default");
        String actor = args.getOrDefault("actor", "cli");
        String rawRoles = args.getOrDefault("roles", "USER");
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        for (String role : rawRoles.split(",")) {
            if (role != null && !role.isBlank()) {
                roles.add(role.trim());
            }
        }
        return ExecutionContext.of(tenant, actor).withRoles(roles);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(Path path) throws IOException {
        if (path == null) {
            return Map.of();
        }
        Object value = objectMapper.readValue(path.toFile(), Object.class);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Map.copyOf(out);
        }
        return Map.of("value", value);
    }

    private void printByFormat(String format, Object payload) {
        if ("json".equals(format)) {
            printJson(payload);
            return;
        }
        if (payload instanceof ExecutionResult result) {
            out.println(result.getStatus() + " executionId=" + result.getExecutionId());
            return;
        }
        out.println(String.valueOf(payload));
    }

    private void printJson(Object payload) {
        try {
            out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed serializing JSON output", exception);
        }
    }

    private void printUsage() {
        out.println("""
                NPDev CLI

                Commands:
                  compile-model [--model <path>] [--out <file>] [--format text|json]
                  validate-bundle [--model <path>] [--format text|json]
                  execute --model <path> --flow <name> [--tenant <id>] [--actor <id>] [--roles <csv>] [--permissions <file>] [--json <file>] [--sim <file>] [--store-dir <dir>] [--format text|json]
                  run-procedure --model <path> --procedure <name> [--tenant <id>] [--actor <id>] [--roles <csv>] [--permissions <file>] [--json <file>] [--sim <file>] [--store-dir <dir>] [--format text|json]
                  debug-procedure --model <path> --procedure <name> [--tenant <id>] [--actor <id>] [--roles <csv>] [--permissions <file>] [--json <file>] [--sim <file>] [--store-dir <dir>] [--format text|json]
                  publish-event --model <path> --event <name> --correlation <id> [--tenant <id>] [--actor <id>] [--roles <csv>] [--permissions <file>] [--json <file>] [--sim <file>] [--store-dir <dir>] [--format text|json]
                  resume --model <path> --execution <id> [--sim <file>] [--store-dir <dir>] [--format text|json]
                  trace --model <path> --execution <id> [--store-dir <dir>] [--format text|json] [--export <file>]
                  diagnose --model <path> --execution <id> [--limit <n>] [--store-dir <dir>] [--format text|json] [--export <file>]
                  list-executions --model <path> [--mode recent|waiting] [--tenant <id>] [--limit <n>] [--offset <n>] [--store-dir <dir>] [--format text|json]
                  list-failures --model <path> [--tenant <id>] [--limit <n>] [--offset <n>] [--store-dir <dir>] [--format text|json]
                  correlation --model <path> --id <correlationId> [--tenant <id>] [--limit <n>] [--offset <n>] [--store-dir <dir>] [--format text|json]
                  list-flows --model <path> [--format text|json]
                  list-procedures --model <path> [--format text|json]
                """);
    }

    private static Map<String, ProcedureDefinition> buildProcedureDefinitions(CompiledModel compiledModel) {
        Map<String, ProcedureDefinition> definitions = new LinkedHashMap<>();
        for (CompiledProcedure procedure : compiledModel.getProcedures()) {
            definitions.put(procedure.name(), toProcedureDefinition(procedure));
        }
        return Map.copyOf(definitions);
    }

    private static ProcedureDefinition toProcedureDefinition(CompiledProcedure procedure) {
        List<ProcedureStep> steps = procedure.steps().stream()
                .map(NPDevCliMain::toProcedureStep)
                .toList();
        return new ProcedureDefinition(procedure.name(), steps);
    }

    private static Map<String, Object> procedureSummary(CompiledProcedure procedure) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", procedure.name());
        summary.put("parameterCount", procedure.parameters().size());
        summary.put("variableCount", procedure.variables().size());
        summary.put("stepCount", countProcedureSteps(procedure.steps()));
        summary.put("stepTypes", procedureStepTypes(procedure.steps()));
        return summary;
    }

    private static int countProcedureSteps(List<CompiledProcedureStep> steps) {
        int count = 0;
        for (CompiledProcedureStep step : steps) {
            count++;
            count += countProcedureSteps(step.thenSteps());
            count += countProcedureSteps(step.elseSteps());
            count += countProcedureSteps(step.steps());
        }
        return count;
    }

    private static List<String> procedureStepTypes(List<CompiledProcedureStep> steps) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        collectProcedureStepTypes(steps, types);
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private static void collectProcedureStepTypes(List<CompiledProcedureStep> steps, LinkedHashSet<String> types) {
        for (CompiledProcedureStep step : steps) {
            String type = normalized(step.type());
            if (type != null) {
                types.add(type);
            }
            collectProcedureStepTypes(step.thenSteps(), types);
            collectProcedureStepTypes(step.elseSteps(), types);
            collectProcedureStepTypes(step.steps(), types);
        }
    }

    private static ProcedureStep toProcedureStep(CompiledProcedureStep step) {
        ProcedureStepType type = ProcedureStep.parseType(step.type());
        String target = normalized(step.target());
        String concept = normalized(step.concept());
        return switch (type) {
            case READ_CONCEPT -> ProcedureStep.readConcept(
                    stepName(step),
                    concept,
                    refOf(step.id(), "id"),
                    target
            );
            case LIST_CONCEPTS -> ProcedureStep.listConcepts(stepName(step), concept, target);
            case RUN_QUERY -> ProcedureStep.runQuery(stepName(step), normalized(step.query()), concept, target);
            case SAVE_CONCEPT -> ProcedureStep.saveConcept(
                    stepName(step),
                    concept,
                    refOf(step.id(), "id"),
                    dataRef(step),
                    target
            );
            case DELETE_CONCEPT -> ProcedureStep.deleteConcept(stepName(step), concept, refOf(step.id(), "id"));
            case PATCH_CONCEPT -> ProcedureStep.patchConcept(
                    stepName(step),
                    concept,
                    refOf(step.id(), "id"),
                    step.set(),
                    target,
                    step.createIfMissing()
            );
            case CALL_CAPABILITY -> ProcedureStep.callCapability(
                    stepName(step),
                    normalized(step.capability()),
                    "",
                    "",
                    normalized(step.operation()),
                    step.args().values().stream().map(value -> refOf(value, String.valueOf(value))).toList(),
                    target
            );
            case PUBLISH_EVENT -> ProcedureStep.publishEvent(stepName(step), normalized(step.event()), dataRef(step));
            case CALL_PROCEDURE -> ProcedureStep.callProcedure(stepName(step), normalized(step.procedure()), dataRef(step), target);
            case IF -> ProcedureStep.ifThenElse(
                    stepName(step),
                    refOf(step.condition(), "condition"),
                    step.thenSteps().stream().map(NPDevCliMain::toProcedureStep).toList(),
                    step.elseSteps().stream().map(NPDevCliMain::toProcedureStep).toList()
            );
            case FOR_EACH -> ProcedureStep.forEach(
                    stepName(step),
                    refOf(step.items(), "items"),
                    normalized(step.as()) == null ? "item" : normalized(step.as()),
                    step.steps().stream().map(NPDevCliMain::toProcedureStep).toList()
            );
            case MAP_LIST -> ProcedureStep.mapList(
                    stepName(step),
                    refOf(step.items(), "items"),
                    normalized(step.as()) == null ? "item" : normalized(step.as()),
                    step.select(),
                    target
            );
            case MAP_VALUE -> ProcedureStep.mapValue(stepName(step), refOf(step.value(), "input"), target);
            case COMPUTE_VALUE -> ProcedureStep.computeValue(
                    stepName(step), normalized(step.operation()), step.left(), step.right(), target);
            case RETURN -> ProcedureStep.returnValue(stepName(step), refOf(step.value(), target == null ? "input" : target));
        };
    }

    private static String dataRef(CompiledProcedureStep step) {
        if (step.data() != null && !step.data().isEmpty()) {
            Object input = step.data().get("input");
            if (input != null) {
                return refOf(input, "input");
            }
            Object payload = step.data().get("payload");
            if (payload != null) {
                return refOf(payload, "input");
            }
        }
        return "input";
    }

    private static String refOf(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        return text.startsWith("$") ? text.substring(1) : text;
    }

    private static String stepName(CompiledProcedureStep step) {
        String name = normalized(step.name());
        return name == null ? "procedure-step" : name;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private int repoPublish(ParsedArgs args) throws IOException {
        Path modelPath = args.pathOrDefault("model", CanonicalModelPaths.defaultModelPath());
        Path repoModelsDir = args.pathOrDefault("repo-dir", CanonicalModelPaths.defaultRepositoryModelsDir());

        ModelRepository repo = new FileSystemModelRepository(repoModelsDir);
        ModelArtifact artifact = repo.publish(modelPath);

        if ("json".equals(args.format())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", artifact.name());
            payload.put("hash", artifact.hash());
            payload.put("rootDir", artifact.rootDir().toString());
            payload.put("modelPath", artifact.modelJsonPath().toString());
            payload.put("compiledPath", artifact.compiledModelJsonPath().toString());
            payload.put("compiledMetadataPath", artifact.compiledMetadataJsonPath().toString());
            out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } else {
            out.println("Published model:");
            out.println(" - name: " + artifact.name());
            out.println(" - hash: " + artifact.hash());
            out.println(" - dir : " + artifact.rootDir());
        }
        return 0;
    }

    private int repoList(ParsedArgs args) throws IOException {
        Path repoModelsDir = args.pathOrDefault("repo-dir", CanonicalModelPaths.defaultRepositoryModelsDir());
        ModelRepository repo = new FileSystemModelRepository(repoModelsDir);

        List<ModelArtifact> artifacts = repo.list();
        if ("json".equals(args.format())) {
            out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifacts));
            return 0;
        }

        if (artifacts.isEmpty()) {
            out.println("No published models found in: " + repoModelsDir);
            return 0;
        }

        out.println("Published models in: " + repoModelsDir);
        for (ModelArtifact artifact : artifacts) {
            out.println(" - " + artifact.name() + " @ " + artifact.hash());
        }
        return 0;
    }

    private int repoShow(ParsedArgs args) throws IOException {
        Path repoModelsDir = args.pathOrDefault("repo-dir", CanonicalModelPaths.defaultRepositoryModelsDir());
        String name = args.required("name");
        String hash = args.required("hash");

        ModelRepository repo = new FileSystemModelRepository(repoModelsDir);
        Optional<ModelArtifactManifest> manifest = repo.readManifest(name, hash);
        if (manifest.isEmpty()) {
            err.println("Manifest not found for " + name + " @ " + hash + " in " + repoModelsDir);
            return 1;
        }

        if ("json".equals(args.format())) {
            out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest.get()));
        } else {
            ModelArtifactManifest m = manifest.get();
            out.println("Model: " + m.name());
            out.println("Hash : " + m.hash());
            out.println("UTC  : " + m.createdAtUtc());
            out.println("Files:");
            for (Map.Entry<String, String> entry : m.files().entrySet()) {
                out.println(" - " + entry.getKey() + ": " + entry.getValue());
            }
        }
        return 0;
    }

    static final class ParsedArgs {
        private final String command;
        private final Map<String, String> options;

        private ParsedArgs(String command, Map<String, String> options) {
            this.command = command;
            this.options = Map.copyOf(options);
        }

        static ParsedArgs parse(String[] args) {
            if (args == null || args.length == 0) {
                return new ParsedArgs(null, Map.of());
            }
            String command = args[0].trim();
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (!token.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected token: " + token);
                }
                String key = token.substring(2).trim();
                if (key.isBlank()) {
                    throw new IllegalArgumentException("Invalid option: " + token);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    options.put(key, "true");
                    continue;
                }
                options.put(key, args[++index]);
            }
            return new ParsedArgs(command, options);
        }

        String command() {
            return command;
        }

        String required(String key) {
            String value = options.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required option --" + key);
            }
            return value;
        }

        String getOrDefault(String key, String fallback) {
            String value = options.get(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        String format() {
            String value = getOrDefault("format", "text").toLowerCase(Locale.ROOT);
            if (!"json".equals(value) && !"text".equals(value)) {
                throw new IllegalArgumentException("Unsupported --format: " + value);
            }
            return value;
        }

        int intOrDefault(String key, int fallback) {
            String raw = options.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid integer for --" + key + ": " + raw);
            }
        }

        Path path(String key) {
            String value = options.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Path.of(value);
        }

        Path pathOrDefault(String key, Path fallback) {
            Path path = path(key);
            return path == null ? fallback : path;
        }
    }
}
