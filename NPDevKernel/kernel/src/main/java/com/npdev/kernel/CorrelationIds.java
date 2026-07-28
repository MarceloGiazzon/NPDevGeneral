package com.npdev.kernel;

/**
 * REG-47: bounds the caller-supplied correlation id before it becomes durable index key material.
 *
 * <h2>Why a bound is needed at all</h2>
 *
 * <p>A correlation id arrives as ordinary request data — {@code EventPublishRequest.correlationId},
 * the flow-execution endpoints — and used to be passed through with nothing but {@code trim()}. It is
 * then written into {@code TEXT} columns that are btree index key material in four tables:
 * {@code npdev_correlation_owner.correlation_id} (its <b>primary key</b>), plus indexed
 * {@code correlation_id} in {@code npdev_event_store}, {@code npdev_flow_instance} and
 * {@code npdev_trace} — eight indexes in total.</p>
 *
 * <p>On Postgres an oversized <em>incompressible</em> value exceeds the btree index-entry limit and
 * the write throws. That is REG-36's failure mode on a different key, and it fails at the worst
 * possible moment: the event has already been published or the flow has already executed, so the
 * caller is told the operation failed when it did not. For {@code npdev_correlation_owner} the
 * failing write is the very one that establishes correlation ownership.</p>
 *
 * <h2>Why this REJECTS where REG-36 digests</h2>
 *
 * <p>Owner decision, 2026-07-25. {@code IdempotencyKeys} digests, because a model author's
 * {@code idempotencyKeyField} may legitimately point at a large payload-derived value — silently
 * shortening it keeps a valid use case working. A correlation id is different in two ways:</p>
 *
 * <ol>
 *   <li>It is caller-chosen metadata for tracing. There is no legitimate 10,000-character form, so
 *       rejecting cannot break a real caller.</li>
 *   <li><b>Callers look it up again.</b> It is a {@code @PathVariable} on the correlation-timeline and
 *       event-query controllers. Digesting would silently store an id different from the one the
 *       caller holds, so every lookup site would have to apply the identical transform or quietly
 *       return nothing. Rejecting at the edge has no such coupling.</li>
 * </ol>
 *
 * <p>The check runs <b>before any side effect</b> — that is the part that actually matters. The HTTP
 * status the rejection surfaces as is presentation; the guarantee is that nothing is published,
 * executed or persisted first.</p>
 */
public final class CorrelationIds {

    /**
     * Generous next to any real correlation id (a UUID is 36 characters, a W3C traceparent 55), and
     * far inside Postgres's ~2704-byte btree index-entry limit even once tenant and timestamp columns
     * share the composite indexes.
     */
    public static final int MAX_CHARS = 400;

    private CorrelationIds() {
    }

    /**
     * @return {@code raw} trimmed, or {@code null} when it is null/blank (every caller already treats
     *         those as "no correlation id")
     * @throws IllegalArgumentException if it exceeds {@link #MAX_CHARS}
     */
    public static String require(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        if (trimmed.length() > MAX_CHARS) {
            throw new IllegalArgumentException(
                    "correlationId is " + trimmed.length() + " characters; the maximum is " + MAX_CHARS
                            + ". It is stored as index key material in npdev_event_store, npdev_flow_instance, "
                            + "npdev_trace and npdev_correlation_owner (where it is the primary key), so an "
                            + "oversized value would fail the write after the operation had already run (REG-47).");
        }
        return trimmed;
    }
}
