package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Host-side handle to one real OS child process running {@link PluginIpcChildProcessMain} (SEC-3, step 2
 * -- docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 6). One process per invocation, no pool
 * yet (step 3) -- the cold-start cost this accepts is deliberate per the design doc's sequencing.
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

    private PluginIpcChildProcess(Process process, Path classpathArgFile) {
        this.process = process;
        this.classpathArgFile = classpathArgFile;
    }

    /** Spawns a child process running {@code handlerClassName} on this JVM's own classpath. */
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
        Objects.requireNonNull(classpath, "classpath");
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Path argFile = writeClasspathArgFile(classpath);
        ProcessBuilder builder = new ProcessBuilder(
                javaBin, "@" + argFile, PluginIpcChildProcessMain.class.getName(), handlerClassName
        );
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return new PluginIpcChildProcess(builder.start(), argFile);
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
     */
    public CapabilityResult invoke(
            PluginIpcHostSession hostSession,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState
    ) {
        CapabilityResult result = hostSession.invoke(
                contribution, call, contextState, process.getInputStream(), process.getOutputStream()
        );
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

    /** Grace period then force-kill, per design section 2's shutdown handling. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // Only deleted once the process is confirmed dead: java's launcher reads the argfile during its own
        // startup, so deleting it any earlier would race a still-starting child.
        try {
            Files.deleteIfExists(classpathArgFile);
        } catch (IOException exception) {
            // Best-effort cleanup of a one-shot temp file; leaving it behind is harmless.
        }
    }
}
