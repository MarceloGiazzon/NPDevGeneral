package com.npdev.kernel.ports;

import com.npdev.kernel.FlowStepDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal invariant engine port. Adapters implement it (CEL, rules, etc).
 */
public interface InvariantEngine {
    /**
     * CRUD contract.
     * Flow runtime must call evaluate(InvariantEvaluationRequest) with explicit invariant refs.
     *
     * @return list of invariant violation messages (empty means OK)
     */
    @Deprecated
    List<String> evaluate(String entityName, Object payload);

    /**
     * v3 contract: kernel executes explicit invariant refs from model-compiled flow steps.
     */
    default InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
        Objects.requireNonNull(request, "request");

        List<Violation> violations = evaluate(
                request.invariantRefs(),
                new EvaluationContext(
                        request.metadata().flowName(),
                        request.conceptName(),
                        request.metadata().checkpoint(),
                        request.payload(),
                        request.state()
                )
        );

        List<Violation> enriched = violations.stream()
                .map(violation -> enrichViolation(violation, request))
                .toList();
        return new InvariantEvaluationResult(enriched);
    }

    /**
     * v2 contract used by sovereign kernel execution.
     * Default implementation supports single-invariant adapters.
     */
    default List<Violation> evaluate(List<String> invariants, EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        List<String> refs = invariants == null ? List.of() : List.copyOf(invariants);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("invariants must not be empty");
        }
        if (refs.size() > 1) {
            throw new IllegalStateException(
                    "InvariantEngine adapter must implement evaluate(List<String>, EvaluationContext) "
                            + "for multi-invariant evaluation; single-invariant evaluation cannot map violations "
                            + "to invariantRefs."
            );
        }

        List<String> messages = evaluate(context.entityName(), context.payload());
        String singleRef = refs.get(0);
        return messages.stream()
                .map(message -> new Violation(
                        "INVARIANT_FAIL",
                        message,
                        singleRef,
                        context.entityName(),
                        context.flowName(),
                        null,
                        null,
                        Map.of("requestedInvariantRefs", refs)
                ))
                .toList();
    }

    record EvaluationContext(
            String flowName,
            String entityName,
            FlowStepDefinition.InvariantCheckpoint checkpoint,
            Object payload,
            Map<String, Object> state
    ) {
        public EvaluationContext {
            if (flowName == null || flowName.isBlank()) {
                throw new IllegalArgumentException("flowName must be non-blank");
            }
            if (entityName == null || entityName.isBlank()) {
                throw new IllegalArgumentException("entityName must be non-blank");
            }
            if (checkpoint == null) {
                throw new IllegalArgumentException("checkpoint must be non-null");
            }
            if (state == null) {
                state = Map.of();
            } else {
                state = Map.copyOf(state);
            }
        }
    }

    record InvariantEvaluationRequest(
            String conceptName,
            Object payload,
            List<String> invariantRefs,
            EvaluationMetadata metadata,
            Map<String, Object> state
    ) {
        public InvariantEvaluationRequest {
            if (conceptName == null || conceptName.isBlank()) {
                throw new IllegalArgumentException("conceptName must be non-blank");
            }
            if (invariantRefs == null || invariantRefs.isEmpty()) {
                throw new IllegalArgumentException("invariantRefs must not be empty");
            }
            invariantRefs = List.copyOf(invariantRefs);
            metadata = Objects.requireNonNull(metadata, "metadata");
            state = state == null ? Map.of() : Map.copyOf(state);
        }
    }

    record EvaluationMetadata(
            String flowName,
            String stepName,
            Integer stepIndex,
            FlowStepDefinition.InvariantCheckpoint checkpoint,
            String correlationId
    ) {
        public EvaluationMetadata {
            if (flowName == null || flowName.isBlank()) {
                throw new IllegalArgumentException("flowName must be non-blank");
            }
            if (stepName == null || stepName.isBlank()) {
                throw new IllegalArgumentException("stepName must be non-blank");
            }
            if (stepIndex == null || stepIndex < 0) {
                throw new IllegalArgumentException("stepIndex must be >= 0");
            }
            if (checkpoint == null) {
                throw new IllegalArgumentException("checkpoint must be non-null");
            }
        }
    }

    record InvariantEvaluationResult(List<Violation> violations) {
        public InvariantEvaluationResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public static InvariantEvaluationResult ok() {
            return new InvariantEvaluationResult(List.of());
        }
    }

    /**
     * R4.4: one declared aggregate invariant, in the shape this port needs to evaluate it. A
     * deliberate mirror of the DSL's {@code CompiledAggregateInvariant} rather than that type
     * itself -- the kernel's ports stay free of DSL types (no other port takes one), and the
     * caller that HAS the compiled model, {@code AggregateRuntime}, does the two-field mapping.
     */
    record AggregateInvariantSpec(String name, String expression, String message) {
        public AggregateInvariantSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("expression must be non-blank");
            }
        }
    }

    /**
     * R4.4: evaluates an aggregate's declared invariants against its DRAFT tree -- root fields
     * bound by name plus every declared collection bound by name to its list of row maps, the same
     * shape {@code AggregateRuntime} already hands a declared {@code onValidate} procedure. Runs in
     * that same pre-commit slot, before any write exists to roll back.
     *
     * <p>Defaults to "no violations" so an adapter that does not evaluate expressions is unaffected;
     * the CEL adapter overrides it. Each returned {@link Violation} carries the failing invariant's
     * name in {@code invariantRef}, which is what lets the commit API name the rule that vetoed.</p>
     */
    default List<Violation> evaluateAggregateInvariants(
            String aggregateName,
            String rootConcept,
            List<AggregateInvariantSpec> invariants,
            Map<String, Object> draftTree
    ) {
        return List.of();
    }

    record Violation(
            String code,
            String message,
            String invariantRef,
            String conceptName,
            String flowName,
            String stepName,
            Integer stepIndex,
            Map<String, Object> details
    ) {
        public Violation(String code, String message, String invariantRef) {
            this(code, message, invariantRef, null, null, null, null, Map.of());
        }

        public Violation {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must be non-blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must be non-blank");
            }
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    private static Violation enrichViolation(Violation violation, InvariantEvaluationRequest request) {
        if (violation == null) {
            if (request.invariantRefs().size() > 1) {
                throw new IllegalStateException(
                        "Invariant engine returned null violation for multi-invariant evaluation"
                );
            }
            return new Violation(
                    "INVARIANT_FAIL",
                    "Invariant engine returned null violation",
                    request.invariantRefs().get(0),
                    request.conceptName(),
                    request.metadata().flowName(),
                    request.metadata().stepName(),
                    request.metadata().stepIndex(),
                    Map.of("requestedInvariantRefs", request.invariantRefs())
            );
        }

        String invariantRef = violation.invariantRef();
        if (invariantRef == null || invariantRef.isBlank()) {
            if (request.invariantRefs().size() == 1) {
                invariantRef = request.invariantRefs().get(0);
            } else {
                throw new IllegalStateException(
                        "Invariant violation missing invariantRef for multi-invariant evaluation"
                );
            }
        } else if ("<unknown>".equalsIgnoreCase(invariantRef.trim())) {
            if (request.invariantRefs().size() == 1) {
                invariantRef = request.invariantRefs().get(0);
            } else {
                throw new IllegalStateException(
                        "Invariant violation missing invariantRef for multi-invariant evaluation"
                );
            }
        }
        String conceptName = violation.conceptName() == null || violation.conceptName().isBlank()
                ? request.conceptName()
                : violation.conceptName();
        String flowName = violation.flowName() == null || violation.flowName().isBlank()
                ? request.metadata().flowName()
                : violation.flowName();
        String stepName = violation.stepName() == null || violation.stepName().isBlank()
                ? request.metadata().stepName()
                : violation.stepName();
        Integer stepIndex = violation.stepIndex() == null
                ? request.metadata().stepIndex()
                : violation.stepIndex();

        return new Violation(
                violation.code(),
                violation.message(),
                invariantRef,
                conceptName,
                flowName,
                stepName,
                stepIndex,
                violation.details()
        );
    }
}
