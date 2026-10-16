package com.finalexec.scheduler;

import com.npdev.kernel.ports.MailMessage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OPS-11 (runtimehost-core, pure logic, no Spring): decides WHAT to e-mail. The scheduler in the
 * per-app template (NPDevRuntimeHost/src/main/.../OpsAlertScheduler) gathers the inputs from
 * ExecutionMonitorService + HealthEndpoint and sends whatever this returns.
 *
 * Rules: an execution id is reported once (FAILED or STUCK); health is reported on a transition
 * (UP -> not UP, and back to UP as "recovered"); nothing new -> no mail.
 */
public final class OpsAlertDigest {

    private static final int REMEMBERED_IDS = 5000;

    private final Set<String> reportedExecutionIds = new LinkedHashSet<>();
    private String lastHealth = "UP";

    /** @param failedOrStuck execution cards from ExecutionMonitorService (keys: executionId, flowName,
     *                       status, lastErrorCode, detailPath) -- already filtered to FAILED/FAILED_PERMANENT/STUCK
     *  @param health        HealthEndpoint status code, e.g. "UP", "DOWN", "OUT_OF_SERVICE"
     *  @param appName       for the subject line
     *  @param baseUrl       e.g. https://app.example.com (may be blank) */
    public synchronized Optional<MailMessage> evaluate(List<Map<String, Object>> failedOrStuck, String health,
                                                      List<String> to, String appName, String baseUrl) {
        List<String> lines = new ArrayList<>();
        String normalizedHealth = health == null || health.isBlank() ? "UNKNOWN" : health;
        boolean healthChanged = !normalizedHealth.equals(lastHealth);
        if (healthChanged) {
            lines.add("UP".equals(normalizedHealth)
                    ? "App health recovered: UP (was " + lastHealth + ")"
                    : "App health is " + normalizedHealth + " (was " + lastHealth + ")");
            lastHealth = normalizedHealth;
        }
        for (Map<String, Object> card : failedOrStuck) {
            String id = String.valueOf(card.getOrDefault("executionId", ""));
            if (id.isBlank() || !reportedExecutionIds.add(id)) {
                continue;
            }
            lines.add(card.getOrDefault("status", "?") + "  " + card.getOrDefault("flowName", "?")
                    + "  " + id + "  " + card.getOrDefault("lastErrorCode", "")
                    + "  " + (baseUrl == null ? "" : baseUrl) + card.getOrDefault("detailPath", ""));
        }
        trim();
        if (lines.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        String subject = "[" + appName + "] " + (healthChanged && !"UP".equals(normalizedHealth)
                ? "app " + normalizedHealth : lines.size() + " item(s) need attention");
        String body = String.join("\n", lines) + "\n\nExecution monitor: " + (baseUrl == null ? "" : baseUrl)
                + "/api/executions/stuck\n";
        return Optional.of(new MailMessage(to, subject, body, Map.of()));
    }

    private void trim() {
        while (reportedExecutionIds.size() > REMEMBERED_IDS) {
            String oldest = reportedExecutionIds.iterator().next();
            reportedExecutionIds.remove(oldest);
        }
    }
}
