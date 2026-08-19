package com.npdev.samples.dslconformance.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real, minimal mounted {@code plugin:java-source} capability, exercising the same mechanism
 * NPDevSamples/probes/lib-probe's {@code LibrarySignatureCapability} proves live.
 *
 * <p>This replaces dsl-conformance-max's original {@code auditLog} binding, which pointed at an
 * invented builtin-style adapter id ({@code audit-log-inproc}) that was never registered in any
 * plugin manifest and had no backing adapter class anywhere in the platform -- a fixture defect,
 * not a generator/runtime one, since nothing in the generator or runtime ever advertises that name
 * as real. {@code NpdevCapabilityBindingConfig.capabilityRegistry()} (NPDevRuntimeHost) resolves
 * every declared model binding against {@code RuntimePluginAdapterRegistry} eagerly at boot, so the
 * generated app failed to start even though nothing ever called the capability.
 */
public final class AuditLogCapability {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * Records one audit entry. In-memory only (this is a DSL-conformance fixture, not a durable
     * audit sink) -- returns the recorded entry's sequence number and timestamp so a caller can
     * confirm the record actually happened, the same "assert the returned value" discipline
     * {@code LibrarySignatureCapability#sign} uses to prove the mount is live rather than merely
     * compiled.
     */
    public Map<String, Object> record(Map<String, Object> input) {
        long sequence = SEQUENCE.incrementAndGet();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sequence", sequence);
        result.put("recordedAt", Instant.now().toString());
        result.put("entry", input == null ? Map.of() : input);
        return result;
    }
}
