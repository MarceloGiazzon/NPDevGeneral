package com.finalexec.npdev.service.pluginipc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Linux cgroups v2 resource limiting (SEC-3 Model B step 4, design doc section 3). Two mechanisms, tried
 * in the order the design doc recommends:
 *
 * <ol>
 *   <li><b>{@code systemd-run --user --scope}</b> (primary): reuses a stable, already-present OS
 *   mechanism instead of hand-rolled cgroup-fs writes. Probed once at construction by actually launching
 *   a real no-op transient scope -- the only way to know {@code --user} has a usable systemd session
 *   (e.g. via {@code loginctl linger}) without guessing.</li>
 *   <li><b>Direct cgroup v2 filesystem writes</b> (fallback), for a target where systemd is unavailable
 *   (e.g. a minimal container): create a child cgroup under this process's own cgroup, write {@code
 *   memory.max}/{@code cpu.max}, then move the spawned child's PID into it after {@code
 *   ProcessBuilder.start()}. Genuinely best-effort -- it depends on cgroup delegation
 *   (writable {@code cgroup.subtree_control}) that an unprivileged process is not guaranteed to have; a
 *   failure here degrades to "spawned without the cgroup applied" rather than failing the invocation,
 *   logged loudly per design section 3's "never a silent downgrade."</li>
 * </ol>
 *
 * <p>Neither mechanism proving out degrades the whole limiter to {@link PluginNoOpResourceLimiter}, handled by
 * {@link PluginProcessResourceLimiter#forCurrentOs()} -- this class reports {@link #isAvailable()} truthfully
 * rather than pretending.</p>
 */
final class PluginLinuxCgroupResourceLimiter implements PluginProcessResourceLimiter {

    private static final Logger LOG = Logger.getLogger(PluginLinuxCgroupResourceLimiter.class.getName());
    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

    private enum Mode { SYSTEMD_RUN, RAW_CGROUP, UNAVAILABLE }

    private final Mode mode;

    PluginLinuxCgroupResourceLimiter() {
        this.mode = probe();
    }

    /** Testable seam: force a specific mode without re-probing the real OS each time. */
    PluginLinuxCgroupResourceLimiter(boolean forceUnavailable) {
        this.mode = forceUnavailable ? Mode.UNAVAILABLE : probe();
    }

    private static Mode probe() {
        if (probeSystemdRunUser()) {
            return Mode.SYSTEMD_RUN;
        }
        if (probeRawCgroupWritable()) {
            return Mode.RAW_CGROUP;
        }
        return Mode.UNAVAILABLE;
    }

    private static boolean probeSystemdRunUser() {
        try {
            Process probe = new ProcessBuilder(
                    "systemd-run", "--user", "--scope", "--quiet", "--collect", "--", "/bin/true"
            ).redirectErrorStream(true).start();
            boolean exited = probe.waitFor(5, TimeUnit.SECONDS);
            return exited && probe.exitValue() == 0;
        } catch (IOException | InterruptedException probeFailure) {
            if (probeFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.FINE, "systemd-run --user --scope probe failed -- falling back to raw cgroup v2 "
                    + "(this is the normal path on a host without a lingering user session)", probeFailure);
            return false;
        }
    }

    private static boolean probeRawCgroupWritable() {
        try {
            if (!Files.isDirectory(CGROUP_ROOT.resolve("cgroup.controllers"))
                    && !Files.isRegularFile(CGROUP_ROOT.resolve("cgroup.controllers"))) {
                return false; // not cgroup v2's unified hierarchy
            }
            return Files.isWritable(ownCgroupDir());
        } catch (IOException | RuntimeException probeFailure) {
            LOG.log(Level.FINE, "Raw cgroup v2 writability probe failed -- this limiter will report "
                    + "unavailable and the child process will run without an OS-level ceiling.", probeFailure);
            return false;
        }
    }

    private static Path ownCgroupDir() throws IOException {
        // cgroup v2 unified hierarchy: exactly one line, "0::/<path>".
        String line = Files.readString(Path.of("/proc/self/cgroup"), StandardCharsets.UTF_8).strip();
        int separator = line.indexOf("::");
        String relative = separator >= 0 ? line.substring(separator + 2) : line;
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative.isEmpty() ? CGROUP_ROOT : CGROUP_ROOT.resolve(relative);
    }

    @Override
    public boolean isAvailable() {
        return mode != Mode.UNAVAILABLE;
    }

    @Override
    public List<String> wrapCommand(List<String> command, PluginProcessResourceLimits limits) {
        if (mode != Mode.SYSTEMD_RUN || limits.isEmpty()) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(List.of("systemd-run", "--user", "--scope", "--quiet", "--collect"));
        if (limits.memoryLimitMb() != null) {
            wrapped.add("-p");
            wrapped.add("MemoryMax=" + limits.memoryLimitMb() + "M");
        }
        if (limits.cpuRatePercent() != null) {
            wrapped.add("-p");
            wrapped.add("CPUQuota=" + limits.cpuRatePercent() + "%");
        }
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    @Override
    public ResourceLimitAttachment attachAfterStart(Process process, PluginProcessResourceLimits limits) {
        if (mode != Mode.RAW_CGROUP || limits.isEmpty()) {
            return ResourceLimitAttachment.NONE;
        }
        try {
            Path pluginCgroup = ownCgroupDir().resolve("npdev-plugin-" + process.pid());
            Files.createDirectory(pluginCgroup);
            if (limits.memoryLimitMb() != null) {
                Files.writeString(pluginCgroup.resolve("memory.max"), Long.toString(limits.memoryLimitMb() * 1024L * 1024L));
            }
            if (limits.cpuRatePercent() != null) {
                long periodMicros = 100_000L;
                long quotaMicros = periodMicros * limits.cpuRatePercent() / 100;
                Files.writeString(pluginCgroup.resolve("cpu.max"), quotaMicros + " " + periodMicros);
            }
            Files.writeString(pluginCgroup.resolve("cgroup.procs"), Long.toString(process.pid()));
            return () -> deleteQuietly(pluginCgroup);
        } catch (IOException | RuntimeException attachFailure) {
            LOG.log(Level.WARNING,
                    "Failed to apply a raw cgroup v2 resource limit to plugin child process pid=" + process.pid()
                            + " -- it is running WITHOUT the configured memory/CPU ceiling.", attachFailure);
            return ResourceLimitAttachment.NONE;
        }
    }

    private static void deleteQuietly(Path pluginCgroup) {
        try {
            Files.deleteIfExists(pluginCgroup);
        } catch (IOException cleanupFailure) {
            // Best-effort: an empty cgroup left behind once its member process has exited is harmless.
        }
    }
}
