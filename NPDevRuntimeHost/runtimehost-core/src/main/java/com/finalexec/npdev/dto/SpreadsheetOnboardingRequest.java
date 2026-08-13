package com.finalexec.npdev.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class SpreadsheetOnboardingRequest {

    private String templateId;
    private String onboardingName;
    private Map<String, Object> mappings = new LinkedHashMap<>();

    public SpreadsheetOnboardingRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getOnboardingName() {
        return onboardingName;
    }

    public void setOnboardingName(String onboardingName) {
        this.onboardingName = onboardingName;
    }

    public Map<String, Object> getMappings() {
        return mappings;
    }

    public void setMappings(Map<String, Object> mappings) {
        this.mappings = mappings == null ? new LinkedHashMap<>() : mappings;
    }
}