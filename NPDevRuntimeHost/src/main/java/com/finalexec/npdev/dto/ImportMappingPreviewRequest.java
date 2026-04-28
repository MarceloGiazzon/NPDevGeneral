package com.finalexec.npdev.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportMappingPreviewRequest {

    private String templateId;
    private List<String> spreadsheetColumns;
    private Map<String, Object> mappings = new LinkedHashMap<>();

    public ImportMappingPreviewRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public List<String> getSpreadsheetColumns() {
        return spreadsheetColumns;
    }

    public void setSpreadsheetColumns(List<String> spreadsheetColumns) {
        this.spreadsheetColumns = spreadsheetColumns;
    }

    public Map<String, Object> getMappings() {
        return mappings;
    }

    public void setMappings(Map<String, Object> mappings) {
        this.mappings = mappings == null ? new LinkedHashMap<>() : mappings;
    }
}