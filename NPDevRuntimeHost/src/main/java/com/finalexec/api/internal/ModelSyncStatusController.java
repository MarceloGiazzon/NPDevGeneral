package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.experimental.*;

import com.finalexec.npdev.service.internal.ModelSyncStatusService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/model")
public class ModelSyncStatusController {

    private final ModelSyncStatusService modelSyncStatusService;

    public ModelSyncStatusController(ModelSyncStatusService modelSyncStatusService) {
        this.modelSyncStatusService = modelSyncStatusService;
    }

    @PostMapping(
            path = "/sync-status",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ModelSyncStatusService.ModelSyncStatus> syncStatus(
            @RequestBody String authoringModelJson
    ) {
        return ResponseEntity.ok(modelSyncStatusService.computeSyncStatus(authoringModelJson));
    }
}
