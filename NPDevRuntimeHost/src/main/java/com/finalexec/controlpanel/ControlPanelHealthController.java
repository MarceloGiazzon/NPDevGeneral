package com.finalexec.controlpanel;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The deliberate, reviewed lift of the "verification page is read-only, forever" boundary
 * (VERIFICATION_PANEL_AND_PROBE_PLAN's S5.3: "a verification page served over HTTP is exactly the
 * shape of remote-code-execution if it can run scripts"). That reasoning is still correct in
 * general -- what changed is that this controller does not run "a script"; it runs exactly one of
 * THREE fixed, individually-reviewed, read-only {@code _ops} scripts, chosen because each was
 * read line-by-line and confirmed to touch no destructive or secret-leaking state. SUPERUSER auth
 * (like every other ControlPanel controller) is defense in depth, not the primary control -- the
 * primary control is that nothing outside {@link #RUNNABLE_SCRIPTS} can ever be named by a
 * request, no matter what role calls this.
 *
 * <h2>Why these three and no others</h2>
 * Every script this platform's two {@code _ops} emitters (Build-NpdevApp.ps1,
 * OperationalRunbookEmitter.java) write was read, not assumed safe by its name -- two that
 * SOUNDED diagnostic were not: {@code Print-DbConnectionInfo.ps1} writes the live DB password to
 * its own stdout (which a log-viewing endpoint would then have served back over HTTP), and both
 * {@code Test-App.ps1} and {@code Smoke-Test.ps1} create real rows in the app's own database on
 * every run (a "smoke user" with a fresh random email each time, never cleaned up). Excluded for
 * the obvious reasons: anything that starts, stops, builds, migrates, resets, or reinstalls the
 * app or its environment ({@code Start-App}, {@code Stop-App}, {@code Start-Environment},
 * {@code Stop-Environment}, {@code Build-App}, {@code Migrate-Only}, {@code Reset-Environment},
 * {@code Install-Service}, {@code Reissue-SuperUserKey}, {@code Serve-AppConsole} -- the last of
 * which is an interactive shell and would BE the remote-code-execution S5.3 warned about).
 * {@code Status-App.ps1} only GETs {@code /actuator/health}. {@code Status-Environment.ps1} only
 * reads PID files / probes TCP reachability / runs {@code docker ps} (read-only inspect).
 * {@code Check-Provenance.ps1} only performs authenticated GETs and runs a static-analysis Python
 * script against the result, failing cleanly (not unsafely) when the sibling NPDev repo it needs
 * is not present on this machine, which is the common case for a real deployment.
 *
 * <h2>What is NOT reused from the Manager's own executor</h2>
 * {@code npdev_executor.py} (the Manager-local equivalent) resolves its allowlist from a
 * declared-command policy file and shells through a reviewed PowerShell runner. This controller
 * cannot reuse that machinery (it is a local dev-machine tool, reachable only from
 * {@code npdev verify --run} and the Manager's own Tauri command, never from a served page,
 * PER ITS OWN DOCSTRING) -- so this allowlist is independently hardcoded and independently
 * reasoned about, on purpose, rather than inheriting a policy file shaped for a different threat
 * model.
 */
@RestController
@RequestMapping("/api/admin/health")
public class ControlPanelHealthController {

    /** id -> script filename. The id is also the filename's lower-kebab form, used in every URL. */
    private static final Map<String, String> RUNNABLE_SCRIPTS = Map.of(
            "status-app", "Status-App.ps1",
            "status-environment", "Status-Environment.ps1",
            "check-provenance", "Check-Provenance.ps1"
    );

    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final RuntimeContextService runtimeContextService;
    private final String opsDir;
    private final long runTimeoutSeconds;
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "npdev-health-run-timeout");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public ControlPanelHealthController(
            RuntimeContextService runtimeContextService,
            @Value("${npdev.ops.dir:}") String opsDir,
            @Value("${npdev.health.run-timeout-seconds:600}") long runTimeoutSeconds
    ) {
        this.runtimeContextService = runtimeContextService;
        this.opsDir = opsDir;
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    /** One run's mutable state, tracked only in memory -- does not need to survive a restart, since a
     *  restarted JVM cannot still be running the child process it forgot about either. */
    private static final class RunState {
        volatile Process process;
        volatile Instant startedAt;
        volatile String logPath;
        volatile String result; // "running" | "passed" | "failed" | "timed-out" | "stopped"
        volatile Integer exitCode;
        volatile Double durationSeconds;
    }

    @GetMapping("/items")
    public ResponseEntity<Map<String, Object>> items(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("opsDirConfigured", !opsDir.isBlank());
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : RUNNABLE_SCRIPTS.entrySet()) {
            String id = entry.getKey();
            String scriptFile = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("scriptFile", scriptFile);
            item.put("available", !opsDir.isBlank() && resolveScript(scriptFile) != null);
            RunState state = runs.get(id);
            if (state != null) {
                Map<String, Object> lastRun = new LinkedHashMap<>();
                lastRun.put("startedAt", state.startedAt == null ? null : state.startedAt.toString());
                lastRun.put("result", state.result);
                lastRun.put("exitCode", state.exitCode);
                lastRun.put("durationSeconds", state.durationSeconds);
                item.put("lastRun", lastRun);
            } else {
                item.put("lastRun", null);
            }
            items.add(item);
        }
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/run/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        String scriptFile = RUNNABLE_SCRIPTS.get(id);
        if (scriptFile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown health item: " + id);
        }
        Path script = resolveScript(scriptFile);
        if (script == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this app was not launched through a script that sets NPDEV_OPS_DIR, or " + scriptFile
                            + " is not present in _ops -- nothing to run.");
        }
        RunState existing = runs.get(id);
        if (existing != null && "running".equals(existing.result)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, id + " is already running");
        }

        RunState state = new RunState();
        state.startedAt = Instant.now();
        state.result = "running";
        String logFileName = "health-run-" + id + "-" + LOG_TIMESTAMP.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)) + ".log";
        Path logPath = logsDir().resolve(logFileName);
        state.logPath = logPath.toString();
        runs.put(id, state);

        try {
            Files.createDirectories(logsDir());
            ProcessBuilder builder = new ProcessBuilder("pwsh", "-NoProfile", "-File", script.toString())
                    .redirectOutput(logPath.toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();
            state.process = process;
            process.onExit().thenAccept(finished -> finishRun(id, state, finished.exitValue(), "stopped".equals(state.result)));
            timeoutExecutor.schedule(() -> {
                if ("running".equals(state.result) && state.process != null && state.process.isAlive()) {
                    state.result = "timed-out";
                    state.process.destroyForcibly();
                }
            }, runTimeoutSeconds, TimeUnit.SECONDS);
        } catch (IOException e) {
            state.result = "failed";
            state.durationSeconds = 0.0;
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "could not start " + scriptFile + ": " + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("result", state.result);
        return ResponseEntity.accepted().body(body);
    }

    private void finishRun(String id, RunState state, int exitCode, boolean wasStopped) {
        state.durationSeconds = (System.currentTimeMillis() - state.startedAt.toEpochMilli()) / 1000.0;
        state.exitCode = exitCode;
        if (wasStopped) {
            state.result = "stopped";
        } else if ("timed-out".equals(state.result)) {
            // Already set by the timeout task; leave it.
        } else {
            state.result = exitCode == 0 ? "passed" : "failed";
        }
    }

    @PostMapping("/stop/{id}")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String id, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        if (!RUNNABLE_SCRIPTS.containsKey(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown health item: " + id);
        }
        RunState state = runs.get(id);
        if (state == null || state.process == null || !state.process.isAlive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, id + " is not running");
        }
        state.result = "stopped";
        state.process.destroyForcibly();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("result", "stopping");
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping(value = "/log/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> log(@PathVariable String id, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        if (!RUNNABLE_SCRIPTS.containsKey(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown health item: " + id);
        }
        RunState state = runs.get(id);
        if (state == null || state.logPath == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no run recorded yet for " + id);
        }
        try {
            String content = Files.readString(Path.of(state.logPath));
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "log not readable: " + e.getMessage());
        }
    }

    /**
     * Resolves a KNOWN-SAFE filename (never request input -- callers only ever pass a value out of
     * {@link #RUNNABLE_SCRIPTS}) against the configured ops directory, then verifies the resolved,
     * canonicalized path is still literally inside it. The filename is already fixed and constant,
     * so this check can never fail for an adversarial reason -- it exists purely as defense in
     * depth against a future edit accidentally passing untrusted input into this method.
     */
    private Path resolveScript(String scriptFile) {
        if (opsDir.isBlank()) {
            return null;
        }
        try {
            Path opsRoot = Path.of(opsDir).toRealPath();
            Path candidate = opsRoot.resolve(scriptFile).normalize();
            if (!candidate.startsWith(opsRoot) || !Files.isRegularFile(candidate)) {
                return null;
            }
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    private Path logsDir() {
        // _ops's sibling App/logs directory -- the same one Run-FinalApp.ps1 already writes
        // app-*.log into, and the same one spared on regeneration. "App" is a literal, hardcoded
        // sibling folder name here, not derived -- verified identical in both AppGen builders
        // (Build-NpdevApp.ps1 and Build-ClaudeApp.ps1 both set $GeneratedAppRoot = <OutRoot>/App,
        // $OpsDir = <OutRoot>/_ops), but a THIRD builder using a different app folder name would
        // silently miss this and fall back to opsDir's own logs/ below instead.
        Path viaAppSibling = Path.of(opsDir).getParent().resolve("App").resolve("logs");
        return Files.isDirectory(viaAppSibling) ? viaAppSibling : Path.of(opsDir).resolve("logs");
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
