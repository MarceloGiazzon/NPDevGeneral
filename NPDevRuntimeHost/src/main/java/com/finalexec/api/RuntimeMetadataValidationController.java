package com.finalexec.api;

import com.finalexec.npdev.service.RuntimeMetadataValidationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({
        "/api/v1/runtime/metadata",
        "/api/runtime/metadata",
        "/api/v1/admin/runtime/metadata",
        "/api/admin/runtime/metadata"
})
public class RuntimeMetadataValidationController {

    private final RuntimeMetadataValidationService runtimeMetadataValidationService;

    public RuntimeMetadataValidationController(RuntimeMetadataValidationService runtimeMetadataValidationService) {
        this.runtimeMetadataValidationService = runtimeMetadataValidationService;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody(required = false) String modelJson) {
        return runtimeMetadataValidationService.validate(modelJson);
    }
}
