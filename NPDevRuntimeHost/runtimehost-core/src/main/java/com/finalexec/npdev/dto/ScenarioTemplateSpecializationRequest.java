package com.finalexec.npdev.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScenarioTemplateSpecializationRequest {

    private String templateId;
    private String specializationName;
    private Map<String, Object> inputs = new LinkedHashMap<>();

    public ScenarioTemplateSpecializationRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getSpecializationName() {
        return specializationName;
    }

    public void setSpecializationName(String specializationName) {
        this.specializationName = specializationName;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs == null ? new LinkedHashMap<>() : inputs;
    }
}