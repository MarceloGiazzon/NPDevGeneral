package com.npdev.kernel.ports;

import com.npdev.kernel.events.EventEnvelope;

/**
 * R2.4: where a {@code scheduleEvent} flow step with a non-zero delay hands its envelope off for
 * later delivery, instead of publishing it now.
 *
 * <p><b>Why this is a port and not a call.</b> The durable substrate this writes to
 * ({@code npdev_scheduled_event}, its {@code due_at} column, and the {@code processDueScheduledEvents}
 * drain R2.3 wired to a timer) lives in {@code :adapters:runtime-support}, which depends on
 * {@code :kernel} -- so the kernel cannot call it directly. This is the same seam, in the same
 * direction, as {@code ScheduledEventDrainRunner.ScheduledEventDrain}: one method, JDK/kernel types
 * only, bound by whoever already holds both halves.
 *
 * <p><b>There is deliberately no in-memory default.</b> A no-op or publish-now fallback would
 * reinstate exactly the trap R2.4 removes -- a step declaring {@code delayMinutes: 1440} that fires
 * instantly while labelling itself {@code deliveryMode: scheduled}. A runner with no scheduler
 * configured fails a delayed {@code scheduleEvent} step loudly (EVENT_PERSIST_FAILED), the same way
 * the step has always failed when no {@code EventStore} is configured.
 */
@FunctionalInterface
public interface DeferredEventScheduler {

    /**
     * Durably records {@code envelope} for publication at {@code dueAtEpochMs}.
     *
     * <p>Implementations must not publish or append the envelope themselves -- the drain does that
     * when the row comes due. Returning {@code true} means "a durable row exists for this envelope",
     * which includes the case where an identical row was already there (a re-executed step must not
     * schedule twice). Returning {@code false} means nothing was persisted; the calling step then
     * fails rather than silently degrading to an immediate publish.
     *
     * @param envelope      the event exactly as it would have been published now
     * @param dueAtEpochMs  wall-clock epoch millis at or after which the event may be published
     * @return whether a durable schedule row exists for this envelope
     */
    boolean scheduleForLaterDelivery(EventEnvelope envelope, long dueAtEpochMs);
}
