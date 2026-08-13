package com.finalexec.npdev.dto;

public class SemanticRollbackRequest {

    private String rollbackScope;
    private String targetReference;
    private String requestedBy;
    private String rationale;
    private String rollbackAction;
    private String tenantId;

    public SemanticRollbackRequest() {
    }

    public String getRollbackScope() {
        return rollbackScope;
    }

    public void setRollbackScope(String rollbackScope) {
        this.rollbackScope = rollbackScope;
    }

    public String getTargetReference() {
        return targetReference;
    }

    public void setTargetReference(String targetReference) {
        this.targetReference = targetReference;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getRollbackAction() {
        return rollbackAction;
    }

    public void setRollbackAction(String rollbackAction) {
        this.rollbackAction = rollbackAction;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}