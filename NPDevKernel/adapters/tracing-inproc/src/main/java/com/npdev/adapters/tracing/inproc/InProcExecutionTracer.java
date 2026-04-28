package com.npdev.adapters.tracing.inproc;

import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.ports.TraceSummaryStore;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.trace.TraceSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InProcExecutionTracer implements ExecutionTracer, TraceStore, TraceSummaryStore {
    public record TraceSignal(
            String type,
            String executionId,
            Integer stepIndex,
            String stepName,
            String stepType,
            long timestampEpochMs
    ) {
    }

    private final Map<String, List<TraceSignal>> signalsByExecutionId = new LinkedHashMap<>();
    private final Map<String, FlowTrace> flowTraceByExecutionId = new LinkedHashMap<>();

    @Override
    public synchronized void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
        appendSignal(meta.executionId(), new TraceSignal(
                "FlowStart",
                meta.executionId(),
                null,
                null,
                null,
                startedAtEpochMs
        ));
    }

    @Override
    public synchronized void onStepStart(
            FlowTraceMeta meta,
            int stepIndex,
            String stepName,
            String stepType,
            long startedAtEpochMs
    ) {
        appendSignal(meta.executionId(), new TraceSignal(
                "StepStart",
                meta.executionId(),
                stepIndex,
                stepName,
                stepType,
                startedAtEpochMs
        ));
    }

    @Override
    public synchronized void onStepEnd(FlowTraceMeta meta, StepTrace stepTrace) {
        appendSignal(meta.executionId(), new TraceSignal(
                "StepEnd",
                meta.executionId(),
                stepTrace.stepIndex(),
                stepTrace.stepName(),
                stepTrace.stepType(),
                stepTrace.endedAtEpochMs()
        ));
    }

    @Override
    public synchronized void onFlowEnd(FlowTrace flowTrace) {
        save(flowTrace);
        appendSignal(flowTrace.meta().executionId(), new TraceSignal(
                "FlowEnd",
                flowTrace.meta().executionId(),
                null,
                null,
                null,
                flowTrace.endedAtEpochMs()
            ));
    }

    @Override
    public synchronized void save(FlowTrace trace) {
        if (trace == null) {
            return;
        }
        flowTraceByExecutionId.put(trace.meta().executionId(), trace);
    }

    @Override
    public synchronized Optional<FlowTrace> findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(flowTraceByExecutionId.get(executionId));
    }

    @Override
    public synchronized List<FlowTrace> search(TraceQuery query) {
        TraceQuery effective = query == null ? TraceQuery.empty() : query;
        List<FlowTrace> filtered = new ArrayList<>();
        for (FlowTrace trace : flowTraceByExecutionId.values()) {
            if (trace == null) {
                continue;
            }
            if (effective.correlationId() != null
                    && !effective.correlationId().equals(trace.meta().correlationId())) {
                continue;
            }
            if (effective.flowName() != null
                    && !effective.flowName().equals(trace.meta().flowName())) {
                continue;
            }
            if (effective.tenantId() != null
                    && !effective.tenantId().equals(trace.meta().tenantId())) {
                continue;
            }
            if (effective.actorId() != null
                    && !effective.actorId().equals(trace.meta().actorId())) {
                continue;
            }
            if (effective.status() != null
                    && !effective.status().equals(resolveStatus(trace))) {
                continue;
            }
            if (effective.fromEpochMs() != null
                    && trace.startedAtEpochMs() < effective.fromEpochMs()) {
                continue;
            }
            if (effective.toEpochMs() != null
                    && trace.startedAtEpochMs() > effective.toEpochMs()) {
                continue;
            }
            filtered.add(trace);
        }

        filtered.sort(Comparator
                .comparingLong(FlowTrace::startedAtEpochMs).reversed()
                .thenComparing(trace -> trace.meta().executionId(), Comparator.reverseOrder()));

        int from = Math.min(effective.offset(), filtered.size());
        int to = Math.min(from + effective.limit(), filtered.size());
        return List.copyOf(filtered.subList(from, to));
    }

    @Override
    public synchronized List<TraceSummary> searchSummaries(TraceQuery query) {
        return search(query).stream()
                .map(InProcExecutionTracer::toSummary)
                .toList();
    }

    public synchronized FlowTrace getFlowTrace(String executionId) {
        return flowTraceByExecutionId.get(executionId);
    }

    public synchronized List<TraceSignal> getSignals(String executionId) {
        List<TraceSignal> signals = signalsByExecutionId.get(executionId);
        if (signals == null) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(signals));
    }

    public synchronized int getSignalCount(String executionId) {
        return getSignals(executionId).size();
    }

    public synchronized List<FlowTrace> snapshotTraces() {
        return flowTraceByExecutionId.values().stream()
                .sorted(Comparator.comparing(trace -> trace.meta().executionId()))
                .toList();
    }

    private void appendSignal(String executionId, TraceSignal signal) {
        signalsByExecutionId.computeIfAbsent(executionId, key -> new ArrayList<>()).add(signal);
    }

    private static String resolveStatus(FlowTrace trace) {
        if (trace.outcome() == StepOutcome.OK) {
            return "OK";
        }
        boolean waiting = trace.steps().stream()
                .anyMatch(step -> "WAITING".equals(String.valueOf(step.info().get("awaitedEventStatus"))));
        return waiting ? "WAITING" : "FAILED";
    }

    private static TraceSummary toSummary(FlowTrace trace) {
        if (trace == null || trace.meta() == null) {
            return new TraceSummary("<unknown>", null, null, null, "UNKNOWN", 0L, 0L, 0L);
        }
        return new TraceSummary(
                trace.meta().executionId(),
                trace.meta().tenantId(),
                trace.meta().correlationId(),
                trace.meta().flowName(),
                resolveStatus(trace),
                trace.startedAtEpochMs(),
                trace.endedAtEpochMs(),
                trace.endedAtEpochMs()
        );
    }
}
