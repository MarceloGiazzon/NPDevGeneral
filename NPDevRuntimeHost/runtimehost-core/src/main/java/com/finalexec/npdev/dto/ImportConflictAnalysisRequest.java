package com.finalexec.npdev.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportConflictAnalysisRequest {

    private String templateId;
    private String analysisName;
    private String uniqueField;
    private String referenceField;
    private List<String> knownReferenceValues = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();

    public ImportConflictAnalysisRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getAnalysisName() {
        return analysisName;
    }

    public void setAnalysisName(String analysisName) {
        this.analysisName = analysisName;
    }

    public String getUniqueField() {
        return uniqueField;
    }

    public void setUniqueField(String uniqueField) {
        this.uniqueField = uniqueField;
    }

    public String getReferenceField() {
        return referenceField;
    }

    public void setReferenceField(String referenceField) {
        this.referenceField = referenceField;
    }

    public List<String> getKnownReferenceValues() {
        return knownReferenceValues;
    }

    public void setKnownReferenceValues(List<String> knownReferenceValues) {
        this.knownReferenceValues = knownReferenceValues == null ? new ArrayList<>() : knownReferenceValues;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }
}