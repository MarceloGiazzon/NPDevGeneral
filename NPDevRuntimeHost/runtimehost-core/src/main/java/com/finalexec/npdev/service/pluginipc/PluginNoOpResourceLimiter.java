package com.finalexec.npdev.service.pluginipc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Degrade posture when no real per-OS resource-limiting mechanism is available (design doc section 3:
 * "degrades to wall-clock timeout only, logged loudly as a posture warning at boot ... never a silent
 * downgrade"). Command/process pass through unchanged; the wall-clock timeout stays the only
 * containment. Logs exactly once, and only once a caller actually asks for a real limit that this
 * instance cannot enforce -- constructing this class is not itself news (it is also what a caller gets
 * when nothing was ever configured, today's status quo), so warning on construction would be noise.
 */
final class PluginNoOpResourceLimiter implements PluginProcessResourceLimiter {

    private static final Logger LOG = Logger.getLogger(PluginNoOpResourceLimiter.class.getName());

    private final AtomicBoolean warned = new AtomicBoolean(false);

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public java.util.List<String> wrapCommand(java.util.List<String> command, PluginProcessResourceLimits limits) {
        warnIfLimitsRequested(limits);
        return command;
    }

    @Override
    public ResourceLimitAttachment attachAfterStart(Process process, PluginProcessResourceLimits limits) {
        warnIfLimitsRequested(limits);
        return ResourceLimitAttachment.NONE;
    }

    private void warnIfLimitsRequested(PluginProcessResourceLimits limits) {
        if (limits == null || limits.isEmpty()) {
            return;
        }
        if (warned.compareAndSet(false, true)) {
            LOG.log(Level.WARNING,
                    "Plugin process resource limits were requested (memoryLimitMb={0}, cpuRatePercent={1}) "
                            + "but no OS-level containment mechanism is available on this host ({2}) -- plugin "
                            + "child processes are UNBOUNDED beyond the wall-clock timeout. See "
                            + "docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 3.",
                    new Object[]{limits.memoryLimitMb(), limits.cpuRatePercent(), System.getProperty("os.name")});
        }
    }
}
