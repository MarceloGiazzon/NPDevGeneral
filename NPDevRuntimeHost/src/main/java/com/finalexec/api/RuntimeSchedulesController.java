package com.finalexec.api;

import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/runtime/schedules", "/api/admin/schedules"})
public class RuntimeSchedulesController {
    private final GeneratedCrudRuntimeSupport runtimeSupport;

    public RuntimeSchedulesController(GeneratedCrudRuntimeSupport runtimeSupport) {
        this.runtimeSupport = runtimeSupport;
    }

    @GetMapping
    public List<Map<String, Object>> listSchedules(
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset
    ) {
        return run(() -> runtimeSupport.listScheduledEvents(limit, offset));
    }

    @PostMapping("/process-due")
    @Transactional
    public ResponseEntity<Map<String, Object>> processDueSchedules(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) Boolean forceDue,
            @RequestParam(required = false, defaultValue = "100") Integer limit
    ) {
        Boolean resolvedForceDue = forceDue;
        if (resolvedForceDue == null && body != null && body.containsKey("forceDue")) {
            resolvedForceDue = toBoolean(body.get("forceDue"));
        }
        final Boolean effectiveForceDue = resolvedForceDue;
        Map<String, Object> result = run(() -> runtimeSupport.processDueScheduledEvents(effectiveForceDue, limit));
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("requestedForceDue", effectiveForceDue != null && effectiveForceDue);
        return ResponseEntity.ok(response);
    }

    private <T> T run(RuntimeScheduleCall<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SecurityException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface RuntimeScheduleCall<T> {
        T get();
    }
}
