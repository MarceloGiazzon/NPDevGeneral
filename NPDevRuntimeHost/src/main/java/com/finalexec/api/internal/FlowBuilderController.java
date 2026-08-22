package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.internal.*;

import com.finalexec.npdev.dto.FlowBuilderDraftRequest;
import com.finalexec.npdev.dto.FlowBuilderStepRequest;
import com.finalexec.npdev.service.internal.FlowBuilderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/flows", "/api/flows"})
public class FlowBuilderController {

    private final FlowBuilderService flowBuilderService;

    public FlowBuilderController(FlowBuilderService flowBuilderService) {
        this.flowBuilderService = flowBuilderService;
    }

    @GetMapping("/drafts")
    public Map<String, Object> drafts() {
        return flowBuilderService.listDrafts();
    }

    @GetMapping("/drafts/history")
    public Map<String, Object> history() {
        return flowBuilderService.draftHistory();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> saveDraft(@RequestBody FlowBuilderDraftRequest body) {
        try {
            return flowBuilderService.saveDraft(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/{flowName}/steps")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> addStep(
            @PathVariable String flowName,
            @RequestBody FlowBuilderStepRequest body
    ) {
        try {
            return flowBuilderService.addStep(flowName, body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
