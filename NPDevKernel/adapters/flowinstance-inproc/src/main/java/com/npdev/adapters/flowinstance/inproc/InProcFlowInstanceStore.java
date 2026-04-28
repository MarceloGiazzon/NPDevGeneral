package com.npdev.adapters.flowinstance.inproc;

import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.ExecutionSummaryStore;
import com.npdev.kernel.ports.FlowInstanceStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InProcFlowInstanceStore implements FlowInstanceStore, ExecutionSummaryStore {
    private static final Comparator<FlowInstance> WAITING_ORDER =
            Comparator.comparingLong(FlowInstance::updatedAtEpochMs)
                    .thenComparing(FlowInstance::executionId);

    private static final Comparator<FlowInstance> ELIGIBLE_RESUME_ORDER =
            Comparator.comparingLong(InProcFlowInstanceStore::nextEligibleOrZero)
                    .thenComparing(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                    .thenComparing(FlowInstance::executionId);

    private static final Comparator<FlowInstance> STALE_WAITING_ORDER =
            Comparator.comparingLong(InProcFlowInstanceStore::lastProgressOrZero)
                    .thenComparing(FlowInstance::executionId);

    private final Map<String, FlowInstance> byExecutionId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> waitingByCorrelation = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> waitingByEvent = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(FlowInstance instance) {
        upsert(instance);
    }

    @Override
    public synchronized void update(FlowInstance instance) {
        upsert(instance);
    }

    @Override
    public Optional<FlowInstance> findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExecutionId.get(executionId));
    }

    @Override
    public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return waitingSnapshot(waitingByCorrelation.get(correlationId));
    }

    @Override
    public List<FlowInstance> findWaitingByEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        return waitingSnapshot(waitingByEvent.get(eventName));
    }

    @Override
    public List<FlowInstance> findAllWaiting(int limit) {
        int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<FlowInstance> waiting = new ArrayList<>();
        for (FlowInstance instance : byExecutionId.values()) {
            if (instance == null || instance.status() != FlowInstanceStatus.WAITING_EVENT) {
                continue;
            }
            waiting.add(instance);
        }
        waiting.sort(WAITING_ORDER);
        if (waiting.size() <= effectiveLimit) {
            return List.copyOf(waiting);
        }
        return List.copyOf(waiting.subList(0, effectiveLimit));
    }

    @Override
    public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        List<FlowInstance> eligible = byExecutionId.values().stream()
                .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> instance.isResumeEligible(nowEpochMs))
                .sorted(ELIGIBLE_RESUME_ORDER)
                .limit(effectiveLimit)
                .toList();
        return List.copyOf(eligible);
    }

    @Override
    public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        List<FlowInstance> stale = byExecutionId.values().stream()
                .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> lastProgressOrZero(instance) <= olderThanEpochMs)
                .sorted(STALE_WAITING_ORDER)
                .toList();
        return paginate(stale, limit, offset);
    }

    @Override
    public List<FlowInstance> findRecent(String tenantId, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        List<FlowInstance> instances = byExecutionId.values().stream()
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .toList();
        return paginate(instances, limit, offset);
    }

    @Override
    public List<FlowInstance> findWaiting(String tenantId, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        List<FlowInstance> instances = byExecutionId.values().stream()
                .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .toList();
        return paginate(instances, limit, offset);
    }

    @Override
    public List<FlowInstance> findByCorrelationId(String tenantId, String correlationId) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return byExecutionId.values().stream()
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> correlationId.equals(instance.correlationId()))
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public List<ExecutionSummary> listSummaries(String tenantId, String mode, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        boolean waitingOnly = "waiting".equalsIgnoreCase(mode);
        List<ExecutionSummary> summaries = byExecutionId.values().stream()
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> !waitingOnly || instance.status() == FlowInstanceStatus.WAITING_EVENT)
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .map(InProcFlowInstanceStore::toSummary)
                .toList();
        return paginateSummaries(summaries, limit, offset);
    }

    @Override
    public List<ExecutionSummary> listByCorrelation(String tenantId, String correlationId, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        List<ExecutionSummary> summaries = byExecutionId.values().stream()
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> correlationId.equals(instance.correlationId()))
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .map(InProcFlowInstanceStore::toSummary)
                .toList();
        return paginateSummaries(summaries, limit, offset);
    }

    @Override
    public List<ExecutionSummary> listFailureSummaries(String tenantId, int limit, int offset) {
        return listByStatus(tenantId, FlowInstanceStatus.FAILED_PERMANENT, limit, offset);
    }

    @Override
    public List<ExecutionSummary> listStuckSummaries(String tenantId, int limit, int offset) {
        return listByStatus(tenantId, FlowInstanceStatus.STUCK, limit, offset);
    }

    public List<FlowInstance> snapshotInstances() {
        return byExecutionId.values().stream()
                .sorted(Comparator.comparing(FlowInstance::executionId))
                .toList();
    }

    private void upsert(FlowInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must be non-null");
        }
        FlowInstance previous = byExecutionId.put(instance.executionId(), instance);
        deindex(previous);
        index(instance);
    }

    private List<FlowInstance> waitingSnapshot(Set<String> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return List.of();
        }
        List<FlowInstance> out = new ArrayList<>();
        for (String executionId : executionIds) {
            FlowInstance instance = byExecutionId.get(executionId);
            if (instance == null || instance.status() != FlowInstanceStatus.WAITING_EVENT) {
                continue;
            }
            out.add(instance);
        }
        out.sort(WAITING_ORDER);
        return List.copyOf(out);
    }

    private void index(FlowInstance instance) {
        if (instance == null || instance.status() != FlowInstanceStatus.WAITING_EVENT) {
            return;
        }
        String eventName = instance.waitingForEventName();
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        waitingByCorrelation
                .computeIfAbsent(instance.correlationId(), key -> ConcurrentHashMap.newKeySet())
                .add(instance.executionId());
        waitingByEvent
                .computeIfAbsent(eventName, key -> ConcurrentHashMap.newKeySet())
                .add(instance.executionId());
    }

    private void deindex(FlowInstance instance) {
        if (instance == null || instance.status() != FlowInstanceStatus.WAITING_EVENT) {
            return;
        }
        removeFromIndex(waitingByCorrelation, instance.correlationId(), instance.executionId());
        String eventName = instance.waitingForEventName();
        if (eventName != null && !eventName.isBlank()) {
            removeFromIndex(waitingByEvent, eventName, instance.executionId());
        }
    }

    private static void removeFromIndex(
            Map<String, Set<String>> index,
            String key,
            String executionId
    ) {
        Set<String> executionIds = index.get(key);
        if (executionIds == null) {
            return;
        }
        executionIds.remove(executionId);
        if (executionIds.isEmpty()) {
            index.remove(key);
        }
    }

    private static String normalizeTenant(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static long nextEligibleOrZero(FlowInstance instance) {
        Long next = instance.nextEligibleResumeAtEpochMs();
        return next == null ? 0L : next;
    }

    private static long lastProgressOrZero(FlowInstance instance) {
        Long progress = instance.lastProgressAtEpochMs();
        return progress == null ? 0L : progress;
    }

    private static List<FlowInstance> paginate(List<FlowInstance> source, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        int fromIndex = Math.min(effectiveOffset, source.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, source.size());
        return List.copyOf(source.subList(fromIndex, toIndex));
    }

    private static List<ExecutionSummary> paginateSummaries(List<ExecutionSummary> source, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        int fromIndex = Math.min(effectiveOffset, source.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, source.size());
        return List.copyOf(source.subList(fromIndex, toIndex));
    }

    private static ExecutionSummary toSummary(FlowInstance instance) {
        return new ExecutionSummary(
                instance.executionId(),
                instance.tenantId(),
                instance.correlationId(),
                instance.flowName(),
                instance.status() == null ? null : instance.status().name(),
                instance.currentStepIndex(),
                instance.waitingForEventName(),
                instance.updatedAtEpochMs(),
                instance.resumeAttemptCount(),
                instance.lastResumeAtEpochMs(),
                instance.lastResumeErrorCode(),
                instance.nextEligibleResumeAtEpochMs(),
                instance.lastProgressAtEpochMs(),
                instance.lastErrorKind(),
                instance.lastErrorCode(),
                instance.failedAtEpochMs()
        );
    }

    private List<ExecutionSummary> listByStatus(
            String tenantId,
            FlowInstanceStatus status,
            int limit,
            int offset
    ) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null || status == null) {
            return List.of();
        }
        List<ExecutionSummary> summaries = byExecutionId.values().stream()
                .filter(instance -> scopedTenantId.equals(instance.tenantId()))
                .filter(instance -> instance.status() == status)
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed()
                        .thenComparing(FlowInstance::executionId, Comparator.reverseOrder()))
                .map(InProcFlowInstanceStore::toSummary)
                .toList();
        return paginateSummaries(summaries, limit, offset);
    }
}
