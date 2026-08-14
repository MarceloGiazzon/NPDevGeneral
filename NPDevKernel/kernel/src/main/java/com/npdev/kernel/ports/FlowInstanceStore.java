package com.npdev.kernel.ports;

import com.npdev.kernel.execution.FlowInstance;

import java.util.List;
import java.util.Optional;

public interface FlowInstanceStore extends ExecutionReadStore {
    void save(FlowInstance instance);

    void update(FlowInstance instance);

    Optional<FlowInstance> findByExecutionId(String executionId);

    List<FlowInstance> findWaitingByCorrelation(String correlationId);

    List<FlowInstance> findWaitingByEvent(String eventName);

    List<FlowInstance> findAllWaiting(int limit);

    List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit);

    /**
     * R8c (RUN-2): {@link #findWaitingEligibleToResume}'s claiming counterpart -- atomically marks
     * each returned instance as held by {@code claimantId} until {@code nowEpochMs + leaseMillis},
     * so two resumers polling the SAME database never both attempt to resume the SAME flow
     * instance. A row already claimed (by anyone, including this same caller from a still-active
     * lease) and not yet expired is excluded from the result, exactly like an ineligible
     * {@code next_eligible_resume_at}.
     *
     * <p><b>Default implementation performs NO claiming</b> -- it delegates to {@link
     * #findWaitingEligibleToResume} verbatim, so a store that does not override this method offers
     * exactly its prior behaviour (no double-resume protection, unchanged). This is a deliberate
     * default rather than an abstract method: {@code FlowInstanceStore} has 14 anonymous test
     * implementations plus {@link NoopHolder}, and a new non-default method on this interface is a
     * compile break across every one of them. Only {@code JdbcFlowInstanceStore} answers this for
     * real today, via {@code SqlDialect#selectForUpdateSkipLocked}.
     *
     * @param leaseMillis how long a claim is held before it is treated as abandoned and becomes
     *                     claimable again -- a safety net for a claimant that crashes mid-resume
     * @param claimantId   an opaque id identifying the caller (one per resumer instance/process),
     *                     recorded for observability; never consulted for authorization
     */
    default List<FlowInstance> claimWaitingEligibleToResume(
            String tenantId, long nowEpochMs, long leaseMillis, String claimantId, int limit) {
        return findWaitingEligibleToResume(tenantId, nowEpochMs, limit);
    }

    List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset);

    static FlowInstanceStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final FlowInstanceStore INSTANCE = new FlowInstanceStore() {
            @Override
            public void save(FlowInstance instance) {
            }

            @Override
            public void update(FlowInstance instance) {
            }

            @Override
            public Optional<FlowInstance> findByExecutionId(String executionId) {
                return Optional.empty();
            }

            @Override
            public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingByEvent(String eventName) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findAllWaiting(int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
                return List.of();
            }
        };

        private NoopHolder() {
        }
    }
}