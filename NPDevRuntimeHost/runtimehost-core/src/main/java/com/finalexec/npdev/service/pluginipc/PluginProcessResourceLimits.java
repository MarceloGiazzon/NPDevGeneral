package com.finalexec.npdev.service.pluginipc;

/**
 * Config-driven ceilings for one plugin child process (SEC-3 Model B step 4, design doc section 3).
 * Either numeric field may be {@code null}, meaning that dimension is unbounded -- there is no
 * principled platform-wide default without real usage data (design section 3), so callers must opt
 * in explicitly.
 *
 * <p>{@code sandboxEnabled} (SEC-10, B30 lift) is a SEPARATE axis, not a third resource ceiling: it
 * gates the OS-level network/filesystem sandbox {@link PluginLinuxCgroupResourceLimiter} applies
 * only in its {@code systemd-run} mode ({@code --property=PrivateNetwork=yes} etc.), independent of
 * whether a memory/CPU ceiling is configured at all -- {@code npdev.runtime.plugin-ipc-pool.sandbox}
 * defaults {@code on} regardless of {@link #isEmpty()}.</p>
 */
public record PluginProcessResourceLimits(Integer memoryLimitMb, Integer cpuRatePercent, boolean sandboxEnabled) {

    public static final PluginProcessResourceLimits NONE = new PluginProcessResourceLimits(null, null, true);

    /** Convenience for existing 2-arg call sites -- sandbox stays enabled (the new default). */
    public PluginProcessResourceLimits(Integer memoryLimitMb, Integer cpuRatePercent) {
        this(memoryLimitMb, cpuRatePercent, true);
    }

    public boolean isEmpty() {
        return memoryLimitMb == null && cpuRatePercent == null;
    }
}
