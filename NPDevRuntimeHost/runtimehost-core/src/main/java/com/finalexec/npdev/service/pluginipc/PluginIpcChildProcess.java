package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Host-side handle to one real OS child process running {@link PluginIpcChildProcessMain} (SEC-3,
 * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 6). Two spawn shapes: {@link #start}, one
 * process per invocation (step 2, cold-start cost deliberately accepted), and {@link #startPooled}, a
 * fungible worker not bound to any plugin class, reused across invocations by {@link
 * PluginIpcChildProcessPool} (step 3).
 *
 * <p>Delegates the actual wire protocol to {@link PluginIpcHostSession}, which stays stream-agnostic per
 * its own contract; this class's only job is the OS-process-specific pieces: spawning the child with the
 * right classpath/main class, and -- per section 4's crash-handling sequence -- telling apart an ordinary
 * closed channel from a child process that died mid-invoke, so a killed worker surfaces as
 * {@code PLUGIN_EXECUTION_PROCESS_KILLED} rather than the generic {@code PLUGIN_IPC_CHANNEL_CLOSED}.</p>
 */
public final class PluginIpcChildProcess implements AutoCloseable {

    private static final String CHANNEL_CLOSED_CODE = "PLUGIN_IPC_CHANNEL_CLOSED";

    private final Process process;
    private final Path classpathArgFile;
    private final PluginProcessResourceLimiter.ResourceLimitAttachment resourceLimitAttachment;

    private PluginIpcChildProcess(
            Process process, Path classpathArgFile, PluginProcessResourceLimiter.ResourceLimitAttachment resourceLimitAttachment
    ) {
        this.process = process;
        this.classpathArgFile = classpathArgFile;
        this.resourceLimitAttachment = resourceLimitAttachment;
    }

    /** Spawns a one-shot child process running {@code handlerClassName} on this JVM's own classpath. */
    public static PluginIpcChildProcess start(String handlerClassName) throws IOException {
        return start(handlerClassName, System.getProperty("java.class.path"));
    }

    /**
     * Passes {@code classpath} via a {@code java @argfile} rather than a raw {@code -cp} argument: a real
     * host's own runtime classpath (dozens of adapter jars) reliably exceeds Windows's CreateProcess
     * command-line length limit (observed: {@code CreateProcess error=206}, "the filename or extension is
     * too long"), which a raw {@code -cp <classpath>} argument hits well before any POSIX limit would.
     */
    public static PluginIpcChildProcess start(String handlerClassName, String classpath) throws IOException {
        Objects.requireNonNull(handlerClassName, "handlerClassName");
        return spawn(classpath, PluginProcessResourceLimits.NONE, handlerClassName);
    }

    /** Same as the two-arg {@link #start}, additionally applying an OS-level resource ceiling (SEC-3 step 4). */
    public static PluginIpcChildProcess start(String handlerClassName, String classpath, PluginProcessResourceLimits limits)
            throws IOException {
        Objects.requireNonNull(handlerClassName, "handlerClassName");
        return spawn(classpath, limits, handlerClassName);
    }

    /** Spawns a fungible pooled worker (SEC-3 step 3) on this JVM's own classpath, bound to no plugin. */
    public static PluginIpcChildProcess startPooled() throws IOException {
        return startPooled(System.getProperty("java.class.path"));
    }

    public static PluginIpcChildProcess startPooled(String classpath) throws IOException {
        return spawn(classpath, PluginProcessResourceLimits.NONE);
    }

    /** Same as {@link #startPooled(String)}, additionally applying an OS-level resource ceiling (SEC-3 step 4). */
    public static PluginIpcChildProcess startPooled(String classpath, PluginProcessResourceLimits limits) throws IOException {
        return spawn(classpath, limits);
    }

    private static final String SPRING_BOOT_PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    private static PluginIpcChildProcess spawn(String classpath, PluginProcessResourceLimits limits, String... childMainArgs)
            throws IOException {
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(limits, "limits");
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Path argFile = null;
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(childHeapFlags(limits));
        if (isExecutableSpringBootArchive(classpath)) {
            // SEC-5: a REAL deployed app is always launched `java -jar FinalExec.jar` (Start-App.ps1's
            // own invocation) -- under that launch, `java.class.path` is just the fat jar's OWN single
            // path, not a real multi-entry classpath. A plain `-cp <thatJar> PluginIpcChildProcessMain`
            // then fails with ClassNotFoundException: the class lives under BOOT-INF/classes/ inside the
            // jar, which only Spring Boot's own nested-jar-aware loader knows how to resolve -- a bare
            // URLClassLoader (what a plain `-cp` launch uses) treats the jar as flat and never finds it.
            // PropertiesLauncher (bundled in every Spring Boot fat jar) is Spring Boot's own documented
            // mechanism for launching an ALTERNATE main class through that same loader via
            // `-Dloader.main=<class>` -- confirmed live (SEC-5 live-fire, 2026-09-01): the two-line
            // ClassNotFoundException in app.stderr.log for BOTH pool workers, from a real `java -jar`
            // boot of NPDevSamples/dsl-conformance-max, is what this branch exists to fix. Never hit by
            // any test in this repo before that live-fire, since every test JVM is launched by Gradle
            // with a real multi-entry classpath, not a packaged fat jar.
            command.add("-cp");
            command.add(classpath);
            command.add("-Dloader.main=" + PluginIpcChildProcessMain.class.getName());
            command.add(SPRING_BOOT_PROPERTIES_LAUNCHER);
        } else {
            argFile = writeClasspathArgFile(classpath);
            command.add("@" + argFile);
            command.add(PluginIpcChildProcessMain.class.getName());
        }
        command.addAll(List.of(childMainArgs));
        PluginProcessResourceLimiter limiter = PluginProcessResourceLimiter.forCurrentOs();
        command = limiter.wrapCommand(command, limits);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        PluginProcessResourceLimiter.ResourceLimitAttachment attachment = limiter.attachAfterStart(process, limits);
        return new PluginIpcChildProcess(process, argFile, attachment);
    }

    /**
     * True when {@code classpath} is a single jar file that bundles Spring Boot's own loader classes --
     * i.e. this JVM was itself launched {@code java -jar <thatJar>} as a Spring Boot executable archive,
     * the shape every real deployed app uses (Start-App.ps1's own invocation). Checked by looking for
     * the loader class directly inside the jar, not by trusting the manifest's {@code Main-Class} (a
     * cheap, self-contained proof that the {@code -Dloader.main} mechanism below will actually work
     * against this specific jar, not just that it LOOKS like a Boot jar).
     */
    private static boolean isExecutableSpringBootArchive(String classpath) {
        if (classpath.contains(File.pathSeparator) || !classpath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        Path jarPath = Path.of(classpath);
        if (!Files.isRegularFile(jarPath)) {
            return false;
        }
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            return jarFile.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * QUAL-51: HotSpot's default ergonomic {@code InitialHeapSize} is derived from the HOST's full
     * physical memory (roughly {@code physical/64}), entirely unaware that an OS-level ceiling (Job
     * Object / cgroup) is about to be applied moments after this process starts. On a host with enough
     * RAM, that default initial commit alone (observed: ~252MiB on a 16.5GB dev machine) can exceed a
     * modest configured ceiling before the child's {@code main()} even runs, failing with a native
     * commit error (Windows: {@code ERROR_COMMITMENT_LIMIT} / "paging file too small") that has nothing
     * to do with the plugin's own behavior. An explicit, small {@code -Xms} -- scaled down for very low
     * ceilings so it never itself exceeds one -- removes that startup spike. {@code -Xmx} is
     * deliberately left at its default: it is a virtual RESERVATION, not a commit, so it does not itself
     * threaten the ceiling, and leaving it alone means a genuinely runaway plugin still grows its real
     * heap commit past the ceiling and gets caught by the OS limiter exactly as before.
     */
    private static List<String> childHeapFlags(PluginProcessResourceLimits limits) {
        if (limits.memoryLimitMb() == null) {
            return List.of();
        }
        int initialHeapMb = Math.max(8, Math.min(32, limits.memoryLimitMb() / 4));
        return List.of("-Xms" + initialHeapMb + "m");
    }

    private static Path writeClasspathArgFile(String classpath) throws IOException {
        Path argFile = Files.createTempFile("npdev-plugin-ipc-classpath-", ".args");
        String escaped = classpath.replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(argFile, "-cp \"" + escaped + "\"" + System.lineSeparator());
        return argFile;
    }

    /**
     * Sends the invoke frame and blocks on this process's real stdin/stdout pipes exactly like
     * {@link PluginIpcHostSession#invoke}, then -- per design section 4 -- remaps a closed-channel failure
     * into a process-kill-specific error when the child process is confirmed dead, carrying its exit code.
     * Equivalent to {@link #invoke(PluginIpcHostSession, RuntimePluginAdapterRegistry.RegisteredAdapterContribution,
     * CapabilityCall, Map, String)} with a {@code null} handler class name, for a one-shot process (step 2).
     */
    public CapabilityResult invoke(
            PluginIpcHostSession hostSession,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState
    ) {
        return invoke(hostSession, contribution, call, contextState, null);
    }

    /** Same as the four-arg {@link #invoke}, additionally naming the class a pooled worker (step 3) must load. */
    public CapabilityResult invoke(
            PluginIpcHostSession hostSession,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState,
            String handlerClassName
    ) {
        CapabilityResult result;
        try {
            result = hostSession.invoke(
                    contribution, call, contextState, handlerClassName, process.getInputStream(), process.getOutputStream()
            );
        } catch (UncheckedIOException pipeFailure) {
            // Design section 4 names both directions ("the host's pipe read/write to a worker fails"): a
            // process killed (e.g. by an OS resource-limit ceiling, SEC-3 step 4) fast enough can fail the
            // very WRITE of the invoke frame, not just a subsequent read -- PluginIpcHostSession.invoke
            // throws in that case rather than returning a CHANNEL_CLOSED result, so this must be caught
            // here too, not only inspected as a returned error code below.
            return awaitDeathAndDescribe(call, CapabilityResult.failure(
                    CHANNEL_CLOSED_CODE,
                    "Plugin IPC channel failed: " + pipeFailure.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            ));
        }
        if (result.ok() || !CHANNEL_CLOSED_CODE.equals(result.error().code())) {
            return result;
        }
        return awaitDeathAndDescribe(call, result);
    }

    private CapabilityResult awaitDeathAndDescribe(CapabilityCall call, CapabilityResult fallback) {
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            return fallback;
        }
        int exitValue = process.exitValue();
        return CapabilityResult.failure(
                "PLUGIN_EXECUTION_PROCESS_KILLED",
                "Plugin child process exited before completing the invocation (exit code " + exitValue + ")",
                CapabilityErrorKind.PERMANENT,
                Map.of("capability", call.capability(), "operation", call.operation(), "exitValue", exitValue)
        );
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Graceful signal, grace period, then force-kill -- design section 2's shutdown handling ("sends a
     * close frame to every live worker... then {@code Process.destroyForcibly()} any still alive"), using
     * the SAME EOF-on-closed-pipe signal {@link PluginIpcChildRuntime#runUntilClosed} already treats as
     * shutdown rather than a fourth wire frame kind: closing this process's stdin is indistinguishable, on
     * the child's side, from a real close frame arriving. Harmless no-op on an already-exited one-shot
     * worker (step 2), which is the common case here -- it already exited on its own once its single
     * response was sent.
     */
    @Override
    public void close() {
        if (process.isAlive()) {
            try {
                process.getOutputStream().close();
            } catch (IOException exception) {
                // A broken pipe here just means the child is already gone; the isAlive()/waitFor below
                // still runs to confirm and fall back to a forceful kill if it somehow is not.
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // Only deleted once the process is confirmed dead: java's launcher reads the argfile during its own
        // startup, so deleting it any earlier would race a still-starting child. Null when this worker was
        // launched via PropertiesLauncher (SEC-5): no argfile is written in that branch.
        if (classpathArgFile != null) {
            try {
                Files.deleteIfExists(classpathArgFile);
            } catch (IOException exception) {
                // Best-effort cleanup of a one-shot temp file; leaving it behind is harmless.
            }
        }
        // Closed last, after the process is confirmed dead: on Windows this releases the Job Object handle
        // (harmless once the process it bounded has already exited); on Linux this removes the raw-cgroup
        // fallback's directory, if that path was used.
        resourceLimitAttachment.close();
    }
}
