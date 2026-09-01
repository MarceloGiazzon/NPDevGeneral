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
 *   (e.g. a minimal container): create a cgroup as a SIBLING of this process's own cgroup, under the
 *   nearest ancestor that actually delegates the {@code memory}/{@code cpu} controllers to its children
 *   ({@link #delegatingParent()}), write {@code memory.max}/{@code cpu.max}, then move the spawned
 *   child's PID into it after {@code ProcessBuilder.start()}. Deliberately NOT a child of this
 *   process's own cgroup -- cgroup v2's "no internal process" rule means a cgroup holding this JVM can
 *   never enable controllers for its children, so a child cgroup there would have no {@code memory.max}
 *   to write at all (found live-firing this class's own Docker/Linux proof, SEC-3 fork (a), 2026-09-01).
 *   Genuinely best-effort -- it depends on cgroup delegation that an unprivileged process is not
 *   guaranteed to have; a failure here degrades to "spawned without the cgroup applied" rather than
 *   failing the invocation, logged loudly per design section 3's "never a silent downgrade."</li>
 * </ol>
 *
 * <p>Neither mechanism proving out degrades the whole limiter to {@link PluginNoOpResourceLimiter}, handled by
 * {@link PluginProcessResourceLimiter#forCurrentOs()} -- this class reports {@link #isAvailable()} truthfully
 * rather than pretending.</p>
 */
final class PluginLinuxCgroupResourceLimiter implements PluginProcessResourceLimiter {

    private static final Logger LOG = Logger.getLogger(PluginLinuxCgroupResourceLimiter.class.getName());
    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    /** Controllers this limiter needs delegated to it before a ceiling can be applied at all. */
    private static final List<String> REQUIRED_CONTROLLERS = List.of("memory", "cpu");

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
        if (!Files.isRegularFile(CGROUP_ROOT.resolve("cgroup.controllers"))) {
            return false; // not cgroup v2's unified hierarchy
        }
        return delegatingParent() != null;
    }

    /**
     * The nearest ancestor of this process's own cgroup (its parent first, then that parent's
     * parent, ...) that is writable AND already delegates every controller in
     * {@link #REQUIRED_CONTROLLERS} to its children. Returns {@code null} when no such directory
     * exists, which is a truthful "this mechanism is not available here", not an error.
     *
     * <p>Never returns the process's OWN cgroup: cgroup v2's "no internal process" rule means a
     * cgroup holding this JVM cannot have controllers enabled for its children, so a child created
     * there would have no {@code memory.max} to write -- this was the SEC-3/Docker-proof defect
     * (a container never has a systemd user session, so the raw-cgroup fallback is the only path
     * exercised there, and the shipped child-of-own-cgroup placement cannot ever delegate).</p>
     */
    private static Path delegatingParent() {
        Path candidate;
        try {
            candidate = ownCgroupDir().getParent();
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, "Could not read this process's own cgroup path", unreadable);
            return null;
        }
        while (candidate != null && candidate.startsWith(CGROUP_ROOT)) {
            if (Files.isWritable(candidate) && delegatesRequiredControllers(candidate)) {
                return candidate;
            }
            if (candidate.equals(CGROUP_ROOT)) {
                return null;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static boolean delegatesRequiredControllers(Path cgroupDir) {
        Path subtreeControl = cgroupDir.resolve("cgroup.subtree_control");
        try {
            if (!Files.isReadable(subtreeControl)) {
                return false;
            }
            List<String> enabled = List.of(
                    Files.readString(subtreeControl, StandardCharsets.UTF_8).strip().split("\\s+"));
            return enabled.containsAll(REQUIRED_CONTROLLERS);
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, "Could not read " + subtreeControl, unreadable);
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
            // Same reasoning as the raw-cgroup fallback's memory.swap.max=0 (see attachAfterStart):
            // MemoryMax= alone lets the kernel reclaim to swap instead of OOM-killing once the
            // ceiling is reached, which is not deterministic containment on a host with swap enabled.
            wrapped.add("-p");
            wrapped.add("MemorySwapMax=0");
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
        Path parent = delegatingParent();
        if (parent == null) {
            LOG.log(Level.WARNING,
                    "No cgroup v2 ancestor delegates " + REQUIRED_CONTROLLERS + " to its children, so plugin child "
                            + "process pid=" + process.pid() + " is running WITHOUT the configured memory/CPU ceiling. "
                            + "On a container host this usually means the container was started without "
                            + "--privileged --cgroupns=private, or without the cgroup delegation init step.");
            return ResourceLimitAttachment.NONE;
        }
        Path pluginCgroup = parent.resolve("npdev-plugin-" + process.pid());
        try {
            Files.createDirectory(pluginCgroup);
            if (limits.memoryLimitMb() != null) {
                Path memoryMax = pluginCgroup.resolve("memory.max");
                if (!Files.exists(memoryMax)) {
                    throw new IOException("memory.max does not exist in " + pluginCgroup
                            + " -- the memory controller is not delegated after all");
                }
                Files.writeString(memoryMax, Long.toString(limits.memoryLimitMb() * 1024L * 1024L));
                // Without this, memory.max alone is NOT a hard ceiling: once resident usage nears it,
                // the kernel reclaims anonymous pages to swap (memory.swap.max defaults to
                // unlimited) rather than invoking the OOM killer, so a runaway plugin just thrashes
                // instead of dying -- found live-firing this exact ceiling in the SEC-3/Docker proof
                // (2026-09-01): a 256MB-ceiling memory hog ran for the full 30s test timeout with
                // zero kill, because 2GiB of host swap absorbed everything it touched. Best-effort:
                // memory.swap.max may not exist if the kernel was built without swap accounting, in
                // which case memory.max is still applied, just without this hardening.
                Path swapMax = pluginCgroup.resolve("memory.swap.max");
                if (Files.exists(swapMax)) {
                    Files.writeString(swapMax, "0");
                } else {
                    LOG.log(Level.FINE, "memory.swap.max does not exist in " + pluginCgroup + " -- swap "
                            + "accounting is unavailable, so the memory.max ceiling just applied may not "
                            + "produce a deterministic OOM-kill if swap is enabled on this host.");
                }
            }
            if (limits.cpuRatePercent() != null) {
                long periodMicros = 100_000L;
                long quotaMicros = periodMicros * limits.cpuRatePercent() / 100;
                Files.writeString(pluginCgroup.resolve("cpu.max"), quotaMicros + " " + periodMicros);
            }
            // LAST: moving the pid in is what arms the ceiling, so every limit file must already
            // be written. A child moved in first could allocate past the ceiling in the gap.
            Files.writeString(pluginCgroup.resolve("cgroup.procs"), Long.toString(process.pid()));
            return () -> deleteQuietly(pluginCgroup);
        } catch (IOException | RuntimeException attachFailure) {
            LOG.log(Level.WARNING,
                    "Failed to apply a raw cgroup v2 resource limit to plugin child process pid=" + process.pid()
                            + " at " + pluginCgroup + " -- it is running WITHOUT the configured memory/CPU ceiling.",
                    attachFailure);
            deleteQuietly(pluginCgroup);
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
