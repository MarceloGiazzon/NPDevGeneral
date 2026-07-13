package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import com.npdev.kernel.procedures.ProcedureStep;
import com.npdev.kernel.procedures.ProcedureStepType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared execution of a compiled model's declared procedures, used by both the panel runtime
 * (panel action bindings, data-source procedures) and the aggregate runtime (procedure-over-aggregate
 * invoke). Extracting this here breaks the {@code AggregateRuntime -> PanelRuntime} injection cycle:
 * both services depend on this leaf runner instead of on each other.
 */
@Service
public class ProcedureRunner {

    private final CompiledModel compiledModel;
    private final ConceptGateway conceptGateway;
    private final CapabilityDispatcher capabilityDispatcher;
    private final EventBus eventBus;

    @Autowired
    public ProcedureRunner(
            ObjectProvider<CompiledModel> compiledModel,
            ObjectProvider<ConceptGateway> conceptGateway,
            ObjectProvider<CapabilityDispatcher> capabilityDispatcher,
            ObjectProvider<EventBus> eventBus
    ) {
        this(
                compiledModel == null ? null : compiledModel.getIfAvailable(),
                conceptGateway == null ? null : conceptGateway.getIfAvailable(),
                capabilityDispatcher == null ? null : capabilityDispatcher.getIfAvailable(),
                eventBus == null ? null : eventBus.getIfAvailable()
        );
    }

    public ProcedureRunner(
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus
    ) {
        this.compiledModel = compiledModel;
        this.conceptGateway = conceptGateway;
        this.capabilityDispatcher = capabilityDispatcher;
        this.eventBus = eventBus;
    }

    /** Whether the compiled model declares a procedure with this name. */
    public boolean hasProcedure(String procedureName) {
        return procedureName != null && buildProcedureDefinitions().containsKey(procedureName.trim());
    }

    /**
     * Execute the declared procedure {@code procedureName} with {@code input} as its initial state.
     *
     * @throws IllegalArgumentException if the name is blank or no such procedure is declared
     * @throws IllegalStateException    if no ConceptGateway is available
     */
    public ProcedureExecutionResult execute(
            String procedureName,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        if (procedureName == null || procedureName.isBlank()) {
            throw new IllegalArgumentException("Procedure execution requires a procedure name.");
        }
        Map<String, ProcedureDefinition> procedures = buildProcedureDefinitions();
        ProcedureDefinition definition = procedures.get(procedureName.trim());
        if (definition == null) {
            throw new IllegalArgumentException("Procedure not found: " + procedureName);
        }
        if (conceptGateway == null) {
            throw new IllegalStateException("ConceptGateway is required to execute procedures.");
        }
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                conceptGateway,
                capabilityDispatcher == null ? ProcedureRunner::capabilityUnavailable : capabilityDispatcher,
                eventBus == null ? event -> { } : eventBus,
                procedures,
                com.npdev.kernel.procedures.ProcedureExecutionLimits.defaults(),
                buildQueriesByName()
        );
        return executor.execute(definition, input == null ? Map.of() : input,
                context == null ? ExecutionContext.anonymous() : context);
    }

    private Map<String, ProcedureDefinition> buildProcedureDefinitions() {
        if (compiledModel == null) {
            return Map.of();
        }
        Map<String, ProcedureDefinition> definitions = new LinkedHashMap<>();
        for (CompiledProcedure procedure : compiledModel.getProcedures()) {
            definitions.put(procedure.name(), toProcedureDefinition(procedure));
        }
        return Map.copyOf(definitions);
    }

    /** LIFT-QUERY-P1: lets a {@code runQuery} procedure step resolve its declared query's
     * where/orderBy/limit instead of always returning every row for the concept. */
    private Map<String, com.npdev.dsl.v1.compiled.CompiledQuery> buildQueriesByName() {
        if (compiledModel == null) {
            return Map.of();
        }
        Map<String, com.npdev.dsl.v1.compiled.CompiledQuery> queries = new LinkedHashMap<>();
        for (com.npdev.dsl.v1.compiled.CompiledQuery query : compiledModel.getQueries()) {
            queries.put(query.name(), query);
        }
        return Map.copyOf(queries);
    }

    private static ProcedureDefinition toProcedureDefinition(CompiledProcedure procedure) {
        return new ProcedureDefinition(
                procedure.name(),
                procedure.steps().stream().map(ProcedureRunner::toProcedureStep).toList()
        );
    }

    private static ProcedureStep toProcedureStep(CompiledProcedureStep step) {
        ProcedureStepType type = ProcedureStep.parseType(step.type());
        String target = normalized(step.target());
        String concept = normalized(step.concept());
        return switch (type) {
            case READ_CONCEPT -> ProcedureStep.readConcept(stepName(step), concept, refOf(step.id(), "id"), target);
            case LIST_CONCEPTS -> ProcedureStep.listConcepts(stepName(step), concept, target);
            case RUN_QUERY -> ProcedureStep.runQuery(stepName(step), normalized(step.query()), concept, target);
            case SAVE_CONCEPT -> ProcedureStep.saveConcept(stepName(step), concept, refOf(step.id(), "id"), dataRef(step), target);
            case DELETE_CONCEPT -> ProcedureStep.deleteConcept(stepName(step), concept, refOf(step.id(), "id"));
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
                    step.thenSteps().stream().map(ProcedureRunner::toProcedureStep).toList(),
                    step.elseSteps().stream().map(ProcedureRunner::toProcedureStep).toList()
            );
            case FOR_EACH -> ProcedureStep.forEach(
                    stepName(step),
                    refOf(step.items(), "items"),
                    normalized(step.as()) == null ? "item" : normalized(step.as()),
                    step.steps().stream().map(ProcedureRunner::toProcedureStep).toList()
            );
            case MAP_VALUE -> ProcedureStep.mapValue(stepName(step), refOf(step.value(), "input"), target);
            case RETURN -> ProcedureStep.returnValue(stepName(step), refOf(step.value(), target == null ? "input" : target));
        };
    }

    private static CapabilityResult capabilityUnavailable(com.npdev.kernel.CapabilityCall call, Map<String, Object> state) {
        return CapabilityResult.failure(
                "CAPABILITY_UNAVAILABLE",
                "Procedure execution has no capability dispatcher for " + (call == null ? "" : call.capability()),
                CapabilityErrorKind.PERMANENT,
                Map.of()
        );
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
        return name == null ? "panel-procedure-step" : name;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
