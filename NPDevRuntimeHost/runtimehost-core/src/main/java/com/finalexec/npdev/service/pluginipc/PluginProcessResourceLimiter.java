package com.finalexec.npdev.service.pluginipc;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Applies OS-level resource ceilings to a plugin child process (SEC-3 Model B step 4, design doc
 * section 3 / section 6 step 4). Two real implementations -- there is no cross-platform JVM API for
 * hard memory/CPU caps on a child process -- selected once via {@link #forCurrentOs()}: {@link
 * LinuxCgroupResourceLimiter} and {@link WindowsJobObjectResourceLimiter}. A platform (or a probe
 * failure) with neither mechanism available degrades to {@link NoOpResourceLimiter}, which leaves the
 * wall-clock timeout as the only containment -- logged loudly, never silently.
 *
 * <p>Two attachment points, because the two real mechanisms attach at different points in a process's
 * lifecycle: Linux wraps the spawn COMMAND itself ({@code systemd-run --scope ...} execs into the real
 * child, so the limit exists before the child's first instruction runs); Windows assigns an
 * already-started process to a Job Object (there is no "start already inside a job" primitive on
 * Windows short of a suspended-process trick this design doesn't need). Both methods have permissive
 * defaults so an implementation only needs to override the one it actually uses.</p>
 */
public interface PluginProcessResourceLimiter {

    boolean isAvailable();

    /** Called before {@code ProcessBuilder.start()}; may wrap/prepend the command. Default: unchanged. */
    default List<String> wrapCommand(List<String> command, PluginProcessResourceLimits limits) {
        return command;
    }

    /** Called immediately after the process is spawned; may attach it to an OS limiting construct. */
    default ResourceLimitAttachment attachAfterStart(Process process, PluginProcessResourceLimits limits) throws IOException {
        return ResourceLimitAttachment.NONE;
    }

    /** Live handle to whatever OS construct enforces the limit; closed alongside the process it guards. */
    interface ResourceLimitAttachment extends AutoCloseable {
        ResourceLimitAttachment NONE = () -> { };

        @Override
        void close();
    }

    /**
     * Picks (and caches, per classloading of this class -- effectively once per JVM) the real limiter for
     * the current OS, falling back to {@link NoOpResourceLimiter} the moment a real mechanism cannot be
     * proven available. Never throws: an unavailable mechanism is a degrade, not a startup failure.
     */
    static PluginProcessResourceLimiter forCurrentOs() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final PluginProcessResourceLimiter INSTANCE = detect();

        private Holder() {
        }

        private static PluginProcessResourceLimiter detect() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            try {
                if (osName.contains("win")) {
                    PluginWindowsJobObjectResourceLimiter windows = new PluginWindowsJobObjectResourceLimiter();
                    return windows.isAvailable() ? windows : new PluginNoOpResourceLimiter();
                }
                if (osName.contains("linux")) {
                    PluginLinuxCgroupResourceLimiter linux = new PluginLinuxCgroupResourceLimiter();
                    return linux.isAvailable() ? linux : new PluginNoOpResourceLimiter();
                }
            } catch (RuntimeException | LinkageError probeFailure) {
                // A probe failure (e.g. JNA native library missing/incompatible) must degrade, not crash
                // the host -- this is defence-in-depth on top of the wall-clock timeout, never the sole
                // containment mechanism.
                return new PluginNoOpResourceLimiter();
            }
            return new PluginNoOpResourceLimiter();
        }
    }
}
