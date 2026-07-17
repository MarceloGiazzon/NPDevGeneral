package com.finalexec.controlpanel;

import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowSchedule;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.finalexec.scheduler.ScheduleOutcome;
import com.finalexec.scheduler.ScheduleOutcomeTracker;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LNCH-12: ControlPanel visibility into every flow's declared {@code schedule} plus its last known
 * outcome ({@link ScheduleOutcomeTracker}, populated by {@code NpdevCronSchedulerService}).
 * SUPERUSER-only, like the rest of ControlPanel.
 */
/**
 * LNCH-12: deliberately NOT {@code /api/admin/schedules} -- {@link com.finalexec.api.RuntimeSchedulesController}
 * already owns that path for a different, pre-existing feature (the {@code scheduleEvent}
 * orchestration action's delayed-event queue, unrelated to cron-based flow scheduling).
 */
@RestController
@RequestMapping("/api/admin/cron-schedules")
public class ControlPanelSchedulesController {

    private final CompiledModel compiledModel;
    private final ScheduleOutcomeTracker tracker;
    private final RuntimeContextService runtimeContextService;

    public ControlPanelSchedulesController(
            CompiledModel compiledModel,
            ScheduleOutcomeTracker tracker,
            RuntimeContextService runtimeContextService
    ) {
        this.compiledModel = compiledModel;
        this.tracker = tracker;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);

        Map<String, ScheduleOutcome> outcomesByKey = new LinkedHashMap<>();
        for (ScheduleOutcome outcome : tracker.all()) {
            outcomesByKey.put(ScheduleOutcomeTracker.key(outcome.flowName(), outcome.tenantId()), outcome);
        }

        List<Map<String, Object>> declared = new java.util.ArrayList<>();
        for (CompiledFlow flow : compiledModel.getFlows()) {
            CompiledFlowSchedule schedule = flow.getSchedule();
            if (schedule == null) {
                continue;
            }
            List<String> tenants = schedule.getTenantScope().isEmpty()
                    ? List.of("default") : schedule.getTenantScope();
            for (String tenantId : tenants) {
                String normalizedTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
                ScheduleOutcome outcome = outcomesByKey.get(ScheduleOutcomeTracker.key(flow.getName(), normalizedTenant));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("flowName", flow.getName());
                row.put("tenantId", normalizedTenant);
                row.put("cron", schedule.getCron());
                row.put("status", outcome == null ? ScheduleOutcome.STATUS_PENDING : outcome.status());
                row.put("lastRunAt", outcome == null || outcome.lastRunAt() == null ? null : outcome.lastRunAt().toString());
                row.put("lastError", outcome == null ? null : outcome.lastError());
                row.put("runCount", outcome == null ? 0 : outcome.runCount());
                declared.add(row);
            }
        }
        return declared;
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
