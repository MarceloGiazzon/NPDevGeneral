package com.finalexec.npdev.service.pluginipc;

/**
 * Config-driven ceilings for one plugin child process (SEC-3 Model B step 4, design doc section 3).
 * Either field may be {@code null}, meaning that dimension is unbounded -- there is no principled
 * platform-wide default without real usage data (design section 3), so callers must opt in explicitly.
 */
public record PluginProcessResourceLimits(Integer memoryLimitMb, Integer cpuRatePercent) {

    public static final PluginProcessResourceLimits NONE = new PluginProcessResourceLimits(null, null);

    public boolean isEmpty() {
        return memoryLimitMb == null && cpuRatePercent == null;
    }
}
