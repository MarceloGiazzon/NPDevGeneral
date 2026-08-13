package com.finalexec.npdev.dto;

public class StructuralCanonicalizationRequest {

    private String requestId;
    private String actionType;
    private String conceptName;
    private String fieldName;
    private String requestedBy;
    private String businessLabel;
    private Boolean required;

    public StructuralCanonicalizationRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getConceptName() {
        return conceptName;
    }

    public void setConceptName(String conceptName) {
        this.conceptName = conceptName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getBusinessLabel() {
        return businessLabel;
    }

    public void setBusinessLabel(String businessLabel) {
        this.businessLabel = businessLabel;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }
}