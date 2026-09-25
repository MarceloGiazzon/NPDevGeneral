package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.service.internal.ModelSyncStatusService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/model")
public class ModelSyncStatusController {

    private final ModelSyncStatusService modelSyncStatusService;
    private final RuntimeContextService runtimeContextService;

    public ModelSyncStatusController(
            ModelSyncStatusService modelSyncStatusService,
            RuntimeContextService runtimeContextService
    ) {
        this.modelSyncStatusService = modelSyncStatusService;
        this.runtimeContextService = runtimeContextService;
    }

    @PostMapping(
            path = "/sync-status",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ModelSyncStatusService.ModelSyncStatus> syncStatus(
            @RequestBody String authoringModelJson,
            HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        return ResponseEntity.ok(modelSyncStatusService.computeSyncStatus(authoringModelJson));
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
