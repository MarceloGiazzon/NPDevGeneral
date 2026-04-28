package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.experimental.*;

import com.finalexec.npdev.model.RuntimeRefreshStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/admin/runtime", "/api/admin/runtime"})
public class RuntimeRefreshController {

    @GetMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuntimeRefreshStatus> getRefreshStatus() {
        return ResponseEntity.ok(new RuntimeRefreshStatus(
                true,
                true,
                "CONTROLLED_REFRESH",
                "READY",
                "Controlled refresh is supported. Full process restart is still required for classpath changes."
        ));
    }

    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuntimeRefreshStatus> requestRefresh() {
        return ResponseEntity.ok(new RuntimeRefreshStatus(
                true,
                true,
                "CONTROLLED_REFRESH",
                "REQUEST_ACCEPTED",
                "Refresh request accepted. Rebuild and restart are required to apply projected source changes."
        ));
    }
}
