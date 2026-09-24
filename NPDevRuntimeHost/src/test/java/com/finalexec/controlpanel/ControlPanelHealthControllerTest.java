package com.finalexec.controlpanel;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The S5.3 boundary lift: proves the allowlist is total (an unknown id 404s, never reaches a
 * process), a non-SUPERUSER caller is forbidden on every endpoint, and a real allowlisted script
 * actually runs, can be stopped, and its log is readable -- against a REAL {@code pwsh} child
 * process in a temp {@code _ops} directory, not a mock, the same "test the real failure mode"
 * discipline {@code secrets.rs}'s own Rust tests already use for the OS keyring.
 */
class ControlPanelHealthControllerTest {

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @TempDir
    Path tempRoot;

    private Path opsDir;
    private Path appLogsDir;

    @BeforeEach
    void setUp() throws IOException {
        opsDir = tempRoot.resolve("_ops");
        appLogsDir = tempRoot.resolve("App").resolve("logs");
        Files.createDirectories(opsDir);
        Files.createDirectories(appLogsDir);
    }

    @AfterEach
    void tearDown() {
        // Belt and suspenders: a timed-out test must not leave a real pwsh process behind.
    }

    private void writeScript(String name, String body) throws IOException {
        Files.writeString(opsDir.resolve(name), body);
    }

    private ControlPanelHealthController controller(long timeoutSeconds) {
        return new ControlPanelHealthController(runtimeContextService, opsDir.toString(), timeoutSeconds);
    }

    private ControlPanelHealthController controllerWithNoOpsDir() {
        return new ControlPanelHealthController(runtimeContextService, "", 600);
    }

    private void authenticateAs(String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of(roles)));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    // ------------------------------------------------------------------ allowlist + auth

    @Test
    void nonSuperUserIsForbiddenOnEveryEndpoint() {
        authenticateAs("USER");
        ControlPanelHealthController controller = controller(600);

        assertThrows(ResponseStatusException.class, () -> controller.items(request()));
        assertThrows(ResponseStatusException.class, () -> controller.run("status-app", request()));
        assertThrows(ResponseStatusException.class, () -> controller.stop("status-app", request()));
        assertThrows(ResponseStatusException.class, () -> controller.log("status-app", request()));
    }

    @Test
    void runRefusesAnIdOutsideTheAllowlist() {
        authenticateAs("SUPERUSER");
        ControlPanelHealthController controller = controller(600);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.run("reset-environment", request()));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void runRefusesWhenOpsDirIsNotConfigured() {
        authenticateAs("SUPERUSER");
        ControlPanelHealthController controller = controllerWithNoOpsDir();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.run("status-app", request()));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void itemsReportsAvailabilityPerScriptPresence() throws IOException {
        authenticateAs("SUPERUSER");
        writeScript("Status-App.ps1", "exit 0\n");
        // Status-Environment.ps1 and Check-Provenance.ps1 deliberately absent.
        ControlPanelHealthController controller = controller(600);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = controller.items(request()).getBody();
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) body.get("items");
        assertEquals(3, items.size());
        Map<String, Object> statusApp = items.stream().filter(i -> "status-app".equals(i.get("id"))).findFirst().orElseThrow();
        assertEquals(true, statusApp.get("available"));
        Map<String, Object> statusEnv = items.stream().filter(i -> "status-environment".equals(i.get("id"))).findFirst().orElseThrow();
        assertEquals(false, statusEnv.get("available"));
    }

    // ------------------------------------------------------------------ real execution

    @Test
    void runExecutesTheRealScriptAndRecordsAPassedResult() throws Exception {
        authenticateAs("SUPERUSER");
        writeScript("Status-App.ps1", "Write-Host 'UP'\nexit 0\n");
        ControlPanelHealthController controller = controller(600);

        ResponseEntity<Map<String, Object>> accepted = controller.run("status-app", request());
        assertEquals(202, accepted.getStatusCode().value());

        Map<String, Object> item = waitForResult(controller, "status-app", 20_000);
        Map<String, Object> lastRun = (Map<String, Object>) item.get("lastRun");
        assertEquals("passed", lastRun.get("result"));
        assertEquals(0, lastRun.get("exitCode"));

        String log = controller.log("status-app", request()).getBody();
        assertTrue(log.contains("UP"), "the real script's own output must land in the log: " + log);
    }

    @Test
    void runRecordsAFailedResultOnNonZeroExit() throws Exception {
        authenticateAs("SUPERUSER");
        writeScript("Status-App.ps1", "Write-Host 'DOWN'\nexit 1\n");
        ControlPanelHealthController controller = controller(600);

        controller.run("status-app", request());
        Map<String, Object> item = waitForResult(controller, "status-app", 20_000);
        Map<String, Object> lastRun = (Map<String, Object>) item.get("lastRun");
        assertEquals("failed", lastRun.get("result"));
        assertEquals(1, lastRun.get("exitCode"));
    }

    @Test
    void runRefusesASecondConcurrentRunOfTheSameId() throws Exception {
        authenticateAs("SUPERUSER");
        writeScript("Status-App.ps1", "Start-Sleep -Seconds 10\nexit 0\n");
        ControlPanelHealthController controller = controller(600);

        controller.run("status-app", request());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.run("status-app", request()));
        assertEquals(409, ex.getStatusCode().value());

        controller.stop("status-app", request());
        // Wait for the kill to actually settle -- on Windows, destroyForcibly() returning does not
        // guarantee the OS has released the child's log-file handle yet, and @TempDir's own cleanup
        // (which runs immediately after this method returns) was observed racing that release and
        // failing with "the file is already in use by another process". waitForResult already blocks
        // on the SAME onExit() callback that would let a real caller believe the stop completed.
        waitForResult(controller, "status-app", 20_000);
    }

    @Test
    void stopKillsARunningProcessAndRecordsStopped() throws Exception {
        authenticateAs("SUPERUSER");
        writeScript("Status-App.ps1", "Start-Sleep -Seconds 60\nexit 0\n");
        ControlPanelHealthController controller = controller(600);

        controller.run("status-app", request());
        Thread.sleep(1000); // let the process actually start before stopping it
        ResponseEntity<Map<String, Object>> stopResponse = controller.stop("status-app", request());
        assertEquals(202, stopResponse.getStatusCode().value());

        Map<String, Object> item = waitForResult(controller, "status-app", 20_000);
        Map<String, Object> lastRun = (Map<String, Object>) item.get("lastRun");
        assertEquals("stopped", lastRun.get("result"));
    }

    @Test
    void stopRefusesWhenNothingIsRunning() {
        authenticateAs("SUPERUSER");
        ControlPanelHealthController controller = controller(600);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.stop("status-app", request()));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void logIsNotFoundBeforeAnyRun() {
        authenticateAs("SUPERUSER");
        ControlPanelHealthController controller = controller(600);

        assertThrows(ResponseStatusException.class, () -> controller.log("status-app", request()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForResult(ControlPanelHealthController controller, String id, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> body = controller.items(request()).getBody();
            var items = (java.util.List<Map<String, Object>>) body.get("items");
            Map<String, Object> item = items.stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
            Map<String, Object> lastRun = (Map<String, Object>) item.get("lastRun");
            if (lastRun != null && !"running".equals(lastRun.get("result"))) {
                return item;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(id + " did not settle within " + timeoutMillis + "ms");
    }
}
